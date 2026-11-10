package com.tianting.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.ATTACHMENT_DIR
import com.tianting.app.core.AppDeps
import com.tianting.app.core.FILE_CACHE_DIR
import com.tianting.app.core.PickKind
import com.tianting.app.core.chatPath
import com.tianting.app.ui.DataConvId
import com.tianting.app.ui.DataPinned
import com.tianting.sdk.ConnState
import com.tianting.sdk.FileClient
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.LocalFile
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgType
import com.tianting.sdk.Platform
import com.tianting.sdk.ProcessedImage
import com.tianting.sdk.SendResult
import com.tianting.sdk.StaticTokenAuth
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C5 端到端的公共部分（C5E2eTest、C5KillE2e）：真实 im-server（IM_CONV_SETTINGS_ENABLED=1）、对方和"自己的另一台设备"是同一进程里的 IMClient。 */
class C5Real(val rule: ComposeTestRule) {
    val server = RealServer()
    val ui = E2eUi(rule)
    val peers = ArrayList<IMClient>()
    val model get() = testApp.model
    val picks = java.util.concurrent.CopyOnWriteArrayList<android.net.Uri>()

    fun uid(p: String) = p + System.currentTimeMillis().toString(36) + (0..999).random()

    fun users(vararg ids: String) {
        server.admin("POST", "/admin/users/import", obj("users" to ids.map { obj("userId" to it) }))
    }

    fun token(u: String) = server.admin("POST", "/admin/users/token", obj("userId" to u))["token"]!!.jsonPrimitive.content

    /**
     * 一个 IMClient（演示 token）：对方，或同一账号的另一台设备（u 是 App 的用户）。带文件接口，沙盒、缓存放在测试自己的目录。
     * loopback：经 adb reverse 连（run-tests.sh 传 ttImLoopback），App 开飞行模式时它不断。
     */
    fun client(u: String, loopback: Boolean = false, tokenOf: String? = null): IMClient {
        val t = tokenOf ?: token(u)
        val url = if (loopback) androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ttImLoopback")!! else "${server.base.replace("http://", "ws://")}/ws"
        val root = File(testContext.cacheDir, "peer-$u-${peers.size}")
        val c = IMClient(
            IMClientOptions(
                url = url, platform = Platform.WEB, files = FileClient(StaticTokenAuth(server.base, t)),
                attachmentDir = File(root, "a"), fileCacheDir = File(root, "c"),
            ),
        )
        peers.add(c)
        c.connect(t)
        until("client $u connected") { c.state.value == ConnState.CONNECTED }
        return c
    }

    fun deps(): AppDeps = realDepsWithPicks({ _ -> picks.toList() })

