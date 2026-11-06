package com.tianting.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onRoot
import androidx.test.uiautomator.UiDevice
import com.tianting.app.FakeAccountHttp.Reply
import com.tianting.app.core.LoginResult
import com.tianting.app.core.Session
import com.tianting.app.core.LoginApi
import com.tianting.app.ui.DataStatus
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FE-615：登录、注册、恢复码、配置（PRD-chat F-ACC-01、02、05、07、10，第 13.3、13.4 节）。假服务端。 */
class AccountUiTest : UiTest() {
    private val refreshPath = "/v1/account/token/refresh"

    @Before fun accountOnly() {
        http.demoLogin = false
        http.registerMode = "invite"
    }

    private fun openLogin() {
        install(loggedIn = false)
        launch()
        rule.waitTag("account-username")
    }

    private fun fillRegister() {
        click("login-to-register")
        type("register-username", "alice_1")
        type("register-password", "Good-pass-1")
        type("register-invite", "INVITE-1")
    }

    private fun fillLogin(password: String = "Good-pass-1") {
        type("account-username", "alice")
        type("account-password", password)
    }

    private fun pressBack() {
        UiDevice.getInstance(inst).pressBack()
        rule.waitForIdle()
    }

    /** 模拟杀 App：内存里的东西都没有了，只留存储，新建 AppModel，Activity 重新打开。 */
    private fun killAndRestart() {
        replaceModel(newDeps())
        scenarioRestart()
    }

    @Test fun tc_acc_01_12_register_goes_to_save_page_and_only_ack_leaves_it() { // TC-ACC-01-12、AC-ACC-01-12
        openLogin()
        fillRegister()
        click("register-submit")
        rule.waitTag("recovery-code")
        n("recovery-code").assertTextEquals("CODE-REG-1")
        n("recovery-warning").assertTextEquals("恢复码丢失后账号无法找回")
        rule.onNode(androidx.compose.ui.test.hasText("我已保存"), useUnmergedTree = true).assertExists()
        assertNull(sessions.session, "确认前 token 不写存储")
        assertTrue(server.frames("auth").isEmpty(), "确认前不连接")
        assertFalse(rule.hasTag("tab-chats"))
        // 复制走系统剪贴板
        click("recovery-copy")
        assertEquals(listOf("CODE-REG-1"), copied.toList())
        rule.waitText("已复制")
        // 返回键不起作用（TC-ACC-01-12 步骤 2）
        pressBack()
        rule.waitTag("recovery-code")
        assertFalse(rule.hasTag("tab-chats"))
        assertNull(sessions.session)
        click("recovery-saved")
        rule.waitTag("tab-chats")
        assertEquals(1, http.to("/v1/account/recovery-code/ack").size)
        assertEquals("1", http.to("/v1/account/recovery-code/ack")[0].body["gen"]?.jsonPrimitive?.content)
        waitFor("确认后才落盘") { sessions.session?.mode == com.tianting.sdk.SessionMode.ACCOUNT }
        waitFor("确认后才连接") { server.frames("auth").size == 1 }
    }

    @Test fun tc_acc_01_12_copy_failure_is_reported() { // 第 13.4 节：复制失败
        copyWorks = false
        openLogin()
        fillRegister()
        click("register-submit")
        click("recovery-copy")
        rule.waitText("复制失败，请长按文字手动复制")
    }

    @Test fun tc_acc_01_13_username_taken_shown_under_the_field_and_inputs_kept() { // TC-ACC-01-13、AC-ACC-01-13
        http.err("/v1/account/register", 409, 4095)
        openLogin()
        fillRegister()
        click("register-submit")
        rule.waitTag("register-username-error")
        n("register-username-error").assertTextEquals("这个用户名不能使用")
        assertEquals("Good-pass-1".length, textOf("register-password").length) // 密码框是掩码，长度还在
        assertTrue("INVITE-1" in textOf("register-invite"))
        assertTrue("alice_1" in textOf("register-username"))
        n("register-submit").assertIsEnabled()
    }

