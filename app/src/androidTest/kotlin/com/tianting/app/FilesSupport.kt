// 图片和文件用例的公共部分（FE-635、FE-636）：测试图片、自动应答的假对象存储、真实测试服务端旁边的假 S3（scripts/fake_s3.py）。
package com.tianting.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.sdk.HttpCall
import com.tianting.sdk.ObjectCallback
import com.tianting.sdk.ObjectTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 测试用的图片和文件内容。都在模拟器上现做，不进仓库。 */
object TestMedia {
    fun dir(): File = File(testContext.cacheDir, "test-media").apply { mkdirs() }

    fun write(name: String, bytes: ByteArray): File = File(dir(), name).apply { writeBytes(bytes) }

    fun uri(f: File): Uri = Uri.fromFile(f)

    /** 纯色或左右两色的位图。 */
    fun bitmap(w: Int, h: Int, left: Int = Color.RED, right: Int = left, alpha: Boolean = false): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (alpha) b.eraseColor(Color.TRANSPARENT)
        val p = Paint()
        p.color = left
        c.drawRect(0f, 0f, w / 2f, h.toFloat(), p)
        p.color = right
        c.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    fun encode(b: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, q: Int = 95): ByteArray =
        ByteArrayOutputStream().also { b.compress(format, q, it) }.toByteArray()

    fun jpeg(w: Int, h: Int, color: Int = Color.RED): ByteArray = encode(bitmap(w, h, color))

