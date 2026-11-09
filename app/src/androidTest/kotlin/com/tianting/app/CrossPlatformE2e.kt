package com.tianting.app

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.AppDeps
import com.tianting.app.core.PickKind
import com.tianting.app.core.chatPath
import com.tianting.app.ui.DataClientMsgId
import com.tianting.sdk.ConnState
import com.tianting.sdk.FileVariant
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgType
import com.tianting.sdk.c2cConvId
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FE-640：AC-FILE-02-1、AC-FILE-03-1 的三端互发互收（H5、Android、iOS 对同一个 im-server + 假 S3）。
 * 三端不能同时跑，所以分两步：先各端用自己的界面发（send），再各端用自己的界面收（receive），接收方按消息拉取、下载并核对字节。
 * 共同约定（三个仓库里同名的用例照抄这一段）：
 *   - 运行号 R（`-e ttXp R`），用户 `xp{R}h5`、`xp{R}android`、`xp{R}ios`，群 `xp{R}g`（三人都在群里；用户和群由协调脚本先建好）。
 *   - 每端给另外两端各发一张 1600×1200 的纯色 jpeg（单聊）：h5 (200,40,40)、android (40,160,60)、ios (40,60,200)。
 *   - 每端往群里发一个文件 `跨端-{端}.pdf`："%PDF-1.7\n" 加 N 个字节，第 i 个是 (i×31+seed) & 0xFF，N = 1048576 + seed，
 *     seed：h5 1、android 2、ios 3。接收方自己按同样的公式算出期望的 SHA-256。
 * 接收（`-e ttXpFrom h5,ios` 指定这次核对哪些发送方）：单聊里对方的图片显示缩略图、点开显示原图，SDK 下载的原图字节数等于
 * `size`、解出来 1600×1200、中心颜色是对方的颜色，缩略图长边 ≤ 480；群里对方的文件名、大小、mime 对，界面点"下载"后 App 缓存里的
 * 文件 SHA-256 等于期望值。没有 `ttXp` 时跳过（普通的 e2e 运行不受影响）。结果是模拟器、本地。
 */