    @Test fun tc_acc_01_18_register_error_copy() { // TC-ACC-01-18、AC-ACC-01-18
        openLogin()
        fillRegister()
        val cases = listOf(
            Reply.Err(400, 4004) to "用户名格式不正确",
            Reply.Err(400, 4000, "passwordWeak") to "密码太简单",
            Reply.Err(403, 4030, "inviteInvalid") to "邀请码无效",
            Reply.Err(429, 4290) to "操作太频繁，请稍后再试",
            Reply.Err(503, 5030) to "服务暂不可用",
            Reply.NetworkFail to "网络异常，请稍后重试",
            Reply.Raw(502, "<html>bad gateway</html>") to "网络异常，请稍后重试",
        )
        for ((reply, text) in cases) {
            http.handlers["/v1/account/register"] = { reply }
            click("register-submit")
            rule.waitUntil(5000) { rule.hasTag("register-error") && textOf("register-error") == text }
            assertTrue("alice_1" in textOf("register-username"), text)
            assertTrue("INVITE-1" in textOf("register-invite"), text)
            assertEquals("Good-pass-1".length, textOf("register-password").length, text)
            n("register-submit").assertIsEnabled()
        }
        // 提交中：按钮禁用，只发一个请求
        http.handlers["/v1/account/register"] = { Reply.Hold }
        val before = http.to("/v1/account/register").size
        click("register-submit")
        n("register-submit").assertIsNotEnabled()
        rule.waitText("提交中…")
        assertEquals(before + 1, http.to("/v1/account/register").size)
        http.release("/v1/account/register", Reply.NetworkFail)
        rule.waitUntil(5000) { rule.hasText("注册") }
    }

    @Test fun tc_acc_02_08_wrong_password_clears_password_keeps_username() { // TC-ACC-02-08、AC-ACC-02-8
        http.err("/v1/account/login", 401, 4018)
        openLogin()
        fillLogin("wrong-pass-1")
        click("account-submit")
        rule.waitTag("account-error")
        n("account-error").assertTextEquals("用户名或密码错误")
        assertEquals("", textOf("account-password"))
        assertTrue("alice" in textOf("account-username"))
    }

    @Test fun tc_acc_02_09_too_many_attempts_shows_minutes_and_does_not_retry() { // TC-ACC-02-09、AC-ACC-02-9
        http.err("/v1/account/login", 429, 4294, details = obj("retryAfterSeconds" to 600))
        openLogin()
        fillLogin()
        click("account-submit")
        rule.waitTag("account-error")
        n("account-error").assertTextEquals("尝试次数过多，请 10 分钟后再试")
        clock.advance(120_000)
        assertNeverWithin(2000, "自动重试") { http.to("/v1/account/login").size > 1 }
        assertEquals(1, http.to("/v1/account/login").size)
    }

    @Test fun login_failures_keep_the_page() { // 第 13.4 节、F-UI-43
        openLogin()
        fillLogin()
        for ((reply, text) in listOf(
            Reply.Err(503, 5030) to "服务暂不可用",
            Reply.NetworkFail to "网络异常，请稍后重试",
            Reply.Err(403, 4034, details = obj("bannedUntil" to null)) to "账号已被封禁",
        )) {
            http.handlers["/v1/account/login"] = { reply }
            click("account-submit")
            rule.waitUntil(5000) { rule.hasTag("account-error") && textOf("account-error") == text }
            assertEquals("Good-pass-1".length, textOf("account-password").length, text)
        }
    }

    @Test fun login_during_deletion_cooldown_offers_cancel() { // F-ACC-08 规则 2：撤销后再正常登录
        val deleteAt = System.currentTimeMillis() + 3 * 86_400_000L
        var cancelled = false
        http.handlers["/v1/account/login"] = { if (cancelled) Reply.Json(http.auth()) else Reply.Err(403, 4030, "accountDeletionPending", obj("deleteAt" to deleteAt)) }
        http.handlers["/v1/account/delete/cancel"] = {
            cancelled = true
            Reply.Json(obj())
        }
        openLogin()
        fillLogin()
        click("account-submit")
        rule.waitTag("deletion-pending")
        rule.waitText("账号正在注销，${com.tianting.app.core.formatDate(deleteAt)}后不可恢复，是否撤销？")
        click("deletion-cancel-request")
        rule.waitText("已撤销注销，请重新登录")
        assertEquals("Good-pass-1", http.to("/v1/account/delete/cancel")[0].body["password"]?.jsonPrimitive?.content)
        assertEquals("", textOf("account-password"), "不自动登录，密码框清空")
        assertEquals(1, http.to("/v1/account/login").size)
        type("account-password", "Good-pass-1")
        click("account-submit")
        rule.waitTag("tab-chats")
    }

