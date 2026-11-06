package com.tianting.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.KeystorePendingLogoutStore
import com.tianting.app.core.KeystoreSessionStore
import com.tianting.app.core.UiPrefs
import com.tianting.app.core.defaultDeps
import com.tianting.sdk.AccountSession
import com.tianting.sdk.HttpCall
import com.tianting.sdk.HttpCallback
import com.tianting.sdk.HttpRequest
import com.tianting.sdk.HttpResponse
import com.tianting.sdk.HttpTransport
import com.tianting.sdk.OkHttpHttpTransport
import com.tianting.sdk.SessionStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真实杀进程（scripts/run-tests.sh kill）：phase1 在暂停点写标记后 halt，进程直接没了（不走任何清理）；phase2 是新进程，只用磁盘上的东西。
 * 标记文件是测试自己的，只在模拟器的测试数据里，phase2 读完就删。没有标记说明 phase1 没走到暂停点，phase2 失败。
 */
class AccountKillE2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val ui = E2eUi(rule)
    private val marker get() = File(testContext.filesDir, "kill-marker.json")

    @Before fun guard() = server.guard()

    private fun die(m: JSONObject): Nothing {
        marker.writeText(m.toString())
        Runtime.getRuntime().halt(0)
        throw IllegalStateException("unreachable")
    }

    private fun readMarker(): JSONObject {
        assertTrue(marker.isFile, "phase1 没有走到暂停点")
        return JSONObject(marker.readText()).also { marker.delete() }
    }

    private fun prepare(): String {
        marker.delete()
        server.resetLimits()
        clearLocalAccountFiles()
        val u = newUsername()
        val d = OtherDevice(server)
        runBlocking {
            val r = d.account.register(u, GOOD_PASSWORD, inviteCode = server.invite())
            d.account.ackRecoveryCode(r.recoveryCodeGen!!)
            d.account.logout()
        }
        d.close()
        return u
    }

    /** 同一个存储，armed 之后写完就是暂停点："落盘后、使用前"。 */
    private class HaltAfterSave(private val real: SessionStore, private val onSaved: (AccountSession) -> Unit) : SessionStore {
        @Volatile var armed = false
        override suspend fun load() = real.load()
        override suspend fun save(session: AccountSession) {
            real.save(session)
            if (armed) onSaved(session)
        }
        override suspend fun clear() = real.clear()
    }

    @Test fun refreshSaved_phase1() { // TC-ACC-03-13：响应到达并落盘，使用前被杀
        val u = prepare()
        lateinit var m: JSONObject
        val store = HaltAfterSave(KeystoreSessionStore(testContext)) { die(m.put("saved", it.refreshToken)) }
        replaceWithRealDeps(defaultDeps(testContext, sessions = store))
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        m = JSONObject().put("user", u).put("old", storedSession()!!.refreshToken).put("replay", server.metric("im_refresh_replay_total"))
        store.armed = true
        runBlocking { testApp.model.account.refresh() }
    }

    @Test fun refreshSaved_phase2() { // AC-ACC-03-13
        val m = readMarker()
        assertEquals(m.getString("saved"), storedSession()?.refreshToken, "磁盘上是新值")
        assertNotEquals(m.getString("old"), m.getString("saved"))
        replaceWithRealDeps()
        ui.openApp()
        ui.waitConnected()
        assertFalse(ui.hasTag("kicked-dialog"))
        assertFalse(ui.hasTag("account-username"))
        assertEquals(m.getDouble("replay"), server.metric("im_refresh_replay_total"), "没有重放")
        cleanupLogout()
    }

    /** 收到响应但还没交给 SDK 就被杀：服务端处理过，App 没拿到。 */
    private class HaltOnResponse(private val path: String, private val onResponse: () -> Unit) : HttpTransport {
        private val real = OkHttpHttpTransport()
        @Volatile var armed = false
        override fun request(req: HttpRequest, callback: HttpCallback): HttpCall {
            if (!armed || !req.url.endsWith(path)) return real.request(req, callback)
            return real.request(
                req,
                object : HttpCallback {
                    override fun onResponse(response: HttpResponse) = this@HaltOnResponse.onResponse()
                    override fun onFailure() = this@HaltOnResponse.onResponse()
                },
            )
        }
    }

    @Test fun refreshResponseLost_phase1() { // TC-ACC-03-20
        val u = prepare()
        lateinit var m: JSONObject
        val http = HaltOnResponse("/v1/account/token/refresh") { die(m) }
        replaceWithRealDeps(defaultDeps(testContext, http))
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        m = JSONObject().put("user", u).put("old", storedSession()!!.refreshToken)
        http.armed = true
        runBlocking { testApp.model.account.refresh() }
    }

    @Test fun refreshResponseLost_phase2() { // AC-ACC-03-20：[IM 钟] 推进 5 分钟后重启
        val m = readMarker()
        assertEquals(m.getString("old"), storedSession()?.refreshToken, "磁盘上还是旧值")
        val reissue = server.metric("im_refresh_reissue_total")
        server.freezeClock(System.currentTimeMillis() + 5 * 60_000L)
        try {
            // 下次刷新要满足"不足 6 小时"：把本地到期时间改到已过期，等于冷启动时的主路径
            runBlocking {
                val s = KeystoreSessionStore(testContext)
                s.save(s.load()!!.copy(accessExpiresAt = System.currentTimeMillis() - 1000))
            }
            replaceWithRealDeps()
            ui.openApp()
            ui.waitConnected()
            assertFalse(ui.hasTag("kicked-dialog"))
            assertFalse(ui.hasTag("account-username"))
            assertNotEquals(m.getString("old"), storedSession()?.refreshToken)
            assertTrue(server.metric("im_refresh_reissue_total") > reissue, "服务端按重发处理")
        } finally {
            server.freezeClock(null)
        }
        cleanupLogout()
    }

    @Test fun savePage_phase1() { // TC-ACC-05-06
        marker.delete()
        server.resetLimits()
        clearLocalAccountFiles()
        val u = newUsername()
        val d = OtherDevice(server)
        runBlocking { d.account.register(u, GOOD_PASSWORD, inviteCode = server.invite()) }
        replaceWithRealDeps()
        ui.openApp()
        ui.submitLogin(u, GOOD_PASSWORD)
        ui.waitTag("recovery-code")
        assertFalse(File(testContext.noBackupFilesDir, KeystoreSessionStore.FILE).exists(), "确认前没有落盘")
        die(JSONObject().put("user", u).put("code", ui.textOf("recovery-code")))
    }

    @Test fun savePage_phase2() { // AC-ACC-05-7
        val m = readMarker()
        assertNull(storedSession())
        replaceWithRealDeps()
        ui.openApp()
        ui.toAccountForm()
        assertFalse(ui.hasTag("kicked-dialog"))
        assertFalse(ui.hasTag("recovery-code"))
        ui.submitLogin(m.getString("user"), GOOD_PASSWORD)
        ui.waitTag("recovery-code")
        assertNotEquals(m.getString("code"), ui.textOf("recovery-code"))
        ui.tap("recovery-saved")
        ui.waitConnected()
        cleanupLogout()
    }

    /** logout 一直没有响应时被杀（K2）。 */
    private class HoldLogout : HttpTransport {
        private val real = OkHttpHttpTransport()
        override fun request(req: HttpRequest, callback: HttpCallback): HttpCall =
            if (req.url.endsWith("/v1/account/logout")) HttpCall { } else real.request(req, callback)
    }

    @Test fun logoutInFlight_phase1() { // TC-ACC-04-06、K2：等响应期间杀 App 也不回到登录状态，还能补发
        val u = prepare()
        replaceWithRealDeps(defaultDeps(testContext, HoldLogout()))
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val refresh = storedSession()!!.refreshToken!!
        inst.runOnMainSync { testApp.model.nav.resetTo("/me/settings") }
        ui.tap("logout")
        ui.tap("logout-dialog-ok")
        ui.toAccountForm()
        rule.waitUntil(10_000) { storedSession() == null && runBlocking { KeystorePendingLogoutStore(testContext).load() }.size == 1 }
        assertTrue(testContext.getSharedPreferences(UiPrefs.FILE, android.content.Context.MODE_PRIVATE).getString(UiPrefs.WIPE_PENDING, null) != null)
        die(JSONObject().put("refresh", refresh))
    }

    @Test fun logoutInFlight_phase2() { // AC-ACC-04-6
        val m = readMarker()
        assertNull(storedSession(), "重启后不回到登录状态")
        val raw = File(testContext.noBackupFilesDir, KeystorePendingLogoutStore.FILE).readText()
        assertFalse(m.getString("refresh") in raw, "待退出是加密的")
        replaceWithRealDeps()
        ui.openApp()
        ui.toAccountForm()
        rule.waitUntil(20_000) { runBlocking { KeystorePendingLogoutStore(testContext).load() }.isEmpty() }
        // 被杀时本地库还没清：这次启动补上（缓存不留到下次登录）
        rule.waitUntil(5000) { testContext.getSharedPreferences(UiPrefs.FILE, android.content.Context.MODE_PRIVATE).getString(UiPrefs.WIPE_PENDING, null) == null }
        val db = com.tianting.sdk.android.AndroidLocalDatabase.open(testContext)
        val cached = db.query("SELECT value FROM meta WHERE key = 'owner'")
        db.close()
        assertTrue(cached.isEmpty(), "本地库已清空")
        val (status, reason) = server.refreshStatus(m.getString("refresh"))
        assertEquals(401, status)
        assertEquals("revoked", reason)
    }

    private fun cleanupLogout() {
        ui.scenario?.close()
        inst.runOnMainSync { testApp.model.logout() }
        runBlocking { testApp.model.awaitTeardown() }
        clearLocalAccountFiles()
    }
}
