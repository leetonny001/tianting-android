// UI 测试的公共部分：假服务端（SDK 的 FakeTransport + 自动回 ack）和启动 App 的基类。
package com.tianting.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.AppDeps
import com.tianting.app.core.AppModel
import com.tianting.app.core.LoginApi
import com.tianting.app.core.Session
import com.tianting.app.core.SessionStore
import com.tianting.app.core.UiPrefs
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.Transport
import com.tianting.sdk.TransportConnection
import com.tianting.sdk.TransportHandlers
import com.tianting.sdk.testing.FakeConnection
import com.tianting.sdk.testing.FakeTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

fun js(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to js(x) })
    is List<*> -> JsonArray(v.map { js(it) })
    else -> throw IllegalArgumentException("$v")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.toMap().mapValues { js(it.value) })

/** 固定为测试开始前一小时：SDK 会清掉 30 天以前的申请。 */
val T0: Long = System.currentTimeMillis() - 3_600_000

fun convJson(
    convId: String,
    name: String = convId,
    maxSeq: Long = 0,
    unread: Long = 0,
    lastMsg: JsonObject? = null,
    memberCount: Int? = null,
    myRole: String? = null,
    updatedAt: Long = T0,
): JsonObject {
    val m = linkedMapOf<String, Any?>(
        "convId" to convId, "type" to if (convId.startsWith("group:")) "group" else "c2c", "name" to name, "maxSeq" to maxSeq,
        "readSeq" to maxSeq - unread, "minSeq" to 1, "unread" to unread, "lastMsg" to lastMsg, "updatedAt" to updatedAt,
    )
    memberCount?.let { m["memberCount"] = it }
    myRole?.let { m["myRole"] = it }
    return js(m).jsonObject
}

fun lastMsg(from: String, text: String?, type: String = "text", ts: Long = T0, deleted: Boolean = false) =
    obj("msgId" to "m-$ts", "from" to from, "type" to type, "text" to text, "ts" to ts, "deleted" to deleted)

fun msgJson(convId: String, seq: Long, from: String, text: String, ts: Long = T0 + seq * 1000, type: String = "text"): JsonObject = obj(
    "msgId" to "$convId#$seq", "clientMsgId" to "$from-$convId-$seq", "convId" to convId, "seq" to seq, "from" to from, "type" to type,
    "body" to if (type == "text") obj("text" to text) else obj("data" to obj("x" to 1)), "ts" to ts,
)

fun tipJson(convId: String, seq: Long, kind: String, actor: String = "", targets: List<String> = emptyList(), count: Int? = null, ts: Long = T0 + seq * 1000): JsonObject = obj(
    "msgId" to "$convId#$seq", "clientMsgId" to "tip-$convId-$seq", "convId" to convId, "seq" to seq, "from" to actor, "type" to "tip",
    "body" to obj("tip" to JsonObject(listOfNotNull("kind" to js(kind), "actor" to js(actor), "targets" to js(targets), count?.let { "count" to js(it) }).toMap())), "ts" to ts,
)

fun deletedJson(convId: String, seq: Long, from: String, ts: Long = T0 + seq * 1000): JsonObject = obj(
    "msgId" to "$convId#$seq", "clientMsgId" to "$from-$convId-$seq", "convId" to convId, "seq" to seq, "from" to from, "type" to "text", "ts" to ts, "deleted" to true, "deletedAt" to ts + 1,
)

fun profileJson(nickname: String, official: Boolean = false, avatar: String = "") = obj("nickname" to nickname, "avatar" to avatar, "official" to official)

fun friendJson(userId: String, nickname: String = userId, remark: String = "", ver: Long = 1, official: Boolean = false): JsonObject = obj(
    "userId" to userId, "remark" to remark, "source" to "other", "createdAt" to T0, "ver" to ver, "profile" to profileJson(nickname, official),
)

fun requestJson(userId: String, direction: String = "in", status: String = "pending", message: String = "", ver: Long = 1, source: String = "search", updatedAt: Long = T0, expireAt: Long = Long.MAX_VALUE / 2, profile: JsonObject? = profileJson(userId)): JsonObject = obj(
    "userId" to userId, "direction" to direction, "status" to status, "message" to message, "source" to source, "applyCount" to 1,
    "createdAt" to T0, "updatedAt" to updatedAt, "expireAt" to expireAt, "ver" to ver, "profile" to profile,
)

fun blockJson(userId: String, ver: Long = 1, createdAt: Long = T0, profile: JsonObject? = null) = obj("userId" to userId, "createdAt" to createdAt, "ver" to ver, "profile" to profile)

class Frame(val id: String, val cmd: String, val data: JsonObject)

