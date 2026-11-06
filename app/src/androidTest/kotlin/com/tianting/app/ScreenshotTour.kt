package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.tianting.app.ui.theme.ThemePref
import java.io.File
import java.io.FileOutputStream

/** 不做断言的截图巡检：scripts/run-tests.sh shots 跑完后把 PNG 拉到 shots/，人工看一遍（浅色、深色）。 */
class ScreenshotTour : UiTest() {
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val dir = File(testContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val bmp = inst.uiAutomation.takeScreenshot()
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tour(prefix: String, pref: ThemePref) {
        server.apply {
            convs = listOf(
                convJson(BOB_CONV, "阿博", maxSeq = 6, unread = 3, lastMsg = lastMsg("bob", "晚上一起打球吗？", ts = System.currentTimeMillis())),
                convJson(G1, "周末球局", maxSeq = 6, unread = 120, memberCount = 12, myRole = "owner", lastMsg = lastMsg("carol", "周六 9 点集合", ts = System.currentTimeMillis() - 3600_000)),
                convJson("c2c:alice:kefu", "天听客服", maxSeq = 1, lastMsg = lastMsg("kefu", "有什么可以帮你？", ts = System.currentTimeMillis() - 90_000_000)),
                convJson("group:g2", "没有消息的群", memberCount = 3),
            )
            users["kefu"] = obj("userId" to "kefu", "nickname" to "天听客服", "official" to true)
            users["bob"] = obj("userId" to "bob", "nickname" to "阿博")
            users["alice"] = obj("userId" to "alice", "nickname" to "爱丽丝")
            val now = System.currentTimeMillis()
            messages[BOB_CONV] = listOf(
                msgJson(BOB_CONV, 1, "bob", "在吗？", ts = now - 7_200_000),
                msgJson(BOB_CONV, 2, "alice", "在的，什么事？", ts = now - 7_100_000),
                msgJson(BOB_CONV, 3, "bob", "晚上一起打球吗？老地方，七点半开始，带上你的新球拍，我们再叫上老王和小李，打完一起吃个宵夜。", ts = now - 600_000),
                deletedJson(BOB_CONV, 4, "bob", ts = now - 500_000),
                msgJson(BOB_CONV, 5, "bob", "", ts = now - 400_000, type = "custom"),
                msgJson(BOB_CONV, 6, "alice", "好的，到时候见", ts = now - 300_000),
            )
            messages[G1] = listOf(
                tipJson(G1, 1, "created", "alice", ts = now - 7_000_000),
                tipJson(G1, 2, "joined", "alice", listOf("bob", "carol"), ts = now - 6_900_000),
                msgJson(G1, 3, "bob", "大家好", ts = now - 6_000_000),
                msgJson(G1, 4, "carol", "周六 9 点集合", ts = now - 3_600_000),
                tipJson(G1, 5, "noticeUpdated", "bob", ts = now - 3_500_000),
                msgJson(G1, 6, "alice", "收到", ts = now - 3_400_000),
            )
            friends = listOf(friendJson("bob", "阿博"), friendJson("carol", "卡罗尔", "卡姐"), friendJson("zhangsan", "张三"), friendJson("kefu", "天听客服", official = true), friendJson("n1", "123"))
            requests = listOf(requestJson("dave", message = "我是 dave", ver = 3, updatedAt = now), requestJson("eve", status = "accepted", ver = 2, updatedAt = now - 1), requestJson("frank", "out", message = "球友", ver = 1, profile = null, updatedAt = now - 2))
            blocks = listOf(blockJson("mallory", profile = profileJson("mallory")))
            reply("groupInfo", groupInfoJson("owner", owner = "alice", notice = "周六 9 点在老地方集合，记得带水。", memberCount = 12, noticeBy = "alice", noticeAt = now - 3_500_000))
            reply("groupMembers", obj("members" to listOf(memberJson("alice", "owner", "爱丽丝"), memberJson("bob", "admin", "阿博"), memberJson("carol", nickname = "卡罗尔"), memberJson("dave"), memberJson("eve")), "nextCursor" to ""))
            reply("myGroups", obj("groups" to listOf(obj("groupId" to "g1", "name" to "周末球局", "memberCount" to 12, "joinedAt" to T0), obj("groupId" to "g2", "name" to "Alpha", "memberCount" to 3, "joinedAt" to T0)), "nextCursor" to ""))
            reply("userSearch", obj("user" to obj("userId" to "bob", "nickname" to "阿博", "avatar" to "", "official" to false, "relation" to "none")))
        }
        start()
        inst.runOnMainSync { model.setTheme(pref) }
        rule.waitUntil(5000) { model.im?.socialLoaded == true }
        rule.waitTag("conv-list")
        shot("$prefix-01-chats")
        click("plus-button"); shot("$prefix-02-plus"); inst.waitForIdleSync()
        androidx.test.uiautomator.UiDevice.getInstance(inst).pressBack()
        rule.waitForIdle()
        click("tab-contacts"); rule.waitTag("contacts-count"); shot("$prefix-03-contacts")
        click("tab-me"); rule.waitTag("me-name"); shot("$prefix-04-me")
        go(chatPath(BOB_CONV)); rule.waitText("好的，到时候见"); shot("$prefix-05-chat")
        click("emoji-button"); rule.waitTag("emoji-panel"); shot("$prefix-06-emoji"); click("emoji-button")
        type("composer-input", "这是一段草稿")
        inst.runOnMainSync { model.nav.resetTo(chatPath(G1)) }
        rule.waitText("收到"); shot("$prefix-07-group-chat")
        click("chat-more"); rule.waitTag("member-grid"); shot("$prefix-08-group-settings")
        go(membersPath(G1)); rule.waitTag("member-list"); shot("$prefix-09-members")
        inst.runOnMainSync { model.nav.back() }
        go(noticePath(G1)); rule.waitTag("notice-text"); shot("$prefix-10-notice")
        inst.runOnMainSync { model.nav.resetTo("/contacts/requests") }; rule.waitTag("request-row"); shot("$prefix-11-requests")
        inst.runOnMainSync { model.nav.resetTo(userPath("bob")) }; rule.waitTag("user-chat"); shot("$prefix-12-user")
        inst.runOnMainSync { model.nav.resetTo("/contacts/add") }
        type("search-input", "bob"); n("search-input").performImeAction(); rule.waitTag("search-card"); shot("$prefix-13-search")
        inst.runOnMainSync { model.nav.resetTo(applyPath("bob")) }; rule.waitTag("apply-send"); shot("$prefix-14-apply")
        inst.runOnMainSync { model.nav.resetTo("/me/settings") }; rule.waitTag("logout"); shot("$prefix-15-settings")
        inst.runOnMainSync { model.nav.resetTo("/me/settings/appearance") }; rule.waitTag("appearance-dark"); shot("$prefix-16-appearance")
        inst.runOnMainSync { model.nav.resetTo("/me/settings/privacy") }; rule.waitTag("privacy-switch"); shot("$prefix-17-privacy")
        inst.runOnMainSync { model.nav.resetTo("/groups/new") }; rule.waitTag("pick-row"); shot("$prefix-18-new-group")
        inst.runOnMainSync { model.nav.resetTo("/me") }; rule.waitTag("me-notices")
        server.pushFrame(obj("pushId" to "tour-1", "type" to "notice", "data" to obj("text" to "今晚 22 点系统维护，预计 30 分钟"), "ts" to System.currentTimeMillis() + 60_000, "target" to "all"))
        rule.waitTag("notice-banner"); rule.waitTag("me-notices-count"); shot("$prefix-22-notice-banner")
        inst.runOnMainSync { model.nav.resetTo("/me/notices") }; rule.waitTag("notice-item"); shot("$prefix-23-notices")
        inst.runOnMainSync { model.nav.resetTo(chatPath(BOB_CONV)) }; rule.waitTag("composer-input")
        server.pushEvent("muted", "scope" to "user", "mutedUntil" to System.currentTimeMillis() + 600_000)
        rule.waitTag("composer-disabled"); shot("$prefix-19-muted")
        server.pushEvent("kicked", "reason" to "suspended", "until" to System.currentTimeMillis() + 86_400_000)
        server.drop(4012)
        rule.waitTag("kicked-dialog"); shot("$prefix-20-banned")
        click("kicked-ok")
        rule.waitTag("login-username"); shot("$prefix-21-login")
    }

    /** FE-615、FE-616 的账号页面（AC-CHAT-90-9：深色模式截图）。 */
    private fun accountTour(prefix: String, pref: ThemePref) {
        http.demoLogin = false
        http.registerMode = "invite"
        http.privacy = obj("version" to "v1", "url" to "https://invalid.invalid/p")
        http.eventsBody = obj(
            "events" to listOf(
                obj("kind" to "newDeviceLogin", "at" to System.currentTimeMillis(), "platform" to "android"),
                obj("kind" to "passwordChanged", "at" to System.currentTimeMillis() - 86_400_000L, "platform" to "ios"),
                obj("kind" to "recoveryCodeRegenerated", "at" to System.currentTimeMillis() - 2 * 86_400_000L),
            ),
            "otherSessions" to obj("count" to 2, "lastActiveAt" to System.currentTimeMillis() - 3_600_000L),
        )
        server.users["alice"] = obj("userId" to "alice", "nickname" to "爱丽丝")
        install(loggedIn = false)
        inst.runOnMainSync { model.setTheme(pref) }
        launch()
        rule.waitTag("account-username"); shot("$prefix-30-login")
        type("account-username", "alice"); type("account-password", "wrong-pass")
        http.err("/v1/account/login", 401, 4018)
        click("account-submit"); rule.waitTag("account-error"); shot("$prefix-31-login-error")
        http.handlers.remove("/v1/account/login")
        click("login-to-recover"); rule.waitTag("recover-username"); shot("$prefix-32-recover")
        androidx.test.uiautomator.UiDevice.getInstance(inst).pressBack(); rule.waitTag("account-username")
        click("login-to-register"); rule.waitTag("register-username")
        type("register-username", "alice_1"); type("register-password", "Good-pass-1"); type("register-invite", "INVITE")
        http.err("/v1/account/register", 409, 4095)
        click("register-submit"); rule.waitTag("register-username-error"); shot("$prefix-33-register")
        http.handlers.remove("/v1/account/register")
        http.handlers["/v1/account/register"] = { FakeAccountHttp.Reply.Json(http.auth(code = "K7QF-3M9X-TZ2P-8HNB-5WRD-C4VY-6JGE-A1LS")) }
        click("register-submit"); rule.waitTag("recovery-code"); shot("$prefix-34-recovery")
        click("recovery-saved"); rule.waitTag("tab-chats")
        click("tab-me"); rule.waitTag("me-card"); shot("$prefix-35-me")
        go("/me/nickname"); rule.waitTag("nickname-input"); shot("$prefix-36-nickname")
        inst.runOnMainSync { model.nav.resetTo("/me/settings") }; rule.waitTag("settings-security"); shot("$prefix-37-settings")
        click("settings-security"); rule.waitTag("security-event"); shot("$prefix-38-security")
        click("security-delete"); rule.waitTag("delete-dialog"); shot("$prefix-39-delete")
        click("delete-cancel")
        go("/me/settings/security/password"); rule.waitTag("password-old"); shot("$prefix-40-password")
        inst.runOnMainSync { model.nav.back() }
        go("/me/settings/security/others"); rule.waitTag("others-password"); shot("$prefix-41-others")
        inst.runOnMainSync { model.nav.back() }
        server.pushEvent("kicked", "reason" to "passwordChanged", "recoverBefore" to System.currentTimeMillis() + 7 * 86_400_000L)
        server.drop(4012)
        rule.waitTag("kicked-dialog"); shot("$prefix-42-recover-date")
        click("kicked-ok"); rule.waitTag("account-username")
        http.handlers["/v1/config"] = { FakeAccountHttp.Reply.NetworkFail }
        inst.runOnMainSync { model.retryConfig() }
        rule.waitTag("config-error"); shot("$prefix-43-config-failed")
    }

    @Test fun account_light() = accountTour("light", ThemePref.LIGHT)

    @Test fun account_dark() = accountTour("dark", ThemePref.DARK)

    @Test fun light() = tour("light", ThemePref.LIGHT)

    @Test fun dark() = tour("dark", ThemePref.DARK)
}
