package com.tianting.app

import androidx.compose.ui.test.assertTextEquals
import androidx.test.uiautomator.UiDevice
import com.tianting.app.FakeAccountHttp.Reply
import com.tianting.sdk.AccountSession
import com.tianting.sdk.ConnState
import com.tianting.sdk.SessionDialog
import com.tianting.sdk.SessionMode
import com.tianting.sdk.sessionDialogText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FE-616：会话、账号安全、断网退出（F-ACC-03、04、06、08，AC-ACC 里的前端部分）。假服务端 + [SDK 钟]。杀进程和飞行模式的真实路径在 AccountE2eTest、AccountKillE2e。 */
class SessionUiTest : UiTest() {
    private val refresh = "/v1/account/token/refresh"
    private val hour = 3_600_000L
    private val offline = "网络不可用"
    private val recoverAt = System.currentTimeMillis() + 7 * 24 * hour
    private val recoverText get() = sessionDialogText(SessionDialog.RECOVER_BEFORE, recoverAt)!!

    @Before fun noDemo() {
        http.demoLogin = false
    }

    /** 账号会话已登录并且连上。 */
    private fun openConnected(session: AccountSession = http.session()) {
        installAccount(session)
        launch()
        rule.waitTag("tab-chats")
        waitConnected()
    }

    private fun waitConnected() {
        rule.waitUntil(5000) { model.im?.state == ConnState.CONNECTED }
        Thread.sleep(100) // connectionChanged 是投递到账号线程的，等它开定时器
    }

    private fun kick(reason: String, code: Int = 4012, recoverBefore: Long? = null) {
        if (recoverBefore != null) server.pushEvent("kicked", "reason" to reason, "recoverBefore" to recoverBefore) else server.pushEvent("kicked", "reason" to reason)
        server.drop(code)
    }

    private fun dialogThenLogin(text: String) {
        rule.waitTag("kicked-dialog")
        rule.waitText(text)
        val connectionsAtDialog = server.connections.size
        assertNeverWithin(1500, "重连") { server.connections.size > connectionsAtDialog }
        click("kicked-ok")
        rule.waitTag("account-username")
        waitFor("token 清除") { sessions.session == null }
    }

    @Test fun tc_acc_03_07_expired_access_refreshes_before_auth() { // TC-ACC-03-07、AC-ACC-03-7
        http.handlers[refresh] = { Reply.Hold }
        installAccount(http.session(expiresAt = System.currentTimeMillis() - hour))
        launch()
        rule.waitTag("tab-chats") // 直接进会话列表，没有登录页
        waitFor("先发刷新") { http.to(refresh).size == 1 }
        assertNeverWithin(1000, "auth 或登录页") { server.frames("auth").isNotEmpty() || rule.hasTag("account-username") || rule.hasTag("kicked-dialog") }
        http.release(refresh)
        waitFor("刷新后才 auth") { server.frames("auth").size == 1 }
        assertEquals(sessions.session?.accessToken, server.frames("auth")[0].data["token"]?.jsonPrimitive?.content)
        waitConnected()
        assertEquals(1, http.to(refresh).size)
        assertFalse(rule.hasTag("kicked-dialog"))
    }

    @Test fun tc_acc_03_08_revoked_refresh_on_cold_start_goes_to_login() { // TC-ACC-03-08、AC-ACC-03-8
        http.err(refresh, 401, 4019, "revoked")
        installAccount(http.session(expiresAt = System.currentTimeMillis() - hour))
        launch()
        dialogThenLogin("登录已失效，请重新登录")
        assertTrue(server.frames("auth").isEmpty())
    }

    @Test fun tc_acc_03_10_refresh_before_expiry_reconnects_silently() { // TC-ACC-03-10、AC-ACC-03-10
        server.convs = listOf(convJson(BOB_CONV, "阿博"))
        openConnected(http.session(expiresAt = System.currentTimeMillis() + 6 * hour + 60_000))
        val first = server.current
        assertEquals(0, http.to(refresh).size)
        clock.advance(120_000)
        waitFor("刷新") { http.to(refresh).size == 1 }
        waitFor("用新 token 重连") { server.frames("auth").size == 2 }
        assertEquals(1000, first.closedWith?.first)
        assertEquals(sessions.session?.accessToken, server.frames("auth")[1].data["token"]?.jsonPrimitive?.content)
        assertNotEquals(server.frames("auth")[0].data["token"], server.frames("auth")[1].data["token"])
        assertNeverWithin(1500, "弹窗或连接提示") { rule.hasTag("kicked-dialog") || rule.hasTag("conn-banner") }
        waitConnected()
        server.pushMsg(msgJson(BOB_CONV, 1, "bob", "换 token 后还能收"))
        rule.waitText("换 token 后还能收", substring = true)
    }