/** 处理一条命令：返回 ack 的 data，或用 Err 表示错误，Hold 表示不回。 */
sealed interface Reply {
    data class Data(val data: JsonElement) : Reply
    data class Err(val code: Int, val userId: String? = null, val details: JsonObject? = null) : Reply
    data object Hold : Reply
}

/**
 * 假服务端：连接自动 open，每个命令按默认表回 ack；测试用 handlers 改写（PRD-ui 2.2 节 routeWebSocket 的等价手段）。
 * 回复放在单独的线程上发，和真实网络一样异步。
 */
class FakeServer(val self: String = "alice") : Transport {
    private val inner = FakeTransport()
    private val net = Executors.newSingleThreadExecutor()
    val sent = CopyOnWriteArrayList<Frame>()
    val handlers = java.util.concurrent.ConcurrentHashMap<String, (Frame) -> Reply>()

    var convs: List<JsonObject> = emptyList()
    var truncated = false
    var friends: List<JsonObject> = emptyList()
    var requests: List<JsonObject> = emptyList()
    var blocks: List<JsonObject> = emptyList()
    var mode = "open"
    var strangerMsgLimit = 0
    var searchable = true
    var official = false
    var requestSeenVer = 0L
    var socialVer = 100L
    var authExtra: Map<String, Any?> = emptyMap()
    var users = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    var messages = java.util.concurrent.ConcurrentHashMap<String, List<JsonObject>>()
    private var seq = 1000L

    val current: FakeConnection get() = inner.current

    override fun open(url: String, handlers: TransportHandlers): TransportConnection {
        val c = inner.open(url, handlers) as FakeConnection
        c.onSent = { raw -> onFrame(c, raw) }
        net.execute { c.open() }
        return c
    }

    fun frames(cmd: String): List<Frame> = sent.filter { it.cmd == cmd }

    /** 服务端主动下发（msg 帧、事件）。 */
    fun push(frame: JsonObject) = net.execute { current.receive(frame) }
    fun pushMsg(m: JsonObject) = push(obj("cmd" to "msg", "data" to m))
    fun pushEvent(type: String, vararg pairs: Pair<String, Any?>) = push(obj("cmd" to "event", "data" to obj("type" to type, *pairs)))

    fun drop(code: Int = 1006) = net.execute { current.drop(code) }

    private fun onFrame(c: FakeConnection, raw: String) {
        val o = Json.parseToJsonElement(raw).jsonObject
        val id = o["id"]?.jsonPrimitive?.content ?: return
        val cmd = o["cmd"]?.jsonPrimitive?.content ?: return
        val f = Frame(id, cmd, o["data"] as? JsonObject ?: JsonObject(emptyMap()))
        sent.add(f)
        val reply = handlers[cmd]?.invoke(f) ?: defaultReply(f)
        if (reply is Reply.Data) applySocial(reply.data)
        when (reply) {
            is Reply.Data -> net.execute { c.receive(buildJsonObject { put("id", id); put("cmd", "ack"); put("data", reply.data) }) }
            is Reply.Err -> net.execute {
                c.receive(
                    buildJsonObject {
                        put("id", id)
                        put("cmd", "ack")
                        put(
                            "error",
                            JsonObject(
                                listOfNotNull(
                                    "code" to js(reply.code),
                                    "msg" to js("server says x-$cmd"),
                                    reply.userId?.let { "userId" to js(it) },
                                    reply.details?.let { "details" to it },
                                ).toMap(),
                            ),
                        )
                    },
                )
            }
            Reply.Hold -> Unit
        }
    }

    /** 和真实服务端一样：社交命令的结果之后也出现在 socialSync 里。 */
    private fun applySocial(data: JsonElement) {
        val o = data as? JsonObject ?: return
        val items = o["items"] as? JsonObject ?: return
        fun merge(old: List<JsonObject>, key: String): List<JsonObject> {
            val incoming = (items[key] as? JsonArray).orEmpty().map { it.jsonObject }
            val ids = incoming.map { it["userId"] }.toSet()
            return old.filter { it["userId"] !in ids } + incoming.filter { it["deleted"]?.jsonPrimitive?.content != "true" }
        }
        friends = merge(friends, "friends")
        requests = merge(requests, "requests")
        blocks = merge(blocks, "blocks")
        o["ver"]?.jsonPrimitive?.content?.toLongOrNull()?.let { socialVer = maxOf(socialVer, it) }
    }

