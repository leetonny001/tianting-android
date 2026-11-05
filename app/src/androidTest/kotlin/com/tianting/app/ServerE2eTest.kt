package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.*
import com.tianting.app.ui.*
import com.tianting.sdk.ConnState
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.Message
import com.tianting.sdk.Platform
import com.tianting.sdk.android.AndroidLocalDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 对真实 im-server 的端到端（scripts/run-e2e.sh 启动服务端并传入参数）：
 * alice 走真实 App 界面（真实 WebSocket、真实演示登录、真实本地库），对方用同一进程里的另一个 IMClient。
 */
class ServerE2eTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val adminKey: String? = args.getString("ttAdminKey")
    private val callerId: String? = args.getString("ttCallerId")
    private val callerSecret: String? = args.getString("ttCallerSecret")
    private val base = BuildConfig.SERVER_URL
    private val adminBase: String = args.getString("ttAdminUrl") ?: "http://10.0.2.2:18281"
    private val http = OkHttpClient()
    private val peers = ArrayList<IMClient>()
    private var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null

    private val model get() = testApp.model
    private fun uid(p: String) = p + System.currentTimeMillis().toString(36) + (0..999).random()

    @Before fun guard() {
        assumeTrue("需要 scripts/run-e2e.sh 传入测试服务端参数", adminKey != null)
    }

    @After fun cleanup() {
        scenario?.close()
        inst.runOnMainSync { testApp.model.logout() }
        runBlocking {
            testApp.model.awaitTeardown()
            for (p in peers) {
                runCatching { p.disconnect() }
                runCatching { p.close() }
            }
        }
    }

    private fun send(req: Request): JsonObject = http.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        check(r.isSuccessful) { "${req.method} ${req.url.encodedPath}: ${r.code} $text" }
        if (text.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
    }

    private fun admin(method: String, path: String, body: JsonObject? = null): JsonObject = send(
        Request.Builder().url(adminBase + path).header("X-Admin-Key", adminKey!!).method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build(),
    )

    /** 调用方签名调用管理接口（protocol/ADMIN.md「鉴权」）：封禁、禁言、删除消息只接受这种方式。 */
    private fun signed(method: String, path: String, body: JsonObject): JsonObject {
        val raw = body.toString()
        val ts = System.currentTimeMillis().toString()
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val toSign = listOf(method, path, ts, nonce, hash, "e2e", "", "").joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(callerSecret!!.toByteArray(), "HmacSHA256")) }
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(toSign.toByteArray()))
        return send(
            Request.Builder().url(adminBase + path)
                .header("X-IM-Caller", callerId!!).header("X-IM-Timestamp", ts).header("X-IM-Nonce", nonce)
                .header("X-IM-Operator", "e2e").header("X-IM-Signature", sig)
                .method(method, raw.toRequestBody("application/json".toMediaType())).build(),
        )
    }

    private fun token(u: String) = admin("POST", "/admin/users/token", obj("userId" to u))["token"]!!.jsonPrimitive.content

    private fun users(vararg u: String) {
        admin("POST", "/admin/users/import", obj("users" to u.map { obj("userId" to it) }))
    }

    private fun peer(u: String): IMClient {
        val c = IMClient(IMClientOptions(url = "${base.replace("http://", "ws://")}/ws", platform = Platform.WEB))
        peers.add(c)
        c.connect(token(u))
        val end = System.currentTimeMillis() + 10_000
        while (c.state.value != ConnState.CONNECTED) {
            check(System.currentTimeMillis() < end) { "peer not connected" }
            Thread.sleep(20)
        }
        return c
    }

    private fun appLogin(u: String, nick: String = "") {
        inst.runOnMainSync { testApp.model = AppModel(testContext, defaultDeps(testContext)) }
        scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(5000) { rule.onAllNodesWithTag("login-username", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("login-username", useUnmergedTree = true).performTextReplacement(u)
        if (nick.isNotEmpty()) rule.onNodeWithTag("login-nickname", useUnmergedTree = true).performTextReplacement(nick)
        tapUntilLeft("login-submit")
        try {
            waitTag("tab-chats", 15_000) // 比登录请求的 10 秒超时长：超时了能看到错误文案
        } catch (e: Throwable) {
            val ui = runCatching { allText(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()) }.getOrDefault("?")
            throw AssertionError("登录没有完成：im=${model.im != null} 界面=$ui", e)
        }
        rule.waitUntil(10_000) { model.im?.state == ConnState.CONNECTED }
    }

    /** 键盘弹起时布局在动，触摸可能被取消（测试注入的点击比手指快得多）：按钮还在、也没进入加载态就再点一次。 */
    private fun tapUntilLeft(tag: String) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            Thread.sleep(300)
            rule.waitForIdle()
            val nodes = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isEmpty() || allText(nodes[0]) != "登录") return
            rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            Thread.sleep(700)
        }
    }

    private fun waitTag(tag: String, t: Long = 8000) = rule.waitUntil(t) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(s: String, t: Long = 8000, substring: Boolean = false) = rule.waitUntil(t) { rule.onAllNodesWithText(s, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun has(s: String, substring: Boolean = true) = rule.onAllNodesWithText(s, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun open(path: String) {
        inst.runOnMainSync { model.nav.resetTo(path) }
        rule.waitForIdle()
    }
    private fun say(text: String) {
        waitTag("composer-input")
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performTextReplacement(text)
        rule.waitForIdle()
        rule.onNodeWithTag("send-button", useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }
    private fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(30)
        }
    }
    private fun find(c: IMClient, conv: String, text: String): Message? = c.messages(conv).firstOrNull { it.body.text == text }
    private fun group(vararg members: String): Pair<String, String> {
        val g = uid("g")
        admin("POST", "/admin/groups", obj("groupId" to g, "name" to "群$g", "members" to members.toList()))
        return g to "group:$g"
    }

    @Test fun two_accounts_chat_both_ways_and_second_account_sees_it() { // 两账号互发、AC-UI-03-7、AC-UI-11-2
        val (a, b) = uid("a") to uid("b")
        users(b)
        val bob = peer(b)
        appLogin(a, "张三")
        val conv = com.tianting.sdk.c2cConvId(a, b)
        open(chatPath(conv))
        say("你好，我是 app")
        waitTag("message")
        rule.waitUntil(8000) { rule.onAllNodes(hasTestTag("failed-icon") or hasTestTag("sending-icon"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        until("bob 收到") { bob.conversations.value.any { it.convId == conv && it.lastMsg?.text == "你好，我是 app" } }
        assertEquals("张三", bob.conversations.value.first { it.convId == conv }.name)
        runBlocking { bob.send(conv, "收到，我是 bob") }
        waitText("收到，我是 bob")
        // 对方的消息在左、自己的在右
        val mine = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText("你好，我是 app")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val other = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText("收到，我是 bob")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(other.left < mine.left)
        say("😀")
        until("bob 收到表情") { runBlocking { bob.loadHistory(conv) }; find(bob, conv, "😀") != null }
        assertEquals(1, find(bob, conv, "😀")!!.body.text!!.codePointCount(0, 2))
        // 退出后换另一个账号登录：看不到上一个账号的数据
        inst.runOnMainSync { model.logout() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("login-username", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val c = uid("c")
        rule.onNodeWithTag("login-username", useUnmergedTree = true).performTextReplacement(c)
        rule.onNodeWithTag("login-submit", useUnmergedTree = true).performClick()
        waitTag("chats-empty", 10_000)
        assertFalse(has("你好，我是 app"))
    }

    @Test fun group_chat_with_sender_names() { // 群聊
        val (a, b, c) = Triple(uid("a"), uid("b"), uid("c"))
        users(a, b, c)
        val (_, conv) = group(a, b, c)
        val bob = peer(b)
        val carol = peer(c)
        appLogin(a)
        waitText("群g", substring = true)
        runBlocking { bob.send(conv, "群里第一句") }
        waitText("$b: 群里第一句", substring = false)
        open(chatPath(conv))
        waitText("群里第一句")
        waitTag("msg-name")
        rule.onNode(hasTestTag("chat-title-count") and hasText("（3）"), useUnmergedTree = true).assertExists()
        say("大家好")
        until("carol 收到") { carol.conversations.value.any { it.convId == conv && it.lastMsg?.text == "大家好" } }
        until("bob 收到") { bob.conversations.value.any { it.convId == conv && it.lastMsg?.text == "大家好" } }
        rule.waitUntil(8000) { rule.onAllNodes(hasTestTag("sending-icon"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertFalse(rule.onAllNodesWithTag("failed-icon", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun muted_user_and_group_then_unmute() { // 被禁言：AC-UI-41-1、41-2、41-3、AC-UI-41-5
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val (g1, c1) = group(a, b)
        val (_, c2) = group(a, b)
        val c2c = com.tianting.sdk.c2cConvId(a, b)
        appLogin(a)
        open(chatPath(c1))
        waitTag("composer-input")
        val r = signed("POST", "/admin/mute", obj("scope" to "user", "userId" to a, "durationSeconds" to 600, "reason" to "e2e"))
        val until = r["mute"]?.jsonObject?.get("until")?.jsonPrimitive?.longOrNull ?: r["until"]?.jsonPrimitive?.longOrNull
        waitTag("composer-disabled", 3000)
        assertFalse(rule.onAllNodesWithTag("composer-input", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val bar = allTextOf("composer-disabled")
        assertTrue(bar.startsWith("你已被禁言，解除时间："), bar)
        if (until != null) assertEquals("你已被禁言，解除时间：${formatMonthDayTime(until)}", bar)
        open(chatPath(c2c))
        waitTag("composer-disabled", 3000)
        signed("POST", "/admin/unmute", obj("scope" to "user", "userId" to a, "reason" to "e2e"))
        waitTag("composer-input", 3000)
        // 只在 g1 被永久禁言
        signed("POST", "/admin/mute", obj("scope" to "group", "groupId" to g1, "userId" to a, "durationSeconds" to null, "reason" to "e2e"))
        open(chatPath(c1))
        waitTag("composer-disabled", 3000)
        assertEquals("你已被禁言", allTextOf("composer-disabled"))
        open(chatPath(c2))
        waitTag("composer-input", 3000)
        say("g2 可以发")
        rule.waitUntil(8000) { rule.onAllNodes(hasTestTag("sending-icon"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        open(chatPath(c2c))
        waitTag("composer-input", 3000)
    }

    private fun allTextOf(tag: String) = allText(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())

    @Test fun banned_online_then_login_refused() { // 被封禁：AC-UI-43-1、43-3
        val a = uid("a")
        users(a)
        appLogin(a)
        val r = signed("POST", "/admin/users/$a/ban", obj("durationSeconds" to 86_400, "reason" to "e2e"))
        val until = r["ban"]!!.jsonObject["until"]!!.jsonPrimitive.long
        waitTag("kicked-dialog", 3000)
        val text = "账号已被封禁，解封时间：${formatDateTime(until)}"
        waitText(text)
        Thread.sleep(2000) // 不重连
        assertEquals(ConnState.DISCONNECTED, model.im!!.state)
        // 返回键不关
        androidx.test.uiautomator.UiDevice.getInstance(inst).pressBack()
        rule.waitForIdle()
        waitTag("kicked-dialog")
        rule.onNodeWithTag("kicked-ok", useUnmergedTree = true).performClick()
        waitTag("login-username")
        assertEquals(null, KeystoreSessionStore(testContext).load())
        rule.onNodeWithTag("login-username", useUnmergedTree = true).performTextReplacement(a)
        rule.onNodeWithTag("login-submit", useUnmergedTree = true).performClick()
        waitTag("login-error")
        assertEquals(text, allTextOf("login-error"))
        assertTrue(rule.onAllNodesWithTag("login-username", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun deleted_message_gone_from_ui_memory_and_files() { // 消息被删除后搜不到原文：AC-UI-42-1、42-2、42-3
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val (_, conv) = group(a, b)
        val bob = peer(b)
        appLogin(a)
        val secret = "ZXQ-DEL-" + uid("x")
        runBlocking { bob.send(conv, secret) }
        until("bob ack") { find(bob, conv, secret)?.msgId != null }
        val msgId = find(bob, conv, secret)!!.msgId!!
        waitText(secret, substring = true)
        open(chatPath(conv))
        waitTag("bubble")
        waitText(secret)
        val dbFiles = dbFiles()
        until("对照：删除之前本地库里有原文") { dbFiles().any { contains(it, secret) } }
        signed("POST", "/admin/messages/delete", obj("convId" to conv, "msgIds" to listOf(msgId), "reason" to "e2e"))
        waitTag("msg-deleted", 3000)
        assertEquals("该消息已被删除", allTextOf("msg-deleted"))
        assertFalse(has(secret))
        open("/chats")
        waitText("该消息已被删除")
        assertFalse(has(secret))
        // 内存（调试导出）和本地库文件（主库、-journal）的原始字节里都搜不到
        assertFalse(secret in runBlocking { model.im!!.client.debugExport() }.toString())
        until("本地库文件里搜不到原文") { dbFiles().none { contains(it, secret) } }
    }

    private fun dbFiles(): () -> List<File> = {
        testContext.getDatabasePath("tianting-sdk.db").parentFile!!.listFiles().orEmpty().filter { it.isFile }
    }

    private fun contains(f: File, needle: String): Boolean {
        val h = f.readBytes()
        val n = needle.toByteArray()
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    @Test fun removed_from_group_live() { // AC-UI-33-4、33-14、36-5、40 真实服务端
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val (g, conv) = group(a, b)
        appLogin(a)
        open(chatPath(conv))
        waitTag("composer-input")
        admin("DELETE", "/admin/groups/$g/members", obj("members" to listOf(a)))
        waitTag("composer-disabled", 3000)
        assertEquals("你已不在该群，无法发送消息", allTextOf("composer-disabled"))
    }

    @Test fun friend_flow_and_new_group_with_tips() { // AC-UI-25-2、27-2、35-1、36-5、37-1 对真实服务端
        val (a, b, c) = Triple(uid("a"), uid("b"), uid("c"))
        users(a, b, c)
        val bob = peer(b)
        val carol = peer(c)
        appLogin(a)
        // alice 通过搜索申请 bob，bob 同意
        open("/contacts/add")
        waitTag("search-input")
        rule.onNodeWithTag("search-input", useUnmergedTree = true).performTextReplacement(b)
        rule.onNodeWithTag("search-input", useUnmergedTree = true).performImeAction()
        waitTag("search-add")
        rule.onNodeWithTag("search-add", useUnmergedTree = true).performClick()
        waitTag("apply-send")
        rule.onNodeWithTag("apply-message", useUnmergedTree = true).performTextReplacement("球友")
        rule.onNodeWithTag("apply-remark", useUnmergedTree = true).performTextReplacement("B哥")
        rule.onNodeWithTag("apply-send", useUnmergedTree = true).performClick()
        waitText("已发送")
        until("bob 收到申请") { bob.friendRequests.value.items.any { it.userId == a && it.message == "球友" } }
        runBlocking { bob.respondFriend(a, com.tianting.sdk.FriendAction.ACCEPT) }
        // carol 主动申请，alice 在"新的朋友"同意
        runBlocking { carol.applyFriend(a, com.tianting.sdk.ApplyFriendOptions(message = "我是 carol", source = com.tianting.sdk.ApplySource.SEARCH)) }
        open("/contacts/requests")
        waitTag("request-accept")
        rule.onNodeWithTag("request-accept", useUnmergedTree = true).performClick()
        waitText("已添加")
        until("carol 有 alice") { carol.friends.value.any { it.userId == a } }
        open("/contacts")
        waitText("B哥")
        // 发起群聊
        open("/groups/new")
        waitTag("pick-row")
        rule.onNode(tagged("pick-row", DataUser, b), useUnmergedTree = true).performClick()
        rule.onNode(tagged("pick-row", DataUser, c), useUnmergedTree = true).performClick()
        rule.onNodeWithTag("pick-done", useUnmergedTree = true).performClick()
        waitTag("group-create")
        rule.onNodeWithTag("group-create", useUnmergedTree = true).performClick()
        waitTag("chat-title", 8000)
        waitText("你创建了群聊")
        rule.waitUntil(5000) { allTextOf("chat-title-count") == "（3）" }
        // 改群名：标题和提示消息
        rule.onNodeWithTag("chat-more", useUnmergedTree = true).performClick()
        waitTag("group-name")
        rule.onNodeWithTag("group-name", useUnmergedTree = true).performClick()
        waitTag("rename-input")
        rule.onNodeWithTag("rename-input", useUnmergedTree = true).performTextReplacement("周六球局")
        rule.onNodeWithTag("rename-save", useUnmergedTree = true).performClick()
        rule.waitUntil(8000) { allTextOf("group-name-value") == "周六球局" }
        inst.runOnMainSync { model.nav.back() } // 这里键盘可能还开着，系统返回键会先收键盘
        waitTag("chat-title")
        rule.waitUntil(5000) { allTextOf("chat-title") == "周六球局" }
        // 提示消息：服务端不推给操作者自己的连接（deliverTip 的 exceptConn），SDK 的 updateGroup 也没有补拉（见 README「已知缺口」），
        // 所以这里验证其他成员收到；操作者这边等下一条消息触发缺口补拉后才看到。
        val gconv = decodeParam(model.nav.current.path.removePrefix("/chat/"))
        until("成员看到新群名") { bob.conversations.value.any { it.convId == gconv && it.name == "周六球局" } }
        until("成员收到提示消息") { bob.conversations.value.any { it.convId == gconv && it.lastMsg?.type == com.tianting.sdk.MsgType.TIP } }
        runBlocking { bob.send(gconv, "群名改好了") }
        waitText("群名改好了")
        waitText("你修改群名为\"周六球局\"", substring = true) // 缺口补拉之后操作者也看到
    }

    private val kotlinx.serialization.json.JsonPrimitive.long: Long get() = content.toLong()
}