    @Test fun tc_acc_03_11_offline_expired_cold_start_stays_and_recovers() { // TC-ACC-03-11、AC-ACC-03-11（缓存和真实飞行模式在 AccountE2eTest）
        online = false
        http.handlers[refresh] = { Reply.NetworkFail }
        installAccount(http.session(expiresAt = System.currentTimeMillis() - hour))
        launch()
        rule.waitTag("tab-chats")
        rule.waitTag("conn-banner", 5000)
        rule.waitText(offline)
        assertFalse(rule.hasTag("account-username"))
        assertFalse(rule.hasTag("kicked-dialog"))
        assertTrue(server.frames("auth").isEmpty())
        online = true
        http.handlers.remove(refresh)
        clock.advance(2000) // 退避到点
        waitFor("恢复后刷新并连接") { server.frames("auth").size == 1 }
        rule.waitGoneTag("conn-banner")
    }

    @Test fun tc_acc_03_12_refresh_503_backs_off_and_kicked_expired_is_silent() { // TC-ACC-03-12、AC-ACC-03-12
        var calls = 0
        http.handlers[refresh] = {
            if (++calls == 1) Reply.Err(503, 5030) else Reply.Json(http.auth())
        }
        openConnected(http.session(expiresAt = System.currentTimeMillis() + 6 * hour + 60_000))
        clock.advance(120_000)
        waitFor("第一次") { calls >= 1 }
        assertFalse(rule.hasTag("kicked-dialog"))
        assertFalse(rule.hasTag("account-username"))
        clock.advance(2000) // 响应比 advance 先回来时，退避已经在上一步到点了
        waitFor("退避后重试") { calls == 2 }
        waitFor("重连") { server.frames("auth").size == 2 }
        waitConnected()
        // 连接中收到 kicked/tokenExpired，刷新成功
        kick("tokenExpired", 4011)
        waitFor("再刷新一次") { calls == 3 }
        waitFor("自动重连") { server.frames("auth").size == 3 }
        assertNeverWithin(1000, "弹窗") { rule.hasTag("kicked-dialog") }
    }

