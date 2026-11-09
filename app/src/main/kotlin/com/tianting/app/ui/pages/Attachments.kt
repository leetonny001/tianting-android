// 聊天页里的图片和文件（FE-635、FE-636，PRD-chat F-FILE-02、03、05、07，第 13.4 节页面状态）。
package com.tianting.app.ui.pages

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tianting.app.core.DownloadState
import com.tianting.app.core.ImageProcessing
import com.tianting.app.core.SOURCE_TIP
import com.tianting.app.core.attachmentErrorText
import com.tianting.app.core.canOpen
import com.tianting.app.core.displaySize
import com.tianting.app.core.fileTypeLabel
import com.tianting.app.core.formatSize
import com.tianting.app.core.splitName
import com.tianting.app.core.withinPixelLimit
import com.tianting.app.ui.Confirm
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.Spinner
import com.tianting.app.ui.T
import com.tianting.app.ui.TtDialog
import com.tianting.app.ui.dataStatus
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtDimen
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.FileVariant
import com.tianting.sdk.IMError
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 消息是图片或文件。 */
internal fun Message.isAttachment() = type == MsgType.IMAGE || type == MsgType.FILE

/** 一张图片的加载结果（第 13.4 节：加载中、解码失败、加载失败可重试）。 */
private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Ready(val file: File) : ImageLoad
    data object Undecodable : ImageLoad
    data object Failed : ImageLoad
}

/**
 * 取一个变体到 SDK 的缓存（键 fileId + variant）并检查头部：超过像素上限、认不出的不交给解码器（解码炸弹，AC-FILE-02-12）。
 * 进入组合（LazyColumn 只组合视口附近的项）才取，SDK 把同一轮的地址申请合并成一批（AC-FILE-02-9、04-10）。
 */
