package com.tianting.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.KeystoreSessionStore
import com.tianting.app.core.chatPath
import com.tianting.app.ui.DataMark
import com.tianting.app.ui.DataUser
import com.tianting.sdk.ConnState
import com.tianting.sdk.FriendAction
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.Mentions
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.Platform
import com.tianting.sdk.QuoteRef
import com.tianting.sdk.QuoteState
import com.tianting.sdk.SendResult
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FE-667、FE-668 对真实 im-server（scripts/test-server.sh，IM_MSG_ACTIONS_ENABLED=1，演示登录）的端到端：
 * scripts/run-tests.sh e2e com.tianting.app.C4E2eTest。App 是真实依赖（真实 WebSocket、真实本地库），对方用同一进程里的 IMClient。
 * 帧内容（引用原文、@ 的内容）只在内存里断言，不打印。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class C4E2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val ui = E2eUi(rule)
    private val peers = ArrayList<IMClient>()
    private val model get() = testApp.model
    private fun uid(p: String) = p + System.currentTimeMillis().toString(36) + (0..999).random()

    @Before fun setup() {
        server.guard()
        server.resetLimits()
    }

    @After fun cleanup() {
        runCatching { server.freezeClock(null) }
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

    private fun users(vararg idAndNick: Pair<String, String>) {
        server.admin("POST", "/admin/users/import", obj("users" to idAndNick.map { (u, n) -> obj("userId" to u, "nickname" to n) }))
    }

    private fun token(u: String) = server.admin("POST", "/admin/users/token", obj("userId" to u))["token"]!!.jsonPrimitive.content

    /** 对方。loopback：经 adb reverse 连（run-tests.sh 传 ttImLoopback），App 开飞行模式时它不断。 */
    private fun peer(u: String, loopback: Boolean = false): IMClient {
        val url = if (loopback) androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ttImLoopback")!! else "${server.base.replace("http://", "ws://")}/ws"
        val c = IMClient(IMClientOptions(url = url, platform = Platform.WEB))
        peers.add(c)
        c.connect(token(u))
        until("peer $u connected") { c.state.value == ConnState.CONNECTED }
        return c
    }

    private fun appLogin(u: String) {
        replaceWithRealDeps()
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", u)
        val start = System.currentTimeMillis()
        val end = start + 30_000
        var submits = 0
        var configRetries = 0
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("config-retry")) runCatching { ui.tap("config-retry"); configRetries++ } // 配置请求失败（上一个用例刚断过网）：重试
            runCatching { if (ui.hasTag("login-username") && ui.textOf("login-username") != u) ui.type("login-username", u) } // 登录成功、页面换掉的瞬间可能找不到节点
            if (ui.hasTag("login-submit")) runCatching { rule.onNodeWithTag("login-submit", useUnmergedTree = true).performClick(); submits++ }
            Thread.sleep(700)
        }
        // 重试会把慢登录藏起来：每次记下用时和点了几次（只有数字，不带用户名），FE-672 报告据此判断（logcat 标签 TT-E2E-LOGIN）。
        android.util.Log.i("TT-E2E-LOGIN", "C4E2eTest ms=${System.currentTimeMillis() - start} submits=$submits configRetries=$configRetries")
        try {
            ui.waitTag("tab-chats", 5_000)
        } catch (e: Throwable) {
            val shown = runCatching { allText(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()) }.getOrDefault("?")
            throw AssertionError("登录没有完成：im=${model.im != null} 界面=$shown", e)
        }
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        rule.waitUntil(15_000) { model.im?.msgActions == true } // 服务端 features.msgActions
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

    /** 托管群，指定群主（要签名调用），成员按顺序。 */
    private fun groupOf(owner: String, vararg members: String): Pair<String, String> {
        val g = uid("g")
        server.signed("POST", "/admin/groups", obj("groupId" to g, "name" to "C4 群", "members" to listOf(owner) + members.toList(), "owner" to owner))
        return g to "group:$g"
    }

    /** 对方发一条（4290 等一下重发同一条），等到 ack。 */
    private fun say(c: IMClient, conv: String, text: String, quote: QuoteRef? = null, mentions: Mentions? = null): Message {
        val r = offThread { c.send(conv, text, quote, mentions) }
        check(r is SendResult.Ok) { "send rejected" }
        val id = r.message.clientMsgId
        var tries = 0
        while (true) {
            until("ack", 15_000) { c.messages(conv).first { it.clientMsgId == id }.status != MessageStatus.SENDING }
            val m = c.messages(conv).first { it.clientMsgId == id }
            if (m.status == MessageStatus.SENT) return m
            check(tries++ < 50) { "发送一直失败：${m.error}" }
            Thread.sleep(300)
            offThread { c.retry(conv, id) }
        }
    }

    private fun peerMsg(c: IMClient, conv: String, text: String): Message? {
        offThread { runCatching { c.loadHistory(conv) } }
        return c.messages(conv).firstOrNull { it.body.text == text }
    }

    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private fun menuOf(text: String): List<String> {
        ui.waitText(text)
        rule.waitForIdle()
        bubbleOf(text).performTouchInput { longClick() }
        ui.waitTag("bubble-menu", 5000)
        return listOf("menu-copy", "menu-quote", "menu-recall", "menu-retry", "menu-delete").filter { ui.hasTag(it) }
    }

    private fun closeMenu() {
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(3000) { !ui.hasTag("bubble-menu") }
    }

    private fun sendNow() {
        var last: androidx.compose.ui.geometry.Rect? = null
        var stable = 0
        until("发送按钮停稳", 5000) {
            rule.waitForIdle()
            val r = rule.onNodeWithTag("send-button", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            stable = if (r == last) stable + 1 else 0
            last = r
            Thread.sleep(40)
            stable >= 10
        }
        ui.tap("send-button")
    }

    /** 点选择里的一行。底部面板还在展开动画时点击可能落空：面板还在就再点。 */
    private fun pickRow(userId: String) {
        val end = System.currentTimeMillis() + 10_000
        while (ui.hasTag("mention-picker") && System.currentTimeMillis() < end) {
            runCatching { rule.onNode(hasTestTag("mention-row") and withData(DataUser, userId), useUnmergedTree = true).performClick() }
            Thread.sleep(500)
            rule.waitForIdle()
        }
        check(!ui.hasTag("mention-picker")) { "选择没关" }
    }

    private fun typeAt(text: String) {
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performTextInput(text)
        rule.waitForIdle()
    }

    private fun setAirplane(on: Boolean) {
        inst.uiAutomation.executeShellCommand("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}").close()
        val cm = testContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && (cm.activeNetwork == null) == !on) Thread.sleep(100)
        if (on) return
        // 网络回来以后到服务端真的能连上还要一会儿：等 readyz 通了再往下（否则紧接着的登录会超时）
        val http = okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        while (System.currentTimeMillis() < end) {
            val ok = runCatching { http.newCall(okhttp3.Request.Builder().url("${server.base}/readyz").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
            if (ok) return
            Thread.sleep(300)
        }
    }

    private fun dbFiles(): List<File> = testContext.getDatabasePath("tianting-sdk.db").parentFile!!.listFiles().orEmpty().filter { it.isFile }

    private fun contains(f: File, needle: String): Boolean {
        val h = f.readBytes()
        val n = needle.toByteArray()
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    private fun appToken(): String = runBlocking { KeystoreSessionStore(testContext).load() }?.accessToken.orEmpty()

    // ---- 撤回（FE-667） ----

    @Test fun tc_rcl_01_01_recall_live_both_sides_cleared_from_ui_memory_db_and_logcat() { // TC-RCL-01-01、AC-RCL-01-1、AC-RCL-03-10（真实服务端）
        val since = System.currentTimeMillis()
        val (a, b) = uid("ra") to uid("rb")
        users(a to "甲", b to "乙")
        val bob = peer(b)
        val other = peer(a) // u1 的另一端
        appLogin(a)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        open(chatPath(conv))
        val mine = "ZXQ-RCL-1-" + System.currentTimeMillis().toString(36)
        ui.type("composer-input", mine)
        sendNow()
        until("sent") { model.im!!.client.messages(conv).any { it.body.text == mine && it.status == MessageStatus.SENT } }
        until("对方收到") { peerMsg(bob, conv, mine) != null }
        until("自己另一端收到") { peerMsg(other, conv, mine) != null }
        val bobCopy = peerMsg(bob, conv, mine)!!
        assertTrue("menu-recall" in menuOf(mine))
        val t0 = System.currentTimeMillis()
        ui.tap("menu-recall")
        ui.waitText("你撤回了一条消息", 5000)
        // 对方和自己另一端 2 秒内收到带 recalled 的 msgDeleted
        until("对方变成撤回", 2000 + 1000) { bob.messages(conv).first { it.clientMsgId == bobCopy.clientMsgId }.recalled }
        assertTrue(System.currentTimeMillis() - t0 < 3000)
        assertEquals(a, bob.messages(conv).first { it.clientMsgId == bobCopy.clientMsgId }.recalledBy)
        assertEquals("对方撤回了一条消息", offThread { bob.recallText(bob.messages(conv).first { it.clientMsgId == bobCopy.clientMsgId }) })
        until("另一端变成撤回", 3000) { other.messages(conv).any { it.recalled && it.seq == bobCopy.seq } }
        // 对方撤回自己的
        val theirs = "ZXQ-RCL-2-" + System.currentTimeMillis().toString(36)
        val m2 = say(bob, conv, theirs)
        ui.waitText(theirs)
        offThread { bob.recall(conv, m2.msgId!!) }
        ui.waitText("对方撤回了一条消息", 3000)
        // 提示行长按没有菜单
        rule.onAllNodesWithTag("msg-recalled", useUnmergedTree = true)[0].performTouchInput { longClick() }
        Thread.sleep(800)
        assertFalse(ui.hasTag("bubble-menu"))
        // 页面、内存、本地库文件、Logcat 都搜不到原文
        assertFalse(ui.hasText(mine) || ui.hasText(theirs))
        val dump = offThread { model.im!!.client.debugExport() }.toString()
        assertFalse(mine in dump || theirs in dump, "内存")
        until("本地库文件里搜不到原文", 10_000) { dbFiles().none { contains(it, mine) || contains(it, theirs) } }
        open("/chats")
        ui.waitText("对方撤回了一条消息")
        val log = scanOwnLogcat(since, mapOf("mine" to mine, "theirs" to theirs, "token" to appToken()))
        assertTrue(log.lines > 0)
        assertEquals(emptyList(), log.found, "Logcat")
    }

    @Test fun tc_rcl_01_08_menu_by_server_time_and_4097_after_it_expires_while_open() { // TC-RCL-01-08、AC-RCL-01-8（真实服务端 [IM 钟]）
        val (a, b) = uid("ta") to uid("tb")
        users(a to a, b to b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val me = peer(a)
        // "N 秒前"按服务端时间推算（问题 72）：用对方 SDK 校正后的服务端时间（auth ack 的 serverTime），不用设备时钟。
        // 模拟器时钟和服务端所在的 Mac 差几秒时（实测慢 9 秒），按设备时钟冻结的"112 秒前"按服务端算已经超过 2 分钟。
        try {
            server.freezeClock(me.serverNow() - 125_000)
            say(me, conv, "125 秒前")
            server.freezeClock(me.serverNow() - 112_000)
            say(me, conv, "112 秒前")
        } finally {
            server.freezeClock(null)
        }
        appLogin(a)
        open(chatPath(conv))
        assertEquals(listOf("menu-copy", "menu-quote"), menuOf("125 秒前"), "超过 2 分钟没有撤回")
        closeMenu()
        assertEquals(listOf("menu-copy", "menu-quote", "menu-recall"), menuOf("112 秒前"))
        Thread.sleep(10_000) // 菜单开着，过了 120 秒
        ui.tap("menu-recall")
        ui.waitText("发送时间超过 2 分钟，无法撤回", 5000)
        assertTrue(ui.hasText("112 秒前"))
        assertFalse(ui.hasTag("msg-recalled"))
    }

    @Test fun tc_rcl_02_04_and_02_05_admin_recalls_member_three_views_and_admin_cannot_recall_admin() { // TC-RCL-02-04、02-05，AC-RCL-02-4、02-5
        val (o, a, a2, m, n) = listOf(uid("o"), uid("a"), uid("x"), uid("m"), uid("n"))
        users(o to "群主", a to "管理甲", a2 to "管理乙", m to "成员丙", n to "成员丁")
        val (g, conv) = groupOf(o, a, a2, m, n)
        val owner = peer(o)
        offThread {
            owner.setGroupAdmin(g, a, true)
            owner.setGroupAdmin(g, a2, true)
        }
        val mc = peer(m)
        val nc = peer(n)
        val a2c = peer(a2)
        say(a2c, conv, "管理员乙的话")
        val mm = say(mc, conv, "成员丙的话")
        appLogin(a)
        open(chatPath(conv))
        assertTrue("menu-recall" in menuOf("成员丙的话"))
        ui.tap("menu-recall")
        ui.waitText("你撤回了 成员丙 的一条消息", 5000)
        until("m 看到", 5000) { mc.messages(conv).first { it.clientMsgId == mm.clientMsgId }.recalled }
        offThread {
            mc.getUsers(listOf(a))
            nc.getUsers(listOf(a))
        }
        assertEquals("管理甲撤回了你的一条消息", offThread { mc.recallText(mc.messages(conv).first { it.clientMsgId == mm.clientMsgId }) })
        until("n 看到", 5000) { peerMsg(nc, conv, "管理员乙的话") != null && nc.messages(conv).any { it.recalled } }
        assertEquals("管理甲撤回了一条成员消息", offThread { nc.recallText(nc.messages(conv).first { it.recalled }) })
        // 列表摘要和聊天页一致（规则 1a 第 3 行）
        until("n 的列表", 5000) { nc.conversations.value.first { it.convId == conv }.lastMsg?.recalled == true }
        assertEquals("管理甲撤回了一条成员消息", offThread { nc.recallText(conv, nc.conversations.value.first { it.convId == conv }.lastMsg!!) })
        // 管理员撤回其他管理员：菜单里有，服务端 4030
        assertTrue("menu-recall" in menuOf("管理员乙的话"))
        ui.tap("menu-recall")
        ui.waitText("你没有权限撤回这条消息", 5000)
        assertTrue(ui.hasText("管理员乙的话"))
        open("/chats")
        ui.waitText("你撤回了 成员丙 的一条消息")
    }

    @Test fun tc_rcl_03_03_recalled_while_offline_shows_after_network_back() { // TC-RCL-03-03、AC-RCL-03-3（真实服务端）
        val (a, b) = uid("oa") to uid("ob")
        users(a to a, b to b)
        val bob = peer(b, loopback = true)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val secret = "ZXQ-RCL-2-" + System.currentTimeMillis().toString(36)
        val m = say(bob, conv, secret)
        appLogin(a)
        open(chatPath(conv))
        ui.waitText(secret)
        setAirplane(true)
        until("断开", 20_000) { model.im!!.state != ConnState.CONNECTED }
        offThread { bob.recall(conv, m.msgId!!) }
        setAirplane(false)
        ui.waitText("对方撤回了一条消息", 30_000)
        assertFalse(ui.hasText(secret))
        assertFalse(secret in offThread { model.im!!.client.debugExport() }.toString(), "内存里搜不到原文")
    }

    @Test fun tc_rcl_03_06_group_own_last_recall_list_summary() { // TC-RCL-03-06、AC-RCL-03-6（真实服务端）
        val (o, a, n) = Triple(uid("o"), uid("a"), uid("n"))
        users(o to "群主", a to "爱丽丝", n to "成员丁")
        val (_, conv) = groupOf(o, a, n)
        val nc = peer(n)
        appLogin(a)
        open(chatPath(conv))
        ui.type("composer-input", "我的最后一条")
        sendNow()
        until("sent") { model.im!!.client.messages(conv).any { it.body.text == "我的最后一条" && it.status == MessageStatus.SENT } }
        menuOf("我的最后一条")
        ui.tap("menu-recall")
        ui.waitText("你撤回了一条消息", 5000)
        open("/chats")
        rule.waitUntil(10_000) { ui.hasText("你撤回了一条消息") && !ui.hasText("爱丽丝: ") }
        until("n 的列表", 5000) { nc.conversations.value.firstOrNull { it.convId == conv }?.lastMsg?.recalled == true }
        offThread { nc.getUsers(listOf(a)) } // 对方的资料缓存里有了昵称才按昵称显示
        assertEquals("爱丽丝撤回了一条消息", offThread { nc.recallText(conv, nc.conversations.value.first { it.convId == conv }.lastMsg!!) })
    }

    // ---- 引用（FE-667） ----

    @Test fun tc_quo_01_05_quote_cancel_then_send_peer_gets_quote_and_02_05_html_plain() { // TC-QUO-01-05（文本）、TC-QUO-02-05，AC-QUO-01-5、AC-QUO-02-5、AC-QUO-01-1
        val since = System.currentTimeMillis()
        val (a, b) = uid("qa") to uid("qb")
        users(a to "甲", b to "乙")
        val bob = peer(b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val html = "<img src=x onerror=alert(1)><b>粗</b>ZXQ-QUO-2"
        say(bob, conv, html)
        appLogin(a)
        open(chatPath(conv))
        menuOf(html)
        ui.tap("menu-quote")
        ui.waitTag("quote-bar")
        assertEquals("乙: $html", ui.textOf("quote-bar-text"), "按原样显示")
        ui.tap("quote-cancel")
        rule.waitUntil(3000) { !ui.hasTag("quote-bar") }
        ui.type("composer-input", "不带引用")
        sendNow()
        until("对方收到") { peerMsg(bob, conv, "不带引用") != null }
        assertNull(peerMsg(bob, conv, "不带引用")!!.body.quote, "取消后没有引用")
        menuOf(html)
        ui.tap("menu-quote")
        ui.waitTag("quote-bar")
        ui.type("composer-input", "回复你")
        sendNow()
        until("对方收到引用") { peerMsg(bob, conv, "回复你") != null }
        val q = peerMsg(bob, conv, "回复你")!!.body.quote!!
        assertEquals(QuoteState.NORMAL, q.state)
        assertEquals(html, q.text, "原文摘要")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("msg-quote") and hasAnyDescendant(hasText("乙: $html")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val log = scanOwnLogcat(since, mapOf("quote" to "ZXQ-QUO-2", "token" to appToken()))
        assertEquals(emptyList(), log.found, "引用原文、token 不进 Logcat")
    }

    @Test fun tc_quo_02_03_jump_within_200_and_too_early_beyond() { // TC-QUO-02-03、AC-QUO-02-3（真实服务端，700 条）
        val (a, b) = uid("ja") to uid("jb")
        users(a to a, b to b)
        val bob = peer(b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val sent = ArrayList<Message>()
        for (i in 1..698) {
            sent.add(say(bob, conv, "第${i}条"))
            Thread.sleep(45) // 发送限流每秒 20 条
        }
        val far = sent[180] // 第 181 条
        val near = sent[580] // 第 581 条
        say(bob, conv, "q2", quote = QuoteRef(far.msgId!!))
        say(bob, conv, "q1", quote = QuoteRef(near.msgId!!))
        appLogin(a)
        open(chatPath(conv))
        ui.waitText("q1")
        var last = -1
        until("翻页停下", 20_000) { // 屏幕高、一页填不满时列表会接着翻
            val n = model.im!!.client.messages(conv).size
            val same = n == last
            last = n
            Thread.sleep(1200)
            same
        }
        val seqs = { model.im!!.client.messages(conv).mapNotNull { it.seq } }
        val oldest = seqs().min()
        assertTrue(oldest - near.seq!! in 1..200 && oldest - far.seq!! > 200, "q1 在 200 条以内，q2 在 200 条外")
        rule.onNode(hasTestTag("msg-quote") and hasAnyDescendant(hasText("第181条", substring = true)), useUnmergedTree = true).performClick()
        ui.waitText("消息太早，请翻页查看", 5000)
        assertEquals(oldest, seqs().min(), "没有翻页")
        rule.onNode(hasTestTag("msg-quote") and hasAnyDescendant(hasText("第581条", substring = true)), useUnmergedTree = true).performClick()
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("msg-highlight") and hasAnyDescendant(hasText("第581条")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val loaded = seqs().sorted()
        assertEquals((loaded.first()..loaded.last()).toList(), loaded, "已加载范围连续")
    }

    // ---- @（FE-668） ----

    /** 群：群主 o、app 用户 a、王 w（昵称小王，a 给他的备注"老板"）、u3。 */
    private fun mentionGroup(): Triple<String, String, Map<String, IMClient>> {
        val (o, a, w, u3) = listOf(uid("o"), uid("a"), uid("w"), uid("u"))
        users(o to "群主", a to "爱丽丝", w to "小王", u3 to "路人")
        val (g, conv) = groupOf(o, a, w, u3)
        val wc = peer(w)
        val oc = peer(o)
        val u3c = peer(u3)
        appLogin(a)
        // 王加 a 为好友，a 同意时设备注"老板"（备注只有自己可见）
        offThread { wc.applyFriend(a) }
        rule.waitUntil(10_000) { model.im!!.requests.items.any { it.userId == w } }
        offThread { model.im!!.client.respondFriend(w, FriendAction.ACCEPT, remark = "老板") }
        rule.waitUntil(10_000) { model.im!!.friends.any { it.userId == w && it.remark == "老板" } }
        ids = listOf(o, a, w, u3)
        return Triple(g, conv, mapOf("o" to oc, "w" to wc, "u3" to u3c))
    }

    private var ids: List<String> = emptyList()

    @Test fun tc_men_01_01_and_01_07_mention_nickname_not_remark_u2_flagged_u3_not() { // TC-MEN-01-01、TC-MEN-01-07，AC-MEN-01-1、AC-MEN-01-7（真实服务端）
        val since = System.currentTimeMillis()
        val (_, conv, cs) = mentionGroup()
        val (_, _, w, _) = ids
        open(chatPath(conv))
        ui.waitTag("composer-input")
        typeAt("@")
        ui.waitTag("mention-row", 10_000)
        val row = rule.onNode(hasTestTag("mention-row") and withData(DataUser, w), useUnmergedTree = true)
        row.assertAllTextContains("老板")
        row.assertAllTextContains(w)
        pickRow(w)
        rule.waitUntil(5000) { !ui.hasTag("mention-picker") }
        typeAt("看一下ZXQ-MEN-1")
        sendNow()
        val wc = cs["w"]!!
        until("u2 收到") { peerMsg(wc, conv, "@小王 看一下ZXQ-MEN-1") != null }
        val got = peerMsg(wc, conv, "@小王 看一下ZXQ-MEN-1")!!
        assertEquals(listOf(w), got.body.mentions!!.userIds)
        assertFalse("老板" in got.body.text!!, "正文用昵称，不用备注")
        until("u2 [有人@我]", 10_000) { (wc.conversations.value.first { it.convId == conv }.mentionUnread ?: 0) == 1L }
        val u3c = cs["u3"]!!
        until("u3 收到") { peerMsg(u3c, conv, "@小王 看一下ZXQ-MEN-1") != null }
        assertEquals(0L, u3c.conversations.value.first { it.convId == conv }.mentionUnread ?: 0L, "u3 不提示")
        val log = scanOwnLogcat(since, mapOf("mention" to "ZXQ-MEN-1", "remark" to "老板", "token" to appToken()))
        assertEquals(emptyList(), log.found, "@ 的内容、备注、token 不进 Logcat")
    }

    @Test fun tc_men_01_05_and_01_12_deleted_or_backspaced_mention_not_sent() { // TC-MEN-01-05、TC-MEN-01-12，AC-MEN-01-5、01-12（真实服务端）
        val (_, conv, cs) = mentionGroup()
        val (_, _, w, _) = ids
        open(chatPath(conv))
        ui.waitTag("composer-input")
        typeAt("@")
        ui.waitTag("mention-row", 10_000)
        pickRow(w)
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performTextReplacement("删掉了")
        sendNow()
        rule.waitUntil(5000) { ui.textOf("composer-input").isEmpty() }
        typeAt("你好 ")
        typeAt("@")
        ui.waitTag("mention-row", 10_000)
        pickRow(w)
        rule.waitUntil(3000) { ui.textOf("composer-input") == "你好 @小王 " }
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
        rule.waitUntil(3000) { ui.textOf("composer-input") == "你好 " }
        typeAt("退格了")
        sendNow()
        val wc = cs["w"]!!
        until("收到") { peerMsg(wc, conv, "删掉了") != null && peerMsg(wc, conv, "你好 退格了") != null }
        assertNull(peerMsg(wc, conv, "删掉了")!!.body.mentions)
        assertNull(peerMsg(wc, conv, "你好 退格了")!!.body.mentions)
        assertEquals(0L, wc.conversations.value.first { it.convId == conv }.mentionUnread ?: 0L)
    }

    @Test fun tc_men_01_10_draft_with_mention_and_quote_restored_and_sent() { // TC-MEN-01-10、AC-MEN-01-10（真实服务端）
        val (_, conv, cs) = mentionGroup()
        val (_, _, w, _) = ids
        val oc = cs["o"]!!
        say(oc, conv, "群主的通知")
        open(chatPath(conv))
        menuOf("群主的通知")
        ui.tap("menu-quote")
        ui.waitTag("quote-bar")
        typeAt("收到 ")
        typeAt("@")
        ui.waitTag("mention-row", 10_000)
        pickRow(w)
        rule.waitUntil(5000) { ui.textOf("composer-input") == "收到 @小王 " }
        open("/chats")
        ui.waitTag("draft-tag")
        assertEquals("[草稿] 收到 @小王 ", ui.textOf("draft-tag"))
        open(chatPath(conv))
        ui.waitTag("quote-bar")
        assertEquals("收到 @小王 ", ui.textOf("composer-input"))
        assertEquals("群主: 群主的通知", ui.textOf("quote-bar-text"))
        sendNow()
        val wc = cs["w"]!!
        until("u2 收到") { offThread { runCatching { wc.loadHistory(conv) } }; wc.messages(conv).any { it.body.text?.startsWith("收到 @小王") == true } }
        val got = wc.messages(conv).first { it.body.text?.startsWith("收到 @小王") == true }
        assertEquals(listOf(w), got.body.mentions!!.userIds)
        assertEquals("群主的通知", got.body.quote!!.text)
        until("u2 被提及", 10_000) { (wc.conversations.value.first { it.convId == conv }.mentionUnread ?: 0) == 1L }
    }

    @Test fun tc_men_02_04_all_only_for_owner_and_admin() { // TC-MEN-02-04、AC-MEN-02-4（真实服务端）
        val (g, conv, cs) = mentionGroup()
        val (_, a, _, _) = ids
        open(chatPath(conv))
        ui.waitTag("composer-input")
        typeAt("@")
        ui.waitTag("mention-row", 10_000)
        assertFalse(ui.hasTag("mention-all"), "普通成员看不到\"所有人\"")
        ui.tap("mention-cancel")
        offThread { cs["o"]!!.setGroupAdmin(g, a, true) }
        rule.waitUntil(10_000) { model.im!!.convs.first { it.convId == conv }.myRole == com.tianting.sdk.GroupRole.ADMIN }
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performTextReplacement("")
        typeAt("@")
        ui.waitTag("mention-all", 10_000)
        ui.tap("mention-all")
        typeAt("开会")
        sendNow()
        val wc = cs["w"]!!
        until("收到") { peerMsg(wc, conv, "@所有人 开会") != null }
        assertEquals(true, peerMsg(wc, conv, "@所有人 开会")!!.body.mentions!!.all)
    }

    @Test fun tc_men_02_05_4293_fails_toasts_and_no_resend_after_reconnect() { // TC-MEN-02-05、AC-MEN-02-5（真实服务端）
        val (o, a) = uid("o") to uid("a")
        users(o to "群主", a to "爱丽丝")
        val (_, conv) = groupOf(a, o) // a 是群主
        val other = peer(a) // 同一个人的另一端：先用掉本小时的 3 次
        for (i in 1..3) say(other, conv, "@所有人 第${i}次", mentions = Mentions(all = true))
        val oc = peer(o, loopback = true)
        appLogin(a)
        open(chatPath(conv))
        ui.waitTag("composer-input")
        typeAt("@")
        ui.waitTag("mention-all", 10_000)
        ui.tap("mention-all")
        typeAt("第4次")
        sendNow()
        ui.waitText("@所有人次数已用完", 5000)
        ui.waitTag("failed-icon", 5000)
        setAirplane(true)
        until("断开", 20_000) { model.im!!.state != ConnState.CONNECTED }
        setAirplane(false)
        until("重连", 30_000) { model.im!!.state == ConnState.CONNECTED }
        Thread.sleep(3000)
        offThread { runCatching { oc.loadHistory(conv) } }
        assertTrue(oc.messages(conv).none { it.body.text == "@所有人 第4次" }, "没有自动重发")
        assertEquals(MessageStatus.FAILED, model.im!!.client.messages(conv).first { it.body.text == "@所有人 第4次" }.status)
    }

    @Test fun tc_men_03_05_mention_beats_draft_in_list() { // TC-MEN-03-05、AC-MEN-03-5（真实服务端）
        val (_, conv, cs) = mentionGroup()
        val (_, a, _, _) = ids
        open(chatPath(conv))
        ui.type("composer-input", "我的草稿")
        open("/chats")
        ui.waitTag("draft-tag")
        say(cs["o"]!!, conv, "@爱丽丝 看一下", mentions = Mentions(listOf(a)))
        rule.waitUntil(10_000) { ui.hasTag("mention-tag") }
        assertFalse(ui.hasTag("draft-tag"))
        assertTrue(ui.textOf("mention-tag").startsWith("[有人@我] "))
    }

    @Test fun tc_men_03_06_hundred_unread_mention_at_tenth_jump_button() { // TC-MEN-03-06、AC-MEN-03-6（真实服务端）
        val (o, a) = uid("o") to uid("a")
        users(o to "群主", a to "爱丽丝")
        val (_, conv) = groupOf(o, a)
        val oc = peer(o)
        for (i in 1..100) {
            if (i == 10) say(oc, conv, "第10条@爱丽丝", mentions = Mentions(listOf(a))) else say(oc, conv, "第${i}条")
            Thread.sleep(45)
        }
        appLogin(a)
        rule.waitUntil(10_000) { (model.im!!.convs.firstOrNull { it.convId == conv }?.mentionUnread ?: 0) == 1L }
        open(chatPath(conv))
        ui.waitText("第100条")
        ui.waitTag("mention-jump", 5000)
        ui.tap("mention-jump")
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("msg-highlight") and hasAnyDescendant(hasText("第10条@爱丽丝")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(ui.hasTag("mention-jump"), "按钮消失")
        rule.onNode(hasTestTag("message") and withData(DataMark, "mention"), useUnmergedTree = true).assertExists()
    }
}
