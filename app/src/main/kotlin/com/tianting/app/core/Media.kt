// 图片和文件消息的纯函数部分（PRD-chat 第 6 节）：类型嗅探、头部宽高、像素上限、文案、大小、文件名。
// 不碰 Bitmap 和网络，JVM 单测直接测。语义同 tianting-web 的 src/lib/media.ts。
package com.tianting.app.core

import com.tianting.sdk.Message
import com.tianting.sdk.MsgType
import com.tianting.sdk.SendError

/** 解码前的像素上限（F-FILE-02 规则 3，未校准）。 */
const val MAX_PIXELS = 40_000_000L

/** 一次最多选几个（F-FILE-07 规则 6，未校准）。 */
const val MAX_PICK = 9

/** 处理后的原图上限（IM_IMAGE_MAX_BYTES 默认 20 MiB，F-FILE-02 规则 2 按处理后算）。 */
const val IMAGE_MAX_BYTES = 20L * 1024 * 1024

/** 缩略图上限（F-FILE-02 规则 3）。 */
const val THUMB_MAX_BYTES = 512L * 1024
const val FILE_MAX_BYTES = 100L * 1024 * 1024
const val AVATAR_MAX_BYTES = 2L * 1024 * 1024

/** 原图长边（F-FILE-02 规则 4）、缩略图长边（规则 3）、头像边长（F-ACC-06 规则 3）。 */
const val ORIGINAL_LONG_SIDE = 4096
const val THUMB_LONG_SIDE = 480
const val AVATAR_SIDE = 512

/** F-FILE-07 规则 4、AC-FILE-07-10 的文案（D13）。 */
const val SOURCE_MISSING = "原文件已不可用，请重新选择"
const val PROCESS_FAILED = "无法处理这张图片，请用文件方式发送"
const val STORAGE_FULL = "存储空间不足"
const val KEEP_METADATA_TIP = "文件会保留原始信息（可能包含位置）"
const val SOURCE_TIP = "文件来自其他用户，请确认来源可信"
const val PICK_TOO_MANY = "一次最多选择 $MAX_PICK 个"

enum class ImageKind(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), GIF("image/gif"), WEBP("image/webp"), HEIC("image/heic") }

/** 头部读出来的信息：宽高（HEIC 不解析）、可能透明、jpeg 的 EXIF 方向。 */
data class ImageHead(val kind: ImageKind, val width: Int? = null, val height: Int? = null, val alpha: Boolean = false, val orientation: Int? = null)

