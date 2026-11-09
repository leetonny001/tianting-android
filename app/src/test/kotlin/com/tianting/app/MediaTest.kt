package com.tianting.app

import com.tianting.app.core.ImageKind
import com.tianting.app.core.attachmentErrorText
import com.tianting.app.core.attachmentSummary
import com.tianting.app.core.canOpen
import com.tianting.app.core.displaySize
import com.tianting.app.core.fileTypeLabel
import com.tianting.app.core.formatSize
import com.tianting.app.core.sniffImage
import com.tianting.app.core.splitName
import com.tianting.app.core.withinPixelLimit
import com.tianting.sdk.FileBody
import com.tianting.sdk.ImageBody
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgBody
import com.tianting.sdk.MsgType
import com.tianting.sdk.SendError
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 图片和文件的纯函数（core/Media.kt，同 tianting-web 的 media.ts）。JVM 单测，本地结果。 */
class MediaTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `jpeg 头部：宽高和 EXIF 方向`() {
        // SOI、APP1（Exif，小端，IFD0 一项 Orientation=6）、SOF0（高 20 宽 40）
        val tiff = bytes(0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val exif = "Exif".toByteArray() + bytes(0, 0) + tiff
        val app1 = bytes(0xff, 0xe1, 0, exif.size + 2) + exif
        val sof = bytes(0xff, 0xc0, 0, 11, 8, 0, 20, 0, 40, 3, 1, 0x22, 0)
        val h = sniffImage(bytes(0xff, 0xd8) + app1 + sof)!!
        assertEquals(ImageKind.JPEG, h.kind)
        assertEquals(40, h.width)
        assertEquals(20, h.height)
        assertEquals(6, h.orientation)
    }

    @Test fun `png、gif、webp、heic 和认不出的`() {
        val png = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13) + "IHDR".toByteArray() + bytes(0, 0, 0xc3, 0x50, 0, 0, 0xc3, 0x50, 8, 6, 0, 0, 0)
        val p = sniffImage(png)!!
        assertEquals(ImageKind.PNG, p.kind)
        assertEquals(50_000, p.width)
        assertTrue(p.alpha)
        assertFalse(withinPixelLimit(p.width, p.height), "解码炸弹")
        val gif = sniffImage("GIF89a".toByteArray() + bytes(4, 0, 3, 0))!!
        assertEquals(ImageKind.GIF to (4 to 3), gif.kind to (gif.width to gif.height))
        val webp = sniffImage("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WEBPVP8X".toByteArray() + bytes(0, 0, 0, 0, 0x10, 0, 0, 0, 99, 0, 0, 49, 0, 0))!!
        assertEquals(ImageKind.WEBP, webp.kind)
        assertEquals(100 to 50, webp.width to webp.height)
        assertTrue(webp.alpha)
        assertEquals(ImageKind.HEIC, sniffImage(bytes(0, 0, 0, 24) + "ftypheic".toByteArray())!!.kind)
        assertNull(sniffImage("<html>".toByteArray()))
        assertTrue(withinPixelLimit(4000, 3000))
        assertFalse(withinPixelLimit(null, 10))
    }

    @Test fun `大小、文件名中间省略、占位尺寸、类型标签`() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("5 MB", formatSize(5L * 1024 * 1024))
        assertEquals("100 MB", formatSize(100L * 1024 * 1024))
        val (head, tail) = splitName("长".repeat(196) + ".txt")
        assertEquals("长".repeat(8) + ".txt", tail)
        assertEquals(188, head.length)
        assertEquals("" to "a.pdf", splitName("a.pdf"))
        assertEquals("" to "file", splitName(""))
        assertEquals(200 to 150, displaySize(4000, 3000))
        assertEquals(40 to 200, displaySize(10, 1000))
        assertEquals(160 to 120, displaySize(null, null))
        assertEquals("PDF", fileTypeLabel("报告.PDF"))
        assertEquals("FILE", fileTypeLabel("data.xyz"))
    }

    @Test fun `打开白名单：pdf、txt、图片；扩展名和类型都要对（AC-FILE-03-9）`() {
        assertTrue(canOpen("application/pdf", "a.pdf"))
        assertTrue(canOpen("text/plain", "a.txt"))
        assertTrue(canOpen("image/png", "a.png"))
        assertFalse(canOpen("application/octet-stream", "a.xyz"))
        assertFalse(canOpen("application/pdf", "a.exe"))
        assertFalse(canOpen("text/html", "a.txt"))
    }

    private fun msg(type: MsgType, error: SendError, fileId: String = "", reason: String? = null) = Message(
        clientMsgId = "c", convId = "c2c:a:b", from = "a", type = type, ts = 1, status = MessageStatus.FAILED, error = error, errorReason = reason,
        body = if (type == MsgType.IMAGE) MsgBody(image = ImageBody(fileId)) else MsgBody(file = FileBody(fileId)),
    )

    @Test fun `失败文案：PRD 写了的用 PRD 的（AC-FILE-01-12、01-17，F-FILE-02 规则 2，F-FILE-07）`() {
        assertEquals("今天的上传额度已用完", attachmentErrorText(msg(MsgType.IMAGE, SendError.Code(4292))))
        assertEquals("上传任务太多，请稍后再试", attachmentErrorText(msg(MsgType.FILE, SendError.Code(4290), reason = "uploadPendingLimit")))
        assertEquals("无法处理这张图片，请用文件方式发送", attachmentErrorText(msg(MsgType.IMAGE, SendError.Process)))
        assertEquals("存储空间不足", attachmentErrorText(msg(MsgType.FILE, SendError.Storage)))
        assertEquals("原文件已不可用，请重新选择", attachmentErrorText(msg(MsgType.IMAGE, SendError.SourceMissing)))
        assertEquals("文件过大，最大 100 MB", attachmentErrorText(msg(MsgType.FILE, SendError.Code(4139))))
        assertEquals("文件上传失败，请检查网络后重发", attachmentErrorText(msg(MsgType.FILE, SendError.Network)))
        // 已经上传完：4292 来自 send（发起新聊天的次数），不是上传额度
        assertFalse(attachmentErrorText(msg(MsgType.IMAGE, SendError.Code(4292), fileId = "F1")).contains("上传额度"))
        assertEquals("[图片]", attachmentSummary(MsgType.IMAGE))
        assertEquals("[文件]", attachmentSummary(MsgType.FILE))
        assertNull(attachmentSummary(MsgType.TEXT))
    }
}