    @Test fun tc_acc_05_04_recover_shows_new_code_then_chats() { // TC-ACC-05-04、AC-ACC-05-5
        openLogin()
        click("login-to-recover")
        type("recover-username", "alice")
        type("recover-code", "OLD-CODE")
        type("recover-password", "New-pass-12")
        click("recover-submit")
        rule.waitTag("recovery-code")
        n("recovery-code").assertTextEquals("CODE-REC-1")
        assertNull(sessions.session)
        click("recovery-saved")
        rule.waitTag("tab-chats")
        assertEquals("2", http.to("/v1/account/recovery-code/ack")[0].body["gen"]?.jsonPrimitive?.content)
    }

    @Test fun recover_errors_stay_on_the_page() { // 第 13.4 节
        openLogin()
        click("login-to-recover")
        type("recover-username", "alice")
        type("recover-code", "BAD")
        type("recover-password", "New-pass-12")
        for ((reply, text) in listOf(
            Reply.Err(401, 4018) to "用户名或恢复码错误",
            Reply.Err(400, 4000, "passwordWeak") to "密码太简单",
            Reply.Err(429, 4294, details = obj("retryAfterSeconds" to 30)) to "尝试次数过多，请 1 分钟后再试",
            Reply.Err(429, 4290, "recoverLimited") to "操作太频繁，请稍后再试",
            Reply.Err(503, 5030) to "服务暂不可用",
            Reply.NetworkFail to "网络异常，请稍后重试",
        )) {
            http.handlers["/v1/account/recover"] = { reply }
            click("recover-submit")
            rule.waitUntil(5000) { rule.hasTag("recover-error") && textOf("recover-error") == text }
            assertTrue("BAD" in textOf("recover-code"), text)
        }
        pressBack() // 第一次可能只是收起键盘
        if (!rule.hasTag("account-username")) {
            Thread.sleep(500)
            if (!rule.hasTag("account-username")) pressBack()
        }
        rule.waitTag("account-username")
    }

    @Test fun tc_acc_05_06_kill_on_save_page_needs_login_again_and_shows_a_new_code() { // TC-ACC-05-06、AC-ACC-05-7
        var n = 0
        http.handlers["/v1/account/login"] = { Reply.Json(http.auth(code = "CODE-${++n}", codeGen = n.toLong())) }
        openLogin()
        fillLogin()
        click("account-submit")
        rule.waitTag("recovery-code")
        n("recovery-code").assertTextEquals("CODE-1")
        assertNull(sessions.session, "确认前存储里没有 token")
        assertTrue(sessions.saves.isEmpty(), "确认前没有写过存储")
        killAndRestart()
        rule.waitTag("account-username")
        assertFalse(rule.hasTag("recovery-code"))
        assertFalse(rule.hasTag("kicked-dialog"))
        assertTrue(server.frames("auth").isEmpty(), "没有 token 不连接")
        fillLogin()
        click("account-submit")
        rule.waitTag("recovery-code")
        n("recovery-code").assertTextEquals("CODE-2")
        click("recovery-saved")
        rule.waitTag("tab-chats")
        assertEquals("2", http.to("/v1/account/recovery-code/ack")[0].body["gen"]?.jsonPrimitive?.content)
    }

    @Test fun tc_acc_05_09_three_pages_warn_and_none_has_support_entry() { // TC-ACC-05-09、AC-ACC-05-10
        openLogin()
        click("login-to-register")
        n("register-warning").assertTextEquals("恢复码丢失后账号无法找回")
        assertFalse(rule.hasText("客服", substring = true))
        pressBack()
        click("login-to-recover")
        n("recover-warning").assertTextEquals("恢复码丢失后账号无法找回")
        assertFalse(rule.hasText("客服", substring = true))
        type("recover-username", "alice")
        type("recover-code", "C")
        type("recover-password", "New-pass-12")
        click("recover-submit")
        rule.waitTag("recovery-warning")
        n("recovery-warning").assertTextEquals("恢复码丢失后账号无法找回")
        assertFalse(rule.hasText("客服", substring = true))
    }