private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
private fun u32be(b: ByteArray, i: Int): Long = (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()
private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
private fun u24le(b: ByteArray, i: Int) = u16le(b, i) or ((b[i + 2].toInt() and 0xff) shl 16)
private fun ascii(b: ByteArray, i: Int, n: Int) = String(b, i, n, Charsets.ISO_8859_1)

/** 按内容判断图片类型并读出宽高（不解码像素）。认不出返回 null。 */
fun sniffImage(b: ByteArray): ImageHead? {
    if (b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()) return jpegHead(b)
    if (b.size >= 8 && u32be(b, 0) == 0x89504e47L && u32be(b, 4) == 0x0d0a1a0aL) return pngHead(b)
    if (b.size >= 6 && (ascii(b, 0, 6) == "GIF87a" || ascii(b, 0, 6) == "GIF89a")) {
        return if (b.size >= 10) ImageHead(ImageKind.GIF, u16le(b, 6), u16le(b, 8)) else ImageHead(ImageKind.GIF)
    }
    if (b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP") return webpHead(b)
    if (b.size >= 12 && ascii(b, 4, 4) == "ftyp") {
        if (ascii(b, 8, 4) in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")) return ImageHead(ImageKind.HEIC)
    }
    return null
}

private fun jpegHead(b: ByteArray): ImageHead {
    var orientation: Int? = null
    var i = 2
    while (i + 4 <= b.size) {
        if (b[i] != 0xff.toByte()) break
        val marker = b[i + 1].toInt() and 0xff
        if (marker == 0xff) {
            i += 1
            continue
        }
        if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) {
            i += 2
            continue
        }
        val len = u16be(b, i + 2)
        if (marker == 0xe1 && orientation == null) orientation = exifOrientation(b, i + 4, len - 2)
        val sof = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
        if (sof && i + 9 <= b.size) return ImageHead(ImageKind.JPEG, u16be(b, i + 7), u16be(b, i + 5), orientation = orientation)
        if (marker == 0xda) break
        i += 2 + len
    }
    return ImageHead(ImageKind.JPEG, orientation = orientation)
}

private fun exifOrientation(b: ByteArray, start: Int, len: Int): Int? {
    if (start + 14 > b.size || ascii(b, start, 4) != "Exif") return null
    val t = start + 6
    val le = ascii(b, t, 2) == "II"
    fun r16(i: Int) = if (le) u16le(b, i) else u16be(b, i)
    fun r32(i: Int): Long = if (le) (u16le(b, i).toLong() or (u16le(b, i + 2).toLong() shl 16)) else u32be(b, i)
    val ifd = t + r32(t + 4).toInt()
    if (ifd < 0 || ifd + 2 > minOf(b.size, start + len)) return null
    val n = r16(ifd)
    for (k in 0 until n) {
        val e = ifd + 2 + k * 12
        if (e + 12 > b.size) return null
        if (r16(e) == 0x0112) return r16(e + 8)
    }
    return null
}

private fun pngHead(b: ByteArray): ImageHead {
    if (b.size < 26 || ascii(b, 12, 4) != "IHDR") return ImageHead(ImageKind.PNG)
    val colorType = b[25].toInt() and 0xff
    var alpha = colorType == 4 || colorType == 6
    var i = 8
    while (!alpha && i + 8 <= b.size) {
        val len = u32be(b, i)
        val type = ascii(b, i + 4, 4)
        if (type == "tRNS") alpha = true
        if (type == "IDAT" || type == "IEND" || len > Int.MAX_VALUE - 12) break
        i += 12 + len.toInt()
    }
    return ImageHead(ImageKind.PNG, u32be(b, 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), u32be(b, 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), alpha)
}

private fun webpHead(b: ByteArray): ImageHead {
    val chunk = ascii(b, 12, 4)
    if (chunk == "VP8 " && b.size >= 30) return ImageHead(ImageKind.WEBP, u16le(b, 26) and 0x3fff, u16le(b, 28) and 0x3fff, false)
    if (chunk == "VP8L" && b.size >= 25) {
        val b0 = b[21].toInt() and 0xff
        val b1 = b[22].toInt() and 0xff
        val b2 = b[23].toInt() and 0xff
        val b3 = b[24].toInt() and 0xff
        return ImageHead(ImageKind.WEBP, 1 + (b0 or ((b1 and 0x3f) shl 8)), 1 + ((b1 shr 6) or (b2 shl 2) or ((b3 and 0x0f) shl 10)), true)
    }
    if (chunk == "VP8X" && b.size >= 30) return ImageHead(ImageKind.WEBP, 1 + u24le(b, 24), 1 + u24le(b, 27), (b[20].toInt() and 0x10) != 0)
    return ImageHead(ImageKind.WEBP)
}

/** 宽高都知道且不超过像素上限（解码炸弹，F-FILE-02 规则 3、AC-FILE-02-12）。 */
fun withinPixelLimit(width: Int?, height: Int?): Boolean =
    width != null && height != null && width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_PIXELS

/** 选中的文件按图片处理：类型是 image 开头，或者扩展名是图片。 */
fun looksLikeImage(mime: String?, name: String): Boolean =
    mime?.startsWith("image/") == true || Regex("""\.(jpe?g|png|gif|webp|heic|heif|avif|bmp|tiff?)$""", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** 字节数的显示（文件气泡）：B、KB、MB、GB，一位小数。 */
fun formatSize(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB")
    var v = n / 1024.0
    var u = 0
    while (v >= 1024 && u < units.size - 1) {
        v /= 1024
        u++
    }
    val shown = if (v >= 100) Math.round(v).toString() else (Math.round(v * 10) / 10.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
    return "$shown ${units[u]}"
}

/** 文件名拆成"前段 + 结尾"：前段可以省略，结尾（最多 8 个码点 + 扩展名）始终显示（AC-FILE-03-4 中间省略）。 */
fun splitName(name: String): Pair<String, String> {
    val cps = (name.ifEmpty { "file" }).codePoints().toArray().map { String(Character.toChars(it)) }
    val dot = cps.lastIndexOf(".")
    val ext = if (dot > 0 && cps.size - dot <= 9) cps.size - dot else 0
    val keep = minOf(cps.size, ext + 8)
    if (cps.size <= keep + 4) return "" to cps.joinToString("")
    return cps.subList(0, cps.size - keep).joinToString("") to cps.subList(cps.size - keep, cps.size).joinToString("")
}

/** 气泡里图片的显示尺寸（dp）：按宽高比缩进 box × box，最小边 40（AC-FILE-02-6：加载前就按这个占位）。 */
fun displaySize(w: Int?, h: Int?, box: Int = 200): Pair<Int, Int> {
    if (w == null || h == null || w <= 0 || h <= 0) return (box * 0.8f).toInt() to (box * 0.6f).toInt()
    val s = minOf(box.toDouble() / w, box.toDouble() / h, 1.0)
    return maxOf(40, Math.round(w * s).toInt()) to maxOf(40, Math.round(h * s).toInt())
}

/** 扩展名（小写，不带点）；没有时为空。 */
fun extensionOf(name: String): String = name.substringAfterLast('.', "").takeIf { it.length in 1..8 && '/' !in it }?.lowercase().orEmpty()

/** F-FILE-03 规则 3：图片、pdf、txt 可以"打开"，其他只提供"保存"（R-CS-13）。按服务端记录的 mime 和扩展名同时判断。 */
fun canOpen(mime: String?, name: String): Boolean {
    val ext = extensionOf(name)
    val m = mime.orEmpty().lowercase()
    return when {
        m == "application/pdf" -> ext == "pdf"
        m == "text/plain" -> ext == "txt"
        m in setOf("image/jpeg", "image/png", "image/gif", "image/webp") -> ext in setOf("jpg", "jpeg", "png", "gif", "webp")
        else -> false
    }
}

/** 文件气泡的类型标签（按扩展名，F-FILE-03 规则 3 的"类型图标"）。 */
fun fileTypeLabel(name: String): String = when (extensionOf(name)) {
    "pdf" -> "PDF"
    "doc", "docx" -> "DOC"
    "xls", "xlsx", "csv" -> "XLS"
    "ppt", "pptx" -> "PPT"
    "zip", "rar", "7z", "gz", "tar" -> "ZIP"
    "txt", "md", "log" -> "TXT"
    "jpg", "jpeg", "png", "gif", "webp", "heic" -> "IMG"
    "mp3", "wav", "m4a", "aac" -> "AUD"
    "mp4", "mov", "mkv", "avi" -> "VID"
    else -> "FILE"
}

/**
 * 附件发送失败的提示（F-FILE-01 规则 6、6a，F-FILE-02 规则 2，F-FILE-07 规则 4、异常）。
 * 只有 PRD 写了文案的用 PRD 的；网络或对象存储失败 PRD 没写（D12），文案同 H5（H22）。
 */
fun attachmentErrorText(m: Message): String {
    val e = m.error ?: return ""
    val image = m.type == MsgType.IMAGE
    val uploaded = !m.body.fileId.isNullOrEmpty()
    return when (e) {
        SendError.Process -> PROCESS_FAILED
        SendError.SourceMissing -> SOURCE_MISSING
        SendError.Storage -> STORAGE_FULL
        SendError.Network -> if (image) "图片上传失败，请检查网络后重发" else "文件上传失败，请检查网络后重发"
        SendError.Timeout, SendError.Kicked -> "发送失败，可以点击重发"
        is SendError.Code -> when {
            // 已经有 fileId：上传完成了，错误来自 send（4292 这时是"发起新聊天"的次数，不是上传额度）。
            uploaded && e.code != 4048 && e.code != 4096 -> messageFor(e.code, "send")
            e.code == 4292 -> "今天的上传额度已用完"
            e.code == 4290 -> if (m.errorReason == "uploadPendingLimit") "上传任务太多，请稍后再试" else messageFor(4290)
            e.code == 4139 -> "文件过大，最大 ${if (image) "20 MB" else "100 MB"}"
            e.code == 4150 -> "不支持发送这种文件"
            else -> messageFor(e.code, "send")
        }
    }
}

/** 会话列表、新消息的摘要（F-FILE-02 规则 5、F-FILE-03 规则 5：文件只显示"[文件]"，不带文件名）。 */
fun attachmentSummary(type: MsgType): String? = when (type) {
    MsgType.IMAGE -> "[图片]"
    MsgType.FILE -> "[文件]"
    else -> null
}
