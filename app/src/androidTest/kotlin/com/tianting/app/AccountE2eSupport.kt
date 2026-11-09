// 账号端到端的公共部分：真实 im-server（scripts/test-server.sh）、真实 Keystore、真实 OkHttp。
package com.tianting.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.KeystorePendingLogoutStore
import com.tianting.app.core.KeystoreSessionStore
import com.tianting.sdk.AccountClient
import com.tianting.sdk.AccountClientOptions
import com.tianting.sdk.AccountSession
import com.tianting.sdk.ConnState
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64 as JBase64
import java.util.Calendar
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val GOOD_PASSWORD = "Good-pass-1"
const val NEW_PASSWORD = "New-pass-12"

/** 真实服务端的管理接口和直接请求。 */
class RealServer {
    private val args = InstrumentationRegistry.getArguments()
    private val adminKey: String? = args.getString("ttAdminKey")
    private val adminBase: String = args.getString("ttAdminUrl") ?: "http://10.0.2.2:18281"
    private val metricsUrl: String = args.getString("ttMetricsUrl") ?: "http://10.0.2.2:18282/metrics"
    private val http = OkHttpClient()
    val base: String = BuildConfig.SERVER_URL.trimEnd('/')

    fun guard() = assumeTrue("需要 scripts/run-tests.sh 传入测试服务端参数", adminKey != null)

    fun admin(method: String, path: String, body: JsonObject = obj()): JsonObject {
        val req = Request.Builder().url(adminBase + path).header("X-Admin-Key", adminKey!!).method(method, if (method == "GET") null else body.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "$method $path: ${r.code}" }
            if (text.isEmpty()) obj() else Json.parseToJsonElement(text).jsonObject
        }
    }

    /** 调用方签名（protocol/ADMIN.md「鉴权」）：邀请码只接受这种方式。 */
    private fun signed(method: String, path: String, body: JsonObject): JsonObject {
        val raw = if (method == "GET") "" else body.toString()
        val ts = System.currentTimeMillis().toString()
        val nonce = JBase64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val toSign = listOf(method, path, ts, nonce, hash, "e2e", "", "").joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(args.getString("ttCallerSecret")!!.toByteArray(), "HmacSHA256")) }
        val sig = JBase64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(toSign.toByteArray()))
        val req = Request.Builder().url(adminBase + path)
            .header("X-IM-Caller", args.getString("ttCallerId")!!).header("X-IM-Timestamp", ts).header("X-IM-Nonce", nonce)
            .header("X-IM-Operator", "e2e").header("X-IM-Signature", sig)
            .method(method, if (method == "GET") null else raw.toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "$method $path: ${r.code}" }
            Json.parseToJsonElement(text).jsonObject
        }
    }

    fun resetLimits() {
        admin("POST", "/admin/test/limits/reset", obj("scope" to "auth"))
    }

    /** [IM 钟]。null 恢复真实时间。 */
    fun freezeClock(nowMs: Long?) {
        admin("POST", "/admin/test/clock", if (nowMs == null) obj() else obj("nowMs" to nowMs))
    }

    fun invite(maxUses: Int = 1): String {
        val r = signed("POST", "/admin/invites", obj("count" to 1, "maxUses" to maxUses, "expiresAt" to System.currentTimeMillis() + 86_400_000L))
        val first = r["invites"]?.jsonArray?.get(0) ?: r["items"]!!.jsonArray[0]
        return first.jsonObject["code"]!!.jsonPrimitive.content
    }

    /** 这个邀请码的已用次数（管理接口，只比后 4 位）。 */
    fun inviteUsed(code: String): Int {
        val rows = signed("GET", "/admin/invites", obj())["invites"]!!.jsonArray.map { it.jsonObject }
        return rows.first { it["codeLast4"]!!.jsonPrimitive.content == code.takeLast(4) }["usedCount"]!!.jsonPrimitive.content.toInt()
    }

    /** 直接请求刷新：(状态码, reason)。只用来确认服务端的状态。 */
    fun refreshStatus(refreshToken: String): Pair<Int, String?> {
        val req = Request.Builder().url("$base/v1/account/token/refresh").post(obj("refreshToken" to refreshToken).toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val o = runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrDefault(obj())
            r.code to o["reason"]?.jsonPrimitive?.content
        }
    }

    /** 持有偷来 refresh 的人：刷新，返回新的 access。 */
    fun refreshAccess(refreshToken: String): String {
        val req = Request.Builder().url("$base/v1/account/token/refresh").post(obj("refreshToken" to refreshToken).toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "refresh: ${r.code}" }
            Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["accessToken"]!!.jsonPrimitive.content
        }
    }

    /** 用 access 调一次接口（转正）。 */
    fun eventsStatus(accessToken: String): Int =
        http.newCall(Request.Builder().url("$base/v1/account/events").header("Authorization", "Bearer $accessToken").build()).execute().use { it.code }

    /** 指标的值，没有这一行是 0。 */
    fun metric(name: String): Double = http.newCall(Request.Builder().url(metricsUrl).build()).execute().use { r ->
        r.body?.string().orEmpty().lines().filter { it.startsWith(name) }.sumOf { it.substringAfterLast(' ').toDoubleOrNull() ?: 0.0 }
    }
}

