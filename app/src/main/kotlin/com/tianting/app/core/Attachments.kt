// 图片和文件（FE-635、FE-636，PRD-chat F-FILE-02、03、05、07，F-ACC-06 规则 3）：选中的内容先复制进 App 私有目录，
// 图片轮到它时再处理（SDK 的"处理中"），处理好的结果交给 SDK，SDK 再复制进自己的沙盒（规则 2）；下载用 SDK 的 OkHttp 到私有缓存。
// 文件名、地址不进日志（F-FILE-04 规则 5）。
package com.tianting.app.core

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tianting.sdk.DownloadAbortedError
import com.tianting.sdk.FileInput
import com.tianting.sdk.FileVariant
import com.tianting.sdk.IMClient
import com.tianting.sdk.Message
import com.tianting.sdk.ProcessedImage
import com.tianting.sdk.UploadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** 选中的一项：已经复制到 App 私有目录。name 只给服务端（文件消息），不进日志。 */
class Picked(val file: File, val name: String, val mime: String?)

/** 复制选中的内容失败：存储空间不足，或者源已经读不到。 */
class PickFailed(val storage: Boolean) : Exception(if (storage) "storage" else "source")

/** 文件气泡的下载状态（第 13.4 节"下载中"）。 */
sealed interface DownloadState {
    data class Running(val bytes: Long) : DownloadState
    data class Done(val file: File) : DownloadState
    data object Failed : DownloadState
}

/** 选中内容的元数据（SAF 的显示名、大小、类型）。 */
fun queryMeta(resolver: ContentResolver, uri: Uri): Triple<String, Long?, String?> {
    var name: String? = null
    var size: Long? = null
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: RuntimeException) {
            // 有的提供方不支持这些列：按没有处理
        }
    } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
        uri.path?.let { File(it) }?.let {
            name = it.name
            size = it.length()
        }
    }
    val n = name?.takeIf { it.isNotBlank() } ?: "file"
    val mime = (if (uri.scheme == ContentResolver.SCHEME_CONTENT) resolver.getType(uri) else null)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(n))
    return Triple(n, size, mime)
}

/**
 * 一次登录里图片和文件的操作。状态都是 Compose 的 snapshot state，只在主线程写（scope 是 ImSession 的 Main.immediate）。
 * 临时目录：pickDir 放选中内容的副本（处理完删除），procDir 放处理好的结果（SDK 复制进沙盒后删除）。
 */
class MediaOps(private val context: Context, private val client: IMClient, private val scope: CoroutineScope, private val toast: (String) -> Unit = {}) {
    private val pickDir = File(context.cacheDir, PICK_DIR)
    private val procDir = File(context.cacheDir, PROC_DIR)

    /** 上传进度（百分比），按 clientMsgId。 */
    val progress = mutableStateMapOf<String, Int>()

    /** 文件气泡的下载状态，按 fileId。 */
    val downloads = mutableStateMapOf<String, DownloadState>()
    private val downloadJobs = HashMap<String, Job>()

    /** 别人发的文件：这个会话已经确认过来源提示（F-FILE-05 规则 3，只在内存，同 H5 的 H17）。 */
    val sourceAcked = mutableStateMapOf<String, Boolean>()

    /** 这次登录里刚换的头像：服务端地址在测试环境不是 https，本机先用上传的那张（同 H5 的 H13）。 */
    var localAvatar by mutableStateOf<File?>(null)