    /** 演示登录进 App（真实界面），等 features.convSettings 拿到。 */
    fun appLogin(u: String) {
        replaceWithRealDeps(deps())
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", u)
        val start = System.currentTimeMillis()
        val end = start + 30_000
        var submits = 0
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("config-retry")) runCatching { ui.tap("config-retry") }
            runCatching { if (ui.hasTag("login-username") && ui.textOf("login-username") != u) ui.type("login-username", u) }
            if (ui.hasTag("login-submit")) runCatching { rule.onNodeWithTag("login-submit", useUnmergedTree = true).performClick(); submits++ }
            Thread.sleep(700)
        }
        android.util.Log.i("TT-E2E-LOGIN", "C5 ms=${System.currentTimeMillis() - start} submits=$submits")
        ui.waitTag("tab-chats", 5_000)
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        rule.waitUntil(15_000) { model.im?.convSettings == true } // 服务端 features.convSettings
    }

    fun open(path: String) {
        inst.runOnMainSync { model.nav.resetTo(path) }
        rule.waitForIdle()
    }

    fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(30)
        }
    }

    /** 发一条（4290 等一下重发同一条），等到 ack。 */
    fun say(c: IMClient, conv: String, text: String): Message {
        val r = offThread { c.send(conv, text) }
        check(r is SendResult.Ok) { "send rejected" }
        val id = r.message.clientMsgId
        var tries = 0
        while (true) {
            until("ack") { c.messages(conv).first { it.clientMsgId == id }.status != MessageStatus.SENDING }
            val m = c.messages(conv).first { it.clientMsgId == id }
            if (m.status == MessageStatus.SENT) return m
            check(tries++ < 50) { "发送一直失败：${m.error}" }
            Thread.sleep(300)
            offThread { c.retry(conv, id) }
        }
    }

    /** 对方发一张图：原图、缩略图的字节都带特征串（解码器不看尾部多出来的字节）。 */
    fun image(c: IMClient, conv: String, marker: String): Message {
        val tail = marker.toByteArray()
        val o = TestMedia.write("c5-o-" + System.nanoTime(), TestMedia.jpeg(800, 600) + tail)
        val t = TestMedia.write("c5-t-" + System.nanoTime(), TestMedia.jpeg(400, 300) + tail)
        val m = offThread { c.sendImage(conv, ProcessedImage(LocalFile(o, "image/jpeg"), LocalFile(t, "image/jpeg"), 800, 600)) }
        until("image sent", 20_000) {
            val now = c.messages(conv).firstOrNull { it.clientMsgId == m.clientMsgId }
            check(now?.status != MessageStatus.FAILED) { "图片发送失败：${now?.error}" }
            now?.status == MessageStatus.SENT
        }
        return c.messages(conv).first { it.clientMsgId == m.clientMsgId }
    }

    fun setAirplane(on: Boolean) {
        inst.uiAutomation.executeShellCommand("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}").close()
        val cm = testContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && (cm.activeNetwork == null) == !on) Thread.sleep(100)
        if (on) return
        val http = okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        while (System.currentTimeMillis() < end) {
            val ok = runCatching { http.newCall(okhttp3.Request.Builder().url("${server.base}/readyz").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
            if (ok) return
            Thread.sleep(300)
        }
    }

    // ---- 会话列表 ----

    val itemOf = { id: String -> hasTestTag("conv-item") and withData(DataConvId, id) }
    fun order(): List<String> = rule.onAllNodesWithTag("conv-item", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[DataConvId] }
    fun hasItem(id: String) = rule.onAllNodes(itemOf(id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    fun pinnedMark(id: String) = rule.onNode(itemOf(id), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(DataPinned)
    fun hasInItem(id: String, tag: String) = rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(itemOf(id)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    fun textInItem(id: String, tag: String) = allText(rule.onNode(hasTestTag(tag) and hasAnyAncestor(itemOf(id)), useUnmergedTree = true).fetchSemanticsNode())

    /** 长按会话项，点菜单的一项（pin、notify、delete）。 */
    fun menu(id: String, item: String) {
        rule.waitUntil(15_000) { hasItem(id) }
        rule.onNode(itemOf(id), useUnmergedTree = true).performTouchInput { longClick() }
        ui.waitTag("conv-menu", 5000)
        ui.tap("conv-menu-$item")
        rule.waitUntil(3000) { !ui.hasTag("conv-menu") }
    }

    /** 删除并确认。 */
    fun delete(id: String) {
        menu(id, "delete")
        ui.waitTag("conv-delete-dialog", 5000)
        ui.tap("conv-delete-dialog-ok")
    }

    // ---- 本地文件 ----

    fun dbFiles(): List<File> = testContext.getDatabasePath("tianting-sdk.db").parentFile!!.listFiles().orEmpty().filter { it.isFile }
    fun cacheFiles(): List<File> = File(testContext.cacheDir, FILE_CACHE_DIR).walkTopDown().filter { it.isFile }.toList()
    fun sandbox(): List<File> = File(testContext.filesDir, ATTACHMENT_DIR).listFiles()?.toList().orEmpty()

    fun contains(f: File, needle: String): Boolean {
        val h = runCatching { f.readBytes() }.getOrDefault(ByteArray(0))
        val n = needle.toByteArray()
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    fun close() {
        runCatching { setAirplane(false) }
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
}

/**
 * FE-684 对真实 im-server（scripts/test-server.sh，IM_CONV_SETTINGS_ENABLED=1，演示登录，假 S3）的端到端：
 * scripts/run-tests.sh e2e com.tianting.app.C5E2eTest。App 是真实依赖（真实 WebSocket、真实本地库、真实缓存目录）。
 * 特征串只在内存里比较，不打印。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class C5E2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val r = C5Real(rule)
    private val ui get() = r.ui
    private val model get() = testApp.model

    @Before fun setup() {
        r.server.guard()
        r.server.resetLimits()
        TestMedia.dir().deleteRecursively()
    }

    @After fun cleanup() = r.close()

    /** App 是 a；bob、carol 各发一条，两个会话都在列表里。 */
    private fun twoConvs(): Triple<String, Pair<IMClient, String>, Pair<IMClient, String>> {
        val (a, b, c) = Triple(r.uid("va"), r.uid("vb"), r.uid("vc"))
        r.users(a, b, c)
        val bob = r.client(b)
        val carol = r.client(c)
        val bc = com.tianting.sdk.c2cConvId(a, b)
        val cc = com.tianting.sdk.c2cConvId(a, c)
        r.say(carol, cc, "carol 的")
        r.say(bob, bc, "bob 的")
        r.appLogin(a)
        rule.waitUntil(15_000) { r.hasItem(bc) && r.hasItem(cc) }
        return Triple(a, bob to bc, carol to cc)
    }

    @Test fun tc_cvs_04_01_app_pins_and_mutes_other_device_within_2s_and_back() { // TC-CVS-04-01、AC-CVS-04-1（D1 = App、D2 = 另一台；再反过来）
        val (a, bobPair, carolPair) = twoConvs()
        val (_, bc) = bobPair
        val (_, cc) = carolPair
        val d2 = r.client(a)
        r.until("d2 有会话") { d2.conversations.value.any { it.convId == cc } }
        // App 置顶、免打扰 carol：D2 2 秒内更新
        r.menu(cc, "pin")
        val t1 = System.currentTimeMillis()
        r.until("D2 置顶", 2000) { d2.conversations.value.first().let { it.convId == cc && it.pinned } }
        android.util.Log.i("TT-E2E-C5", "pin d2 ms=${System.currentTimeMillis() - t1}")
        r.menu(cc, "notify")
        r.until("D2 免打扰", 2000) { d2.conversations.value.first { it.convId == cc }.notifyOff }
        rule.waitUntil(3000) { r.order().first() == cc && r.pinnedMark(cc) == "true" && r.hasInItem(cc, "notify-off-icon") }
        // 反过来：D2 置顶、免打扰 bob，App 2 秒内更新（移到最前、置顶背景、图标）
        offThread { d2.setConvPinned(bc, true) }
        offThread { d2.setConvNotifyOff(bc, true) }
        val t2 = System.currentTimeMillis()
        rule.waitUntil(2000) { r.order().first() == bc && r.pinnedMark(bc) == "true" && r.hasInItem(bc, "notify-off-icon") }
        android.util.Log.i("TT-E2E-C5", "pin app ms=${System.currentTimeMillis() - t2}")
        assertEquals(d2.conversations.value.map { it.convId }.filter { it == bc || it == cc }, r.order().filter { it == bc || it == cc }, "两端顺序相同")
    }

    @Test fun tc_cvs_03_02_delete_then_peer_sends_reappears_with_only_new_message() { // TC-CVS-03-02、AC-CVS-03-2（App 是 u1）
        val (a, b) = r.uid("da") to r.uid("db")
        r.users(a, b)
        val bob = r.client(b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val secret = "ZXQ-CVS-1-" + System.currentTimeMillis().toString(36)
        r.say(bob, conv, secret)
        r.appLogin(a)
        r.open(chatPath(conv))
        ui.waitText(secret)
        r.until("本地库里有原文（对照）") { r.dbFiles().any { r.contains(it, secret) } }
        r.open("/chats")
        r.delete(conv)
        rule.waitUntil(5000) { !r.hasItem(conv) }
        r.until("本地库里搜不到", 15_000) { r.dbFiles().none { r.contains(it, secret) } }
        // 对方再发 1 条：重新出现，未读 1，摘要是新消息
        r.say(bob, conv, "删除之后的")
        rule.waitUntil(5000) { r.hasItem(conv) && r.hasInItem(conv, "conv-badge") }
        assertEquals("1", r.textInItem(conv, "conv-badge"))
        assertEquals("删除之后的", r.textInItem(conv, "conv-summary"))
        val c = model.im!!.client.conversations.value.first { it.convId == conv }
        assertEquals(2L, c.minSeq, "minSeq = clearedSeq + 1")
        // 打开后只有这 1 条，往上翻不出现旧消息
        r.open(chatPath(conv))
        ui.waitText("删除之后的")
        offThread { runCatching { model.im!!.client.loadHistory(conv) } }
        Thread.sleep(800)
        assertFalse(ui.hasText(secret))
        assertEquals(listOf("删除之后的"), model.im!!.client.messages(conv).mapNotNull { it.body.text })
        assertTrue(r.dbFiles().none { r.contains(it, secret) })
    }

    @Test fun tc_cvs_04_05_other_device_deletes_app_updates_within_2s_purges_db_and_draft() { // TC-CVS-04-05、AC-CVS-04-5（App 是 D2）
        val (a, b) = r.uid("ea") to r.uid("eb")
        r.users(a, b)
        val bob = r.client(b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val secret = "ZXQ-CVS-1-" + System.currentTimeMillis().toString(36)
        r.say(bob, conv, secret)
        val d1 = r.client(a)
        r.until("d1 有会话") { d1.conversations.value.any { it.convId == conv } }
        r.appLogin(a)
        r.open(chatPath(conv))
        ui.waitText(secret)
        r.until("本地库里有原文（对照）") { r.dbFiles().any { r.contains(it, secret) } }
        ui.type("composer-input", "D2 的草稿")
        r.open("/chats")
        rule.waitUntil(5000) { r.hasInItem(conv, "draft-tag") }
        offThread { d1.deleteConv(conv) }
        val t = System.currentTimeMillis()
        rule.waitUntil(2000) { !r.hasItem(conv) }
        android.util.Log.i("TT-E2E-C5", "delete app ms=${System.currentTimeMillis() - t}")
        // D2 的草稿也清除（frontend-chat 第 55 条）：SDK 先更新列表再发 ConvDeleted，同样在 2 秒内
        r.until("D2 的草稿清除", (2000 - (System.currentTimeMillis() - t)).coerceAtLeast(0)) { model.im!!.drafts[conv] == null }
        assertTrue(model.im!!.client.messages(conv).none { it.body.text == secret }, "内存里没有")
        r.until("本地库里搜不到", 15_000) { r.dbFiles().none { r.contains(it, secret) } }
    }

    @Test fun tc_cvs_04_02_offline_while_other_device_pins_and_deletes_then_consistent() { // TC-CVS-04-02、AC-CVS-04-2（App 是 D2）
        val (a, bobPair, carolPair) = twoConvs()
        val (_, bc) = bobPair
        val (carol, cc) = carolPair
        val secret = "ZXQ-CVS-1-" + System.currentTimeMillis().toString(36)
        r.say(carol, cc, secret)
        r.open(chatPath(cc))
        ui.waitText(secret)
        r.until("本地库里有 c2 的原文（对照）") { r.dbFiles().any { r.contains(it, secret) } }
        r.open("/chats")
        val d1 = r.client(a, loopback = true) // App 开飞行模式时 D1 不断
        r.until("d1 有会话") { d1.conversations.value.any { it.convId == cc } }
        r.setAirplane(true)
        rule.waitUntil(30_000) { model.im?.state != ConnState.CONNECTED }
        offThread { d1.setConvPinned(bc, true) }
        offThread { d1.deleteConv(cc) }
        assertTrue(r.hasItem(cc), "离线时还是旧状态")
        r.setAirplane(false)
        rule.waitUntil(30_000) { model.im?.state == ConnState.CONNECTED }
        rule.waitUntil(10_000) { !r.hasItem(cc) && r.order().firstOrNull() == bc && r.pinnedMark(bc) == "true" }
        val d1Order = d1.conversations.value.map { it.convId }
        assertEquals(d1Order, r.order(), "列表顺序和 D1 相同")
        assertEquals(d1.conversations.value.map { it.pinned to it.notifyOff }, model.im!!.client.conversations.value.map { it.pinned to it.notifyOff })
        r.until("c2 的本地消息被清除", 15_000) { r.dbFiles().none { r.contains(it, secret) } }
    }

    @Test fun tc_cvs_03_08_delete_clears_cache_file_failed_messages_upload_and_draft() { // TC-CVS-03-08（真实服务端 + 假 S3）、AC-CVS-03-8
        val (a, b) = r.uid("fa") to r.uid("fb")
        r.users(a, b)
        val bob = r.client(b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        r.say(bob, conv, "先说一句")
        val marker = "ZXQ-CVS-2-" + System.currentTimeMillis().toString(36)
        r.image(bob, conv, marker)
        r.appLogin(a)
        rule.waitUntil(15_000) { model.im?.filesEnabled == true }
        r.open(chatPath(conv))
        ui.waitTag("image-loaded", 15_000)
        r.until("缓存目录里有图片（对照）") { r.cacheFiles().any { r.contains(it, marker) } }
        // 失败消息：对方拉黑时发的文字和图片（4036），图片的沙盒副本还在
        offThread { bob.block(a) }
        ui.type("composer-input", "会失败的")
        ui.tap("send-button")
        r.until("文字失败", 30_000) { model.im!!.client.messages(conv).any { it.body.text == "会失败的" && it.status == MessageStatus.FAILED } }
        val photo = TestMedia.write("c5-pick.jpg", TestMedia.jpeg(640, 480))
        r.picks.clear()
        r.picks.add(TestMedia.uri(photo))
        ui.tap("attach-button")
        ui.tap("attach-image")
        r.until("图片失败", 30_000) { model.im!!.client.messages(conv).any { it.type == MsgType.IMAGE && it.from == a && it.status == MessageStatus.FAILED } }
        assertTrue(r.sandbox().isNotEmpty(), "失败的图片有沙盒副本（对照）")
        offThread { bob.unblock(a) }
        ui.type("composer-input", "草稿内容")
        r.open("/chats")
        rule.waitUntil(5000) { r.hasInItem(conv, "draft-tag") }
        // 取消：都还在
        r.menu(conv, "delete")
        ui.waitText("删除后聊天记录不再显示，确定删除？", 5000)
        ui.tap("conv-delete-dialog-cancel")
        Thread.sleep(800)
        assertTrue(r.hasItem(conv) && r.hasInItem(conv, "draft-tag"))
        assertEquals(2, model.im!!.client.messages(conv).count { it.status == MessageStatus.FAILED })
        assertTrue(r.cacheFiles().any { r.contains(it, marker) } && r.sandbox().isNotEmpty())
        // 确认：会话消失；草稿、失败消息、沙盒副本、缓存文件都没有了
        r.delete(conv)
        rule.waitUntil(5000) { !r.hasItem(conv) }
        // 会话在 ack 之前就隐藏（SDK 乐观更新），草稿在 ack 之后的 ConvDeleted 里清：等它，不是马上就有
        r.until("草稿清除", 5000) { model.im!!.drafts[conv] == null }
        r.until("失败消息清除") { model.im!!.client.messages(conv).isEmpty() }
        r.until("缓存目录搜不到", 15_000) { r.cacheFiles().none { r.contains(it, marker) } }
        r.until("沙盒副本清除", 15_000) { r.sandbox().isEmpty() }
        // 从通讯录重新进入：空的聊天页，输入框没有草稿
        r.open(chatPath(conv))
        ui.waitTag("composer-input")
        Thread.sleep(800)
        assertFalse(ui.hasTag("image-thumb") || ui.hasText("先说一句") || ui.hasText("会失败的"))
        assertEquals("", ui.textOf("composer-input"))
    }

    @Test fun f_cvs_03_rule_4_deleted_group_from_my_groups_is_empty_and_can_send() { // F-CVS-03 规则 4、AC-CVS-03-4 的客户端部分
        val (a, b) = r.uid("ga") to r.uid("gb")
        r.users(a, b)
        val g = r.uid("g")
        r.server.signed("POST", "/admin/groups", obj("groupId" to g, "name" to "C5 群", "members" to listOf(b, a), "owner" to b))
        val conv = "group:$g"
        val bob = r.client(b)
        r.say(bob, conv, "群里旧的")
        r.appLogin(a)
        r.delete(conv)
        rule.waitUntil(5000) { !r.hasItem(conv) }
        Thread.sleep(1000)
        assertFalse(ui.hasText("你已不在该群"))
        r.open("/contacts/groups")
        ui.waitText("C5 群")
        rule.onNodeWithText("C5 群", useUnmergedTree = true).performClick()
        ui.waitTag("composer-input")
        Thread.sleep(800)
        assertFalse(ui.hasText("群里旧的") || ui.hasText("你已不在该群"))
        ui.type("composer-input", "删除后照常发言")
        ui.tap("send-button")
        r.until("发出") { model.im!!.client.messages(conv).any { it.body.text == "删除后照常发言" && it.status == MessageStatus.SENT } }
        r.until("对方收到") {
            offThread { runCatching { bob.loadHistory(conv) } }
            bob.messages(conv).any { it.body.text == "删除后照常发言" }
        }
        r.open("/chats")
        rule.waitUntil(5000) { r.hasItem(conv) } // 有新消息重新出现
    }
}