    @Test fun tc_acc_05_14_stale_ack_tells_and_goes_to_login() { // TC-ACC-05-14、AC-ACC-05-15
        http.err("/v1/account/recovery-code/ack", 400, 4000, "recoveryCodeStale")
        openLogin()
        fillRegister()
        click("register-submit")
        click("recovery-saved")
        rule.waitTag("kicked-dialog")
        txt("恢复码已更新，请重新登录查看").assertExists()
        click("kicked-ok")
        rule.waitTag("account-username")
        assertFalse(rule.hasTag("recovery-code"))
        assertNull(sessions.session)
        assertTrue(server.frames("auth").isEmpty())
    }

    @Test fun ack_network_failure_stays_on_page_and_can_retry() { // 第 13.4 节
        http.handlers["/v1/account/recovery-code/ack"] = { Reply.NetworkFail }
        openLogin()
        fillRegister()
        click("register-submit")
        click("recovery-saved")
        rule.waitTag("recovery-error")
        n("recovery-error").assertTextEquals("网络异常，请稍后重试")
        assertNull(sessions.session)
        http.handlers.remove("/v1/account/recovery-code/ack")
        click("recovery-saved")
        rule.waitTag("tab-chats")
    }

    @Test fun tc_acc_05_18_unacked_forces_save_page_with_password() { // TC-ACC-05-18 场景一、AC-ACC-05-19
        server.authExtra = mapOf("recoveryCodeUnacked" to true)
        installAccount()
        launch()
        rule.waitTag("recovery-need-password")
        assertFalse(rule.hasTag("tab-chats"))
        pressBack()
        rule.waitTag("recovery-need-password")
        // 密码错误原地提示，不离开
        http.err("/v1/account/recovery-code", 401, 4018)
        type("recovery-password", "wrong-pass")
        click("recovery-regenerate")
        rule.waitTag("recovery-error")
        n("recovery-error").assertTextEquals("密码错误")
        http.handlers.remove("/v1/account/recovery-code")
        type("recovery-password", "Good-pass-1")
        click("recovery-regenerate")
        rule.waitTag("recovery-code")
        n("recovery-code").assertTextEquals("CODE-NEW-1")
        click("recovery-saved")
        rule.waitTag("tab-chats")
        assertEquals("3", http.to("/v1/account/recovery-code/ack")[0].body["gen"]?.jsonPrimitive?.content)
    }

    @Test fun tc_acc_05_18_unacked_page_can_log_out() { // 密码也忘了时的出口，再走"忘记密码"
        server.authExtra = mapOf("recoveryCodeUnacked" to true)
        installAccount()
        launch()
        click("recovery-logout")
        rule.waitTag("account-username")
        waitFor("退出") { http.to("/v1/account/logout").size == 1 }
    }

    @Test fun tc_acc_05_18_kill_on_save_page_after_password_change_goes_to_login() { // TC-ACC-05-18 场景二
        installAccount()
        launch()
        rule.waitTag("tab-chats")
        go("/me/settings/security/password")
        type("password-old", "Good-pass-1")
        type("password-new", "New-pass-12")
        click("password-submit")
        rule.waitTag("recovery-code")
        waitFor("旧 token 清除") { sessions.session == null }
        killAndRestart()
        rule.waitTag("account-username")
        assertFalse(rule.hasTag("kicked-dialog"), "不能拿旧值刷新再弹凭证有变化")
        assertTrue(http.to(refreshPath).isEmpty())
    }

    @Test fun tc_acc_07_05_account_form_without_demo() { // TC-ACC-07-05、AC-ACC-07-5
        openLogin()
        for (t in listOf("account-username", "account-password", "login-to-register", "login-to-recover")) assertTrue(rule.hasTag(t), t)
        assertFalse(rule.hasTag("login-username"), "没有演示表单")
        assertFalse(rule.hasText("业务方", substring = true))
        click("login-to-register")
        rule.waitTag("register-invite")
        assertFalse(rule.hasText("业务方", substring = true))
    }

