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
import com.tianting.app.core.UiPrefs
import com.tianting.sdk.AccountClient
import com.tianting.sdk.AccountClientOptions
import com.tianting.sdk.AccountSession
import com.tianting.sdk.Clock
import com.tianting.sdk.HttpCall
import com.tianting.sdk.HttpCallback
import com.tianting.sdk.HttpRequest
import com.tianting.sdk.HttpResponse
import com.tianting.sdk.HttpTransport
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.PendingLogout
import com.tianting.sdk.PendingLogoutStore
import com.tianting.sdk.SessionMode
import com.tianting.sdk.SessionStore
import com.tianting.sdk.Timer
import com.tianting.sdk.Transport
import com.tianting.sdk.TransportConnection
import com.tianting.sdk.TransportHandlers
import com.tianting.sdk.testing.FakeConnection
import com.tianting.sdk.testing.FakeTransport
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.net.URI
import java.util.Base64 as JBase64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
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
    allMuted: Boolean = false,
): JsonObject {
    val m = linkedMapOf<String, Any?>(
        "convId" to convId, "type" to if (convId.startsWith("group:")) "group" else "c2c", "name" to name, "maxSeq" to maxSeq,
        "readSeq" to maxSeq - unread, "minSeq" to 1, "unread" to unread, "lastMsg" to lastMsg, "updatedAt" to updatedAt,
    )
    memberCount?.let { m["memberCount"] = it }
    if (allMuted) m["allMuted"] = true
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

    /** 收件箱（push.data）：pushSync 从这里取。 */
    @Volatile var inbox: List<JsonObject> = emptyList()
    @Volatile var latestOverride: Long? = null

    val current: FakeConnection get() = inner.current
    val connections: List<FakeConnection> get() = inner.connections.toList()

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

    fun pushFrame(data: JsonObject) = push(obj("cmd" to "push", "data" to data))

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
        "pushSync" -> {
            val after = f.data["afterSeq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
            val out = inbox.filter { it["pushSeq"]!!.jsonPrimitive.content.toLong() > after }
            val latest = inbox.maxOfOrNull { it["pushSeq"]!!.jsonPrimitive.content.toLong() } ?: 0
            Reply.Data(obj("pushes" to out, "hasMore" to false, "latestSeq" to (latestOverride ?: latest)))
        }
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

class MemorySessions(@Volatile var session: AccountSession? = null) : SessionStore {
    val saves = CopyOnWriteArrayList<AccountSession>()

    override suspend fun load() = session
    override suspend fun save(session: AccountSession) {
        saves.add(session)
        this.session = session
    }

    override suspend fun clear() {
        session = null
    }
}

class MemoryPending : PendingLogoutStore {
    @Volatile var items: List<PendingLogout> = emptyList()
    override suspend fun load() = items
    override suspend fun save(items: List<PendingLogout>) {
        this.items = items
    }
}

/** [SDK 钟]：定时器放在 SDK 账号线程上执行，和正式运行的线程一样。advance 走完才返回。 */
class TestClock(private val on: ExecutorService, start: Long = System.currentTimeMillis()) : Clock {
    private class Item(val at: Long, val id: Long, val fn: () -> Unit)

    private val lock = Any()
    private val timers = ArrayList<Item>()
    private var nextId = 1L
    @Volatile private var t = start

    override fun now(): Long = t

    override fun schedule(delayMs: Long, fn: () -> Unit): Timer {
        val item = synchronized(lock) { Item(t + maxOf(0, delayMs), nextId++, fn).also { timers.add(it) } }
        return Timer { synchronized(lock) { timers.remove(item) } }
    }

    fun advance(ms: Long) {
        val end = t + ms
        while (true) {
            val due = synchronized(lock) {
                timers.filter { it.at <= end }.minWithOrNull(compareBy<Item> { it.at }.thenBy { it.id })?.also {
                    timers.remove(it)
                    t = maxOf(t, it.at)
                }
            } ?: break
            on.submit { due.fn() }.get()
        }
        t = end
    }
}

/** 假账号接口：记录每个请求，默认按真实服务端的形状应答，测试用 handlers 改写。回复放在单独的线程上发。 */
class FakeAccountHttp(var user: String = "alice") : HttpTransport {
    class Req(val method: String, val path: String, val bearer: String?, val body: JsonObject)

    sealed interface Reply {
        data class Json(val data: JsonElement, val status: Int = 200) : Reply
        data class Err(val status: Int, val code: Int, val reason: String? = null, val details: JsonObject? = null) : Reply
        data object NetworkFail : Reply
        data object Hold : Reply

        /** 不是约定的 JSON（代理的页面）。 */
        data class Raw(val status: Int, val text: String) : Reply
    }

    private val net = Executors.newSingleThreadExecutor()
    private val held = CopyOnWriteArrayList<Pair<Req, HttpCallback>>()
    val requests = CopyOnWriteArrayList<Req>()
    val handlers = java.util.concurrent.ConcurrentHashMap<String, (Req) -> Reply>()

    var registerMode = "open"
    var demoLogin = true
    var privacy: JsonObject? = null
    var eventsBody: JsonObject = obj("events" to emptyList<JsonObject>(), "otherSessions" to obj("count" to 0))
    private var nextGen = 2L
    @Volatile var sid = "sid-1"
    @Volatile var accessTtlMs = 24 * 3_600_000L

    fun to(path: String): List<Req> = requests.filter { it.path == path }

    fun jwt(gen: Long, sessionId: String = sid): String {
        val payload = obj("sid" to sessionId, "gen" to gen, "exp" to (System.currentTimeMillis() / 1000 + 86400)).toString()
        return "h." + JBase64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
    }

    /** 一份 AccountSession（给已登录的用例放进存储）。 */
    fun session(gen: Long = 1, expiresAt: Long = System.currentTimeMillis() + accessTtlMs, absolute: Long = System.currentTimeMillis() + 365 * 86_400_000L, refresh: String = "r$gen") = AccountSession(
        mode = SessionMode.ACCOUNT, userId = user, accessToken = jwt(gen), accessExpiresAt = expiresAt, refreshToken = refresh,
        refreshExpiresAt = System.currentTimeMillis() + 90 * 86_400_000L, absoluteExpiresAt = absolute,
    )

    /** AuthResponse。code 不为空时带这一代恢复码。 */
    fun auth(gen: Long = nextGen++, code: String? = null, codeGen: Long = 1, unacked: Boolean = false, expiresAt: Long = System.currentTimeMillis() + accessTtlMs, absolute: Long = System.currentTimeMillis() + 365 * 86_400_000L): JsonObject {
        val m = linkedMapOf<String, Any?>(
            "userId" to user, "accessToken" to jwt(gen), "accessExpiresAt" to expiresAt, "refreshToken" to "r$gen",
            "refreshExpiresAt" to System.currentTimeMillis() + 90 * 86_400_000L, "absoluteExpiresAt" to absolute,
        )
        if (code != null) {
            m["recoveryCode"] = code
            m["recoveryCodeGen"] = codeGen
        }
        if (unacked) m["recoveryCodeUnacked"] = true
        return js(m).jsonObject
    }

    private fun configBody(): JsonObject {
        val m = linkedMapOf<String, Any?>(
            "registerMode" to registerMode, "demoLogin" to demoLogin, "features" to obj("files" to false),
            "limits" to obj("usernameMinLen" to 4, "usernameMaxLen" to 32, "passwordMinLen" to 8, "passwordMaxLen" to 128, "nicknameMaxLen" to 32, "deviceNameMaxLen" to 64),
        )
        privacy?.let { m["privacyNotice"] = it }
        return js(m).jsonObject
    }

    private fun defaultReply(r: Req): Reply = when (r.path) {
        "/v1/config" -> Reply.Json(configBody())
        "/v1/account/register" -> Reply.Json(auth(code = "CODE-REG-1"))
        "/v1/account/login" -> Reply.Json(auth())
        "/v1/account/recover" -> Reply.Json(auth(code = "CODE-REC-1", codeGen = 2))
        "/v1/account/token/refresh" -> Reply.Json(auth())
        "/v1/account/password" -> {
            sid = "sid-" + (nextGen + 100)
            Reply.Json(auth(gen = 1, code = "CODE-PW-1", codeGen = 2))
        }
        "/v1/account/logout-others" -> {
            sid = "sid-" + (nextGen + 100)
            Reply.Json(auth(gen = 1))
        }
        "/v1/account/recovery-code" -> Reply.Json(obj("recoveryCode" to "CODE-NEW-1", "recoveryCodeGen" to 3))
        "/v1/account/events" -> Reply.Json(eventsBody)
        else -> Reply.Json(JsonObject(emptyMap()))
    }

    override fun request(req: HttpRequest, callback: HttpCallback): HttpCall {
        val r = Req(req.method, URI(req.url).path, req.headers["Authorization"]?.removePrefix("Bearer "), req.body?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap()))
        requests.add(r)
        val reply = handlers[r.path]?.invoke(r) ?: defaultReply(r)
        if (reply == Reply.Hold) held.add(r to callback) else net.execute { deliver(reply, callback) }
        return HttpCall { }
    }

    private fun deliver(reply: Reply, cb: HttpCallback) {
        when (reply) {
            is Reply.Json -> cb.onResponse(HttpResponse(reply.status, reply.data.toString()))
            is Reply.Err -> cb.onResponse(
                HttpResponse(
                    reply.status,
                    JsonObject(listOfNotNull("code" to js(reply.code), "msg" to js("server says x"), reply.reason?.let { "reason" to js(it) }, reply.details?.let { "details" to it }).toMap()).toString(),
                ),
            )
            is Reply.Raw -> cb.onResponse(HttpResponse(reply.status, reply.text))
            Reply.NetworkFail -> cb.onFailure()
            Reply.Hold -> Unit
        }
    }

    /** 放行之前 Hold 的请求。 */
    fun release(path: String, reply: Reply? = null) {
        for (h in held.filter { it.first.path == path }) {
            held.remove(h)
            net.execute { deliver(reply ?: handlersDefault(h.first), h.second) }
        }
    }

    private fun handlersDefault(r: Req): Reply = defaultReply(r)

    fun releaseAll() {
        for (h in held) {
            held.remove(h)
            net.execute { deliver(Reply.NetworkFail, h.second) }
        }
    }

    fun err(path: String, status: Int, code: Int, reason: String? = null, details: JsonObject? = null) {
        handlers[path] = { Reply.Err(status, code, reason, details) }
    }
}

/** 不关心账号接口的用例（只要 config）。 */
fun fakeAccount(sessions: SessionStore, http: HttpTransport = FakeAccountHttp()) =
    AccountClient(AccountClientOptions(baseUrl = "http://fake", store = sessions, http = http))

/** 换成真实依赖（对真实服务端的端到端），旧的模型释放掉。 */
fun replaceWithRealDeps(deps: AppDeps = com.tianting.app.core.defaultDeps(testContext)) {
    val old = testApp.model
    inst.runOnMainSync { testApp.model = AppModel(testContext, deps) }
    runBlocking { old.close() }
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
    val pending = MemoryPending()
    val copied = CopyOnWriteArrayList<String>()
    var copyWorks = true
    var demoBuild = true
    val http by lazy { FakeAccountHttp(server.self) }
    private val accountThread by lazy { Executors.newSingleThreadExecutor() }
    val clock by lazy { TestClock(accountThread) }

    /** 新的 AccountClient，接假 HTTP、[SDK 钟] 和内存存储。模拟杀 App 重启时每次换一个。 */
    fun newAccount() = AccountClient(
        AccountClientOptions(
            baseUrl = "http://fake",
            store = sessions,
            pendingLogoutStore = pending,
            http = http,
            clock = clock,
            random = { 0.5 },
            dispatcher = accountThread.asCoroutineDispatcher(),
        ),
    )

    fun newDeps(transport: Transport = server, clientOptions: (visible: () -> Boolean) -> IMClientOptions = { v -> IMClientOptions(url = "ws://fake/ws", transport = transport, isVisible = v) }) = AppDeps(
        newClient = { visible -> IMClient(clientOptions(visible)) },
        login = { u, n -> loginApi.login(u, n) },
        sessions = sessions,
        isOnline = { online },
        bindNetwork = { null },
        account = newAccount(),
        copyText = { t -> if (copyWorks) copied.add(t) else false },
        demoBuild = demoBuild,
    )

    /** 换掉 App 模型。旧的释放掉，它的线程和监听不留到下一个用例。 */
    fun replaceModel(deps: AppDeps) {
        val old = testApp.model
        inst.runOnMainSync { testApp.model = AppModel(testContext, deps) }
        runBlocking { old.close() }
    }

    /** 用假依赖换掉 App 模型。 */
    fun install(loggedIn: Boolean = true, transport: Transport = server, self: String = server.self) {
        sessions.session = if (loggedIn) AccountSession(mode = SessionMode.DEMO, userId = self, accessToken = "test-token") else null
        replaceModel(newDeps(transport))
    }

    /** 账号会话已经在存储里。 */
    fun installAccount(session: AccountSession? = http.session()) {
        sessions.session = session
        replaceModel(newDeps())
    }

    fun launch(deepLink: String? = null) {
        val intent = Intent(testContext, MainActivity::class.java)
        if (deepLink != null) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(deepLink)
        }
        scenario = ActivityScenario.launch(intent)
    }

    /** 模拟 App 重启：关掉 Activity，再打开一次（App 模型由测试换好）。 */
    fun scenarioRestart() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
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
        // 用例中途失败时可能还有 Hold 的请求：放掉，否则下面等退出结束会一直挂着
        http.handlers.clear()
        http.releaseAll()
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
