package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import com.tianting.sdk.ConvType
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/** F-UI-01、F-UI-03、deep link（3.6 节 AC-UI-01-3、AC-UI-01-7）。 */
class NavLoginTest : UiTest() {
    private fun okLogin(user: String = "alice") { loginApi = LoginApi { u, _ -> LoginResult.Ok(Session(u, "tok-$user")) } }

    @Test fun tabs_and_selection() { // AC-UI-01-1
        start()
        rule.waitTag("tab-chats")
        n("tab-chats").assertIsSelected()
        n("tab-me").assertIsNotSelected()
        click("tab-me")
        rule.waitTag("me-name")
        n("tab-me").assertIsSelected()
        click("tab-contacts")
        rule.waitTag("entry-requests")
        n("tab-contacts").assertIsSelected()
    }

    @Test fun deep_link_cold_start_goes_login_then_conversation() { // AC-UI-01-3
        install(loggedIn = false)
        okLogin()
        launch("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("login-username")
        assertFalse(rule.hasTag("chat-title"))
        type("login-username", "alice")
        click("login-submit")
        rule.waitTag("chat-title")
        n("chat-title").assertTextEquals("bob")
        assertEquals("/chat/c2c%3Aalice%3Abob", model.nav.current.path)
        // 返回键回到会话列表
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("tab-chats")
    }

    @Test fun deep_link_when_logged_in_opens_conversation() { // AC-UI-01-3
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("chat-title")
        n("chat-title").assertTextEquals("bob")
    }

    @Test fun deep_link_unknown_routes_go_to_chats() { // AC-UI-01-7
        for (bad in listOf("tianting://evil.com/x", "tianting://not-a-route", "tianting://chat/a/b/c/d")) {
            start(bad)
            rule.waitTag("tab-chats")
            assertEquals("/chats", model.nav.current.path, bad)
            finish()
        }
    }

    @Test fun logged_in_login_link_goes_to_chats() { // AC-UI-01-4
        start("tianting://login")
        rule.waitTag("tab-chats")
        assertFalse(rule.hasTag("login-username"))
    }

    @Test fun secondary_pages_hide_tab_bar() { // AC-UI-01-6
        start()
        rule.waitTag("tab-chats")
        for (p in listOf(chatPath(BOB_CONV), infoPath(BOB_CONV), "/me/settings", "/contacts/requests", userPath("bob"))) {
            go(p)
            rule.waitGoneTag("tab-chats")
            inst.runOnMainSync { model.nav.back() }
            rule.waitTag("tab-chats")
        }
    }

    @Test fun reselect_chats_scrolls_to_top() { // AC-UI-01-8
        server.convs = (1..30).map { convJson("c2c:alice:u$it", "u$it", updatedAt = T0 - it) }
        start()
        rule.waitTag("conv-list")
        n("conv-list").performScrollToIndex(20)
        rule.waitForIdle()
        assertFalse(rule.hasText("u1"))
        click("tab-chats")
        rule.waitText("u1", timeout = 1000)
    }

    @Test fun login_button_disabled_when_blank() { // AC-UI-03-1
        install(loggedIn = false)
        launch()
        rule.waitTag("login-submit")
        n("login-submit").assertIsNotEnabled()
        type("login-username", "   ")
        n("login-submit").assertIsNotEnabled()
        type("login-username", "alice")
        n("login-submit").assertIsEnabled()
    }

    @Test fun nickname_over_32_disables_login() { // AC-UI-03-8
        install(loggedIn = false)
        launch()
        type("login-username", "alice")
        type("login-nickname", "汉".repeat(33))
        txt("昵称最多 32 个字").assertExists()
        n("login-submit").assertIsNotEnabled()
        type("login-nickname", "汉".repeat(32))
        n("login-submit").assertIsEnabled()
    }

    @Test fun repeated_taps_send_one_request() { // AC-UI-03-3
        val calls = AtomicInteger()
        val gate = CompletableDeferred<LoginResult>()
        install(loggedIn = false)
        loginApi = LoginApi { _, _ -> calls.incrementAndGet(); gate.await() }
        launch()
        type("login-username", "alice")
        click("login-submit")
        n("login-submit").assertIsNotEnabled()
        n("login-submit").performClick()
        n("login-submit").performClick()
        rule.waitForIdle()
        assertEquals(1, calls.get())
        gate.complete(LoginResult.Failed("x"))
        rule.waitTag("login-error")
    }

    @Test fun invalid_account_format() { // AC-AUTH-04-4
        install(loggedIn = false)
        launch()
        type("login-username", "a b")
        click("login-submit")
        n("login-error").assertTextEquals("账号格式不正确")
    }

    @Test fun failure_messages_stay_on_login() { // AC-UI-03-4、AC-UI-03-5、AC-UI-43-1、AC-UI-43-2
        install(loggedIn = false)
        loginApi = LoginApi { _, _ -> parseLoginResponse(404, "") }
        launch()
        type("login-username", "alice")
        click("login-submit")
        rule.waitTag("login-error")
        n("login-error").assertTextEquals("登录服务未开启")
        loginApi = LoginApi { _, _ -> parseLoginResponse(403, """{"code":4034,"details":{"bannedUntil":null}}""") }
        click("login-submit")
        rule.waitText("账号已被封禁")
        val until = System.currentTimeMillis() + 86_400_000
        loginApi = LoginApi { _, _ -> parseLoginResponse(403, """{"code":4034,"details":{"bannedUntil":$until}}""") }
        click("login-submit")
        rule.waitText("账号已被封禁，解封时间：${formatDateTime(until)}")
        loginApi = LoginApi { _, _ -> LoginResult.Failed(DemoLoginApi.NETWORK_ERROR) }
        click("login-submit")
        rule.waitText("网络异常，请稍后重试")
        n("login-submit").assertIsEnabled()
    }

    @Test fun login_timeout_is_configured_to_10s() { // AC-UI-03-5：超时由 OkHttp callTimeout 执行，只检查配置值
        assertEquals(10_000L, DemoLoginApi.LOGIN_TIMEOUT_MS)
    }

    @Test fun parse_conv_id_strict_is_used_for_routes() { // AC-UI-33-13 的路由入口；用例本身由 SDK 跑 convid.json
        assertEquals(ConvType.C2C, com.tianting.sdk.parseConvIdStrict(BOB_CONV, "alice").convType)
    }
}
