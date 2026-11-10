package com.tianting.app

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.tianting.app.core.ATTACHMENT_DIR
import com.tianting.app.core.AppDeps
import com.tianting.app.core.PickKind
import com.tianting.app.core.chatPath
import com.tianting.app.ui.DataStatus
import com.tianting.sdk.ConnState
import com.tianting.sdk.FileClient
import com.tianting.sdk.FileInput
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.LocalFile
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgType
import com.tianting.sdk.Platform
import com.tianting.sdk.ProcessedImage
import com.tianting.sdk.StaticTokenAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FE-635、FE-636 对真实 im-server（IM_FILES_ENABLED=1）+ 假 S3（scripts/fake_s3.py）的端到端：
 * scripts/run-tests.sh e2e com.tianting.app.FilesE2eTest。App 是真实依赖（演示登录、真实 OkHttp、真实本地库、真实图片处理），
 * 选择器和"保存到"用测试替身（系统照片选择器、SAF 是系统界面）。对方用同一进程里的另一个 IMClient。结果是模拟器、本地。
 */
class FilesE2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val s3 = FakeS3()
    private val ui = E2eUi(rule)
    private val peers = ArrayList<IMClient>()
    private val picks = java.util.concurrent.CopyOnWriteArrayList<android.net.Uri>()
    private val saves = java.util.concurrent.CopyOnWriteArrayList<File>()
    private val model get() = testApp.model
    private fun uid(p: String) = p + System.currentTimeMillis().toString(36) + (0..999).random()

    @Before fun setup() {
        server.guard()
        s3.guard()
        s3.reset()
        server.resetLimits()
        TestMedia.dir().deleteRecursively()
    }

    @After fun cleanup() {
        runCatching { s3.reset() }
        setAirplane(false)
        ui.scenario?.close()
        inst.runOnMainSync { testApp.model.logout() }
        offThread {
            testApp.model.awaitTeardown()
            for (p in peers) {
                runCatching { p.disconnect() }
                runCatching { p.close() }
            }
        }
    }

    // ---- 准备 ----

    private fun token(u: String) = server.admin("POST", "/admin/users/token", obj("userId" to u))["token"]!!.jsonPrimitive.content

    /** 对方：带文件接口的 IMClient（演示 token）。沙盒、缓存放在测试自己的目录。 */
    private fun peer(u: String): IMClient {
        server.admin("POST", "/admin/users/import", obj("users" to listOf(obj("userId" to u))))
        val t = token(u)
        val root = File(testContext.cacheDir, "peer-$u")
        val c = IMClient(
            IMClientOptions(
                url = "${server.base.replace("http://", "ws://")}/ws", platform = Platform.WEB,
                files = FileClient(StaticTokenAuth(server.base, t)),
                attachmentDir = File(root, "a"), fileCacheDir = File(root, "c"),
            ),
        )
        peers.add(c)
        c.connect(t)
        until("peer connected") { c.state.value == ConnState.CONNECTED }
        return c
    }

    private fun deps(): AppDeps = realDepsWithPicks(
        { _ -> picks.toList() },
        { name -> File(TestMedia.dir(), "saved-" + saves.size + "-" + name.takeLast(12).filter { it.isLetterOrDigit() || it == '.' }).also { saves.add(it) }.let { android.net.Uri.fromFile(it) } },
    )

    /** 演示登录进 App（真实界面）。 */
    private fun appLogin(u: String) {
        replaceWithRealDeps(deps())
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", u)
        val end = System.currentTimeMillis() + 15_000
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("login-submit")) runCatching { rule.onNodeWithTag("login-submit", useUnmergedTree = true).performClick() }
            Thread.sleep(700)
        }
        ui.waitTag("tab-chats", 15_000)
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        rule.waitUntil(15_000) { model.im?.filesEnabled == true } // 配置拉到了
    }

    private fun open(path: String) {
        inst.runOnMainSync { model.nav.resetTo(path) }
        rule.waitForIdle()
    }

    private fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(30)
        }
    }

    private fun pick(kind: PickKind, vararg files: File) {
        picks.clear()
        files.forEach { picks.add(TestMedia.uri(it)) }
        ui.tap("attach-button")
        ui.tap(if (kind == PickKind.IMAGES) "attach-image" else "attach-file")
    }

    /** App 里这个会话的附件消息。 */
    private fun appAttachments(conv: String): List<Message> = model.im!!.client.messages(conv).filter { it.type == MsgType.IMAGE || it.type == MsgType.FILE }

    private fun peerMessages(p: IMClient, conv: String, type: MsgType): List<Message> {
        offThread { runCatching { p.loadHistory(conv) } }
        return p.messages(conv).filter { it.type == type && it.seq != null }
    }

    /** 对方发一张图（处理好的原图、缩略图）。 */
    private fun peerImage(p: IMClient, conv: String, orig: ByteArray, thumb: ByteArray, w: Int, h: Int, mime: String = "image/jpeg"): Message {
        val o = TestMedia.write("p-o-" + System.nanoTime(), orig)
        val t = TestMedia.write("p-t-" + System.nanoTime(), thumb)
        val m = offThread { p.sendImage(conv, ProcessedImage(LocalFile(o, mime), LocalFile(t, "image/jpeg"), w, h)) }
        until("peer image sent", 20_000) {
            val now = p.messages(conv).firstOrNull { it.clientMsgId == m.clientMsgId }
            check(now?.status != MessageStatus.FAILED) { "对方的图片发送失败：${now?.error}" }
            now?.status == MessageStatus.SENT
        }
        return p.messages(conv).first { it.clientMsgId == m.clientMsgId }
    }

    private fun peerFile(p: IMClient, conv: String, name: String, bytes: ByteArray, mime: String): Message {
        val f = TestMedia.write("p-f-" + System.nanoTime(), bytes)
        val m = offThread { p.sendFile(conv, FileInput(f, name, mime)) }
        until("peer file sent", 30_000) {
            val now = p.messages(conv).firstOrNull { it.clientMsgId == m.clientMsgId }
            check(now?.status != MessageStatus.FAILED) { "对方的文件发送失败：${now?.error}" }
            now?.status == MessageStatus.SENT
        }
        return p.messages(conv).first { it.clientMsgId == m.clientMsgId }
    }

    private fun statusOf(clientMsgId: String): String? = rule.onAllNodes(hasTestTag("message"), useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull { it.config.getOrNull(com.tianting.app.ui.DataClientMsgId) == clientMsgId }?.config?.getOrNull(DataStatus)

    private fun setAirplane(on: Boolean) {
        inst.uiAutomation.executeShellCommand("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}").close()
        val cm = testContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && (cm.activeNetwork == null) == !on) Thread.sleep(100)
    }

    private fun sandbox(): List<File> = File(testContext.filesDir, ATTACHMENT_DIR).listFiles()?.toList().orEmpty()

    /** 点失败图标、确认重发。重发可能很快又失败（故障注入），等待条件由调用方给。 */
    private fun tapRetry() {
        ui.waitTag("failed-icon")
        rule.onAllNodes(hasTestTag("failed-icon"), useUnmergedTree = true)[0].performClick()
        ui.tap("retry-dialog-ok")
    }

    private var appConv: String? = null

    /** App（a）和对方（b）的单聊，已打开聊天页。 */
    private fun c2c(): Triple<String, IMClient, String> {
        val (a, b) = uid("fa") to uid("fb")
        val bob = peer(b)
        appLogin(a)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        appConv = conv
        open(chatPath(conv))
        ui.waitTag("composer-input")
        return Triple(a, bob, conv)
    }

    // ---- 用例 ----

    @Test fun tc_file_02_01_jpeg_both_ways_thumb_then_original() { // TC-FILE-02-01（Android 部分）、AC-FILE-02-1、TC-FILE-02-08、AC-FILE-02-8
        val since = System.currentTimeMillis()
        val (_, bob, conv) = c2c()
        // App → 对方：2 MiB 左右的 jpeg（噪点，处理后仍是 jpeg）
        val big = android.graphics.Bitmap.createBitmap(1600, 1200, android.graphics.Bitmap.Config.ARGB_8888)
        val r = java.util.Random(3)
        val px = IntArray(1600 * 1200) { Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
        big.setPixels(px, 0, 1600, 0, 0, 1600, 1200)
        val src = TestMedia.write("photo-2m.jpg", TestMedia.encode(big, q = 92))
        pick(PickKind.IMAGES, src)
        until("app image sent", 30_000) { appAttachments(conv).singleOrNull()?.status == MessageStatus.SENT }
        val mine = appAttachments(conv).single()
        until("bob received", 15_000) { peerMessages(bob, conv, MsgType.IMAGE).isNotEmpty() }
        val got = peerMessages(bob, conv, MsgType.IMAGE).single()
        assertEquals(mine.body.image!!.size, got.body.image!!.size, "两边 size 一致")
        assertEquals("image/jpeg", got.body.image!!.mime)
        assertEquals(mine.body.image!!.mime, got.body.image!!.mime)
        assertEquals(1600, got.body.image!!.width)
        // 对方 → App：先显示缩略图，点开看原图
        s3.reset()
        peerImage(bob, conv, TestMedia.jpeg(1200, 900, Color.GREEN), TestMedia.jpeg(480, 360, Color.GREEN), 1200, 900)
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("image-loaded", useUnmergedTree = true).fetchSemanticsNodes().size >= 2 }
        assertEquals(0, s3.stats().first["original"] ?: 0, "列表里只取缩略图")
        rule.onAllNodesWithTag("image-loaded", useUnmergedTree = true)[0].performClick()
        ui.waitTag("viewer-image", 15_000)
        assertTrue((s3.stats().first["original"] ?: 0) >= 1, "全屏取原图")
        ui.tap("viewer-close")
        // 会话列表摘要（AC-FILE-02-8）
        open("/chats")
        ui.waitText("[图片]")
        // AC-FILE-04-11：上传下载之后，App 进程的 Logcat 里没有预签名查询串和文件名
        val scan = scanOwnLogcat(since, mapOf("signature" to "X-Amz-Signature", "credential" to "X-Amz-Credential", "name" to "photo-2m"))
        assertTrue(scan.lines > 0, "读到了日志")
        assertEquals(emptyList(), scan.found)
    }

    @Test fun tc_file_02_04_exif_and_gps_removed_orientation_applied() { // TC-FILE-02-04（Android 部分）、AC-FILE-02-4、问题 H18 同法
        val (_, bob, conv) = c2c()
        pick(PickKind.IMAGES, TestMedia.write("exif.jpg", TestMedia.jpegWithExif(40, 20, 6)))
        until("sent", 30_000) { appAttachments(conv).singleOrNull()?.status == MessageStatus.SENT }
        val img = appAttachments(conv).single().body.image!!
        assertEquals(20, img.width, "按 EXIF 方向转正后是竖的")
        assertEquals(40, img.height)
        for (variant in listOf("original", "thumb")) {
            val o = assertNotNull(s3.finalOf(img.fileId, variant), "最终对象 $variant")
            val bytes = s3.raw(o.key)
            val head = com.tianting.app.core.sniffImage(bytes)
            assertEquals(com.tianting.app.core.ImageKind.JPEG, head?.kind)
            assertEquals(null, head?.orientation, "$variant 没有 EXIF 方向")
            assertFalse(containsBytes(bytes, "Exif".toByteArray()), "$variant 没有 Exif 段")
            assertFalse(containsBytes(bytes, "ZXQ-CAM-77".toByteArray()), "$variant 没有相机型号")
            assertFalse(hasApp1(bytes), "$variant 没有 APP1")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(20, bmp.width)
            assertEquals(40, bmp.height)
            // 上红下蓝（原图左红右蓝，Orientation=6 顺时针转 90 度）
            val top = bmp.getPixel(10, 5)
            val bottom = bmp.getPixel(10, 35)
            assertTrue(Color.red(top) > 180 && Color.blue(top) < 80, "上面是红：${Integer.toHexString(top)}")
            assertTrue(Color.blue(bottom) > 180 && Color.red(bottom) < 80, "下面是蓝：${Integer.toHexString(bottom)}")
        }
        until("bob received") { peerMessages(bob, conv, MsgType.IMAGE).isNotEmpty() }
    }

    private fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** jpeg 的段里有没有 APP1（0xFFE1），扫描数据之前。 */
    private fun hasApp1(b: ByteArray): Boolean {
        var i = 2
        while (i + 4 <= b.size && b[i] == 0xff.toByte()) {
            val marker = b[i + 1].toInt() and 0xff
            if (marker == 0xe1) return true
            if (marker == 0xda) return false
            i += 2 + (((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff))
        }
        return false
    }

    @Test fun tc_file_02_05_cancel_mid_upload_never_sends() { // TC-FILE-02-05（Android，真实服务端）、AC-FILE-02-5
        val (_, bob, conv) = c2c()
        s3.ctl("put_delay_ms" to 8000)
        pick(PickKind.FILES, TestMedia.write("doc.zip", ByteArray(200_000) { 3 }))
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("attachment-status", useUnmergedTree = true).fetchSemanticsNodes().any { allText(it).startsWith("上传中") } }
        ui.tap("attachment-cancel")
        until("gone") { appAttachments(conv).isEmpty() }
        rule.waitUntil(5000) { !ui.hasTag("file-bubble") }
        Thread.sleep(9000) // PUT 的响应迟到之后也不会发
        assertTrue(peerMessages(bob, conv, MsgType.FILE).isEmpty(), "对方没有收到")
        assertTrue(appAttachments(conv).isEmpty())
        assertEquals(emptyList(), sandbox(), "沙盒副本删除")
    }

    @Test fun tc_file_02_06_placeholder_keeps_next_message_still() { // TC-FILE-02-06、AC-FILE-02-6
        val (_, bob, conv) = c2c()
        // 先调慢再让对方发图（问题 74）：c2c() 已经打开了聊天页，图片一到 App 就去取缩略图，调慢晚了缩略图已经按正常速度取完，看不到"加载中"。
        // 假 S3 每写完 16 KiB 才等一次：纯色的 480×360 缩略图只有约 1.8 KB，一块就写完，调慢不起作用；用噪点图（远大于 16 KiB）。
        s3.ctl("get_chunk_ms" to 1500) // 缩略图慢慢到
        val noise = android.graphics.Bitmap.createBitmap(480, 360, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(6)
        for (y in 0 until 360) for (x in 0 until 480) noise.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        val thumb = TestMedia.encode(noise)
        check(thumb.size > 3 * 16 * 1024) { "缩略图太小，调慢不起作用：${thumb.size}" }
        peerImage(bob, conv, TestMedia.jpeg(4000, 3000, Color.BLUE), thumb, 4000, 3000)
        offThread { bob.send(conv, "图片下面的一条") }
        open("/chats")
        open(chatPath(conv))
        ui.waitText("图片下面的一条")
        ui.waitTag("image-loading")
        val next = { rule.onNode(hasTestTag("message") and hasAnyDescendant(hasText("图片下面的一条")), useUnmergedTree = true).getUnclippedBoundsInRoot() }
        val before = next()
        // 量"之前"的位置时还在加载（占位在、图还没到），否则下面"没有移动"比的是加载完之后的两次位置（问题 74）
        assertTrue(ui.hasTag("image-loading") && !ui.hasTag("image-loaded"), "量位置时缩略图已经到了")
        rule.waitUntil(30_000) { ui.hasTag("image-loaded") }
        rule.waitForIdle()
        val after = next()
        val dy = kotlin.math.abs((after.top - before.top).value)
        assertTrue(dy <= 1f, "下一条消息移动了 $dy dp")
        val box = rule.onNodeWithTag("image-thumb", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(200f, box.width.value, 0.6f, "按宽高比占位（4000×3000 → 200×150）")
        assertEquals(150f, box.height.value, 0.6f)
    }

    @Test fun tc_file_02_09_lazy_thumbs_and_04_10_cache_200() { // TC-FILE-02-09、AC-FILE-02-9、TC-FILE-04-10、AC-FILE-04-10
        val (_, bob, conv) = c2c()
        open("/chats")
        val small = TestMedia.jpeg(64, 48, Color.MAGENTA)
        repeat(200) {
            peerImage(bob, conv, small, small, 64, 48)
            Thread.sleep(80) // 对方自己的发送频率（每秒 20 条）
        }
        s3.reset()
        open(chatPath(conv))
        rule.waitUntil(20_000) { ui.hasTag("image-loaded") }
        Thread.sleep(2000)
        val (gets1, _) = s3.stats()
        val visible = rule.onAllNodesWithTag("image-thumb", useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(0, gets1["original"] ?: 0, "不请求原图")
        val thumbs1 = gets1["thumb"] ?: 0
        assertTrue(thumbs1 in 1..(visible + 4), "只请求视口附近的缩略图：$thumbs1 次，组合了 $visible 个")
        // 连续往上滚到最早，再回到底：没有 4290（失败显示"加载失败"），每张缩略图只下载 1 次
        val list = rule.onNodeWithTag("message-list", useUnmergedTree = true)
        repeat(60) {
            list.performTouchInput { swipeDown(startY = top + 100f, endY = bottom - 100f, durationMillis = 150) }
            rule.waitForIdle()
        }
        repeat(60) {
            list.performTouchInput { swipeUp(startY = bottom - 100f, endY = top + 100f, durationMillis = 150) }
            rule.waitForIdle()
        }
        Thread.sleep(2000)
        assertFalse(ui.hasTag("image-retry"), "没有加载失败（4290）")
        val thumbs2 = s3.stats().first["thumb"] ?: 0
        assertTrue(thumbs2 <= 200, "每张最多下载一次：$thumbs2")
        // 再进一次：全部命中缓存（键 fileId + variant），不再下载
        open("/chats")
        open(chatPath(conv))
        rule.waitUntil(15_000) { ui.hasTag("image-loaded") }
        Thread.sleep(1500)
        assertEquals(thumbs2, s3.stats().first["thumb"] ?: 0, "第二次命中缓存")
    }

    @Test fun tc_file_02_13_over_20_mib_photo_compresses_and_sends() { // TC-FILE-02-13 后半、AC-FILE-02-13
        val (_, bob, conv) = c2c()
        // 像照片：渐变上加小幅噪点（png 压不下去，jpeg 压得下去），6000×4000（2400 万像素，在像素上限内）
        val w = 6000
        val h = 4000
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val r = java.util.Random(5)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val n = r.nextInt(17) - 8
                row[x] = Color.rgb(((x * 200) / w + 20 + n).coerceIn(0, 255), ((y * 200) / h + 20 + r.nextInt(17) - 8).coerceIn(0, 255), (120 + r.nextInt(17) - 8))
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        val src = TestMedia.write("huge.png", TestMedia.encode(bmp, android.graphics.Bitmap.CompressFormat.PNG, 100))
        bmp.recycle()
        assertTrue(src.length() > 20L * 1024 * 1024, "源文件超过 20 MiB：${src.length()}")
        pick(PickKind.IMAGES, src)
        until("sent", 120_000) {
            val m = appAttachments(conv).singleOrNull()
            check(m?.status != MessageStatus.FAILED) { "发送失败：${m?.error}" }
            m?.status == MessageStatus.SENT
        }
        val size = appAttachments(conv).single().body.image!!.size!!
        assertTrue(size <= 20L * 1024 * 1024, "按处理后的大小：$size")
        until("bob received") { peerMessages(bob, conv, MsgType.IMAGE).isNotEmpty() }
    }

    @Test fun tc_file_02_14_gif_first_frame_and_badge() { // TC-FILE-02-14（Android）、AC-FILE-02-14
        val (_, bob, conv) = c2c()
        peerImage(bob, conv, TestMedia.gif(), TestMedia.jpeg(1, 1), 1, 1, mime = "image/gif")
        rule.waitUntil(15_000) { ui.hasTag("gif-badge") }
        assertTrue(ui.hasTag("image-loaded"))
        // App 发 GIF：不重新编码（原样上传）
        val gif = TestMedia.write("anim.gif", TestMedia.gif())
        pick(PickKind.IMAGES, gif)
        val me = model.im!!.self
        until("sent", 30_000) { appAttachments(conv).singleOrNull { it.from == me }?.status == MessageStatus.SENT }
        val img = appAttachments(conv).single { it.from == me }.body.image!!
        assertEquals("image/gif", img.mime)
        assertEquals(TestMedia.sha256(gif), s3.finalOf(img.fileId, "original")!!.sha256, "GIF 原样上传")
    }

    @Test fun tc_file_03_01_group_pdf_sha_and_03_09_open_whitelist_and_05_03_source_prompt() { // TC-FILE-03-01、03-09、05-03，AC-FILE-03-1、03-9、05-3
        val (a, b) = uid("fa") to uid("fb")
        val bob = peer(b)
        appLogin(a)
        val g = uid("g")
        server.admin("POST", "/admin/groups", obj("groupId" to g, "name" to "群$g", "members" to listOf(a, b)))
        val conv = "group:$g"
        appConv = conv
        rule.waitUntil(15_000) { model.im!!.convs.any { it.convId == conv } }
        val pdf = TestMedia.pdf(5 * 1024 * 1024)
        peerFile(bob, conv, "报告.pdf", pdf, "application/pdf")
        open(chatPath(conv))
        ui.waitTag("file-bubble", 15_000)
        assertEquals("报告.pdf", ui.textOf("file-name"))
        assertEquals("5 MB", ui.textOf("file-size"))
        ui.tap("file-download")
        ui.waitTag("source-dialog") // 第一次：来源提示（AC-FILE-05-3）
        assertTrue(ui.hasText("文件来自其他用户，请确认来源可信"))
        ui.tap("source-dialog-ok")
        ui.waitTag("file-save", 30_000)
        assertTrue(ui.hasTag("file-open"), "pdf 有\"打开\"")
        val f = offThread { model.im!!.client.cachedFile(peerMessages(bob, conv, MsgType.FILE).single().body.fileId!!, com.tianting.sdk.FileVariant.ORIGINAL) }!!
        assertEquals(TestMedia.sha256(pdf), TestMedia.sha256(f), "下载内容的 SHA-256 与原文件相同")
        ui.tap("file-save")
        until("saved") { saves.isNotEmpty() && saves[0].length() == pdf.size.toLong() }
        assertEquals(TestMedia.sha256(pdf), TestMedia.sha256(saves[0]))
        // .xyz：只有"保存"；同一会话第二次下载不再提示来源
        peerFile(bob, conv, "data.xyz", ByteArray(4096) { 9 }, "application/octet-stream")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("file-download", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("file-download", useUnmergedTree = true)[0].performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("file-save", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        assertFalse(ui.hasTag("source-dialog"), "同一会话第二次不提示")
        assertEquals(1, rule.onAllNodesWithTag("file-open", useUnmergedTree = true).fetchSemanticsNodes().size, ".xyz 没有\"打开\"")
        // App → 群：成员下载 SHA-256 相同
        val mine = TestMedia.pdf(1024 * 1024 + 17)
        pick(PickKind.FILES, TestMedia.write("mine.pdf", mine))
        until("app file sent", 30_000) { appAttachments(conv).any { it.from == a && it.status == MessageStatus.SENT } }
        until("bob got it") { peerMessages(bob, conv, MsgType.FILE).any { it.from == a } }
        val m = peerMessages(bob, conv, MsgType.FILE).first { it.from == a }
        assertEquals("mine.pdf", m.body.file!!.name)
        val dl = offThread { bob.download(m.body.fileId!!, com.tianting.sdk.FileVariant.ORIGINAL) }
        assertEquals(TestMedia.sha256(mine), TestMedia.sha256(dl))
    }

    @Test fun tc_file_03_05_download_cut_then_retry_from_zero() { // TC-FILE-03-05、AC-FILE-03-5
        val (_, bob, conv) = c2c()
        val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        peerFile(bob, conv, "big.bin", body, "application/octet-stream")
        ui.waitTag("file-download", 15_000)
        s3.reset()
        s3.ctl("get_chunk_ms" to 100)
        ui.tap("file-download")
        if (ui.hasTag("source-dialog")) ui.tap("source-dialog-ok")
        ui.waitTag("file-downloading", 15_000)
        Thread.sleep(1500)
        // 断网：模拟器经 adb reverse 连假 S3，飞行模式断不掉，由假 S3 断开正在传的连接
        s3.ctl("cut_gets" to 1)
        ui.waitTag("file-download-failed", 20_000)
        assertEquals("下载失败，点击重试", ui.textOf("file-download-failed"))
        s3.ctl("get_chunk_ms" to 0)
        ui.tap("file-download-failed")
        ui.waitTag("file-save", 20_000)
        assertEquals(2, s3.stats().first["original"], "重试从头再下一次")
        val f = offThread { model.im!!.client.cachedFile(peerMessages(bob, conv, MsgType.FILE).single().body.fileId!!, com.tianting.sdk.FileVariant.ORIGINAL) }!!
        assertEquals(TestMedia.sha256(body), TestMedia.sha256(f))
    }

    // ---- C4（FE-667）：撤回时正在全屏看、正在下载；引用图片和文件 ----

    @Test fun tc_rcl_03_08_viewer_closes_when_peer_recalls_the_image() { // TC-RCL-03-08、AC-RCL-03-8（真实服务端 + 假 S3）
        val (_, bob, conv) = c2c()
        rule.waitUntil(15_000) { model.im?.msgActions == true }
        val m = peerImage(bob, conv, TestMedia.jpeg(800, 600, Color.BLUE), TestMedia.jpeg(400, 300, Color.BLUE), 800, 600)
        ui.waitTag("image-loaded", 15_000)
        rule.onAllNodesWithTag("image-loaded", useUnmergedTree = true)[0].performClick()
        ui.waitTag("viewer-image", 15_000)
        offThread { bob.recall(conv, m.msgId!!) }
        rule.waitUntil(5000) { !ui.hasTag("image-viewer") }
        ui.waitText("该消息已被撤回", 3000)
        ui.waitText("对方撤回了一条消息", 3000)
        assertFalse(ui.hasTag("image-thumb"))
        val cache = File(testContext.cacheDir, com.tianting.app.core.FILE_CACHE_DIR)
        until("缓存的图片被丢掉", 5000) { cache.walkTopDown().none { it.isFile && m.body.fileId!! in it.name } }
    }

    @Test fun tc_rcl_03_12_download_aborted_when_recalled_no_partial_file() { // TC-RCL-03-12、AC-RCL-03-12（真实服务端 + 假 S3 限速）
        val (_, bob, conv) = c2c()
        rule.waitUntil(15_000) { model.im?.msgActions == true }
        val body = ByteArray(20 * 1024 * 1024) { (it % 253).toByte() }
        val m = peerFile(bob, conv, "大文件ZXQ-RCL-3.bin", body, "application/octet-stream")
        ui.waitTag("file-download", 15_000)
        val cache = File(testContext.cacheDir, com.tianting.app.core.FILE_CACHE_DIR)
        s3.reset()
        s3.ctl("get_chunk_ms" to 100)
        ui.tap("file-download")
        if (ui.hasTag("source-dialog")) ui.tap("source-dialog-ok")
        ui.waitTag("file-downloading", 15_000)
        until("下了一部分", 15_000) { cache.walkTopDown().any { it.isFile && it.length() > 0 } }
        offThread { bob.recall(conv, m.msgId!!) }
        ui.waitText("该消息已被撤回", 5000)
        ui.waitText("对方撤回了一条消息", 5000)
        s3.ctl("get_chunk_ms" to 0)
        until("缓存目录里没有部分文件", 10_000) { cache.walkTopDown().none { it.isFile } }
        assertFalse(ui.hasTag("file-download-failed"))
    }

    @Test fun tc_quo_01_05_quote_image_and_file_summaries() { // TC-QUO-01-05 第 3 步、AC-QUO-01-5、AC-QUO-01-7 的客户端部分（真实服务端）
        val (_, bob, conv) = c2c()
        rule.waitUntil(15_000) { model.im?.msgActions == true }
        peerImage(bob, conv, TestMedia.jpeg(800, 600), TestMedia.jpeg(400, 300), 800, 600)
        peerFile(bob, conv, "报告.pdf", TestMedia.pdf(2048), "application/pdf")
        ui.waitTag("image-loaded", 15_000)
        ui.waitTag("file-bubble", 15_000)
        fun quoteAndSend(bubbleTag: String, reply: String) {
            rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasTestTag(bubbleTag)), useUnmergedTree = true).performTouchInput { longClick() }
            ui.waitTag("menu-quote", 5000)
            ui.tap("menu-quote")
            ui.waitTag("quote-bar")
            ui.type("composer-input", reply)
            ui.tap("send-button")
            until("对方收到 $reply") {
                offThread { runCatching { bob.loadHistory(conv) } }
                bob.messages(conv).any { it.body.text == reply }
            }
        }
        quoteAndSend("image-thumb", "回图片")
        assertTrue(ui.textOf("composer-input").isEmpty())
        assertEquals("[图片]", bob.messages(conv).first { it.body.text == "回图片" }.body.quote!!.text)
        quoteAndSend("file-bubble", "回文件")
        assertEquals("[文件] 报告.pdf", bob.messages(conv).first { it.body.text == "回文件" }.body.quote!!.text)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("msg-quote", useUnmergedTree = true).fetchSemanticsNodes().map { allText(it) }.let { l -> l.any { it.endsWith(": [图片]") } && l.any { it.endsWith(": [文件] 报告.pdf") } } }
    }

    @Test fun tc_file_07_04_text_overtakes_and_images_keep_pick_order() { // TC-FILE-07-04、AC-FILE-07-4
        val (_, bob, conv) = c2c()
        s3.ctl("put_delay_ms" to 1500)
        pick(PickKind.IMAGES, TestMedia.write("1.jpg", TestMedia.jpeg(61, 40)), TestMedia.write("2.jpg", TestMedia.jpeg(62, 40)), TestMedia.write("3.jpg", TestMedia.jpeg(63, 40)))
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("attachment-status", useUnmergedTree = true).fetchSemanticsNodes().any { allText(it).startsWith("上传中") } }
        ui.type("composer-input", "文字先走")
        ui.tap("send-button")
        until("text sent") { model.im!!.client.messages(conv).any { it.body.text == "文字先走" && it.status == MessageStatus.SENT } }
        assertTrue(appAttachments(conv).none { it.status == MessageStatus.SENT }, "文字先显示已发送")
        until("3 images sent", 60_000) { appAttachments(conv).count { it.status == MessageStatus.SENT } == 3 }
        until("bob got 3") { peerMessages(bob, conv, MsgType.IMAGE).size == 3 }
        val text = peerMessages(bob, conv, MsgType.TEXT).single { it.body.text == "文字先走" }
        val imgs = peerMessages(bob, conv, MsgType.IMAGE).sortedBy { it.seq }
        assertEquals(listOf(61, 62, 63), imgs.map { it.body.image!!.width }, "按选择顺序 send")
        assertTrue(text.seq!! < imgs[0].seq!!, "文字先拿到 seq")
    }

    @Test fun tc_file_07_05_source_deleted_retry_uses_sandbox_copy() { // TC-FILE-07-05、AC-FILE-07-5（源文件用测试目录的文件代替相册）
        val (_, bob, conv) = c2c()
        s3.ctl("fail_puts" to 1)
        val src = TestMedia.write("gallery.jpg", TestMedia.jpeg(300, 200))
        pick(PickKind.IMAGES, src)
        until("failed", 30_000) { appAttachments(conv).singleOrNull()?.status == MessageStatus.FAILED }
        assertEquals("图片上传失败，请检查网络后重发", ui.textOf("attachment-error"))
        assertEquals(1, sandbox().size, "失败的保留沙盒副本")
        assertFalse(File(testContext.cacheDir, com.tianting.app.core.MediaOps.PICK_DIR).listFiles().orEmpty().any(), "选中内容的临时副本已删")
        assertTrue(src.delete()) // "相册里删除原图"
        val id = appAttachments(conv).single().clientMsgId
        tapRetry()
        until("sent", 30_000) { appAttachments(conv).single().status == MessageStatus.SENT }
        until("bob got one") { peerMessages(bob, conv, MsgType.IMAGE).size == 1 }
        assertEquals(id, peerMessages(bob, conv, MsgType.IMAGE).single().clientMsgId, "clientMsgId 不变")
        until("sandbox cleared", 5000) { sandbox().isEmpty() }
    }

    @Test fun tc_file_07_06_offline_pick_fails_then_retry_once() { // TC-FILE-07-06（Android）、AC-FILE-07-6
        val (_, bob, conv) = c2c()
        setAirplane(true)
        pick(PickKind.IMAGES, TestMedia.write("offline.jpg", TestMedia.jpeg(120, 90)))
        until("failed", 40_000) { appAttachments(conv).singleOrNull()?.status == MessageStatus.FAILED }
        ui.waitTag("attachment-error")
        setAirplane(false)
        rule.waitUntil(30_000) { model.im?.state == ConnState.CONNECTED }
        tapRetry()
        until("sent", 30_000) { appAttachments(conv).single().status == MessageStatus.SENT }
        until("bob got") { peerMessages(bob, conv, MsgType.IMAGE).isNotEmpty() }
        Thread.sleep(2000)
        assertEquals(1, peerMessages(bob, conv, MsgType.IMAGE).size, "对方只收到一条")
    }

    @Test fun tc_file_07_07_five_failed_puts_never_complete() { // TC-FILE-07-07（Android）、AC-FILE-07-7；额度按问题 H19 的推断
        val (_, bob, conv) = c2c()
        val finalsBefore = s3.list("final/").size
        s3.ctl("fail_puts" to 5)
        pick(PickKind.FILES, TestMedia.write("weak.bin", ByteArray(50_000) { 5 }))
        repeat(5) { i ->
            until("failed #${i + 1}", 30_000) { s3.stats().second == i + 1 && appAttachments(conv).singleOrNull()?.status == MessageStatus.FAILED }
            if (i < 4) tapRetry()
        }
        assertEquals(5, s3.stats().second, "5 次 PUT")
        assertEquals(finalsBefore, s3.list("final/").size, "没有一次 complete 成功（额度只在 complete 成功时扣）")
        assertTrue(peerMessages(bob, conv, MsgType.FILE).isEmpty())
    }

    @Test fun tc_file_07_09_logout_clears_sandbox_copies() { // TC-FILE-07-09、AC-FILE-07-9
        val (_, _, conv) = c2c()
        s3.ctl("fail_puts" to 9)
        val files = (1..9).map { TestMedia.write("f$it.bin", ByteArray(1000 + it) { 1 }) }
        pick(PickKind.FILES, *files.toTypedArray())
        until("9 failed", 60_000) { appAttachments(conv).size == 9 && appAttachments(conv).all { it.status == MessageStatus.FAILED } }
        assertEquals(9, sandbox().size, "9 个沙盒副本")
        inst.runOnMainSync { model.logout() }
        offThread { model.awaitTeardown() }
        until("sandbox cleared", 10_000) { sandbox().isEmpty() }
        assertTrue(File(testContext.cacheDir, com.tianting.app.core.FILE_CACHE_DIR).listFiles().orEmpty().isEmpty(), "下载缓存清空")
    }

    /** 待发送的图片（send 被 4036 拒、已 complete、沙盒副本在），IM 钟推进 25 小时跑 file_purge 删掉上传行；返回旧 fileId 和 PUT 数。 */
    private fun purgedReadyImage(a: String, bob: IMClient, conv: String): Pair<String, Int> {
        offThread { bob.block(a) } // send 被拒（4036）：已 complete 的待发送附件，沙盒副本还在
        pick(PickKind.IMAGES, TestMedia.write("wait.jpg", TestMedia.jpeg(100, 80)))
        until("failed 4036", 30_000) { appAttachments(conv).singleOrNull()?.status == MessageStatus.FAILED }
        val m = appAttachments(conv).single()
        assertEquals(com.tianting.sdk.SendError.Code(4036), m.error)
        val fileId = m.body.image!!.fileId
        assertTrue(!fileId.isNullOrEmpty(), "已经 complete")
        assertEquals(1, sandbox().size)
        offThread { bob.unblock(a) }
        server.freezeClock(System.currentTimeMillis() + 25 * 3_600_000L)
        try {
            server.admin("POST", "/admin/test/jobs/file_purge/run")
        } finally {
            server.freezeClock(null)
        }
        return fileId!! to s3.stats().second
    }

    @Test fun tc_file_07_10_purged_upload_resend_reuploads_from_sandbox() { // TC-FILE-07-10（Android，有沙盒副本）、AC-FILE-07-10（v1.4，P-37 ①②）
        val (a, bob, conv) = c2c()
        val (oldFileId, putsBefore) = purgedReadyImage(a, bob, conv)
        tapRetry()
        // 清理后 send 回 4048（上传行已删，问题 H15）；自己上传的 fileId 按已清理处理：回到上传中，用沙盒副本重新上传后发送成功。
        until("retry settled", 30_000) {
            val now = appAttachments(conv).single()
            now.status == MessageStatus.SENT || (now.status == MessageStatus.FAILED && now.error != com.tianting.sdk.SendError.Code(4036))
        }
        val after = appAttachments(conv).single()
        android.util.Log.i("TC-FILE-07-10", "status ${after.status} error ${(after.error as? com.tianting.sdk.SendError.Code)?.code ?: after.error}") // 只有状态和错误码
        assertEquals(MessageStatus.SENT, after.status, "重新上传后发送成功（error ${after.error}）")
        val newFileId = after.body.image!!.fileId
        assertTrue(!newFileId.isNullOrEmpty() && newFileId != oldFileId, "新的 fileId")
        assertEquals(putsBefore + 2, s3.stats().second, "从头重新上传了原图和缩略图")
        until("bob got") { peerMessages(bob, conv, MsgType.IMAGE).any { it.body.image?.fileId == newFileId } }
        assertEquals(1, peerMessages(bob, conv, MsgType.IMAGE).size, "对方只收到一条")
        until("sandbox copy deleted", 10_000) { sandbox().isEmpty() }
    }

    @Test fun tc_file_07_10_purged_upload_without_sandbox_copy_says_source_missing() { // TC-FILE-07-10（Android，没有沙盒副本）、AC-FILE-07-10
        val (a, bob, conv) = c2c()
        val (_, putsBefore) = purgedReadyImage(a, bob, conv)
        sandbox().forEach { it.deleteRecursively() } // 沙盒副本没了
        tapRetry()
        until("source missing", 30_000) {
            val now = appAttachments(conv).single()
            now.status == MessageStatus.FAILED && now.error == com.tianting.sdk.SendError.SourceMissing
        }
        ui.waitText(com.tianting.app.core.SOURCE_MISSING)
        assertEquals(putsBefore, s3.stats().second, "没有重新上传")
        assertTrue(peerMessages(bob, conv, MsgType.IMAGE).isEmpty())
    }

    @Test fun tc_acc_06_06_avatar_crop_upload_512_and_shown() { // AC-ACC-06-6（Android）；问题 H13（SDK 改头像后重新拉自己的资料）
        val u = newUsername()
        val d = OtherDevice(server)
        offThread {
            val r = d.account.register(u, GOOD_PASSWORD, inviteCode = server.invite())
            d.account.ackRecoveryCode(r.recoveryCodeGen!!)
            d.account.logout()
        }
        d.close()
        clearLocalAccountFiles()
        replaceWithRealDeps(deps())
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        rule.waitUntil(15_000) { model.im?.filesEnabled == true }
        picks.clear()
        picks.add(TestMedia.uri(TestMedia.write("me.jpg", TestMedia.jpeg(1000, 800, Color.CYAN))))
        val before = s3.list("", bucket = "avatars").map { it.key }.toSet()
        ui.tap("tab-me")
        ui.tap("me-avatar")
        ui.waitTag("avatar-crop", 15_000)
        ui.tap("avatar-save")
        ui.waitText("头像已更新", 30_000)
        // 显示新头像（本次登录用上传的那张；服务端地址在测试环境是 http，不显示，同 H5 的 H13）
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("me-avatar") and hasAnyDescendant(hasTestTag("avatar-img")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // 上传的是 512×512：公开桶里新增的对象
        val pub = s3.list("", bucket = "avatars").filter { it.key !in before }
        assertEquals(1, pub.size, "公开桶新增一个头像对象")
        val raw = s3.raw(pub.single().key, bucket = "avatars")
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        assertEquals(512, bmp.width)
        assertEquals(512, bmp.height)
        assertEquals("image/jpeg", pub.single().type)
        // H13：SDK 的资料缓存里是服务端的新地址（PATCH 后重新拉了自己的资料）
        until("own profile refetched") { offThread { model.im!!.client.profileOf(model.im!!.self) }?.avatar?.contains("/avatars/") == true }
    }
}