/** 测试里的另一台设备（H5、其他手机、攻击者）：会话只在内存。 */
class OtherDevice(val server: RealServer, platform: Platform = Platform.IOS) {
    val store = MemorySessions()
    val account = AccountClient(AccountClientOptions(baseUrl = server.base, platform = platform, deviceName = "e2e", store = store))
    var im: IMClient? = null

    fun connect(): IMClient {
        val c = IMClient(IMClientOptions(url = "${server.base.replace("http://", "ws://")}/ws", platform = Platform.IOS))
        im = c
        c.connect(account.tokenProvider)
        val end = System.currentTimeMillis() + 10_000
        while (c.state.value != ConnState.CONNECTED) {
            check(System.currentTimeMillis() < end) { "other device not connected" }
            Thread.sleep(20)
        }
        return c
    }

    fun close() = runBlocking {
        im?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        account.close()
    }
}

fun newUsername(): String = "u" + System.currentTimeMillis().toString(36) + (100..999).random()

/** 找回截止日期的文字。日期由服务端算（换代时刻 + 7 天），跨午夜时允许差一天。 */
fun recoverDateTexts(fromMs: Long): List<String> = listOf(0, 1).map { extra ->
    val c = Calendar.getInstance().apply { timeInMillis = fromMs + (7L + extra) * 86_400_000L }
    "请在 ${c.get(Calendar.YEAR)}/${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)} 前用恢复码找回"
}

fun clearLocalAccountFiles() = runBlocking {
    KeystoreSessionStore(testContext).clear()
    KeystorePendingLogoutStore(testContext).save(emptyList())
}

fun storedSession(): AccountSession? = runBlocking { KeystoreSessionStore(testContext).load() }

/** 界面操作（和 ServerE2eTest 一样，键盘弹起时注入的点击可能被取消，点到离开为止由调用方判断）。 */
class E2eUi(private val rule: ComposeTestRule) {
    var scenario: ActivityScenario<MainActivity>? = null

    fun hasTag(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    fun hasText(text: String, substring: Boolean = true) = rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    fun waitTag(tag: String, t: Long = 15_000) = rule.waitUntil(t) { hasTag(tag) }
    fun waitText(text: String, t: Long = 15_000) = rule.waitUntil(t) { hasText(text) }
    fun textOf(tag: String) = allText(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())

    fun tap(tag: String) {
        waitTag(tag)
        rule.waitForIdle()
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    fun type(tag: String, text: String) {
        waitTag(tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        rule.waitForIdle()
    }

    fun openApp() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** 测试服务端开着演示登录：先切到账号表单。 */
    fun toAccountForm() {
        rule.waitUntil(15_000) { hasTag("login-use-account") || hasTag("account-username") }
        if (hasTag("login-use-account")) tap("login-use-account")
        waitTag("account-username")
    }

    fun submitLogin(user: String, password: String) {
        toAccountForm()
        type("account-username", user)
        type("account-password", password)
        tap("account-submit")
    }

    fun waitConnected() {
        waitTag("tab-chats")
        rule.waitUntil(20_000) { testApp.model.im?.state == ConnState.CONNECTED }
    }

    /** 登录，保存页有就确认，直到连上。 */
    fun loginAndEnter(user: String, password: String) {
        submitLogin(user, password)
        rule.waitUntil(15_000) { hasTag("recovery-saved") || hasTag("tab-chats") }
        if (hasTag("recovery-saved")) tap("recovery-saved")
        waitConnected()
        rule.waitUntil(5000) { storedSession() != null }
    }
}