class CrossPlatformE2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val s3 = FakeS3()
    private val ui = E2eUi(rule)
    private val args = InstrumentationRegistry.getArguments()
    private val run: String? = args.getString("ttXp")
    private val picks = java.util.concurrent.CopyOnWriteArrayList<android.net.Uri>()
    private val model get() = testApp.model

    private fun user(p: String) = "xp${run}$p"
    private val me get() = user(ME)
    private val group get() = "group:xp${run}g"

    @Before fun setup() {
        assumeTrue("跨端用例要协调脚本传 ttXp", !run.isNullOrEmpty())
        server.guard()
        s3.guard()
        server.resetLimits()
        TestMedia.dir().deleteRecursively()
    }

    @After fun cleanup() {
        if (run.isNullOrEmpty()) return
        ui.scenario?.close()
        inst.runOnMainSync { testApp.model.logout() }
        offThread { testApp.model.awaitTeardown() }
    }

    private fun deps(): AppDeps = realDepsWithPicks({ _ -> picks.toList() })

    private fun appLogin(u: String) {
        replaceWithRealDeps(deps())
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", u)
        val end = System.currentTimeMillis() + 15_000
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("login-submit")) runCatching { rule.onAllNodesWithTag("login-submit", useUnmergedTree = true)[0].performClick() }
            Thread.sleep(700)
        }
        ui.waitTag("tab-chats", 15_000)
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        rule.waitUntil(15_000) { model.im?.filesEnabled == true }
    }

    private fun open(path: String) {
        inst.runOnMainSync { model.nav.resetTo(path) }
        rule.waitForIdle()
    }

    private fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(50)
        }
    }

    private fun pick(kind: PickKind, f: File) {
        picks.clear()
        picks.add(TestMedia.uri(f))
        ui.tap("attach-button")
        ui.tap(if (kind == PickKind.IMAGES) "attach-image" else "attach-file")
    }

    private fun attachments(conv: String, type: MsgType): List<Message> = model.im!!.client.messages(conv).filter { it.type == type }

    private fun loadAll(conv: String) {
        offThread { runCatching { model.im!!.client.loadHistory(conv) } }
    }

    // ---- 发送 ----

    @Test fun xp_send() {
        appLogin(me)
        for (p in PLATFORMS - ME) {
            val conv = c2cConvId(me, user(p))
            open(chatPath(conv))
            ui.waitTag("composer-input")
            val before = attachments(conv, MsgType.IMAGE).count { it.from == me }
            pick(PickKind.IMAGES, TestMedia.write("xp-$ME-to-$p.jpg", TestMedia.jpeg(W, H, COLORS.getValue(ME))))
            until("给 $p 的图片已发送", 30_000) {
                val mine = attachments(conv, MsgType.IMAGE).filter { it.from == me }
                check(mine.none { it.status == MessageStatus.FAILED }) { "图片发送失败：${mine.map { it.error }}" }
                mine.size == before + 1 && mine.all { it.status == MessageStatus.SENT }
            }
            val sent = attachments(conv, MsgType.IMAGE).last { it.from == me }.body.image!!
            assertEquals(W, sent.width)
            assertEquals(H, sent.height)
            assertEquals("image/jpeg", sent.mime)
        }
        rule.waitUntil(15_000) { model.im!!.convs.any { it.convId == group } }
        open(chatPath(group))
        ui.waitTag("composer-input")
        pick(PickKind.FILES, TestMedia.write(fileName(ME), fileBytes(ME)))
        until("群文件已发送", 30_000) {
            val mine = attachments(group, MsgType.FILE).filter { it.from == me }
            check(mine.none { it.status == MessageStatus.FAILED }) { "文件发送失败：${mine.map { it.error }}" }
            mine.isNotEmpty() && mine.all { it.status == MessageStatus.SENT }
        }
        val f = attachments(group, MsgType.FILE).last { it.from == me }.body.file!!
        assertEquals(fileName(ME), f.name)
        assertEquals(fileBytes(ME).size.toLong(), f.size)
    }

    // ---- 接收 ----

    @Test fun xp_receive() {
        val from = (args.getString("ttXpFrom") ?: (PLATFORMS - ME).joinToString(",")).split(',').filter { it.isNotEmpty() }
        assertTrue(from.isNotEmpty() && from.all { it in PLATFORMS && it != ME }, "ttXpFrom：$from")
        appLogin(me)
        for (p in from) {
            val conv = c2cConvId(me, user(p))
            open(chatPath(conv))
            ui.waitTag("composer-input")
            var got: Message? = null
            until("收到 $p 的图片", 30_000) {
                got = attachments(conv, MsgType.IMAGE).firstOrNull { it.from == user(p) && it.seq != null }
                if (got == null) loadAll(conv)
                got != null
            }
            val img = got!!.body.image!!
            assertEquals(W, img.width, "$p 的图片宽")
            assertEquals(H, img.height, "$p 的图片高")
            assertEquals("image/jpeg", img.mime, "$p 的图片 mime")
            // 界面：这条消息先显示缩略图，点开是原图
            val loaded = hasTestTag("image-loaded") and hasAnyAncestor(SemanticsMatcher.expectValue(DataClientMsgId, got!!.clientMsgId))
            rule.waitUntil(20_000) { rule.onAllNodes(loaded, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(loaded, useUnmergedTree = true)[0].performClick()
            ui.waitTag("viewer-image", 20_000)
            ui.tap("viewer-close")
            // 字节：App 的 SDK（sdk-kotlin）下载原图和缩略图
            val original = offThread { model.im!!.client.download(img.fileId, FileVariant.ORIGINAL) }
            assertEquals(img.size, original.length(), "$p 的原图字节数等于 size")
            val bmp = BitmapFactory.decodeFile(original.path)
            assertEquals(W, bmp.width)
            assertEquals(H, bmp.height)
            assertColor(COLORS.getValue(p), bmp.getPixel(W / 2, H / 2), "$p 的原图颜色")
            val thumb = offThread { model.im!!.client.download(img.fileId, FileVariant.THUMB) }
            val tb = BitmapFactory.decodeFile(thumb.path)
            assertTrue(tb != null && max(tb.width, tb.height) in 1..480, "$p 的缩略图长边 ≤ 480")
            assertColor(COLORS.getValue(p), tb.getPixel(tb.width / 2, tb.height / 2), "$p 的缩略图颜色")
        }
        rule.waitUntil(15_000) { model.im!!.convs.any { it.convId == group } }
        open(chatPath(group))
        ui.waitTag("composer-input")
        val files = HashMap<String, Message>()
        until("群里收到 ${from.joinToString()} 的文件", 30_000) {
            for (p in from) attachments(group, MsgType.FILE).firstOrNull { it.from == user(p) && it.seq != null }?.let { files[p] = it }
            if (files.size < from.size) loadAll(group)
            files.size == from.size
        }
        for (p in from) {
            val f = files.getValue(p).body.file!!
            assertEquals(fileName(p), f.name)
            assertEquals(fileBytes(p).size.toLong(), f.size)
            assertEquals("application/pdf", f.mime)
        }
        // 界面下载：点"下载"直到对方的文件都在 App 缓存里（第一次有来源提示）
        until("界面下载完成", 60_000) {
            val left = from.filter { p -> offThread { model.im!!.client.cachedFile(files.getValue(p).body.fileId!!, FileVariant.ORIGINAL) } == null }
            if (left.isEmpty()) return@until true
            if (ui.hasTag("source-dialog-ok")) {
                ui.tap("source-dialog-ok")
            } else {
                val dl = rule.onAllNodes(hasTestTag("file-download") and hasAnyAncestor(SemanticsMatcher.expectValue(DataClientMsgId, files.getValue(left[0]).clientMsgId)), useUnmergedTree = true)
                if (dl.fetchSemanticsNodes().isNotEmpty()) runCatching { dl[0].performClick() }
            }
            Thread.sleep(300)
            false
        }
        for (p in from) {
            val cached = offThread { model.im!!.client.cachedFile(files.getValue(p).body.fileId!!, FileVariant.ORIGINAL) }!!
            assertEquals(TestMedia.sha256(fileBytes(p)), TestMedia.sha256(cached), "$p 的文件 SHA-256")
        }
        android.util.Log.i("TiantingXp", "receive ok from=$from")
    }

    private fun assertColor(want: Int, got: Int, what: String) {
        val ok = abs(Color.red(want) - Color.red(got)) <= 40 && abs(Color.green(want) - Color.green(got)) <= 40 && abs(Color.blue(want) - Color.blue(got)) <= 40
        assertTrue(ok, "$what：期望 ${Integer.toHexString(want)}，实际 ${Integer.toHexString(got)}")
    }

    companion object {
        const val ME = "android"
        val PLATFORMS = listOf("h5", "android", "ios")
        const val W = 1600
        const val H = 1200
        val COLORS = mapOf("h5" to Color.rgb(200, 40, 40), "android" to Color.rgb(40, 160, 60), "ios" to Color.rgb(40, 60, 200))
        private val SEEDS = mapOf("h5" to 1, "android" to 2, "ios" to 3)
        fun fileName(p: String) = "跨端-$p.pdf"
        fun fileBytes(p: String): ByteArray {
            val seed = SEEDS.getValue(p)
            val head = "%PDF-1.7\n".toByteArray()
            val n = 1_048_576 + seed
            return head + ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }
        }
    }
}