    @Test fun tc_acc_03_13_saved_token_is_used_after_restart() { // TC-ACC-03-13 的进程内版本，真实杀进程在 AccountKillE2e
        openConnected(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        waitFor("刷新并落盘") { http.to(refresh).size == 1 && sessions.saves.isNotEmpty() }
        val saved = sessions.saves.last()
        replaceModel(newDeps())
        scenarioRestart()
        rule.waitTag("tab-chats")
        waitFor("用新 token") { server.frames("auth").lastOrNull()?.data?.get("token")?.jsonPrimitive?.content == saved.accessToken }
    }

    @Test fun tc_acc_03_18_old_session_is_demo() { // TC-ACC-03-18、AC-ACC-03-18
        installAccount(AccountSession(userId = "alice", accessToken = "old-token"))
        launch()
        rule.waitTag("tab-chats")
        waitFor("按演示连接") { server.frames("auth").firstOrNull()?.data?.get("token")?.jsonPrimitive?.content == "old-token" }
        assertTrue(http.to(refresh).isEmpty())
        go("/me/settings")
        rule.waitTag("settings-blocks")
        assertFalse(rule.hasTag("settings-security"))
        go("/me")
        rule.waitTag("me-name")
        assertFalse(model.nav.current.path == "/me/nickname")
        click("me-card".also { assertTrue(rule.hasTag(it)) })
        assertEquals("/me", model.nav.current.path, "演示会话点卡片不进编辑页")
    }

    @Test fun tc_acc_03_20_refresh_response_lost_old_value_retried_after_restart() { // TC-ACC-03-20 的进程内版本，真实杀进程在 AccountKillE2e
        http.handlers[refresh] = { Reply.Hold }
        val s = http.session(expiresAt = System.currentTimeMillis() + 5 * hour)
        installAccount(s)
        launch()
        waitFor("请求已发出") { http.to(refresh).size == 1 }
        http.handlers.remove(refresh)
        replaceModel(newDeps()) // 请求没有回来就重启
        scenarioRestart()
        waitFor("用旧 refresh 重试") { http.to(refresh).size >= 2 }
        assertEquals(s.refreshToken, http.to(refresh).last().body["refreshToken"]?.jsonPrimitive?.content)
        rule.waitTag("tab-chats")
        assertFalse(rule.hasTag("account-username"))
    }

    @Test fun tc_acc_03_23_entries_merge_and_late_response_is_dropped() { // TC-ACC-03-23、AC-ACC-03-23
        var calls = 0
        http.handlers[refresh] = { if (++calls == 1) Reply.Hold else Reply.Json(http.auth(gen = 50)) }
        installAccount(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        launch()
        waitFor("第一个") { calls == 1 }
        rule.waitTag("tab-chats")
        waitConnected()
        inst.runOnMainSync { model.setForeground(true) }
        runBlocking { model.account.trigger(com.tianting.sdk.RefreshSource.TIMER) }
        kick("tokenExpired", 4011)
        Thread.sleep(300)
        assertEquals(1, http.to(refresh).size, "同一时间只有一个刷新请求")
        clock.advance(30_000) // 超时放弃
        clock.advance(2000) // 退避到点
        waitFor("重试") { calls == 2 }
        waitFor("落盘") { sessions.session?.refreshToken == "r50" }
        http.release(refresh, Reply.Json(http.auth(gen = 49))) // 第一个的迟到响应
        Thread.sleep(300)
        assertEquals("r50", sessions.session?.refreshToken, "迟到响应被丢弃")
    }

    @Test fun tc_acc_03_26_replay_dialog_then_login() { // TC-ACC-03-26、AC-ACC-03-26
        http.err(refresh, 401, 4019, "replayDetected")
        installAccount(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        launch()
        dialogThenLogin("检测到登录凭证异常，这台设备已下线。如果不是你本人操作，请尽快改密码")
    }

    @Test fun tc_acc_03_34_offline_victim_sees_recover_date_via_kick() { // TC-ACC-03-34 的 D-a，真实服务端在 AccountE2eTest
        openConnected()
        kick("passwordChanged", recoverBefore = recoverAt)
        dialogThenLogin(recoverText)
    }

    @Test fun tc_acc_03_34_offline_victim_sees_recover_date_via_refresh() { // TC-ACC-03-34 的 D-b
        http.err(refresh, 401, 4019, "credentialsChanged", obj("recoverBefore" to recoverAt))
        installAccount(http.session(expiresAt = System.currentTimeMillis() - hour))
        launch()
        dialogThenLogin(recoverText)
    }

    @Test fun tc_acc_03_39_rate_limited_refresh_stays() { // TC-ACC-03-39 场景一（场景二、三在服务端 e2e，FE-611）、AC-ACC-03-37
        http.err(refresh, 429, 4290)
        openConnected(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        waitFor("第一次") { http.to(refresh).size == 1 }
        clock.advance(2000)
        waitFor("退避重试") { http.to(refresh).size == 2 }
        assertFalse(rule.hasTag("kicked-dialog"))
        assertTrue(rule.hasTag("tab-chats"))
        assertEquals(ConnState.CONNECTED, model.im?.state)
    }

    @Test fun tc_acc_03_41_superseded_retries_then_login() { // TC-ACC-03-41、AC-ACC-03-39
        http.err(refresh, 401, 4019, "superseded", obj("retryAfterSeconds" to 120))
        installAccount(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        launch()
        waitFor("第 1 次") { http.to(refresh).size == 1 }
        rule.waitTag("tab-chats")
        clock.advance(119_000)
        assertNeverWithin(300, "提前重试") { http.to(refresh).size > 1 }
        clock.advance(1000)
        waitFor("第 2 次") { http.to(refresh).size == 2 }
        assertFalse(rule.hasTag("kicked-dialog"))
        inst.runOnMainSync { model.setForeground(true) } // 回前台不提前
        assertNeverWithin(300, "回前台提前重试") { http.to(refresh).size > 2 }
        clock.advance(120_000)
        waitFor("第 3 次") { http.to(refresh).size == 3 }
        dialogThenLogin("登录已失效，请重新登录")
        assertEquals(3, http.to(refresh).size)
    }

    @Test fun tc_acc_03_44_in_flight_requests_retry_with_new_token_without_refresh() = runBlocking { // TC-ACC-03-44、AC-ACC-03-42：/v1/files/urls 随 C2，这里用 /v1/account/events，FE-636 要补
        val s = http.session()
        http.handlers["/v1/account/events"] = { r -> if (r.bearer == s.accessToken) Reply.Hold else Reply.Json(http.eventsBody) }
        openConnected(s)
        val calls = List(3) { async(kotlinx.coroutines.Dispatchers.IO) { model.account.listEvents() } }
        waitFor("3 个在途") { http.to("/v1/account/events").size == 3 }
        model.account.refresh()
        assertEquals(1, http.to(refresh).size)
        http.release("/v1/account/events", Reply.Err(401, 4011, "tokenSuperseded"))
        calls.awaitAll()
        assertEquals(1, http.to(refresh).size, "没有多发刷新")
        assertEquals(6, http.to("/v1/account/events").size)
        assertTrue(http.to("/v1/account/events").takeLast(3).all { it.bearer == sessions.session?.accessToken })
    }

    @Test fun tc_acc_03_45_absolute_limit_stops_refreshing_then_login() { // TC-ACC-03-45、AC-ACC-03-43
        val end = System.currentTimeMillis() + 5 * hour
        openConnected(http.session(expiresAt = end, absolute = end))
        clock.advance(hour)
        inst.runOnMainSync { model.setForeground(true) }
        assertNeverWithin(500, "刷新或重连") { http.to(refresh).isNotEmpty() || server.frames("auth").size > 1 }
        clock.advance(4 * hour + 1000)
        dialogThenLogin("登录已失效，请重新登录")
        assertTrue(http.to(refresh).isEmpty())
    }

    @Test fun tc_acc_04_05_password_changed_kick_with_and_without_date() { // TC-ACC-04-05、AC-ACC-04-5
        openConnected()
        kick("passwordChanged", recoverBefore = recoverAt)
        dialogThenLogin(recoverText)
        finish()
        openConnected()
        kick("passwordChanged")
        dialogThenLogin("账号已被下线")
    }

    @Test fun tc_acc_04_06_offline_logout_leaves_at_once_and_flushes_later() { // TC-ACC-04-06、AC-ACC-04-6（真实断网在 AccountE2eTest）
        http.handlers["/v1/account/logout"] = { Reply.Hold }
        val s = http.session()
        openConnected(s)
        go("/me/settings")
        click("logout")
        click("logout-dialog-ok")
        rule.waitTag("account-username") // 不等 logout 的响应（K2）
        assertTrue(rule.hasTag("account-submit"))
        waitFor("请求已发出") { http.to("/v1/account/logout").size == 1 }
        waitFor("token 清除") { sessions.session == null }
        assertEquals(listOf(s.refreshToken), pending.items.map { it.refreshToken }, "待退出先写")
        assertEquals("sid-1", pending.items.single().sid)
        assertEquals(s.refreshToken, http.to("/v1/account/logout")[0].body["refreshToken"]?.jsonPrimitive?.content)
        http.release("/v1/account/logout", Reply.NetworkFail)
        waitFor("连接关闭") { server.current.closedWith?.first == 1000 }
        assertEquals(1, pending.items.size, "连不上：留着")
        // 恢复网络：bindNetwork 调 networkOnline
        http.handlers.remove("/v1/account/logout")
        model.account.networkOnline()
        waitFor("补发") { http.to("/v1/account/logout").size == 2 }
        assertEquals(s.refreshToken, http.to("/v1/account/logout")[1].body["refreshToken"]?.jsonPrimitive?.content)
        assertNull(http.to("/v1/account/logout")[1].bearer, "待退出只有 refresh")
        waitFor("待退出清除") { pending.items.isEmpty() }
    }

    @Test fun tc_acc_04_06_pending_logout_flushes_on_next_start_on_login_page() { // TC-ACC-04-06：杀 App 后下次启动补发
        pending.items = listOf(com.tianting.sdk.PendingLogout("sid-x", "rx"))
        install(loggedIn = false)
        launch()
        rule.waitTag("account-username")
        waitFor("启动补发") { http.to("/v1/account/logout").size == 1 }
        assertEquals("rx", http.to("/v1/account/logout")[0].body["refreshToken"]?.jsonPrimitive?.content)
        waitFor("清除") { pending.items.isEmpty() }
    }

    @Test fun login_works_at_once_while_logout_is_in_flight() { // 问题表 A1：logout 只结束它开始时的会话，登录不用等它的响应
        http.handlers["/v1/account/logout"] = { Reply.Hold }
        openConnected()
        val oldToken = sessions.session!!.accessToken
        val oldRefresh = sessions.session!!.refreshToken
        go("/me/settings")
        click("logout")
        click("logout-dialog-ok")
        rule.waitTag("account-username")
        waitFor("退出请求已发出") { http.to("/v1/account/logout").size == 1 }
        val oldConnections = server.connections.size
        type("account-username", "alice")
        type("account-password", "Good-pass-1")
        click("account-submit")
        waitFor("不等 logout 就登录", 3_000) { http.to("/v1/account/login").size == 1 }
        rule.waitTag("tab-chats")
        waitFor("新会话落盘") { sessions.session != null }
        val newToken = sessions.session!!.accessToken
        assertNotEquals(oldToken, newToken)
        waitConnected()
        waitFor("新连接") { server.connections.size > oldConnections }
        // 旧 logout 现在才回：不影响新会话，没有弹窗，没有回登录页。
        http.handlers.remove("/v1/account/logout")
        http.release("/v1/account/logout")
        waitFor("待退出清掉") { pending.items.isEmpty() }
        assertNeverWithin(1_000, "新会话被结束") { rule.hasTag("account-username") || rule.hasTag("kicked-dialog") }
        assertEquals(newToken, sessions.session?.accessToken)
        assertEquals(newToken, model.account.session.value?.accessToken)
        assertEquals(oldRefresh, http.to("/v1/account/logout")[0].body["refreshToken"]?.jsonPrimitive?.content)
        waitConnected()
    }

    @Test fun demo_login_works_at_once_while_logout_is_in_flight() { // 问题表 A1，演示登录同样不等
        http.handlers["/v1/account/logout"] = { Reply.Hold }
        openConnected()
        go("/me/settings")
        click("logout")
        click("logout-dialog-ok")
        rule.waitTag("account-username")
        waitFor("退出请求已发出") { http.to("/v1/account/logout").size == 1 }
        inst.runOnMainSync { model.onLoggedIn(com.tianting.app.core.Session("alice", "demo-token")) }
        rule.waitTag("tab-chats")
        http.handlers.remove("/v1/account/logout")
        http.release("/v1/account/logout")
        assertNeverWithin(1_000, "演示会话被结束") { rule.hasTag("account-username") }
        assertEquals("demo-token", model.account.session.value?.accessToken)
        assertEquals("demo-token", sessions.session?.accessToken)
    }

    @Test fun security_notice_other_kinds_toast_and_unknown_is_silent() { // AC-ACC-03-25、AC-ACC-03-45、F-ACC-05 规则 5
        openConnected()
        server.pushEvent("securityNotice", "kind" to "futureKind", "at" to 1L, "platform" to "android")
        assertNeverWithin(500, "不认识的 kind") { rule.hasTag("toast") }
        server.pushEvent("securityNotice", "kind" to "recoveryCodeRegenerated", "at" to 1L, "platform" to "android")
        rule.waitText("你的恢复码已重新生成")
        server.pushEvent("securityNotice", "kind" to "refreshReplay", "at" to 1L, "platform" to "android")
        rule.waitText("检测到你的账号登录凭证异常")
        server.pushEvent("securityNotice", "kind" to "refreshRateLimited", "at" to 1L, "platform" to "android")
        rule.waitText("你的账号登录凭证刷新过于频繁")
    }

    @Test fun demo_logout_sends_nothing_and_clears() { // F-AUTH-05 不变：演示会话没有 /v1/account/logout
        install()
        launch()
        rule.waitTag("tab-chats")
        go("/me/settings")
        click("logout")
        click("logout-dialog-ok")
        rule.waitTag("account-username")
        waitFor("清除") { sessions.session == null }
        Thread.sleep(200)
        assertTrue(http.to("/v1/account/logout").isEmpty())
        assertTrue(pending.items.isEmpty())
    }

    @Test fun tc_acc_04_09_logout_others_wrong_then_right_password() { // TC-ACC-04-09 的 D1 界面、AC-ACC-04-10（真实服务端在 AccountE2eTest）
        http.err("/v1/account/logout-others", 401, 4018)
        openConnected()
        go("/me/settings/security/others")
        type("others-password", "wrong-pass")
        click("others-submit")
        rule.waitTag("others-error")
        n("others-error").assertTextEquals("密码错误")
        assertEquals(1, server.frames("auth").size, "错误时没有变化")
        http.handlers.remove("/v1/account/logout-others")
        type("others-password", "Good-pass-1")
        click("others-submit")
        rule.waitText("已退出其他设备")
        waitFor("换 sid 后重连") { server.frames("auth").size == 2 }
        assertEquals(sessions.session?.accessToken, server.frames("auth")[1].data["token"]?.jsonPrimitive?.content)
        assertNeverWithin(1000, "连接提示或弹窗") { rule.hasTag("conn-banner") || rule.hasTag("kicked-dialog") }
    }

    @Test fun tc_acc_04_15_password_response_lost_then_credentials_changed() { // TC-ACC-04-15、AC-ACC-04-16（真实服务端在 AccountE2eTest）
        http.handlers["/v1/account/password"] = { Reply.Hold }
        http.err(refresh, 401, 4019, "credentialsChanged", obj("recoverBefore" to recoverAt))
        openConnected()
        go("/me/settings/security/password")
        type("password-old", "Good-pass-1")
        type("password-new", "New-pass-12")
        click("password-submit")
        waitFor("已发出") { http.to("/v1/account/password").size == 1 }
        clock.advance(30_000)
        rule.waitTag("password-error")
        n("password-error").assertTextEquals("网络异常，请稍后重试")
        assertEquals(ConnState.CONNECTED, model.im?.state)
        runBlocking { runCatching { model.account.refresh() } }
        dialogThenLogin(recoverText)
    }

    @Test fun tc_acc_04_16_timer_refresh_and_password_are_serial() { // TC-ACC-04-16（层级是 SDK 单测，这里确认界面路径也串行）、AC-ACC-04-17
        http.handlers[refresh] = { Reply.Hold }
        openConnected(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        waitFor("刷新占着队列") { http.to(refresh).size == 1 }
        go("/me/settings/security/password")
        type("password-old", "Good-pass-1")
        type("password-new", "New-pass-12")
        click("password-submit")
        assertNeverWithin(500, "改密插队") { http.to("/v1/account/password").isNotEmpty() }
        http.release(refresh)
        waitFor("刷新结束后才改密") { http.to("/v1/account/password").size == 1 }
        rule.waitTag("recovery-code")
    }

    @Test fun tc_acc_04_24_date_is_shown_as_given() { // TC-ACC-04-24 的显示部分：kicked、4019 都显示 recoverBefore，序列本身在服务端和 AccountE2eTest
        for (reason in listOf("sessionRevoked", "accountDeleted", "passwordChanged")) {
            openConnected()
            kick(reason, recoverBefore = recoverAt)
            dialogThenLogin(recoverText)
            finish()
        }
    }

    private fun startPasswordChange() {
        go("/me/settings/security/password")
        type("password-old", "Good-pass-1")
        type("password-new", "New-pass-12")
        click("password-submit")
    }

    @Test fun tc_acc_04_25_no_response_kick_applies_after_timeout() { // TC-ACC-04-25 场景一、二，AC-ACC-04-27
        http.handlers["/v1/account/password"] = { Reply.Hold }
        openConnected()
        startPasswordChange()
        waitFor("已发出") { http.to("/v1/account/password").size == 1 }
        kick("passwordChanged")
        assertNeverWithin(800, "踢线没有缓存") { rule.hasTag("kicked-dialog") }
        clock.advance(30_000)
        rule.waitTag("password-error")
        rule.waitTag("kicked-dialog") // 失败提示和踢线都生效
        txt("账号已被下线").assertExists()
        // 场景二：响应 31 秒才到：丢弃
        http.release("/v1/account/password")
        Thread.sleep(300)
        assertFalse(sessions.session != null, "迟到响应不能把会话救回来")
        click("kicked-ok")
        rule.waitTag("account-username")
        // 队列不堵
        http.handlers.remove("/v1/account/password")
        type("account-username", "alice")
        type("account-password", "New-pass-12")
        click("account-submit")
        rule.waitTag("tab-chats")
    }

    @Test fun tc_acc_04_25_failure_then_kick_applies() { // TC-ACC-04-25 场景三
        http.handlers["/v1/account/password"] = { Reply.Hold }
        openConnected()
        startPasswordChange()
        waitFor("已发出") { http.to("/v1/account/password").size == 1 }
        kick("passwordChanged")
        Thread.sleep(300)
        http.release("/v1/account/password", Reply.Err(500, 5000))
        rule.waitTag("password-error")
        n("password-error").assertTextEquals("服务异常，请稍后重试")
        rule.waitTag("kicked-dialog")
    }

    @Test fun tc_acc_04_25_response_before_late_kick_is_silent() { // TC-ACC-04-25 场景四
        openConnected()
        val old = server.current
        startPasswordChange()
        rule.waitTag("recovery-code")
        old.receive(obj("cmd" to "event", "data" to obj("type" to "kicked", "reason" to "passwordChanged")))
        old.drop(4012)
        assertNeverWithin(1000, "弹窗") { rule.hasTag("kicked-dialog") }
        waitFor("新 token 重连") { server.frames("auth").size == 2 }
        click("recovery-saved")
        rule.waitGoneTag("recovery-code")
        waitFor("确认后落盘") { sessions.session != null }
        runBlocking { model.account.listEvents() } // 队列不堵
    }

    @Test fun password_change_errors_stay_on_page() { // 第 13.4 节
        openConnected()
        go("/me/settings/security/password")
        type("password-old", "x")
        type("password-new", "y")
        for ((reply, text) in listOf(
            Reply.Err(401, 4018) to "密码错误",
            Reply.Err(400, 4000, "passwordWeak") to "密码太简单",
            Reply.Err(429, 4294, details = obj("retryAfterSeconds" to 600)) to "尝试次数过多，请 10 分钟后再试",
            Reply.NetworkFail to "网络异常，请稍后重试",
        )) {
            http.handlers["/v1/account/password"] = { reply }
            click("password-submit")
            rule.waitUntil(5000) { rule.hasTag("password-error") && textOf("password-error") == text }
        }
        assertEquals("/me/settings/security/password", model.nav.current.path)
    }

    @Test fun regenerate_recovery_code_goes_to_save_page_without_changing_token() { // F-ACC-05 规则 5
        openConnected()
        val token = sessions.session?.accessToken
        go("/me/settings/security")
        go("/me/settings/security/recovery")
        type("regenerate-password", "Good-pass-1")
        click("regenerate-submit")
        rule.waitTag("recovery-code")
        n("recovery-code").assertTextEquals("CODE-NEW-1")
        assertEquals(token, sessions.session?.accessToken)
        UiDevice.getInstance(inst).pressBack()
        rule.waitTag("recovery-code")
        click("recovery-saved")
        rule.waitTag("security-password")
        assertEquals(1, server.frames("auth").size, "token 不变，连接不动")
    }

    @Test fun tc_acc_02_13_security_notice_and_event_list() { // TC-ACC-02-13 的界面部分、AC-ACC-02-13（真实服务端在 AccountE2eTest）
        http.eventsBody = obj(
            "events" to listOf(obj("kind" to "newDeviceLogin", "at" to System.currentTimeMillis(), "platform" to "android"), obj("kind" to "futureKind", "at" to T0)),
            "otherSessions" to obj("count" to 2, "lastActiveAt" to T0),
        )
        openConnected()
        server.pushEvent("securityNotice", "kind" to "newDeviceLogin", "at" to System.currentTimeMillis(), "platform" to "android")
        rule.waitText("你的账号在新设备登录")
        go("/me/settings")
        click("settings-security")
        rule.waitTag("security-event")
        rule.waitText("新设备登录")
        rule.waitText("账号安全事件")
        assertFalse(rule.hasText("futureKind"))
        assertTrue("其他设备 2 台" in textOf("security-other-count"))
    }

    @Test fun security_events_failure_can_retry() { // 第 13.4 节
        http.handlers["/v1/account/events"] = { Reply.NetworkFail }
        openConnected()
        go("/me/settings/security")
        rule.waitTag("security-events-failed")
        http.handlers.remove("/v1/account/events")
        click("security-events-reload")
        rule.waitTag("security-events-empty")
    }

    @Test fun tc_acc_06_05_nickname_updates_me_and_name_lookup_at_once() { // TC-ACC-06-05 的本端，AC-ACC-06-5（其他端的 10 分钟由 SDK 的 F-SOC-10 用例验证）
        server.users["alice"] = obj("userId" to "alice", "nickname" to "旧昵称")
        openConnected()
        click("tab-me")
        rule.waitUntil(5000) { rule.hasTag("me-name") && textOf("me-name") == "旧昵称" }
        click("me-card")
        rule.waitTag("nickname-input")
        assertTrue("旧昵称" in textOf("nickname-input"))
        type("nickname-input", "汉".repeat(33))
        rule.waitTag("nickname-too-long")
        type("nickname-input", "新昵称")
        http.err("/v1/account/profile", 400, 4000)
        click("nickname-submit")
        rule.waitTag("nickname-error")
        http.handlers.remove("/v1/account/profile")
        click("nickname-submit")
        rule.waitUntil(5000) { rule.hasTag("me-name") && textOf("me-name") == "新昵称" }
        assertEquals("新昵称", http.to("/v1/account/profile").last().body["nickname"]?.jsonPrimitive?.content)
        assertEquals("新昵称", model.im?.nameOf("alice"))
        // 页面没有重新进入时也要刷新（聊天页就是这样）：靠 ProfileChanged，不靠回到"我"页时重取
        runBlocking { model.account.updateProfile(nickname = "再改一次") }
        rule.waitUntil(5000) { textOf("me-name") == "再改一次" }
    }

    @Test fun tc_acc_07_08_demo_has_no_security_or_nickname_account_has() { // TC-ACC-07-08、AC-ACC-07-8、AC-UI-15-3、AC-UI-16-6
        install()
        launch()
        rule.waitTag("tab-chats")
        go("/me/settings")
        rule.waitTag("settings-blocks")
        assertFalse(rule.hasTag("settings-security"))
        go("/me/settings/security") // deep link 进来也不行
        rule.waitTag("tab-chats")
        finish()
        openConnected()
        go("/me/settings")
        rule.waitTag("settings-security")
    }

    @Test fun tc_acc_08_06_delete_dialog_text_and_wrong_password() { // TC-ACC-08-06、AC-ACC-08-6
        http.err("/v1/account/delete", 401, 4018)
        openConnected()
        go("/me/settings/security")
        click("security-delete")
        rule.waitTag("delete-dialog")
        n("delete-text").assertTextEquals("7 天后不可恢复，用户名不能再使用，已发的消息会保留")
        type("delete-password", "wrong-pass")
        click("delete-submit")
        rule.waitTag("delete-error")
        n("delete-error").assertTextEquals("密码错误")
        assertTrue(rule.hasTag("delete-dialog"), "留在确认框")
        assertEquals(ConnState.CONNECTED, model.im?.state)
        http.handlers.remove("/v1/account/delete")
        type("delete-password", "Good-pass-1")
        click("delete-submit")
        rule.waitText(com.tianting.app.core.ACCOUNT_DELETION_REQUESTED)
        click("kicked-ok")
        rule.waitTag("account-username")
        waitFor("清除") { sessions.session == null }
    }

    @Test fun recovery_page_with_fatal_shows_dialog_on_top() { // 改密后的保存页上凭证失效：弹窗在上面，确定后回登录页
        http.err("/v1/account/recovery-code/ack", 400, 4000, "recoveryCodeStale")
        openConnected()
        startPasswordChange()
        rule.waitTag("recovery-code")
        click("recovery-saved")
        dialogThenLogin("恢复码已更新，请重新登录查看")
        assertFalse(rule.hasTag("recovery-code"))
    }

    @Test fun session_ended_before_connect_is_not_a_crash_and_shows_once() { // K7：start 的刷新先收到 4019，App 才走到连接
        http.err(refresh, 401, 4019, "revoked")
        sessions.session = http.session(expiresAt = System.currentTimeMillis() - hour)
        replaceModel(newDeps())
        launch()
        rule.waitTag("kicked-dialog")
        assertEquals(1, rule.onAllNodesWithTagCount("kicked-dialog"), "只有一个弹窗")
        click("kicked-ok")
        rule.waitTag("account-username")
        assertNeverWithin(500, "第二个弹窗") { rule.hasTag("kicked-dialog") }
    }

    @Test fun token_never_in_text_or_url() { // 第 13.1 节：token、refresh 不进 URL
        openConnected(http.session(expiresAt = System.currentTimeMillis() + 5 * hour))
        waitFor("刷新") { http.to(refresh).size == 1 }
        for (r in http.requests) {
            assertFalse("r1" in r.path || "." in r.path, r.path)
        }
        assertFalse(sessions.session.toString().contains(sessions.session!!.accessToken))
        assertEquals(SessionMode.ACCOUNT, sessions.session?.mode)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