@Composable
private fun rememberImageFile(fileId: String, variant: FileVariant, retry: Int): ImageLoad {
    val im = LocalIm.current
    val v by produceState<ImageLoad>(ImageLoad.Loading, fileId, variant, retry) {
        value = ImageLoad.Loading
        value = onMain {
            try {
                val f = im.client.download(fileId, variant)
                val head = withContext(Dispatchers.IO) { ImageProcessing.readHead(f) }
                if (head == null || !withinPixelLimit(head.width, head.height)) ImageLoad.Undecodable else ImageLoad.Ready(f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.tianting.app.core.AppLog.debug("image load failed: ${(e as? IMError)?.code ?: e.javaClass.simpleName}")
                ImageLoad.Failed
            }
        }
    }
    return v
}

/** 解码后显示；解码器失败时显示"图片无法显示"。缓存键是 fileId + variant，不是地址（F-FILE-04 规则 5）。 */
@Composable
private fun DecodedImage(file: File, key: String, modifier: Modifier, scale: ContentScale, tag: String) {
    var broken by remember(key) { mutableStateOf(false) }
    if (broken) {
        Box(modifier, contentAlignment = Alignment.Center) { T("图片无法显示", Modifier.tag("image-undecodable"), style = Tt.type.caption, color = Tt.colors.textTertiary) }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(file).memoryCacheKey(key).crossfade(false).build(),
        contentDescription = null,
        modifier = modifier.tag(tag),
        contentScale = scale,
        onError = { broken = true },
    )
}

/** 图片气泡（F-FILE-02 规则 6）：按宽高预留占位，先显示缩略图，点击全屏看原图。 */
@Composable
internal fun ImageContent(m: Message, onOpen: () -> Unit) {
    val img = m.body.image ?: return
    val (w, h) = displaySize(img.width, img.height)
    var retry by remember { mutableIntStateOf(0) }
    Box(
        Modifier.size(w.dp, h.dp).tag("image-thumb").clip(RoundedCornerShape(TtDimen.radiusSm.dp)).background(Tt.colors.surfaceSelected)
            .semantics { contentDescription = "图片" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            img.expired -> T("图片已过期", Modifier.tag("image-expired"), style = Tt.type.caption, color = Tt.colors.textTertiary)
            img.fileId.isEmpty() -> Unit // 自己的、还没上传完：状态在气泡下面
            else -> when (val load = rememberImageFile(img.fileId, FileVariant.THUMB, retry)) {
                ImageLoad.Loading -> Spinner(20.dp, Tt.colors.textTertiary, Modifier.tag("image-loading"))
                ImageLoad.Undecodable -> T("图片无法显示", Modifier.tag("image-undecodable").padding(4.dp), style = Tt.type.caption, color = Tt.colors.textTertiary)
                ImageLoad.Failed -> T(
                    "加载失败，点击重试",
                    Modifier.tag("image-retry").clickable { retry++ }.padding(4.dp),
                    style = Tt.type.caption,
                    color = Tt.colors.brand,
                )
                is ImageLoad.Ready -> {
                    DecodedImage(load.file, "${img.fileId}:thumb", Modifier.fillMaxSize().semantics { contentDescription = "图片" }.clickable(onClick = onOpen) /* 能点的缩略图自己也要有名字（AC-CHAT-90-9，FE-640 发现） */, ContentScale.Crop, "image-loaded")
                    if (img.mime == "image/gif") {
                        // Android 第一批显示首帧加"GIF"标记（F-FILE-02 规则 4，不加依赖）
                        T(
                            "GIF",
                            Modifier.align(Alignment.BottomEnd).padding(4.dp).tag("gif-badge").clip(RoundedCornerShape(TtDimen.radiusSm.dp))
                                .background(Tt.colors.surface).padding(horizontal = 4.dp),
                            style = Tt.type.caption,
                            color = Tt.colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** 全屏看原图（F-FILE-02 规则 6、9）：缩放（双指、双击还原）、保存到相册。 */
@Composable
internal fun ImageViewer(fileId: String, mime: String?, onClose: () -> Unit) {
    val im = LocalIm.current
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retry by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var denied by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val load = rememberImageFile(fileId, FileVariant.ORIGINAL, retry)

    fun doSave(file: File) {
        val media = im.media ?: return
        saving = true
        scope.launch {
            try {
                onMain { media.saveToGallery(file, mime ?: "image/jpeg") }
                app.toast.show("已保存到相册")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.tianting.app.core.AppLog.debug("save to gallery failed: ${e.javaClass.simpleName}")
                if (MediaOpsNoSpace(e)) app.toast.show(com.tianting.app.core.STORAGE_FULL) else app.toast.show("保存失败")
            } finally {
                saving = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = (load as? ImageLoad.Ready)?.file
        if (ok && f != null) doSave(f) else if (!ok) denied = true
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Tt.colors.scrim).tag("image-viewer")) {
            when (load) {
                ImageLoad.Loading -> Spinner(32.dp, Tt.colors.surface, Modifier.align(Alignment.Center).tag("viewer-loading"))
                ImageLoad.Undecodable -> T("图片无法显示", Modifier.align(Alignment.Center).tag("image-undecodable"), color = Tt.colors.surface)
                ImageLoad.Failed -> T(
                    "加载失败，点击重试",
                    Modifier.align(Alignment.Center).tag("viewer-retry").clickable { retry++ }.padding(12.dp),
                    color = Tt.colors.surface,
                )
                is ImageLoad.Ready -> DecodedImage(
                    load.file,
                    "$fileId:original",
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    ContentScale.Fit,
                    "viewer-image",
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ready = load as? ImageLoad.Ready
                if (ready != null) {
                    TextButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                            ) {
                                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                doSave(ready.file)
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.tag("viewer-save").heightIn(min = TtDimen.sizeTouchMin.dp),
                    ) { T("保存", color = Tt.colors.surface) }
                }
                TextButton(onClose, Modifier.tag("viewer-close").heightIn(min = TtDimen.sizeTouchMin.dp)) { Icon(TtIcons.close, "关闭", tint = Tt.colors.surface) }
            }
        }
    }
    if (denied) {
        // 保存被系统权限拒绝（F-FILE-02 规则 9，第 13.4 节）：提示和前往设置的入口。API 29 以下才需要这个权限。
        TtDialog(
            "gallery-denied",
            { denied = false },
            confirm = {
                TextButton({
                    denied = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }, Modifier.tag("gallery-settings")) { T("前往设置", color = Tt.colors.brand) }
            },
            dismiss = { TextButton({ denied = false }) { T("取消", color = Tt.colors.textSecondary) } },
        ) { T("没有相册权限") }
    }
}

@Suppress("FunctionName")
private fun MediaOpsNoSpace(e: Throwable) = com.tianting.app.core.MediaOps.isNoSpace(e)

/** 文件气泡（F-FILE-03 规则 3）：类型标签、文件名（纯文本，中间省略）、大小；下载有进度、可取消，下载后"打开"（白名单）和"保存"。 */
@Composable
internal fun FileContent(m: Message, mine: Boolean, convId: String) {
    val im = LocalIm.current
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val body = m.body.file ?: return
    val name = body.name?.takeIf { it.isNotEmpty() } ?: "file"
    val media = im.media
    val state = if (body.fileId.isNotEmpty()) media?.downloads?.get(body.fileId) else null
    var askSource by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(body.fileId) { if (body.fileId.isNotEmpty() && !body.expired) media?.checkCached(body.fileId) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(body.mime ?: "application/octet-stream")) { uri ->
        val f = (state as? DownloadState.Done)?.file
        if (uri != null && f != null) saveFile(scope, app, im, f, uri)
    }

    fun start() {
        val ms = media ?: return
        // 别人发的文件：每个会话第一次下载前提示来源（F-FILE-05 规则 3，只对非图片；文件消息都是非图片）。
        if (!mine && ms.sourceAcked[convId] != true) askSource = true else ms.startDownload(body.fileId)
    }

    val (head, tail) = splitName(name)
    val sizeText = body.size?.let { formatSize(it) }.orEmpty()
    Column(
        Modifier.widthIn(min = 160.dp).tag("file-bubble").semantics { contentDescription = "文件 $name，$sizeText" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().then(
                if (!body.expired && body.fileId.isNotEmpty() && (state == null || state == DownloadState.Failed)) Modifier.clickable { start() } else Modifier,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(TtDimen.radiusSm.dp)).background(Tt.colors.surfaceSelected).tag("file-type"),
                contentAlignment = Alignment.Center,
            ) { T(fileTypeLabel(name), style = Tt.type.caption, color = Tt.colors.brand, maxLines = 1) }
            Column(Modifier.weight(1f, fill = false)) {
                // 文件名按纯文本（AC-FILE-02-7），中间省略：前段可以省略，结尾和扩展名始终可见（AC-FILE-03-4）。
                Row(Modifier.tag("file-name")) {
                    if (head.isNotEmpty()) T(head, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    T(tail, maxLines = 1, overflow = TextOverflow.Clip)
                }
                T(if (body.expired) "文件已过期" else sizeText, Modifier.tag(if (body.expired) "file-expired" else "file-size"), style = Tt.type.caption, color = Tt.colors.textTertiary)
            }
        }
        if (!body.expired && body.fileId.isNotEmpty() && media != null) {
            when (state) {
                null -> T("下载", Modifier.tag("file-download").clickable { start() }.padding(vertical = 4.dp), style = Tt.type.caption, color = Tt.colors.brand)
                is DownloadState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pct = body.size?.takeIf { it > 0 }?.let { (state.bytes * 100 / it).coerceIn(0, 100) }
                    T(if (pct != null) "下载中 $pct%" else "下载中…", Modifier.tag("file-downloading"), style = Tt.type.caption, color = Tt.colors.textSecondary)
                    T("取消", Modifier.tag("file-cancel-download").clickable { media.cancelDownload(body.fileId) }.padding(4.dp), style = Tt.type.caption, color = Tt.colors.brand)
                }
                DownloadState.Failed -> T("下载失败，点击重试", Modifier.tag("file-download-failed").clickable { media.startDownload(body.fileId) }.padding(vertical = 4.dp), style = Tt.type.caption, color = Tt.colors.danger)
                is DownloadState.Done -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // pdf、txt、图片白名单才"打开"，其他只提供"保存"（F-FILE-03 规则 3，AC-FILE-03-9）。
                    if (canOpen(body.mime, name)) {
                        T("打开", Modifier.tag("file-open").clickable { openFile(context, app, state.file, body.mime) }.padding(vertical = 4.dp), style = Tt.type.caption, color = Tt.colors.brand)
                    }
                    T(
                        "保存",
                        Modifier.tag("file-save").clickable {
                            val hook = app.deps.saveForTest
                            if (hook != null) hook(name)?.let { saveFile(scope, app, im, state.file, it) } else save.launch(name)
                        }.padding(vertical = 4.dp),
                        style = Tt.type.caption,
                        color = Tt.colors.brand,
                    )
                }
            }
        }
    }
    if (askSource) {
        Confirm(SOURCE_TIP, "下载", "source-dialog", onCancel = { askSource = false }, onOk = {
            askSource = false
            media?.sourceAcked?.set(convId, true)
            media?.startDownload(body.fileId)
        })
    }
}

private fun saveFile(scope: kotlinx.coroutines.CoroutineScope, app: com.tianting.app.core.AppModel, im: com.tianting.app.core.ImSession, file: File, target: Uri) {
    val media = im.media ?: return
    scope.launch {
        try {
            onMain { media.saveTo(file, target) }
            app.toast.show("已保存")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.toast.show(if (com.tianting.app.core.MediaOps.isNoSpace(e)) com.tianting.app.core.STORAGE_FULL else "保存失败")
        }
    }
}

/** 交给其他 App 打开（FileProvider 只临时授权读这一个文件）。 */
private fun openFile(context: android.content.Context, app: com.tianting.app.core.AppModel, file: File, mime: String?) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        app.toast.show("没有可以打开这个文件的应用")
    } catch (_: IllegalArgumentException) {
        app.toast.show("无法打开这个文件")
    }
}

/**
 * 自己发的附件在气泡下面的状态（第 13.4 节：处理中、上传中（进度）、失败、存储空间不足）和取消（AC-FILE-02-5）。
 * 已发送、别人发的不显示。
 */
@Composable
internal fun AttachmentStatus(m: Message, convId: String) {
    val im = LocalIm.current
    val pct = im.media?.progress?.get(m.clientMsgId)
    val text = when (m.status) {
        // 文案同 PRD-chat v1.4 F-FILE-07 规则 9 和 H5："处理中""上传中 N%"（还没有进度时只显示"上传中"）。
        MessageStatus.PROCESSING -> "处理中"
        MessageStatus.UPLOADING -> if (pct != null) "上传中 $pct%" else "上传中"
        MessageStatus.QUEUED -> "等待发送"
        MessageStatus.FAILED -> attachmentErrorText(m)
        else -> ""
    }
    val cancellable = m.status == MessageStatus.PROCESSING || m.status == MessageStatus.UPLOADING || m.status == MessageStatus.QUEUED
    if (text.isEmpty() && !cancellable) return
    Row(
        Modifier.padding(top = 4.dp).semantics { dataStatus = m.status.name.lowercase() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (text.isNotEmpty()) {
            T(
                text,
                Modifier.weight(1f, fill = false).tag(if (m.status == MessageStatus.FAILED) "attachment-error" else "attachment-status"),
                style = Tt.type.caption,
                color = if (m.status == MessageStatus.FAILED) Tt.colors.danger else Tt.colors.textTertiary,
            )
        }
        if (cancellable) {
            T(
                "取消",
                Modifier.tag("attachment-cancel").clickable { im.launchCmd { im.client.cancelUpload(convId, m.clientMsgId) } }.padding(horizontal = 4.dp, vertical = 8.dp),
                style = Tt.type.caption,
                color = Tt.colors.brand,
            )
        }
    }
}