    @Test fun tc_acc_07_06_demo_form_as_before_with_account_entry() { // TC-ACC-07-06、AC-ACC-07-6
        http.demoLogin = true
        install(loggedIn = false)
        loginApi = LoginApi { u, _ -> LoginResult.Ok(Session(u, "demo-t")) }
        launch()
        rule.waitTag("login-username")
        rule.waitText("演示环境：输入任意用户名即可登录")
        click("login-use-account")
        rule.waitTag("account-username")
        click("login-use-demo")
        type("login-username", "alice")
        click("login-submit")
        rule.waitTag("tab-chats")
        waitFor("存的是演示会话") { sessions.session?.mode == com.tianting.sdk.SessionMode.DEMO && sessions.session?.accessToken == "demo-t" }
        waitFor("演示会话用 token 连接") { server.frames("auth").firstOrNull()?.data?.get("token")?.jsonPrimitive?.content == "demo-t" }
    }

    @Test fun tc_acc_07_07_build_switch_off_hides_demo_even_if_config_allows() { // TC-ACC-07-07 步骤 1、AC-ACC-07-7
        demoBuild = false
        http.demoLogin = true
        openLogin()
        assertFalse(rule.hasTag("login-username"))
        assertFalse(rule.hasTag("login-use-demo"))
    }

    @Test fun tc_acc_07_07_config_off_hides_demo_even_if_build_allows() { // TC-ACC-07-07 步骤 2、AC-ACC-07-7
        demoBuild = true
        http.demoLogin = false
        openLogin()
        assertFalse(rule.hasTag("login-username"))
        assertFalse(rule.hasTag("login-use-demo"))
    }

    @Test fun tc_acc_07_09_closed_has_no_register_entry() { // TC-ACC-07-09 步骤 1、AC-ACC-07-9
        http.registerMode = "closed"
        openLogin()
        assertFalse(rule.hasTag("login-to-register"))
        assertTrue(rule.hasTag("login-to-recover"))
    }

    @Test fun open_mode_has_no_invite_field() { // F-ACC-01 规则 1
        http.registerMode = "open"
        openLogin()
        click("login-to-register")
        rule.waitTag("register-username")
        assertFalse(rule.hasTag("register-invite"))
        type("register-username", "alice_1")
        type("register-password", "Good-pass-1")
        n("register-submit").assertIsEnabled()
    }

    @Test fun tc_acc_07_09_config_failure_does_not_guess_mode() { // TC-ACC-07-09 步骤 2、3，AC-ACC-07-9
        for (fail in listOf<Reply>(Reply.NetworkFail, Reply.Raw(502, "<html>"), Reply.Err(500, 5000))) {
            http.handlers["/v1/config"] = { fail }
            http.demoLogin = true
            install(loggedIn = false)
            launch()
            rule.waitTag("config-error")
            n("config-error").assertTextEquals("网络异常，请稍后重试")
            for (t in listOf("login-username", "account-username", "login-to-register", "login-to-recover", "login-use-account")) assertFalse(rule.hasTag(t), t)
            http.handlers.remove("/v1/config")
            click("config-retry")
            rule.waitTag("login-username")
            finish()
        }
    }

    @Test fun tc_acc_07_09_config_failure_ignores_cache_on_login_page() { // F-ACC-07 异常："不猜测模式"，缓存只给已登录的
        install(loggedIn = false)
        launch()
        rule.waitTag("account-username")
        waitFor("缓存写入") { model.cachedConfig != null }
        finish()
        http.handlers["/v1/config"] = { Reply.NetworkFail }
        install(loggedIn = false)
        launch()
        rule.waitTag("config-error")
        assertFalse(rule.hasTag("account-username"))
    }

    @Test fun logged_in_config_failure_uses_cache() { // F-ACC-07 规则 4
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        installAccount()
        launch()
        rule.waitTag("tab-chats")
        waitFor("缓存写入") { model.cachedConfig?.privacyNotice != null }
        http.handlers["/v1/config"] = { Reply.NetworkFail }
        replaceModel(newDeps())
        scenarioRestart()
        rule.waitTag("tab-chats")
        go("/me/settings")
        rule.waitTag("settings-privacy-notice")
    }