    private fun defaultReply(f: Frame): Reply = when (f.cmd) {
        "auth" -> Reply.Data(js(mapOf("userId" to self, "serverTime" to System.currentTimeMillis()) + authExtra))
        "convs" -> Reply.Data(obj("convs" to convs, "truncated" to truncated))
        "socialSync" -> Reply.Data(
            obj(
                "full" to true, "untilVer" to socialVer, "latestVer" to socialVer, "hasMore" to false, "cursor" to "",
                "friends" to friends, "requests" to requests, "blocks" to blocks, "requestSeenVer" to requestSeenVer,
                "settings" to obj("searchable" to searchable), "mode" to mode, "strangerMsgLimit" to strangerMsgLimit, "official" to official,
            ),
        )
        "users" -> Reply.Data(obj("users" to (f.data["userIds"] as? JsonArray).orEmpty().mapNotNull { users[it.jsonPrimitive.content] }))
        "sync" -> {
            val conv = f.data["convId"]!!.jsonPrimitive.content
            val list = messages[conv].orEmpty()
            val before = f.data["beforeSeq"]?.jsonPrimitive?.content?.toLongOrNull()
            val out = if (before != null) list.filter { (it["seq"]!!.jsonPrimitive.content.toLong()) < before } else list
            Reply.Data(obj("messages" to out.takeLast(20), "hasMore" to false))
        }
        "send" -> {
            val conv = f.data["convId"]?.jsonPrimitive?.content ?: sendConv(f)
            val s = ++seq
            Reply.Data(obj("msgId" to "$conv#$s", "convId" to conv, "seq" to s, "ts" to System.currentTimeMillis()))
        }
        else -> Reply.Data(JsonObject(emptyMap()))
    }

    private fun sendConv(f: Frame): String {
        val to = f.data["to"]?.jsonPrimitive?.content.orEmpty()
        return if (f.data["convType"]?.jsonPrimitive?.content == "group") "group:$to" else if (self < to) "c2c:$self:$to" else "c2c:$to:$self"
    }
}

class MemorySessions(var session: Session? = null) : SessionStore {
    override fun load() = session
    override fun save(session: Session) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

val testContext: Context get() = ApplicationProvider.getApplicationContext()
val testApp: TiantingApplication get() = testContext as TiantingApplication
val inst get() = InstrumentationRegistry.getInstrumentation()

fun clearUiPrefs() = testContext.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()

abstract class UiTest {
    @get:Rule
    val rule: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    val server by lazy { FakeServer() }
    var online = true
    var loginApi = LoginApi { _, _ -> error("unexpected login") }
    val sessions = MemorySessions()
    val copied = CopyOnWriteArrayList<String>()
    var copyWorks = true

    /** 用假依赖换掉 App 模型。 */
    fun install(loggedIn: Boolean = true, transport: Transport = server, self: String = server.self) {
        sessions.session = if (loggedIn) Session(self, "test-token") else null
        inst.runOnMainSync {
            testApp.model = AppModel(
                testContext,
                AppDeps(
                    newClient = { visible -> IMClient(IMClientOptions(url = "ws://fake/ws", transport = transport, isVisible = visible)) },
                    login = { u, n -> loginApi.login(u, n) },
                    sessions = sessions,
                    isOnline = { online },
                    bindNetwork = { null },
                    copyText = { t -> if (copyWorks) copied.add(t) else false },
                ),
            )
        }
    }

    fun launch(deepLink: String? = null) {
        val intent = Intent(testContext, MainActivity::class.java)
        if (deepLink != null) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(deepLink)
        }
        scenario = ActivityScenario.launch(intent)
    }

    /** 安装假依赖并打开 App（已登录）。 */
    fun start(deepLink: String? = null) {
        install()
        launch(deepLink)
    }

    fun go(path: String, state: com.tianting.app.core.RouteState = com.tianting.app.core.RouteState()) {
        inst.runOnMainSync { testApp.model.nav.go(path, state) }
    }

    val model get() = testApp.model

    @Before
    fun resetPrefs() {
        clearUiPrefs()
    }

    @After
    fun finish() {
        scenario?.close()
        scenario = null
        inst.runOnMainSync { testApp.model.logout() }
        runBlocking { testApp.model.awaitTeardown() }
    }

    // ---- 断言小工具 ----

    fun ComposeTestRule.waitTag(tag: String, timeout: Long = 5000) = waitUntil(timeout) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    fun ComposeTestRule.waitGoneTag(tag: String, timeout: Long = 5000) = waitUntil(timeout) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    fun ComposeTestRule.waitText(text: String, timeout: Long = 5000, substring: Boolean = false) =
        waitUntil(timeout) { onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    fun ComposeTestRule.waitGoneText(text: String, timeout: Long = 5000) =
        waitUntil(timeout) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    fun ComposeTestRule.hasText(text: String, substring: Boolean = false) = onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    fun ComposeTestRule.hasTag(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** 负向断言：2 秒内不出现（PRD-ui 2.1 节）。 */
    fun assertNeverWithin(ms: Long = 2000, what: String = "", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (cond()) throw AssertionError("不应该出现：$what")
            Thread.sleep(50)
        }
    }

    fun waitFor(what: String = "condition", timeout: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("超时：$what")
            rule.waitForIdle()
            Thread.sleep(30)
        }
    }
}