    /** 把选中的内容复制到私有目录（发送前复制，规则 2；也避免选择器的临时授权过期）。 */
    suspend fun copyIn(uri: Uri): Picked = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, _, mime) = queryMeta(resolver, uri)
        pickDir.mkdirs()
        val out = File(pickDir, UUID.randomUUID().toString())
        try {
            val input = resolver.openInputStream(uri) ?: throw PickFailed(storage = false)
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: IOException) {
            out.delete()
            throw PickFailed(storage = isNoSpace(e))
        } catch (e: SecurityException) {
            out.delete()
            throw PickFailed(storage = false)
        }
        Picked(out, name, mime)
    }

    /**
     * 发一张图片（F-FILE-02、07）：插入一条"处理中"，轮到它时才在后台处理（并发 1，规则 6）。
     * 处理失败：失败，"无法处理这张图片，请用文件方式发送"。
     */
    suspend fun sendImage(convId: String, p: Picked): Message {
        val head = withContext(Dispatchers.IO) { ImageProcessing.readHead(p.file) }
        // 占位尺寸按转正后的方向（Orientation 5～8 宽高互换）。
        val swap = (head?.orientation ?: 1) in 5..8
        val w = if (swap) head?.height else head?.width
        val h = if (swap) head?.width else head?.height
        var made: ProcessedImage? = null
        val holder = arrayOfNulls<String>(1)
        val m = client.sendImage(
            convId,
            w,
            h,
            produce = {
                try {
                    withContext(Dispatchers.IO) { ImageProcessing.process(p.file, procDir) }.also { made = it }
                } catch (e: ImageProcessError) {
                    AppLog.debug("image process failed: ${e.why}") // 只有分类，没有文件名
                    throw e
                } finally {
                    withContext(Dispatchers.IO) { p.file.delete() }
                }
            },
            onProgress = { sent, total -> onProgress(holder, sent, total) { made?.let { deleteProcessed(it) }; made = null } },
        )
        holder[0] = m.clientMsgId
        return m
    }

    /** 发一个文件（F-FILE-03）：内容不重新编码。SDK 复制进沙盒之后删掉这里的副本。 */
    suspend fun sendFile(convId: String, p: Picked): Message {
        val holder = arrayOfNulls<String>(1)
        try {
            val m = client.sendFile(convId, FileInput(p.file, p.name, p.mime ?: "application/octet-stream")) { sent, total -> onProgress(holder, sent, total) {} }
            holder[0] = m.clientMsgId
            return m
        } finally {
            withContext(Dispatchers.IO) { p.file.delete() }
        }
    }

    private fun onProgress(holder: Array<String?>, sent: Long, total: Long, first: () -> Unit) {
        scope.launch {
            first()
            val id = holder[0] ?: return@launch
            progress[id] = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
        }
    }

    private fun deleteProcessed(p: ProcessedImage) {
        scope.launch(Dispatchers.IO) {
            p.original.file.delete()
            p.thumb.file.delete()
        }
    }

    /**
     * 下载文件到私有缓存（F-FILE-03 规则 3）：有进度、可取消；失败显示"下载失败"，重试从 0 开始、重新申请地址（SDK）。
     * 下载中所在消息被撤回（PRD-chat F-RCL-03 规则 9，AC-RCL-03-12）：SDK 中止并删掉部分文件，抛 DownloadAbortedError，这里提示
     * "该消息已被撤回"；被后台删除、过期的（recalled = false）气泡自己会变成"该消息已被删除""文件已过期"，不另外提示（问题 33）。
     */
    fun startDownload(fileId: String) {
        if (downloadJobs[fileId]?.isActive == true) return
        downloads[fileId] = DownloadState.Running(0)
        downloadJobs[fileId] = scope.launch {
            try {
                val f = client.download(fileId, FileVariant.ORIGINAL) { n -> scope.launch { if (downloads[fileId] is DownloadState.Running) downloads[fileId] = DownloadState.Running(n) } }
                downloads[fileId] = DownloadState.Done(f)
            } catch (e: CancellationException) {
                downloads.remove(fileId)
                throw e
            } catch (e: DownloadAbortedError) {
                downloads.remove(fileId)
                if (e.recalled) toast(MESSAGE_RECALLED)
            } catch (e: Exception) {
                AppLog.debug("download failed: ${e.javaClass.simpleName}")
                downloads[fileId] = DownloadState.Failed
            } finally {
                downloadJobs.remove(fileId)
            }
        }
    }

    fun cancelDownload(fileId: String) {
        downloadJobs.remove(fileId)?.cancel()
        downloads.remove(fileId)
    }

    /** 已经下载过的（SDK 的缓存，键 fileId + variant）。 */
    fun checkCached(fileId: String) {
        if (downloads.containsKey(fileId)) return
        scope.launch {
            val f = try {
                client.cachedFile(fileId, FileVariant.ORIGINAL)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (f != null && !downloads.containsKey(fileId)) downloads[fileId] = DownloadState.Done(f)
        }
    }

    /** 把下载好的文件写到 SAF 给的位置（F-FILE-03 规则 3"保存"）。 */
    suspend fun saveTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: throw IOException("no output")
    }

    /**
     * 图片存进相册（F-FILE-02 规则 9）。API 29+ 用 MediaStore，不要权限；更早的要 WRITE_EXTERNAL_STORAGE（调用方先申请）。
     */
    suspend fun saveToGallery(file: File, mime: String) = withContext(Dispatchers.IO) {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val name = "tianting-${System.currentTimeMillis()}.$ext"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Tianting")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("insert")
            try {
                resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: throw IOException("open")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Tianting").apply { mkdirs() }
            val out = File(dir, name)
            file.copyTo(out, overwrite = true)
            @Suppress("DEPRECATION")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, out.path)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(out)
        }
    }

    /**
     * 上传头像（F-ACC-06 规则 3、4）：kind=avatar → PUT → complete，返回 fileId，由调用方 PATCH profile。
     * 走 SDK 的 FileClient（4011 按规则 7 重试），不经过消息的状态机。
     */
    suspend fun uploadAvatar(files: com.tianting.sdk.FileClient, jpeg: File): String {
        val up = files.createUpload(UploadRequest("avatar", "avatar.jpg", jpeg.length(), "image/jpeg"))
        files.put(up.put, jpeg)
        return files.complete(up.fileId).fileId
    }

    /** 退出登录：私有目录里的副本一起清掉（AC-FILE-07-9；SDK 清它自己的沙盒和下载缓存）。 */
    fun clear() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        clearTemp(context)
    }

    companion object {
        const val PICK_DIR = "tt-pick"
        const val PROC_DIR = "tt-proc"

        /** 启动时、退出登录时：上次留下的临时副本（处理中被杀进程等）。 */
        fun clearTemp(context: Context) {
            for (d in listOf(PICK_DIR, PROC_DIR, "tt-avatar")) File(context.cacheDir, d).deleteRecursively()
        }

        fun isNoSpace(e: Throwable): Boolean {
            var c: Throwable? = e
            while (c != null) {
                if (c is ErrnoException && c.errno == OsConstants.ENOSPC) return true
                if (c.message?.contains("ENOSPC") == true || c.message?.contains("No space left") == true) return true
                c = c.cause
            }
            return false
        }
    }
}
