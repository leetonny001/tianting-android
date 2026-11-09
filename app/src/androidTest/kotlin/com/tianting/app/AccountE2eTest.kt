package com.tianting.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.KeystorePendingLogoutStore
import com.tianting.app.core.KeystoreSessionStore
import com.tianting.app.core.defaultDeps
import com.tianting.app.ui.DataStatus
import com.tianting.sdk.AccountError
import com.tianting.sdk.ConnState
import com.tianting.sdk.FatalReason
import com.tianting.sdk.HttpCall
import com.tianting.sdk.HttpCallback
import com.tianting.sdk.HttpRequest
import com.tianting.sdk.HttpTransport
import com.tianting.sdk.OkHttpHttpTransport
import com.tianting.sdk.SessionMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FE-615、FE-616 对真实 im-server 的端到端（scripts/run-tests.sh e2e com.tianting.app.AccountE2eTest）。App 是真实依赖，其他设备用同进程的 AccountClient。结果是模拟器、本地。 */
class AccountE2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val ui = E2eUi(rule)
    private val others = ArrayList<OtherDevice>()
    private val model get() = testApp.model

    @Before fun setup() {
        server.guard()
        server.resetLimits()
        clearLocalAccountFiles()
        replaceWithRealDeps()
    }

    @After fun cleanup() {
        ui.scenario?.close()
        setAirplane(false)
        runCatching { server.freezeClock(null) }
        inst.runOnMainSync { testApp.model.logout() }
        runBlocking { testApp.model.awaitTeardown() }
        others.forEach { it.close() }
        clearLocalAccountFiles()
    }

    private fun device() = OtherDevice(server).also { others.add(it) }

    /** 接口注册并确认恢复码，账号可以直接用。返回 (用户名, 恢复码)。 */
    private fun account(): Pair<String, String> = runBlocking {
        val u = newUsername()
        val d = device()
        val r = d.account.register(u, GOOD_PASSWORD, inviteCode = server.invite())
        d.account.ackRecoveryCode(r.recoveryCodeGen!!)
        val code = r.recoveryCode!!
        d.account.logout()
        u to code
    }

    /** 杀 App 的进程内版本：关 Activity 和连接，留存储和本地库，换新的模型。真实杀进程在 AccountKillE2e。 */
    private fun restartApp(http: HttpTransport? = null) {
        ui.scenario?.close()
        val old = model
        inst.runOnMainSync { old.im?.stop() }
        runBlocking { old.im?.client?.close() }
        replaceWithRealDeps(defaultDeps(testContext, http))
        ui.openApp()
    }

    private fun expireStoredAccess() = runBlocking {
        val store = KeystoreSessionStore(testContext)
        store.save(store.load()!!.copy(accessExpiresAt = System.currentTimeMillis() - 1000))
    }

    private fun setAirplane(on: Boolean) {
        inst.uiAutomation.executeShellCommand("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}").close()
        val cm = testContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && (cm.activeNetwork == null) == !on) Thread.sleep(100)
    }

    private fun assertRecoverDialog(fromMs: Long) {
        ui.waitTag("kicked-dialog", 25_000)
        val texts = recoverDateTexts(fromMs)
        rule.waitUntil(5000) { texts.any { ui.hasText(it) } }
        assertTrue(ui.hasText("你的账号凭证有变化"))
        assertFalse(ui.hasText("检测到登录凭证异常"), "不算重放")
    }

    private fun confirmDialogToLogin() {
        ui.tap("kicked-ok")
        ui.toAccountForm()
        rule.waitUntil(5000) { storedSession() == null }
    }

    @Test fun tc_acc_01_12_and_09_01_register_with_a_real_invite() { // TC-ACC-01-12、TC-ACC-09-01 的 Android 注册部分（后台页面属于 FE-619）、AC-ACC-01-12、AC-ACC-09-1
        val code = server.invite(maxUses = 2)
        ui.openApp()
        ui.toAccountForm()
        ui.tap("login-to-register")
        val r1 = newUsername()
        ui.type("register-username", r1)
        ui.type("register-password", GOOD_PASSWORD)
        ui.type("register-invite", code)
        ui.tap("register-submit")
        ui.waitTag("recovery-code")
        assertTrue(ui.textOf("recovery-code").length >= 16, "真实恢复码")
        assertNull(storedSession(), "确认前 token 不写存储")
        assertFalse(File(testContext.noBackupFilesDir, KeystoreSessionStore.FILE).exists())
        ui.tap("recovery-copy")
        ui.waitText("已复制")
        androidx.test.uiautomator.UiDevice.getInstance(inst).pressBack()
        rule.waitForIdle()
        ui.waitTag("recovery-code")
        ui.tap("recovery-saved")
        ui.waitConnected()
        rule.waitUntil(5000) { storedSession() != null }
        val s = storedSession()!!
        assertEquals(SessionMode.ACCOUNT, s.mode)
        assertEquals(r1, s.userId)
        assertNotNull(s.refreshToken)
        inst.runOnMainSync { model.logout() }
        ui.toAccountForm()
        // 第二个用同一个码（接口），第三个在界面上：邀请码无效
        runBlocking { device().account.register(newUsername(), GOOD_PASSWORD, inviteCode = code) }
        ui.tap("login-to-register")
        ui.type("register-username", newUsername())
        ui.type("register-password", GOOD_PASSWORD)
        ui.type("register-invite", code)
        ui.tap("register-submit")
        ui.waitTag("register-error")
        assertEquals("邀请码无效", ui.textOf("register-error"))
        assertEquals(2, server.inviteUsed(code), "管理接口显示已用 2 次")
        // 用户名已被占用（TC-ACC-01-13）
        ui.type("register-username", r1)
        ui.type("register-invite", server.invite())
        ui.tap("register-submit")
        ui.waitTag("register-username-error")
        assertEquals("这个用户名不能使用", ui.textOf("register-username-error"))
    }

    @Test fun tc_acc_02_08_and_02_09_real_login_failures() { // TC-ACC-02-08、TC-ACC-02-09、AC-ACC-02-8、AC-ACC-02-9
        val (u, _) = account()
        ui.openApp()
        ui.submitLogin(u, "wrong-pass-1")
        ui.waitTag("account-error")
        assertEquals("用户名或密码错误", ui.textOf("account-error"))
        assertEquals("", ui.textOf("account-password"))
        assertTrue(u in ui.textOf("account-username"))
        repeat(4) {
            ui.type("account-password", "wrong-pass-1")
            ui.tap("account-submit")
            rule.waitUntil(15_000) { ui.hasTag("account-error") && ui.textOf("account-password").isEmpty() }
        }
        ui.type("account-password", GOOD_PASSWORD)
        ui.tap("account-submit")
        rule.waitUntil(15_000) { ui.hasText("尝试次数过多，请 ") }
        assertTrue(Regex("尝试次数过多，请 \\d+ 分钟后再试").matches(ui.textOf("account-error")))
        server.resetLimits()
    }

    @Test fun tc_acc_05_04_real_recover() { // TC-ACC-05-04、AC-ACC-05-5
        val (u, code) = account()
        ui.openApp()
        ui.toAccountForm()
        ui.tap("login-to-recover")
        ui.type("recover-username", u)
        ui.type("recover-code", code)
        ui.type("recover-password", NEW_PASSWORD)
        ui.tap("recover-submit")
        ui.waitTag("recovery-code")
        assertNotEquals(code, ui.textOf("recovery-code"))
        assertNull(storedSession())
        ui.tap("recovery-saved")
        ui.waitConnected()
        inst.runOnMainSync { model.logout() }
        runBlocking { model.awaitTeardown() }
        ui.submitLogin(u, GOOD_PASSWORD)
        ui.waitTag("account-error")
        ui.type("account-password", NEW_PASSWORD)
        ui.tap("account-submit")
        ui.waitConnected()
    }

    @Test fun tc_acc_05_06_real_save_page_restart_gives_new_code() { // TC-ACC-05-06、AC-ACC-05-7
        val u = newUsername()
        val d = device()
        runBlocking { d.account.register(u, GOOD_PASSWORD, inviteCode = server.invite()) } // 没有确认
        ui.openApp()
        ui.submitLogin(u, GOOD_PASSWORD)
        ui.waitTag("recovery-code")
        val first = ui.textOf("recovery-code")
        assertNull(storedSession())
        restartApp()
        ui.submitLogin(u, GOOD_PASSWORD)
        ui.waitTag("recovery-code")
        assertNotEquals(first, ui.textOf("recovery-code"))
        ui.tap("recovery-saved")
        ui.waitConnected()
    }

    @Test fun tc_acc_03_07_real_expired_access_cold_start() { // TC-ACC-03-07、AC-ACC-03-7
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val before = storedSession()!!
        expireStoredAccess()
        restartApp()
        ui.waitConnected()
        assertFalse(ui.hasTag("account-username"))
        assertFalse(ui.hasTag("kicked-dialog"))
        rule.waitUntil(5000) { storedSession()?.accessToken != before.accessToken }
    }

    @Test fun tc_acc_03_08_real_force_logout_cold_start() { // TC-ACC-03-08、AC-ACC-03-8
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        ui.scenario?.close()
        inst.runOnMainSync { model.im?.stop() }
        runBlocking { model.im?.client?.close() }
        server.admin("POST", "/admin/users/$u/force-logout")
        expireStoredAccess()
        replaceWithRealDeps()
        ui.openApp()
        ui.waitTag("kicked-dialog")
        ui.waitText("登录已失效，请重新登录")
        confirmDialogToLogin()
    }

    @Test fun tc_acc_03_34_real_attacker_password_change_while_offline() { // TC-ACC-03-34、AC-ACC-03-34
        for (expired in listOf(false, true)) {
            server.resetLimits()
            val (u, _) = account()
            ui.openApp()
            ui.loginAndEnter(u, GOOD_PASSWORD)
            ui.scenario?.close()
            inst.runOnMainSync { model.im?.stop() }
            runBlocking { model.im?.client?.close() }
            val x = device()
            val t = System.currentTimeMillis()
            runBlocking {
                x.account.login(u, GOOD_PASSWORD)
                x.account.changePassword(GOOD_PASSWORD, NEW_PASSWORD)
            }
            if (expired) expireStoredAccess()
            replaceWithRealDeps()
            ui.openApp()
            assertRecoverDialog(t)
            confirmDialogToLogin()
            inst.runOnMainSync { model.logout() }
            runBlocking { model.awaitTeardown() }
        }
    }

    @Test fun tc_acc_04_06_real_airplane_logout_then_flush() { // TC-ACC-04-06、AC-ACC-04-6（系统令牌注销随 C3，未验证）
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val refresh = storedSession()!!.refreshToken!!
        setAirplane(true)
        inst.runOnMainSync { model.nav.resetTo("/me/settings") }
        ui.tap("logout")
        ui.tap("logout-dialog-ok")
        // 立即回登录页。没有网络时配置拉不到，登录页是"网络异常 + 重试"，不猜测模式（AC-ACC-07-9）
        rule.waitUntil(15_000) { ui.hasTag("config-retry") || ui.hasTag("login-use-account") || ui.hasTag("account-username") }
        rule.waitUntil(5000) { storedSession() == null }
        rule.waitUntil(40_000) { runBlocking { KeystorePendingLogoutStore(testContext).load() }.map { it.refreshToken } == listOf(refresh) }
        setAirplane(false)
        rule.waitUntil(40_000) { runBlocking { KeystorePendingLogoutStore(testContext).load() }.isEmpty() }
        val (status, reason) = server.refreshStatus(refresh)
        assertEquals(401, status)
        assertEquals("revoked", reason, "sid 已吊销")
    }

    @Test fun tc_acc_03_11_real_airplane_expired_cold_start_shows_cache() { // TC-ACC-03-11、AC-ACC-03-11
        val (u, _) = account()
        val peer = device()
        val (p, _) = account()
        runBlocking { peer.account.login(p, GOOD_PASSWORD) }
        val peerIm = peer.connect()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val conv = com.tianting.sdk.c2cConvId(p, u)
        runBlocking { peerIm.send(conv, "缓存里的消息") }
        rule.waitUntil(15_000) { ui.hasText("缓存里的消息") }
        expireStoredAccess()
        setAirplane(true)
        restartApp()
        ui.waitTag("tab-chats")
        ui.waitTag("conn-banner")
        ui.waitText("网络不可用")
        ui.waitText("缓存里的消息")
        assertFalse(ui.hasTag("account-username"))
        assertFalse(ui.hasTag("kicked-dialog"))
        setAirplane(false)
        rule.waitUntil(45_000) { model.im?.state == ConnState.CONNECTED }
        assertFalse(ui.hasTag("kicked-dialog"))
    }

    @Test fun tc_acc_02_13_and_04_09_real_new_device_and_logout_others() { // TC-ACC-02-13、TC-ACC-04-09、AC-ACC-02-13、AC-ACC-04-10
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val d2 = device()
        runBlocking { d2.account.login(u, GOOD_PASSWORD) }
        ui.waitText("你的账号在新设备登录", 2000 + 1000) // AC 是 2 秒，留测试取节点的余量
        val d2Im = d2.connect()
        inst.runOnMainSync { model.nav.resetTo("/me/settings") }
        ui.tap("settings-security")
        ui.waitTag("security-event")
        ui.waitText("新设备登录")
        ui.waitText("其他设备 1 台")
        assertFalse(ui.hasText(d2.store.session?.refreshToken ?: "no-such"))
        // 密码错误：原地提示，没有变化
        val firstToken = storedSession()!!.accessToken
        ui.tap("security-others")
        ui.type("others-password", "wrong-pass-1")
        ui.tap("others-submit")
        ui.waitTag("others-error")
        assertEquals("密码错误", ui.textOf("others-error"))
        assertEquals(firstToken, storedSession()!!.accessToken)
        assertEquals(ConnState.CONNECTED, d2Im.state.value)
        // 密码正确：D2 被踢，D1 换 sid 重连，没有提示
        ui.type("others-password", GOOD_PASSWORD)
        ui.tap("others-submit")
        rule.waitUntil(10_000) { d2Im.fatal.value != null }
        val fatal = d2Im.fatal.value as FatalReason.Kicked
        assertEquals("sessionRevoked", fatal.reason)
        assertEquals(4012, fatal.code)
        rule.waitUntil(10_000) { storedSession()?.accessToken != firstToken && model.im?.state == ConnState.CONNECTED }
        assertFalse(ui.hasTag("kicked-dialog"))
        assertEquals(ConnState.CONNECTED, model.im?.state)
        // 密码没有变
        runBlocking { device().account.login(u, GOOD_PASSWORD) }
    }

    /** 服务端已经处理，响应丢失（TC-ACC-04-15 的"测试代理"）。 */
    private class DropResponse(val path: String) : HttpTransport {
        private val real = OkHttpHttpTransport()
        override fun request(req: HttpRequest, callback: HttpCallback): HttpCall {
            if (!req.url.endsWith(path)) return real.request(req, callback)
            return real.request(
                req,
                object : HttpCallback {
                    override fun onResponse(response: com.tianting.sdk.HttpResponse) = Unit
                    override fun onFailure() = Unit
                },
            )
        }
    }

    @Test fun tc_acc_04_15_real_password_response_lost() { // TC-ACC-04-15、AC-ACC-04-16
        val (u, _) = account()
        val t = System.currentTimeMillis()
        val replay = server.metric("im_refresh_replay_total")
        replaceWithRealDeps(defaultDeps(testContext, DropResponse("/v1/account/password")))
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        inst.runOnMainSync { model.nav.resetTo("/me/settings/security/password") }
        ui.type("password-old", GOOD_PASSWORD)
        ui.type("password-new", NEW_PASSWORD)
        ui.tap("password-submit")
        // 服务端先踢线：缓存；30 秒超时后提示失败，踢线生效，弹窗带日期
        assertRecoverDialogOrKicked(t)
        assertEquals(replay, server.metric("im_refresh_replay_total"), "不算重放")
        confirmDialogToLogin()
        ui.submitLogin(u, NEW_PASSWORD)
        ui.waitTag("recovery-code") // 再新一代
    }

    private fun assertRecoverDialogOrKicked(fromMs: Long) {
        ui.waitTag("kicked-dialog", 45_000)
        val texts = recoverDateTexts(fromMs)
        rule.waitUntil(5000) { texts.any { ui.hasText(it) } }
    }

    @Test fun tc_acc_05_18_real_unacked_forces_save_page() { // TC-ACC-05-18 场景一、AC-ACC-05-19
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        // recovery-code 的响应丢失：服务端有了新一代，客户端没见过
        val d2 = device()
        runBlocking {
            d2.account.login(u, GOOD_PASSWORD)
            d2.account.regenerateRecoveryCode(GOOD_PASSWORD)
        }
        restartApp()
        ui.waitTag("recovery-need-password", 20_000)
        ui.type("recovery-password", GOOD_PASSWORD)
        ui.tap("recovery-regenerate")
        ui.waitTag("recovery-code")
        ui.tap("recovery-saved")
        ui.waitTag("tab-chats")
        restartApp()
        ui.waitConnected()
        Thread.sleep(1000)
        assertFalse(ui.hasTag("recovery-need-password"), "确认后解除")
    }

    @Test fun tc_acc_05_18_real_password_change_then_kill_on_save_page() { // TC-ACC-05-18 场景二、AC-ACC-05-19
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        inst.runOnMainSync { model.nav.resetTo("/me/settings/security/password") }
        ui.type("password-old", GOOD_PASSWORD)
        ui.type("password-new", NEW_PASSWORD)
        ui.tap("password-submit")
        ui.waitTag("recovery-code")
        assertFalse(ui.hasTag("kicked-dialog"), "自己改密不弹窗")
        rule.waitUntil(5000) { storedSession() == null }
        restartApp()
        ui.toAccountForm()
        assertFalse(ui.hasTag("kicked-dialog"))
        ui.submitLogin(u, NEW_PASSWORD)
        ui.waitTag("recovery-code")
        ui.tap("recovery-saved")
        ui.waitConnected()
    }

    @Test fun tc_acc_06_05_real_nickname() { // TC-ACC-06-05 的本端、AC-ACC-06-5
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        inst.runOnMainSync { model.nav.setRoot("/me") }
        ui.tap("me-card")
        ui.type("nickname-input", "真实昵称")
        ui.tap("nickname-submit")
        rule.waitUntil(10_000) { ui.hasTag("me-name") && ui.textOf("me-name") == "真实昵称" }
        // 服务端已经是新昵称。其他端的 10 分钟缓存由 SDK 的 F-SOC-10 用例验证。
        assertEquals("真实昵称", server.admin("GET", "/admin/users/$u")["nickname"]?.jsonPrimitive?.content)
    }

    @Test fun tc_acc_08_06_real_delete_and_cancel() { // TC-ACC-08-06、AC-ACC-08-6，撤销注销（F-ACC-08 规则 2）
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        inst.runOnMainSync { model.nav.resetTo("/me/settings/security") }
        ui.tap("security-delete")
        ui.waitText("7 天后不可恢复，用户名不能再使用，已发的消息会保留")
        ui.type("delete-password", "wrong-pass-1")
        ui.tap("delete-submit")
        ui.waitTag("delete-error")
        assertEquals("密码错误", ui.textOf("delete-error"))
        ui.type("delete-password", GOOD_PASSWORD)
        ui.tap("delete-submit")
        ui.waitText(com.tianting.app.core.ACCOUNT_DELETION_REQUESTED)
        confirmDialogToLogin()
        runBlocking { model.awaitTeardown() }
        ui.submitLogin(u, GOOD_PASSWORD)
        ui.waitTag("deletion-pending")
        ui.waitText("后不可恢复，是否撤销？")
        ui.tap("deletion-cancel-request")
        ui.waitText("已撤销注销，请重新登录")
        ui.type("account-password", GOOD_PASSWORD)
        ui.tap("account-submit")
        ui.waitConnected()
    }

    @Test fun tc_acc_10_01_real_privacy_page_opens() { // TC-ACC-10-01、AC-ACC-10-1
        ui.openApp()
        ui.toAccountForm()
        ui.tap("login-privacy")
        rule.waitUntil(20_000) { rule.onAllNodes(tagged("privacy-page", DataStatus, "loaded"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(ui.hasTag("privacy-failed"))
    }

    @Test fun tc_acc_04_24_real_sequences_show_the_same_date() { // TC-ACC-04-24 的甲、乙、丙、丁（时间用 [IM 钟]）、AC-ACC-04-25
        val t0 = System.currentTimeMillis()
        server.freezeClock(t0)
        // 甲：X 先退出其他设备再改密，V 第 1 天上线
        run {
            val (u, _) = account()
            ui.openApp()
            ui.loginAndEnter(u, GOOD_PASSWORD)
            ui.scenario?.close()
            inst.runOnMainSync { model.im?.stop() }
            runBlocking { model.im?.client?.close() }
            val x = device()
            runBlocking {
                x.account.login(u, GOOD_PASSWORD)
                x.account.logoutOthers(GOOD_PASSWORD)
                x.account.changePassword(GOOD_PASSWORD, NEW_PASSWORD)
            }
            server.freezeClock(t0 + 86_400_000L)
            replaceWithRealDeps()
            ui.openApp()
            assertRecoverDialog(t0)
            confirmDialogToLogin()
            runBlocking { model.awaitTeardown() }
        }
        // 乙：X 改密后申请注销，V 第 1 天上线
        server.freezeClock(t0)
        server.resetLimits()
        run {
            val (u, _) = account()
            ui.openApp()
            ui.loginAndEnter(u, GOOD_PASSWORD)
            ui.scenario?.close()
            inst.runOnMainSync { model.im?.stop() }
            runBlocking { model.im?.client?.close() }
            val x = device()
            runBlocking {
                x.account.login(u, GOOD_PASSWORD)
                x.account.changePassword(GOOD_PASSWORD, NEW_PASSWORD)
                x.account.requestDelete(NEW_PASSWORD)
            }
            server.freezeClock(t0 + 86_400_000L)
            replaceWithRealDeps()
            ui.openApp()
            assertRecoverDialog(t0)
            confirmDialogToLogin()
            runBlocking { model.awaitTeardown() }
        }
        // 丙：第 0 天、第 5 天各改一次，V 第 6 天上线：日期不推后
        server.freezeClock(t0)
        server.resetLimits()
        run {
            val (u, _) = account()
            ui.openApp()
            ui.loginAndEnter(u, GOOD_PASSWORD)
            ui.scenario?.close()
            inst.runOnMainSync { model.im?.stop() }
            runBlocking { model.im?.client?.close() }
            val x = device()
            runBlocking { x.account.login(u, GOOD_PASSWORD); x.account.changePassword(GOOD_PASSWORD, NEW_PASSWORD) }
            server.freezeClock(t0 + 5 * 86_400_000L)
            runBlocking { x.account.changePassword(NEW_PASSWORD, GOOD_PASSWORD) }
            server.freezeClock(t0 + 6 * 86_400_000L)
            replaceWithRealDeps()
            ui.openApp()
            assertRecoverDialog(t0)
            confirmDialogToLogin()
            runBlocking { model.awaitTeardown() }
        }
        // 丁：V 自己重新生成（C1 换代），在线时 X 退出其他设备
        server.freezeClock(t0)
        server.resetLimits()
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        inst.runOnMainSync { model.nav.resetTo("/me/settings/security/recovery") }
        ui.type("regenerate-password", GOOD_PASSWORD)
        ui.tap("regenerate-submit")
        ui.waitTag("recovery-code")
        ui.tap("recovery-saved")
        rule.waitUntil(10_000) { !ui.hasTag("recovery-code") }
        val x = device()
        runBlocking { x.account.login(u, GOOD_PASSWORD); x.account.logoutOthers(GOOD_PASSWORD) }
        assertRecoverDialog(t0)
        confirmDialogToLogin()
    }

    @Test fun secrets_never_reach_logcat() { // 第 13.1 节、NF-UI-16：debug 构建也不打 token、refresh、恢复码、密码
        val since = System.currentTimeMillis() // 只读本进程从这里开始的日志，不清空缓冲区（问题 A18）
        val u = newUsername()
        val secretPassword = "ZXQ-PASS-" + System.nanoTime()
        ui.openApp()
        ui.toAccountForm()
        ui.tap("login-to-register")
        ui.type("register-username", u)
        ui.type("register-password", secretPassword)
        ui.type("register-invite", server.invite())
        ui.tap("register-submit")
        ui.waitTag("recovery-code")
        val code = ui.textOf("recovery-code")
        ui.tap("recovery-saved")
        ui.waitConnected()
        rule.waitUntil(5000) { storedSession() != null }
        val s = storedSession()!!
        expireStoredAccess()
        restartApp()
        ui.waitConnected()
        rule.waitUntil(5000) { storedSession()?.accessToken != s.accessToken }
        val s2 = storedSession()!!
        val secrets = listOf("密码" to secretPassword, "恢复码" to code, "access" to s.accessToken, "refresh" to s.refreshToken!!, "新 access" to s2.accessToken, "新 refresh" to s2.refreshToken!!)
        for ((name, value) in secrets) assertTrue(value.isNotEmpty(), name)
        val log = scanOwnLogcat(since, secrets.toMap())
        assertTrue(log.lines > 0, "读到了本进程的日志")
        for ((name, _) in secrets) {
            assertFalse(name in log.found, "$name 进了 Logcat")
        }
    }

    @Test fun wrong_refresh_status_helper_is_4019_shape() { // 自检：refreshStatus 能分辨错误，上面"sid 已吊销"的断言才有意义
        val (status, reason) = server.refreshStatus("a.b.1.c.d")
        assertEquals(401, status)
        assertNotNull(reason)
        runBlocking {
            val e = runCatching { device().account.login("nouser1", "wrong-pass-1") }.exceptionOrNull() as AccountError
            assertEquals(4018, e.code)
        }
    }

    // ---- FE-620：之前只对假服务端跑的部分，提醒和踢线同时出现 ----

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(testContext.getExternalFilesDir(null), "shots-real").apply { mkdirs() }
        val bmp = inst.uiAutomation.takeScreenshot()
        java.io.FileOutputStream(File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 数到达真实服务端的刷新请求，可以拖后响应。 */
    private class CountingHttp(val path: String = "/v1/account/token/refresh", val holdMs: Long = 0) : HttpTransport {
        private val real = OkHttpHttpTransport()
        val count = java.util.concurrent.atomic.AtomicInteger()
        /** 这些请求的响应：状态码和错误码（只取 `code`，不留响应正文，正文里有 token）。 */
        val answers = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun request(req: HttpRequest, callback: HttpCallback): HttpCall {
            if (!req.url.endsWith(path)) return real.request(req, callback)
            count.incrementAndGet()
            if (holdMs == 0L) {
                return real.request(
                    req,
                    object : HttpCallback {
                        override fun onResponse(response: com.tianting.sdk.HttpResponse) {
                            val code = Regex("\"code\":(\\d+)").find(response.text)?.groupValues?.get(1)
                            answers += if (code == null) "${response.status}" else "${response.status}/$code"
                            callback.onResponse(response)
                        }
                        override fun onFailure() {
                            answers += "failure"
                            callback.onFailure()
                        }
                    },
                )
            }
            return real.request(
                req,
                object : HttpCallback {
                    override fun onResponse(response: com.tianting.sdk.HttpResponse) {
                        Thread { Thread.sleep(holdMs); callback.onResponse(response) }.start()
                    }
                    override fun onFailure() = callback.onFailure()
                },
            )
        }
    }

    /** 关掉 Activity 和连接，存储留着（攻击者动手时受害者不在线）。 */
    private fun stopApp() {
        ui.scenario?.close()
        inst.runOnMainSync { model.im?.stop() }
        runBlocking { model.im?.client?.close() }
    }

    /** 用这份存储"重新打开"App：关掉旧连接，存储里的值由 edit 改。 */
    private fun reopenWith(http: HttpTransport? = null, edit: (com.tianting.sdk.AccountSession) -> com.tianting.sdk.AccountSession = { it }) {
        ui.scenario?.close()
        val old = model
        inst.runOnMainSync { old.im?.stop() }
        runBlocking {
            runCatching { old.im?.client?.close() } // stopApp() 已经关过的话会被拒绝，不影响
            val store = KeystoreSessionStore(testContext)
            store.save(edit(store.load()!!))
        }
        replaceWithRealDeps(defaultDeps(testContext, http))
        ui.openApp()
    }

    @Test fun fe620_notices_on_real_server_with_kick() { // AC-ACC-02-13；F-ACC-05 规则 5；A12、A13
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val x = device()
        runBlocking { x.account.login(u, GOOD_PASSWORD) }
        ui.waitText("你的账号在新设备登录", 3000)
        shot("notice-newDeviceLogin")
        runBlocking { x.account.regenerateRecoveryCode(GOOD_PASSWORD) }
        ui.waitText("你的恢复码已重新生成", 8000)
        assertFalse(ui.hasTag("kicked-dialog"))
        shot("notice-recoveryCodeRegenerated")
        val t = System.currentTimeMillis()
        runBlocking { x.account.changePassword(GOOD_PASSWORD, NEW_PASSWORD) }
        // 服务端先发提醒再踢线：两个同时在屏幕上时弹窗要能看、能点。
        assertRecoverDialog(t)
        shot("notice-passwordChanged-with-dialog")
        Thread.sleep(1500) // 弹窗动画结束后再截一张，看两层弹窗是不是叠在一起
        shot("notice-passwordChanged-with-dialog-settled")
        rule.waitUntil(1000) { ui.hasTag("kicked-ok") }
        confirmDialogToLogin()
        shot("notice-after-dialog")
    }

    @Test fun tc_acc_03_10_real_timer_refresh_reconnects_quietly() { // TC-ACC-03-10、AC-ACC-03-10：真实定时器和真实服务端，时间靠改存储里的到期，不是 [SDK 钟]
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val before = storedSession()!!
        val http = CountingHttp()
        reopenWith(http) { it.copy(accessExpiresAt = System.currentTimeMillis() + 6 * 3_600_000L + 8000) }
        ui.waitConnected()
        assertEquals(0, http.count.get(), "还没到 6 小时")
        var banner = false
        rule.waitUntil(20_000) {
            banner = banner || ui.hasTag("conn-banner")
            storedSession()?.refreshToken != before.refreshToken && model.im?.state == ConnState.CONNECTED
        }
        assertEquals(1, http.count.get())
        assertFalse(banner, "不提示")
        assertFalse(ui.hasTag("kicked-dialog"))
        // 换 token 之后消息照常：发给自己，服务端确认。
        val result = runBlocking { model.im!!.client.send("c2c:$u:$u", "after refresh") }
        assertTrue(result is com.tianting.sdk.SendResult.Ok, "$result")
        val msg = (result as com.tianting.sdk.SendResult.Ok).message
        rule.waitUntil(10_000) { model.im!!.client.messages(msg.convId).any { it.clientMsgId == msg.clientMsgId && it.msgId != null } }
    }

    @Test fun tc_acc_03_23_real_start_and_foreground_share_one_refresh() { // TC-ACC-03-23 的启动、回前台入口，AC-ACC-03-23：真实服务端上只到达 1 次
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val http = CountingHttp(holdMs = 1500)
        reopenWith(http) { it.copy(accessExpiresAt = System.currentTimeMillis() - 1000) }
        inst.runOnMainSync { repeat(3) { model.account.foreground() } }
        ui.waitConnected()
        assertEquals(1, http.count.get())
    }

    @Test fun tc_acc_03_26_real_replay_dialog() { // TC-ACC-03-26、AC-ACC-03-26
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        stopApp()
        val t0 = System.currentTimeMillis()
        server.freezeClock(t0)
        val stolen = storedSession()!!.refreshToken!!
        val access = server.refreshAccess(stolen)
        assertEquals(200, server.eventsStatus(access)) // 攻击者转正
        server.freezeClock(t0 + 3 * 60_000L)
        val replay = server.metric("im_refresh_replay_total")
        reopenWith { it.copy(accessExpiresAt = System.currentTimeMillis() - 1000) }
        ui.waitTag("kicked-dialog", 25_000)
        ui.waitText("检测到登录凭证异常")
        assertTrue(server.metric("im_refresh_replay_total") > replay)
        confirmDialogToLogin()
    }

    @Test fun tc_acc_03_41_real_superseded_once_then_replay() { // TC-ACC-03-41、AC-ACC-03-39：真实服务端只能造出 1 次，之后窗口结束就是重放。连续 3 次在 SessionUiTest（假服务端）
        val (u, _) = account()
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        stopApp()
        val t0 = System.currentTimeMillis()
        server.freezeClock(t0)
        val stolen = storedSession()!!.refreshToken!!
        assertEquals(200, server.eventsStatus(server.refreshAccess(stolen)))
        server.freezeClock(t0 + 2 * 60_000L - 4000) // 窗口还剩 4 秒
        val http = CountingHttp()
        reopenWith(http) { it.copy(accessExpiresAt = System.currentTimeMillis() - 1000) }
        rule.waitUntil(15_000) { http.count.get() == 1 }
        Thread.sleep(1500)
        assertEquals(1, http.count.get(), "等 retryAfterSeconds，不提前重试")
        assertFalse(ui.hasTag("kicked-dialog"), "留在当前页")
        assertFalse(ui.hasTag("account-username"), "留在当前页")
        server.freezeClock(t0 + 2 * 60_000L + 1000)
        ui.waitTag("kicked-dialog", 20_000)
        ui.waitText("检测到登录凭证异常")
        assertEquals(2, http.count.get())
        confirmDialogToLogin()
    }

    @Test fun tc_acc_04_25_real_response_after_31_seconds() { // TC-ACC-04-25 场景二、AC-ACC-04-27：真实服务端先踢线，响应 31 秒才到
        val (u, _) = account()
        val t = System.currentTimeMillis()
        val http = CountingHttp("/v1/account/password", holdMs = 31_000)
        replaceWithRealDeps(defaultDeps(testContext, http))
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val before = storedSession()!!
        inst.runOnMainSync { model.nav.resetTo("/me/settings/security/password") }
        ui.type("password-old", GOOD_PASSWORD)
        ui.type("password-new", NEW_PASSWORD)
        ui.tap("password-submit")
        val started = System.currentTimeMillis()
        assertRecoverDialogOrKicked(t)
        assertTrue(System.currentTimeMillis() - started > 25_000, "踢线要等改密超时才生效")
        Thread.sleep(3000) // 迟到的响应到达：丢弃，不救回会话
        assertTrue(ui.hasTag("kicked-dialog"))
        // 踢线生效后会话结束、存储清空；迟到的成功响应不能把会话救回来（不比较值，免得 token 进测试日志）。
        val after = storedSession()
        assertTrue(after == null || after.refreshToken == before.refreshToken, "迟到的响应不换 token")
        confirmDialogToLogin()
    }

    @Test fun tc_acc_03_45_real_absolute_limit() { // TC-ACC-03-45、AC-ACC-03-43：要 IM_REFRESH_ABSOLUTE=40s 的服务端（真实钟），默认服务端跳过
        val (u, _) = account()
        val http = CountingHttp()
        replaceWithRealDeps(defaultDeps(testContext, http))
        ui.openApp()
        ui.loginAndEnter(u, GOOD_PASSWORD)
        val s = storedSession()!!
        org.junit.Assume.assumeTrue("要用 IM_REFRESH_ABSOLUTE=40s 单独跑", s.absoluteExpiresAt!! - System.currentTimeMillis() < 120_000)
        assertEquals(s.absoluteExpiresAt, s.accessExpiresAt)
        inst.runOnMainSync { repeat(2) { model.account.foreground() } }
        Thread.sleep(2000)
        assertEquals(0, http.count.get(), "到上限不再刷新")
        assertEquals(ConnState.CONNECTED, model.im?.state)
        // 模拟器的钟比 Mac（服务端）慢约 4.4 秒（FE-620 实测），这个检查点留 8 秒，按模拟器的钟算仍在上限之前。
        Thread.sleep(maxOf(0L, s.absoluteExpiresAt!! - System.currentTimeMillis() - 8000))
        assertEquals(0, http.count.get(), "到上限之前不刷新")
        assertEquals(ConnState.CONNECTED, model.im?.state)
        // 记下之后连接状态的每次变化：到期后不能再连上（不重连）。
        val states = java.util.concurrent.CopyOnWriteArrayList<ConnState?>()
        val watching = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            while (watching.get()) {
                val now = model.im?.state
                if (states.lastOrNull() != now) states += now
                Thread.sleep(20)
            }
        }.apply { start() }
        try {
            ui.waitTag("kicked-ok", 20_000)
            ui.waitText("登录已失效，请重新登录")
            // 到期之后再等 3 秒（F2 服务端已修；P-35 L1：access 到期等于上限时收到 kicked/tokenExpired，SDK 直接登录失效）：
            // 一次刷新请求都没有，也没有重新连上。
            Thread.sleep(3000)
        } finally {
            watching.set(false)
            watcher.join()
        }
        android.util.Log.i("TC-ACC-03-45", "refresh answers ${http.answers}, states $states") // 只有状态码和错误码
        assertEquals(0, http.count.get(), "到期前后都不刷新：${http.answers}")
        assertEquals(emptyList<String>(), http.answers.toList(), "没有刷新的响应")
        val firstDown = states.indexOfFirst { it != ConnState.CONNECTED }
        assertTrue(firstDown >= 0 && states.drop(firstDown).none { it == ConnState.CONNECTED }, "到期后不重连：$states")
        assertTrue(ui.hasTag("kicked-ok"))
        ui.tap("kicked-ok")
        ui.toAccountForm()
        rule.waitUntil(5000) { storedSession() == null }
    }
}