fun <T> withData(key: SemanticsPropertyKey<T>, value: T) = SemanticsMatcher.expectValue(key, value)
fun tagged(tag: String, key: SemanticsPropertyKey<String>, value: String) = hasTestTag(tag) and withData(key, value)
fun SemanticsNodeInteraction.tryExists(): Boolean = try {
    fetchSemanticsNode()
    true
} catch (_: AssertionError) {
    false
}

fun UiTest.n(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
fun UiTest.txt(text: String) = rule.onNodeWithText(text, useUnmergedTree = true)
fun UiTest.click(tag: String) {
    rule.waitTag(tag)
    n(tag).performClick()
    rule.waitForIdle()
}
fun UiTest.clickText(text: String) {
    rule.waitText(text)
    txt(text).performClick()
    rule.waitForIdle()
}
fun UiTest.type(tag: String, text: String) {
    rule.waitTag(tag)
    n(tag).performTextReplacement(text)
    rule.waitForIdle()
}
fun UiTest.member(convOrUser: String, tag: String, key: SemanticsPropertyKey<String>) = rule.onNode(tagged(tag, key, convOrUser), useUnmergedTree = true)
fun UiTest.waitMember(value: String, tag: String, key: SemanticsPropertyKey<String>, timeout: Long = 5000) =
    rule.waitUntil(timeout) { rule.onAllNodes(tagged(tag, key, value), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

/** 取一个节点的全部文字（含子节点）。 */
fun UiTest.textOf(tag: String): String = allText(n(tag).fetchSemanticsNode())

fun allText(node: androidx.compose.ui.semantics.SemanticsNode): String {
    val own = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() +
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.orEmpty()
    return own + node.children.joinToString("") { allText(it) }
}

const val BOB_CONV = "c2c:alice:bob"
const val G1 = "group:g1"
fun groupInfoJson(myRole: String = "member", managed: Boolean = false, owner: String = "bob", name: String = "周末球局", notice: String = "", memberCount: Int = 3, noticeBy: String = "", noticeAt: Long = 0) = obj(
    "groupId" to "g1", "name" to name, "notice" to notice, "noticeBy" to noticeBy, "noticeAt" to noticeAt, "owner" to owner, "managed" to managed,
    "myRole" to myRole, "memberCount" to memberCount, "maxMembers" to 500, "historyVisible" to false, "createdAt" to T0,
)
fun memberJson(userId: String, role: String = "member", nickname: String = userId) = obj("userId" to userId, "role" to role, "joinedAt" to T0, "nickname" to nickname, "avatar" to "", "official" to false)
fun FakeServer.reply(cmd: String, data: JsonElement) { handlers[cmd] = { Reply.Data(data) } }
fun FakeServer.fail(cmd: String, code: Int, userId: String? = null, details: JsonObject? = null) { handlers[cmd] = { Reply.Err(code, userId, details) } }
fun FakeServer.hold(cmd: String) { handlers[cmd] = { Reply.Hold } }
fun FakeServer.group(myRole: String = "member", managed: Boolean = false, owner: String = "bob", members: List<JsonObject> = listOf(memberJson("bob", "owner"), memberJson("alice"), memberJson("carol")), name: String = "周末球局", notice: String = "") {
    convs = listOf(convJson(G1, name, memberCount = members.size, myRole = myRole))
    reply("groupInfo", groupInfoJson(myRole, managed, owner, name, notice, members.size))
    reply("groupMembers", obj("members" to members, "nextCursor" to ""))
}
fun socialAck(ver: Long = 200, status: String? = null, friends: List<JsonObject> = emptyList(), requests: List<JsonObject> = emptyList(), blocks: List<JsonObject> = emptyList()) =
    JsonObject(listOfNotNull(status?.let { "status" to js(it) }, "ver" to js(ver), "changed" to js(true), "items" to obj("friends" to friends, "requests" to requests, "blocks" to blocks)).toMap())

fun SemanticsNodeInteraction.assertAllTextContains(text: String) {
    val all = allText(fetchSemanticsNode())
    if (text !in all) throw AssertionError("期望包含 \"$text\"，实际 \"$all\"")
}