    @Test fun logged_in_never_fetched_config_hides_new_entries() { // F-ACC-07 规则 4：从未成功过时隐藏
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        http.handlers["/v1/config"] = { Reply.NetworkFail }
        installAccount()
        launch()
        rule.waitTag("tab-chats")
        go("/me/settings")
        rule.waitTag("settings-security")
        assertFalse(rule.hasTag("settings-privacy-notice"))
    }

    @Test fun tc_acc_10_01_privacy_entries_and_failure() { // TC-ACC-10-01、AC-ACC-10-1（打开成功的路径在 AccountE2eTest）
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        openLogin()
        click("login-privacy")
        rule.waitTag("privacy-failed", 15_000)
        n("privacy-failed").assertTextEquals("暂时打不开，请稍后重试")
        pressBack()
        rule.waitTag("account-username")
        fillLogin()
        n("account-submit").assertIsEnabled() // 不阻止登录
        click("login-to-register")
        rule.waitText("注册即表示你已阅读隐私说明")
        click("register-privacy")
        rule.waitTag("privacy-page")
        pressBack()
        rule.waitTag("register-username")
        pressBack()
        fillLogin()
        click("account-submit")
        rule.waitTag("tab-chats")
        go("/me/settings")
        click("settings-privacy-notice")
        rule.waitTag("privacy-page")
    }

    @Test fun tc_acc_10_01_empty_config_hides_entries() { // TC-ACC-10-01 步骤 3
        openLogin()
        assertFalse(rule.hasTag("login-privacy"))
        click("login-to-register")
        rule.waitTag("register-username")
        assertFalse(rule.hasTag("register-privacy"))
        assertFalse(rule.hasText("隐私说明", substring = true))
        pressBack()
        fillLogin()
        click("account-submit")
        rule.waitTag("tab-chats")
        go("/me/settings")
        rule.waitTag("settings-security")
        assertFalse(rule.hasTag("settings-privacy-notice"))
    }

    @Test fun version_without_url_hides_entries() { // 问题 H6：有版本没有地址，打包页面还没有文字
        http.privacy = obj("version" to "v1")
        openLogin()
        assertFalse(rule.hasTag("login-privacy"))
        click("login-to-register")
        rule.waitTag("register-username")
        assertFalse(rule.hasTag("register-privacy"))
    }

    @Test fun tc_acc_10_03_new_version_prompts_once() { // TC-ACC-10-03、AC-ACC-10-3（服务端记录版本见问题单，这里只验证客户端）
        http.privacy = obj("version" to "v2", "url" to "https://invalid.invalid/p")
        openLogin()
        fillLogin()
        click("account-submit")
        rule.waitTag("tab-chats")
        rule.waitTag("privacy-update")
        click("privacy-update-ok")
        rule.waitGoneTag("privacy-update")
        // 退出再登录：不再提示
        inst.runOnMainSync { model.logout() }
        rule.waitTag("account-username")
        fillLogin()
        click("account-submit")
        rule.waitTag("tab-chats")
        assertNeverWithin(1500, "再次提示") { rule.hasTag("privacy-update") }
        // 版本再更新：又提示一次
        inst.runOnMainSync { model.logout() }
        http.privacy = obj("version" to "v3", "url" to "https://invalid.invalid/p")
        inst.runOnMainSync { model.refreshConfig() }
        rule.waitTag("account-username")
        waitFor("新配置") { model.cachedConfig?.privacyNotice?.version == "v3" }
        fillLogin()
        click("account-submit")
        rule.waitTag("privacy-update")
    }

    @Test fun tc_acc_10_03_not_confirming_still_lets_you_use_the_app_and_asks_again() { // TC-ACC-10-03 预期 2：第一批不强制
        http.privacy = obj("version" to "v2", "url" to "https://invalid.invalid/p")
        openLogin()
        fillLogin()
        click("account-submit")
        rule.waitTag("privacy-update")
        pressBack()
        rule.waitGoneTag("privacy-update")
        rule.waitTag("tab-chats")
        inst.runOnMainSync { model.logout() }
        rule.waitTag("account-username")
        fillLogin()
        click("account-submit")
        rule.waitTag("privacy-update")
    }