    /**
     * 带 EXIF 的 jpeg（TC-FILE-02-04）：Orientation、相机型号（特征串 ZXQ-CAM-77）、GPS 纬度。APP1 插在 SOI 后面。
     * 像素是左红右蓝；Orientation=6 的正确显示是上红下蓝（顺时针转 90 度）。
     */
    fun jpegWithExif(w: Int, h: Int, orientation: Int): ByteArray {
        val plain = encode(bitmap(w, h, Color.RED, Color.BLUE), q = 100)
        val model = "ZXQ-CAM-77\u0000".toByteArray(Charsets.US_ASCII)
        // TIFF（小端）：头 8 + IFD0（3 项）+ 型号 + GPS IFD（2 项）+ 纬度（3 个 RATIONAL）
        val ifd0 = 8
        val ifd0Size = 2 + 3 * 12 + 4
        val modelAt = ifd0 + ifd0Size
        val gpsAt = modelAt + model.size
        val gpsSize = 2 + 2 * 12 + 4
        val latAt = gpsAt + gpsSize
        val tiff = ByteBuffer.allocate(latAt + 24).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifd0)
        tiff.putShort(3)
        tiff.putShort(0x0110).putShort(2).putInt(model.size).putInt(modelAt) // Model
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0) // Orientation
        tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(gpsAt) // GPS IFD
        tiff.putInt(0)
        tiff.put(model)
        tiff.putShort(2)
        tiff.putShort(0x0001).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0) // GPSLatitudeRef
        tiff.putShort(0x0002).putShort(5).putInt(3).putInt(latAt) // GPSLatitude
        tiff.putInt(0)
        tiff.putInt(39).putInt(1).putInt(54).putInt(1).putInt(1234).putInt(100)
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
        val app1 = byteArrayOf(0xff.toByte(), 0xe1.toByte(), ((exif.size + 2) shr 8).toByte(), ((exif.size + 2) and 0xff).toByte()) + exif
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    /** 两帧的 1×1 GIF89a（全局色表红、蓝；每帧是经典的 1×1 LZW 数据 02 02 44 01 00）。 */
    fun gif(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0)) // 1×1，全局色表 2 色
        out.write(byteArrayOf(0xff.toByte(), 0, 0, 0, 0, 0xff.toByte())) // 红、蓝
        out.write(byteArrayOf(0x21, 0xff.toByte(), 0x0b) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
        repeat(2) {
            out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0)) // 0.1 秒
            out.write(byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            out.write(byteArrayOf(2, 2, 0x44, 1, 0))
        }
        out.write(0x3b)
        return out.toByteArray()
    }

    /** 头部写着 50000×50000 的 png（解码炸弹的替身，AC-FILE-02-12）：只有 IHDR，后面是垃圾。 */
    fun pngBombHeader(): ByteArray {
        val b = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        b.put(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        b.putInt(13).put("IHDR".toByteArray()).putInt(50_000).putInt(50_000).put(8).put(6).put(0).put(0).put(0).putInt(0)
        return b.array()
    }

    /** 像 pdf 的内容（%PDF 开头，嗅探成 application/pdf），size 字节。 */
    fun pdf(size: Int): ByteArray {
        val head = "%PDF-1.4\n%âãÏÓ\n1 0 obj << /Type /Catalog >> endobj\n".toByteArray(Charsets.ISO_8859_1)
        val r = java.util.Random(7)
        return ByteArray(size) { i -> if (i < head.size) head[i] else ('a'.code + r.nextInt(26)).toByte() }
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun sha256(f: File): String = sha256(f.readBytes())
}

/**
 * 假对象存储：PUT 直接 200（holdPuts 时不回，测"上传中"），GET 按 bodies 里的内容回（按 fileId + variant 找，地址里有它们）。
 * 只在假服务端的 UI 用例里用（FilesUiTest）。
 */
class AutoObjects : ObjectTransport {
    val puts = CopyOnWriteArrayList<String>()
    val gets = CopyOnWriteArrayList<String>()
    val bodies = ConcurrentHashMap<String, ByteArray>() // "fileId/variant" → 内容
    @Volatile var holdPuts = false
    /** GET 写一半就停住（测"下载中"被撤回）：取消时回失败。 */
    @Volatile var holdGets = false
    private val net = Executors.newSingleThreadExecutor()

    override fun put(url: String, headers: Map<String, String>, file: File, onProgress: (Long) -> Unit, callback: ObjectCallback): HttpCall {
        puts.add(url)
        if (holdPuts) {
            net.execute { onProgress(file.length() / 2) }
            return HttpCall { net.execute { callback.onFailure() } }
        }
        net.execute {
            onProgress(file.length())
            callback.onDone(200)
        }
        return HttpCall { }
    }

    override fun get(url: String, dest: File, onProgress: (Long) -> Unit, callback: ObjectCallback): HttpCall {
        gets.add(url)
        if (holdGets) {
            net.execute {
                val key = Uri.parse(url).path.orEmpty().removePrefix("/chat/")
                val body = bodies[key] ?: ByteArray(4096)
                dest.writeBytes(body.copyOf(body.size / 2))
                onProgress((body.size / 2).toLong())
            }
            return HttpCall { net.execute { callback.onFailure() } }
        }
        net.execute {
            val key = Uri.parse(url).path.orEmpty().removePrefix("/chat/")
            val body = bodies[key]
            if (body == null) {
                callback.onDone(404)
            } else {
                dest.writeBytes(body)
                onProgress(body.size.toLong())
                callback.onDone(200)
            }
        }
        return HttpCall { }
    }
}

/** scripts/fake_s3.py 的测试接口（模拟器经 adb reverse 访问）。 */
class FakeS3 {
    private val args = InstrumentationRegistry.getArguments()
    val base: String = args.getString("ttS3Url") ?: ""
    private val http = OkHttpClient()

    fun guard() = assumeTrue("需要 scripts/run-tests.sh 启动带假 S3 的测试服务端（TT_FILES=1）", args.getString("ttFiles") == "1" && base.isNotEmpty())

    private fun get(path: String): String = http.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
        check(r.isSuccessful) { "fake s3 $path: ${r.code}" }
        r.body?.string().orEmpty()
    }

    fun ctl(vararg kv: Pair<String, Any>) {
        get("/__ctl?" + kv.joinToString("&") { "${it.first}=${it.second}" })
    }

    fun reset() = ctl("reset" to 1)

    /** 预签名 GET 的次数（按变体），预签名 PUT 的次数。 */
    fun stats(): Pair<Map<String, Int>, Int> {
        val o = Json.parseToJsonElement(get("/__stats")).jsonObject
        val gets = (o["gets"] as JsonObject).mapValues { it.value.jsonPrimitive.content.toInt() }
        return gets to o["puts"]!!.jsonPrimitive.content.toInt()
    }

    class Obj(val key: String, val size: Long, val sha256: String, val type: String)

    fun list(prefix: String, bucket: String = "chat"): List<Obj> = (Json.parseToJsonElement(get("/__list?bucket=$bucket&prefix=" + Uri.encode(prefix))) as JsonArray).map {
        val o = it.jsonObject
        Obj(o["key"]!!.jsonPrimitive.content, o["size"]!!.jsonPrimitive.content.toLong(), o["sha256"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.content)
    }

    fun raw(key: String, bucket: String = "chat"): ByteArray = http.newCall(Request.Builder().url("$base/__raw?bucket=$bucket&key=" + Uri.encode(key)).build()).execute().use { r ->
        check(r.isSuccessful) { "raw: ${r.code}" }
        r.body!!.bytes()
    }

    /** 直接改写一个对象（测试替身：例如把缩略图换成解码炸弹）。不经过 im-server。 */
    fun overwrite(key: String, body: ByteArray, type: String, bucket: String = "chat") {
        val req = Request.Builder().url("$base/$bucket/$key").put(body.toRequestBody(type.toMediaType())).build()
        http.newCall(req).execute().use { check(it.isSuccessful) { "overwrite: ${it.code}" } }
    }

    /** 这个 fileId 的最终对象（final/{fileId}/{attemptId}/{variant}）。 */
    fun finalOf(fileId: String, variant: String): Obj? = list("final/$fileId/").firstOrNull { it.key.endsWith("/$variant") }
}

/** JSON 数组的字符串值。 */
fun JsonObject.strings(key: String): List<String> = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

/** 真实依赖，只换掉选择器和"保存到"（系统界面）。 */
fun realDepsWithPicks(pick: (com.tianting.app.core.PickKind) -> List<Uri>?, save: ((String) -> Uri?)? = null): com.tianting.app.core.AppDeps {
    val d = com.tianting.app.core.defaultDeps(testContext)
    return com.tianting.app.core.AppDeps(
        d.newClient, d.login, d.sessions, d.isOnline, d.bindNetwork, d.account, d.bindAccountNetwork, d.collator, d.copyText, d.copySecret, d.demoBuild,
        d.newFiles,
        pickForTest = pick,
        saveForTest = save,
    )
}

/**
 * 在另一条线程上跑挂起调用并等结果。测试线程上直接 runBlocking 会借用 Compose 测试规则外层 runTest 的事件循环，
 * 顺带在测试线程上执行界面的帧（measureAndLayoutForTest），和主线程的绘制并发："Detected multithreaded access to SnapshotStateObserver"。
 */
fun <T> offThread(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
    var out: Result<T>? = null
    val t = Thread { out = runCatching { kotlinx.coroutines.runBlocking(block = block) } }
    t.start()
    t.join()
    return out!!.getOrThrow()
}
