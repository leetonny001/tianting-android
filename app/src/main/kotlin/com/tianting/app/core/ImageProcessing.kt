// 发送前的图片处理（PRD-chat F-FILE-02 规则 2～4、F-ACC-06 规则 3）：Android 用系统图片接口。
// 先按 EXIF 方向转正再重新编码，所以输出里没有 EXIF、GPS；长边超过 4096 缩小；缩略图长边 480、≤ 512 KiB；
// GIF 不重新编码（只为缩略图解出首帧）。解码前先看头部的宽高，超过像素上限的不解码（规则 3）。
// API 28+ 用 ImageDecoder（会按 EXIF 方向转正，也能解 HEIC，看系统解码器）；更早的用 BitmapFactory + ExifInterface 自己转。
// SDK 只接收处理好的结果（R-CF-24）。
package com.tianting.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
// 系统的 ExifInterface（API 24 起支持 InputStream / 路径）：androidx.exifinterface 不在依赖里，不新增依赖。只在 API 28 以下用。
import android.media.ExifInterface
import android.os.Build
import com.tianting.sdk.LocalFile
import com.tianting.sdk.ProcessedImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** 处理失败：界面统一提示"无法处理这张图片，请用文件方式发送"（规则 2）。why 只是分类，不带文件名。 */
class ImageProcessError(val why: String) : Exception("image process: $why")

private const val HEAD_BYTES = 256 * 1024
private val QUALITIES = listOf(90, 80, 70, 55, 40)

object ImageProcessing {
    /** 读文件头（不解码像素）。 */
    fun readHead(file: File): ImageHead? = try {
        file.inputStream().use { input ->
            val buf = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            sniffImage(buf.copyOf(n))
        }
    } catch (_: java.io.IOException) {
        null
    }

    /** 处理一张要发送的图片（F-FILE-02 规则 2～4）。结果写在 outDir 下，SDK 复制进沙盒后可以删掉。 */
    fun process(src: File, outDir: File): ProcessedImage {
        val head = readHead(src) ?: throw ImageProcessError("unknown")
        // 认得的头部有宽高时先查像素上限；HEIC 没有解析头部，由解码器的头部回调再查一次。
        if (head.width != null && !withinPixelLimit(head.width, head.height)) throw ImageProcessError("tooManyPixels")
        outDir.mkdirs()
        val id = UUID.randomUUID().toString()
        return try {
            if (head.kind == ImageKind.GIF) {
                // GIF 不重新编码（规则 4）：原样上传，缩略图用首帧。
                if (src.length() > IMAGE_MAX_BYTES) throw ImageProcessError("tooLarge")
                val original = File(outDir, "$id-o.gif")
                src.copyTo(original, overwrite = true)
                val first = decodeOriented(src, THUMB_LONG_SIDE * 2)
                val thumb = encodeUnder(first, THUMB_LONG_SIDE, first.hasAlpha(), THUMB_MAX_BYTES, shrink = true, File(outDir, "$id-t"))
                    ?: throw ImageProcessError("thumb")
                val w = head.width ?: first.width
                val h = head.height ?: first.height
                first.recycle()
                ProcessedImage(LocalFile(original, "image/gif"), thumb.local, w, h)
            } else {
                val bmp = decodeOriented(src, ORIGINAL_LONG_SIDE)
                // 透明的 png、webp 保留透明（输出 png），其他输出 jpeg（规则 2 的矩阵）。
                val alpha = head.alpha && bmp.hasAlpha()
                val main = encodeUnder(bmp, ORIGINAL_LONG_SIDE, alpha, IMAGE_MAX_BYTES, shrink = false, File(outDir, "$id-o"))
                    ?: throw ImageProcessError("tooLarge")
                val thumb = encodeUnder(bmp, THUMB_LONG_SIDE, alpha, THUMB_MAX_BYTES, shrink = true, File(outDir, "$id-t"))
                    ?: throw ImageProcessError("thumb")
                bmp.recycle()
                ProcessedImage(main.local, thumb.local, main.width, main.height)
            }
        } catch (e: OutOfMemoryError) {
            throw ImageProcessError("memory")
        }
    }

    /** 头像（F-ACC-06 规则 3）：已经解码转正的图上取正方形 (x, y, side)，缩放到 512×512，jpeg。 */
    fun avatar(src: Bitmap, x: Int, y: Int, side: Int, out: File): File {
        val s = side.coerceAtMost(minOf(src.width, src.height)).coerceAtLeast(1)
        val cx = x.coerceIn(0, src.width - s)
        val cy = y.coerceIn(0, src.height - s)
        val square = Bitmap.createBitmap(src, cx, cy, s, s)
        val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIDE, AVATAR_SIDE, true)
        if (square !== src && square !== scaled) square.recycle()
        out.parentFile?.mkdirs()
        for (q in QUALITIES) {
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, q, it) }
            if (out.length() <= AVATAR_MAX_BYTES) break
        }
        scaled.recycle()
        return out
    }

    /**
     * 解码并按 EXIF 方向转正，长边不超过 maxLong（只缩小）。超过像素上限、解不了：ImageProcessError。
     * 用于发送前处理、头像裁剪的预览。
     */
    fun decodeOriented(src: File, maxLong: Int): Bitmap {
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeWithImageDecoder(src, maxLong) else decodeLegacy(src, maxLong)
        val out = scaledTo(bmp, maxLong)
        if (out !== bmp) bmp.recycle()
        return out
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(src: File, maxLong: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (!withinPixelLimit(w, h)) throw ImageProcessError("tooManyPixels")
            val s = minOf(1.0, maxLong.toDouble() / max(w, h))
            if (s < 1.0) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: ImageProcessError) {
        throw e
    } catch (e: java.io.IOException) {
        throw ImageProcessError("decode")
    } catch (e: IllegalArgumentException) {
        throw ImageProcessError("decode")
    }

    private fun decodeLegacy(src: File, maxLong: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (!withinPixelLimit(bounds.outWidth, bounds.outHeight)) throw ImageProcessError(if (bounds.outWidth > 0) "tooManyPixels" else "decode")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLong) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw ImageProcessError("decode")
        val orientation = try {
            ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: java.io.IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** 长边缩到 long 以内（只缩小）。不需要缩小时返回同一个对象；传入的图不回收。 */
    private fun scaledTo(bmp: Bitmap, long: Int): Bitmap {
        val s = minOf(1.0, long.toDouble() / max(bmp.width, bmp.height))
        if (s >= 1.0) return bmp
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
    }

    private class Encoded(val local: LocalFile, val width: Int, val height: Int)

    /** 编码到不超过 max 字节：先降质量，还不行再缩小（shrink，缩略图）。做不到返回 null。 */
    private fun encodeUnder(bmp: Bitmap, long: Int, alpha: Boolean, maxBytes: Long, shrink: Boolean, base: File): Encoded? {
        val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (alpha) "image/png" else "image/jpeg"
        val out = File(base.path + if (alpha) ".png" else ".jpg")
        var side = long
        while (side >= 120) {
            val scaled = scaledTo(bmp, side)
            try {
                for (q in if (alpha) listOf(100) else QUALITIES) {
                    FileOutputStream(out).use { scaled.compress(format, q, it) }
                    if (out.length() <= maxBytes) return Encoded(LocalFile(out, mime), scaled.width, scaled.height)
                }
            } finally {
                if (scaled !== bmp) scaled.recycle()
            }
            if (!shrink) break
            side = (side * 0.75).roundToInt()
        }
        out.delete()
        return null
    }
}