    @Test fun registering_counts_as_reading_the_current_version() { // F-ACC-10 规则 2
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        openLogin()
        fillRegister()
        click("register-submit")
        click("recovery-saved")
        rule.waitTag("tab-chats")
        assertNeverWithin(1500, "注册后提示版本更新") { rule.hasTag("privacy-update") }
    }

    @Test fun privacy_prompt_not_over_the_save_page() { // 先保存恢复码
        http.privacy = obj("version" to "v2", "url" to "https://invalid.invalid/p")
        http.handlers["/v1/account/login"] = { Reply.Json(http.auth(code = "C-1")) }
        openLogin()
        fillLogin()
        click("account-submit")
        rule.waitTag("recovery-code")
        assertNeverWithin(1000, "盖在保存页上") { rule.hasTag("privacy-update") }
        click("recovery-saved")
        rule.waitTag("privacy-update")
    }

    @Test fun logged_out_start_never_connects_and_shows_no_dialog() { // K7：没登录过不 connect，不走清本地数据的路径
        openLogin()
        assertNeverWithin(1000, "连接或弹窗") { server.connections.isNotEmpty() || rule.hasTag("kicked-dialog") }
    }

    @Test fun deep_link_waits_for_recovery_ack() { // AC-UI-01-3 + AC-ACC-01-12
        install(loggedIn = false)
        launch("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("account-username")
        click("login-to-register")
        type("register-username", "alice_1")
        type("register-password", "Good-pass-1")
        type("register-invite", "I")
        click("register-submit")
        rule.waitTag("recovery-code")
        assertFalse(rule.hasTag("chat-title"))
        click("recovery-saved")
        rule.waitTag("chat-title")
    }

    /** AC-CHAT-90-9：button-name 的等价检查。这个仓库没有 espresso-accessibility（不新增依赖），用语义树查：能点的节点都要有名字。 */
    private fun assertClickablesHaveNames(where: String) {
        fun walk(n: SemanticsNode, out: MutableList<SemanticsNode>) {
            out.add(n)
            n.children.forEach { walk(it, out) }
        }
        val all = ArrayList<SemanticsNode>()
        walk(rule.onRoot(useUnmergedTree = false).fetchSemanticsNode(), all)
        for (node in all) {
            if (node.config.getOrNull(SemanticsActions.OnClick) == null) continue
            if (node.config.getOrNull(SemanticsProperties.EditableText) != null) continue // 输入框的名字是它上面的文字，不算按钮
            val named = node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true || node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            assertTrue(named, "$where: 能点的节点没有名字：${node.config}")
        }
    }

    @Test fun tc_chat_90_08_new_pages_have_named_buttons() { // TC-CHAT-90-08、AC-CHAT-90-9
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        openLogin()
        assertClickablesHaveNames("登录")
        click("login-to-register")
        rule.waitTag("register-username")
        assertClickablesHaveNames("注册")
        pressBack()
        click("login-to-recover")
        rule.waitTag("recover-username")
        assertClickablesHaveNames("找回")
        pressBack()
        fillRegister()
        click("register-submit")
        rule.waitTag("recovery-code")
        assertClickablesHaveNames("保存恢复码")
        click("recovery-saved")
        rule.waitTag("tab-chats")
        go("/me/settings/security")
        rule.waitTag("security-password")
        assertClickablesHaveNames("账号安全")
        go("/me/settings/security/password")
        rule.waitTag("password-old")
        assertClickablesHaveNames("修改密码")
        go("/me/nickname")
        rule.waitTag("nickname-input")
        assertClickablesHaveNames("修改昵称")
    }

    @Test fun refresh_never_sent_without_session() { // 没登录时不刷新
        openLogin()
        inst.runOnMainSync { model.setForeground(true) }
        assertNeverWithin(500, "刷新") { http.to(refreshPath).isNotEmpty() }
        assertNotNull(http.to("/v1/config").firstOrNull())
    }

    @Test fun recovery_status_tag_data_status_key_exists() { // 隐私页的状态供端到端读取
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        openLogin()
        click("login-privacy")
        rule.waitTag("privacy-failed", 15_000)
        rule.onNode(tagged("privacy-page", DataStatus, "failed"), useUnmergedTree = true).assertExists()
    }
}
