package com.tianting.app

import com.tianting.app.core.ACCOUNT_DELETION_REQUESTED
import com.tianting.app.core.AccountPage
import com.tianting.app.core.LoginResult
import com.tianting.app.core.accountErrorText
import com.tianting.app.core.accountEventText
import com.tianting.app.core.tooManyAttemptsText
import com.tianting.app.core.Session
import com.tianting.app.core.bannedText
import com.tianting.app.core.chatPath
import com.tianting.app.core.decodeParam
import com.tianting.app.core.defaultMessage
import com.tianting.app.core.fatalText
import com.tianting.app.core.formatDateTime
import com.tianting.app.core.formatMonthDayTime
import com.tianting.app.core.formatTime
import com.tianting.app.core.messageFor
import com.tianting.app.core.mutedText
import com.tianting.app.core.needsDivider
import com.tianting.app.core.parseLoginResponse
import com.tianting.app.core.routeFromDeepLink
import com.tianting.app.core.safeRoute
import com.tianting.app.ui.avatarImageUrl
import com.tianting.app.ui.badgeText
import com.tianting.app.ui.pages.ChatRow
import com.tianting.app.ui.pages.buildRows
import com.tianting.app.ui.pages.inviteResultText
import com.tianting.app.ui.pages.memberErrorText
import com.tianting.app.ui.pages.permissionTips
import com.tianting.sdk.FatalReason
import com.tianting.sdk.GroupInviteFailure
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgBody
import com.tianting.sdk.MsgType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {
    @Test fun deep_links_only_known_routes() { // AC-UI-01-7
        assertEquals("/chat/c2c%3Aalice%3Abob", routeFromDeepLink("tianting", "chat", "/c2c%3Aalice%3Abob"))
        assertEquals("/me/settings/privacy", routeFromDeepLink("tianting", "me", "/settings/privacy"))
        assertEquals("/contacts/apply/bob", routeFromDeepLink("tianting", "contacts", "/apply/bob"))
        for ((auth, path) in listOf("evil.com" to "/x", "not-a-route" to "", "chat" to "/a/b/c", "login" to "", "" to "/chats", null to "/chats", "chat" to "/a%2Fb", "chat" to "/a%5Cb")) {
            assertEquals("/chats", routeFromDeepLink("tianting", auth, path), "$auth$path")
        }
        assertEquals("/chats", routeFromDeepLink("https", "chat", "/x"))
        assertEquals("/chats", routeFromDeepLink(null, "chat", "/x"))
    }

    @Test fun redirect_rules() { // AC-UI-01-7 同一组恶意地址
        for (bad in listOf("//evil.com", "https://evil.com", "/\\evil.com", "/not-a-route", "", null, "/login")) assertEquals("/chats", safeRoute(bad), "$bad")
        assertEquals("/contacts", safeRoute("/contacts?x=1"))
    }

    @Test fun paths_encode_and_decode() {
        val p = chatPath("c2c:a:b")
        assertEquals("/chat/c2c%3Aa%3Ab", p)
        assertEquals("c2c:a:b", decodeParam(p.removePrefix("/chat/")))
        assertEquals("a+b", decodeParam("a+b"))
        assertEquals("", decodeParam("%zz"))
    }
}

class ErrorsTest {
    /** AC-UI-19-3：errors.json 里每个 ws 通道的码都有非空文案。协议副本在 SDK 仓库（composite build 同级）。 */
    @Test fun every_ws_code_has_copy() {
        val sdk = File(System.getenv("TIANTING_SDK_DIR") ?: "../../tianting-sdk-kotlin")
        val f = File(sdk, "protocol/errors.json")
        assertTrue(f.isFile, "找不到 ${f.absolutePath}")
        val text = f.readText()
        val codes = Regex("\"code\"\\s*:\\s*(\\d{4})").findAll(text).map { it.groupValues[1].toInt() }.toSet()
        assertTrue(codes.size > 20)
        for (c in codes) assertTrue(defaultMessage(c).isNotBlank(), "$c")
    }

    @Test fun command_table() { // AC-UI-19-4、19-5、F-UI-19 规则 4
        assertEquals("今天的次数已用完，明天再试", messageFor(4292, "friendApply"))
        assertEquals("今天发起新聊天的次数已用完，请稍后再试", messageFor(4292, "send"))
        assertEquals("操作太频繁，请稍后再试", messageFor(4290, "setPrivacy"))
        assertEquals("发送太频繁，请稍后再试", messageFor(4290, "send"))
        assertEquals("对方已不在你的通讯录中", messageFor(4040, "friendRemark"))
        assertEquals("请先将对方移出黑名单", messageFor(4000, "friendApply"))
        assertEquals("当前不允许创建群聊", messageFor(4030, "groupCreate"))
        assertEquals("群成员已达上限", messageFor(4130, "groupInvite"))
        assertEquals("管理员最多 10 人", messageFor(4136, "groupSetAdmin"))
        assertEquals("当前版本过低，请刷新页面", messageFor(4001))
        assertEquals("请求出错", messageFor(4008))
        assertEquals("请求出错", messageFor(4999))
        assertEquals("服务异常，请稍后重试", messageFor(5999))
    }

    @Test fun kicked_copy() { // AC-UI-18-2、43-3、43-4
        assertEquals("登录设备过多，当前设备已下线", fatalText(FatalReason.Kicked("tooManyConnections", 4429)))
        assertEquals("登录已失效，请重新登录", fatalText(FatalReason.Kicked("tokenInvalid", 4010)))
        assertEquals("登录已过期，请重新登录", fatalText(FatalReason.Kicked("tokenExpired", 4011)))
        assertEquals("账号已被下线", fatalText(FatalReason.Kicked("banned", 4012)))
        assertEquals("账号已被封禁", fatalText(FatalReason.Kicked("suspended", 4012, null)))
        assertEquals("账号已被封禁，解封时间：${formatDateTime(1_800_000_000_000)}", fatalText(FatalReason.Kicked("suspended", 4012, 1_800_000_000_000)))
        assertEquals("账号已被下线", fatalText(FatalReason.Kicked("xyz", 4012)))
        assertEquals("账号已被下线", fatalText(FatalReason.Closed(4012, "")))
        assertEquals("登录设备过多，当前设备已下线", fatalText(FatalReason.Closed(4429, "")))
    }

    @Test fun muted_and_banned_copy() { // AC-UI-41-1、41-5、43-2
        assertEquals("你已被禁言", mutedText(null))
        assertEquals("你已被禁言，解除时间：${formatMonthDayTime(1_800_000_000_000)}", mutedText(1_800_000_000_000))
        assertEquals("账号已被封禁", bannedText(null))
    }

    @Test fun login_response() { // F-UI-03 规则 6、F-UI-43
        assertEquals(LoginResult.Failed("登录服务未开启"), parseLoginResponse(404, ""))
        assertEquals(LoginResult.Failed("账号已被封禁"), parseLoginResponse(403, """{"code":4034,"details":{"bannedUntil":null}}"""))
        assertEquals(LoginResult.Failed("账号已被封禁，解封时间：${formatDateTime(1_800_000_000_000)}"), parseLoginResponse(403, """{"code":4034,"details":{"bannedUntil":1800000000000}}"""))
        assertEquals(LoginResult.Ok(Session("alice", "t")), parseLoginResponse(200, """{"userId":"alice","token":"t"}"""))
        assertEquals(LoginResult.Failed("服务异常，请稍后重试"), parseLoginResponse(500, "not json"))
        assertEquals(LoginResult.Failed("账号格式不正确"), parseLoginResponse(400, """{"code":4004,"msg":"secret detail"}"""))
    }

    @Test fun invite_text() { // AC-UI-39-1、39-2，F-UI-39 规则 3
        val n = { u: String -> u.uppercase() }
        assertEquals("已邀请 2 人", inviteResultText(2, emptyList(), n, "me"))
        assertEquals("已邀请 2 人；EVE 不是你的好友，无法邀请", inviteResultText(2, listOf(GroupInviteFailure("eve", 4035)), n, "me"))
        val many = (1..5).map { GroupInviteFailure("u$it", 4035) }
        assertEquals("U1 不是你的好友，无法邀请；U2 不是你的好友，无法邀请；U3 不是你的好友，无法邀请；另有 2 人邀请失败", inviteResultText(0, many, n, "me"))
        assertEquals("你加入的群聊已达上限", memberErrorText(4135, "me", "groupCreate", n, "me"))
        assertEquals("BOB 加入的群聊已达上限", memberErrorText(4135, "bob", "groupCreate", n, "me"))
    }
}

class TimeTest {
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance(tz).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun formats() { // F-MSG-06 规则 4
        val now = at(2026, 11, 5, 12, 0)
        assertEquals("09:05", formatTime(at(2026, 11, 5, 9, 5), now, tz))
        assertEquals("昨天 23:59", formatTime(at(2026, 11, 4, 23, 59), now, tz))
        assertEquals("11-03 08:00", formatTime(at(2026, 11, 3, 8, 0), now, tz))
        assertEquals("2025-12-31 08:00", formatTime(at(2025, 12, 31, 8, 0), now, tz))
    }

    @Test fun divider_gap() { // C-33
        assertTrue(needsDivider(null, 1))
        assertEquals(false, needsDivider(0, 300_000))
        assertEquals(true, needsDivider(0, 300_001))
    }
}

class ChatRowsTest {
    private fun m(id: String, status: MessageStatus = MessageStatus.FAILED, ts: Long = 1000) =
        Message(clientMsgId = id, convId = "c", from = "me", type = MsgType.TEXT, body = MsgBody(text = id), ts = ts, status = status)

    @Test fun same_adjacent_tip_keeps_last() { // AC-UI-31-4
        val ms = listOf(m("a"), m("b"), m("c"))
        assertEquals(mapOf("c" to 4035), permissionTips(ms, mapOf("a" to 4035, "b" to 4035, "c" to 4035)))
    }

    @Test fun different_tips_kept_and_sent_message_drops_tip() { // F-UI-31 规则 3、重发成功后消失
        val ms = listOf(m("a"), m("b"), m("c", MessageStatus.SENT))
        assertEquals(mapOf("a" to 4035, "b" to 4036), permissionTips(ms, mapOf("a" to 4035, "b" to 4036, "c" to 4137)))
    }

    @Test fun rows_have_dividers_every_five_minutes() {
        val ms = listOf(m("a", MessageStatus.SENT, 0), m("b", MessageStatus.SENT, 60_000), m("c", MessageStatus.SENT, 60_000 + 300_001))
        val kinds = buildRows(ms, emptyMap()).map { it::class.simpleName }
        assertEquals(listOf("Divider", "Msg", "Msg", "Divider", "Msg"), kinds)
        assertTrue(buildRows(listOf(m("a")), mapOf("a" to 4035)).any { it is ChatRow.Permission })
    }
}

class AvatarRulesTest {
    @Test fun https_only() { // AC-UI-21-4、21-5
        assertEquals("https://a/b.png", avatarImageUrl("https://a/b.png"))
        for (bad in listOf("http://a/b.png", "", null, "ftp://a", "HTTP://a", "file:///etc/passwd", "content://x")) assertEquals(null, avatarImageUrl(bad), "$bad")
    }

    @Test fun badge() { // AC-UI-04-6
        assertEquals("5", badgeText(5))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }
}

/** AC-UI-90-3、AC-UI-90-5：PRD-ui 6.2 节的对比度和色相，读 design/tokens.json，算法和组合清单与 tianting-web 的 scripts/contrast.mjs 相同。 */
class TokensTest {
    private val tokens = Json.parseToJsonElement(File("../design/tokens.json").readText()).jsonObject
    private val colors = tokens["color"]!!.jsonObject

    private fun value(name: String, mode: String): String = colors[name]?.jsonObject?.get(mode)?.toString()?.trim('"') ?: error("token $name missing")

    private fun lum(hex: String): Double {
        val c = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) / 255.0 }.map { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    private fun contrast(a: String, b: String): Double {
        val (x, y) = listOf(lum(a), lum(b)).sortedDescending()
        return (x + 0.05) / (y + 0.05)
    }

    private val pairs = listOf(
        Triple("on.brand", "brand.fill", 4.5), Triple("on.brand", "brand.fill.pressed", 4.5), Triple("brand", "surface", 4.5), Triple("brand", "bg", 4.5),
        Triple("brand", "surface.selected", 4.5), Triple("text.primary", "surface", 4.5), Triple("text.primary", "bg", 4.5), Triple("text.secondary", "surface", 4.5),
        Triple("text.secondary", "bg", 4.5), Triple("text.secondary", "chat.bg", 4.5), Triple("text.secondary", "surface.selected", 4.5), Triple("text.tertiary", "surface", 4.5),
        Triple("text.tertiary", "bg", 4.5), Triple("text.tertiary", "chat.bg", 4.5), Triple("text.tertiary", "surface.selected", 4.5), Triple("bubble.self.text", "bubble.self.bg", 4.5),
        Triple("bubble.other.text", "bubble.other.bg", 4.5), Triple("text.secondary", "bubble.other.bg", 4.5), Triple("danger", "surface", 4.5), Triple("danger", "chat.bg", 3.0),
        Triple("on.badge", "badge.bg", 4.5), Triple("warning.text", "warning.bg", 4.5), Triple("border.input", "surface", 3.0), Triple("text.tertiary", "chat.bg", 3.0),
        Triple("border.input", "bg", 3.0), Triple("danger", "bg", 4.5),
    )

    @Test fun contrast_pairs() {
        for (mode in listOf("light", "dark")) for ((fg, bg, need) in pairs) {
            val r = contrast(value(fg, mode), value(bg, mode))
            assertTrue(r >= need, "$mode $fg / $bg = $r < $need")
        }
    }

    @Test fun brand_is_not_green() {
        for ((k, v) in colors) {
            if (!k.startsWith("brand") && !k.startsWith("bubble.self")) continue
            for (mode in listOf("light", "dark")) {
                val hex = v.jsonObject[mode].toString().trim('"')
                val rgb = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) / 255.0 }
                val max = rgb.max()
                val min = rgb.min()
                if (max == min) continue
                val d = max - min
                val l = (max + min) / 2
                val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
                val h = when (max) {
                    rgb[0] -> (rgb[1] - rgb[2]) / d + (if (rgb[1] < rgb[2]) 6 else 0)
                    rgb[1] -> (rgb[2] - rgb[0]) / d + 2
                    else -> (rgb[0] - rgb[1]) / d + 4
                } * 60
                assertTrue(!(h in 120.0..165.0 && s > 0.4), "$k $hex")
            }
        }
    }
}

class BubbleMenuPositionTest {
    private val window = androidx.compose.ui.unit.IntSize(1000, 2000)
    private val menu = androidx.compose.ui.unit.IntSize(200, 150)
    private fun place(anchorTop: Int, listTop: Int = 100) = com.tianting.app.ui.pages.BubbleMenuPosition(listTop, 10)
        .calculatePosition(androidx.compose.ui.unit.IntRect(100, anchorTop, 500, anchorTop + 80), window, androidx.compose.ui.unit.LayoutDirection.Ltr, menu)

    @Test fun above_when_room_below_when_not() { // AC-UI-09-6
        assertEquals(1000 - 150 - 10, place(1000).y)
        assertEquals(150 + 80 + 10, place(150).y)
        assertEquals(260 - 150 - 10, place(260).y) // 刚好放得下
    }

    @Test fun stays_inside_window() { // AC-UI-09-6
        val p = com.tianting.app.ui.pages.BubbleMenuPosition(1950, 10).calculatePosition(androidx.compose.ui.unit.IntRect(100, 1900, 500, 1990), window, androidx.compose.ui.unit.LayoutDirection.Ltr, menu)
        assertEquals(2000 - 150, p.y)
    }
}

class ReleaseConfigTest {
    @Test fun main_network_config_blocks_all_cleartext() { // AC-UI-21-5：debug / logcheck 的放开只在各自的源集
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue("cleartextTrafficPermitted=\"false\"" in xml)
        assertTrue("cleartextTrafficPermitted=\"true\"" !in xml)
        assertTrue("domain-config" !in xml)
    }
}

class AccountCopyTest {
    private val recoverAt = 1_800_000_000_000L
    private val dateText = com.tianting.sdk.sessionDialogText(com.tianting.sdk.SessionDialog.RECOVER_BEFORE, recoverAt)!!

    @Test fun session_fatal_uses_dialog_not_close_code() { // K7、AC-ACC-03-26：Session 继承 Closed，分支要在前面
        fun f(dialog: com.tianting.sdk.SessionDialog?, code: Int? = 4019, recoverBefore: Long? = null) =
            fatalText(com.tianting.sdk.FatalReason.Session(com.tianting.sdk.SessionEnd(com.tianting.sdk.SessionEndAction.LOGIN, dialog, recoverBefore, null, code)))
        assertEquals("检测到登录凭证异常，这台设备已下线。如果不是你本人操作，请尽快改密码", f(com.tianting.sdk.SessionDialog.CREDENTIAL_ANOMALY))
        assertEquals(dateText, f(com.tianting.sdk.SessionDialog.RECOVER_BEFORE, recoverBefore = recoverAt))
        assertEquals("登录已失效，请重新登录", f(com.tianting.sdk.SessionDialog.SESSION_EXPIRED))
        assertEquals("恢复码已更新，请重新登录查看", f(com.tianting.sdk.SessionDialog.RECOVERY_CODE_STALE, code = 4000))
        assertEquals("登录已失效，请重新登录", f(null, code = null)) // 没登录过的 K7
    }

    @Test fun session_fatal_other_actions() {
        assertEquals("账号已被封禁", fatalText(com.tianting.sdk.FatalReason.Session(com.tianting.sdk.SessionEnd(com.tianting.sdk.SessionEndAction.BAN_NOTICE, code = 4034))))
        assertEquals(ACCOUNT_DELETION_REQUESTED, fatalText(com.tianting.sdk.FatalReason.Session(com.tianting.sdk.SessionEnd(com.tianting.sdk.SessionEndAction.LOGIN, reason = "accountDeleted"))))
    }

    @Test fun kick_with_date_shows_date_without_date_shows_old_copy() { // AC-ACC-04-5、F-ACC-03 规则 6a
        assertEquals(dateText, fatalText(com.tianting.sdk.FatalReason.Kicked("passwordChanged", 4012, recoverBefore = recoverAt)))
        assertEquals(dateText, fatalText(com.tianting.sdk.FatalReason.Kicked("somethingNew", 4012, recoverBefore = recoverAt)))
        assertEquals("账号已被下线", fatalText(com.tianting.sdk.FatalReason.Kicked("passwordChanged", 4012)))
        assertEquals("账号已被下线", fatalText(com.tianting.sdk.FatalReason.Closed(4012, "x")))
        assertEquals("账号已被封禁", fatalText(com.tianting.sdk.FatalReason.Kicked("suspended", 4012, recoverBefore = recoverAt)))
    }

    @Test fun too_many_attempts_rounds_up() { // AC-ACC-02-9
        assertEquals("尝试次数过多，请 10 分钟后再试", tooManyAttemptsText(600))
        assertEquals("尝试次数过多，请 11 分钟后再试", tooManyAttemptsText(601))
        assertEquals("尝试次数过多，请 1 分钟后再试", tooManyAttemptsText(1))
        assertEquals("尝试次数过多，请 1 分钟后再试", tooManyAttemptsText(null))
    }

    @Test fun account_errors_never_show_server_msg() { // F-UI-19
        val e = com.tianting.sdk.AccountError(400, 4999, "ZXQ-SERVER-MSG")
        assertEquals("请求出错", accountErrorText(e, AccountPage.LOGIN))
        assertEquals("网络异常，请稍后重试", accountErrorText(com.tianting.sdk.AccountNetworkError("status 502"), AccountPage.LOGIN))
        assertEquals("网络异常，请稍后重试", accountErrorText(com.tianting.sdk.AccountTimeoutError(), AccountPage.REGISTER))
        assertEquals("登录已失效，请重新登录", accountErrorText(com.tianting.sdk.SessionEndedError(), AccountPage.PASSWORD_CONFIRM))
    }

    @Test fun error_4018_depends_on_page() {
        val e = com.tianting.sdk.AccountError(401, 4018, "x")
        assertEquals("用户名或密码错误", accountErrorText(e, AccountPage.LOGIN))
        assertEquals("用户名或恢复码错误", accountErrorText(e, AccountPage.RECOVER))
        assertEquals("密码错误", accountErrorText(e, AccountPage.PASSWORD_CONFIRM))
    }

    @Test fun unknown_event_kind_is_not_shown_raw() {
        assertEquals("账号安全事件", accountEventText("futureKind"))
        assertEquals("新设备登录", accountEventText("newDeviceLogin"))
    }

    @Test fun account_routes_are_known() {
        for (p in listOf("/me/nickname", "/me/settings/security", "/me/settings/security/password", "/me/settings/security/recovery", "/me/settings/security/others", "/me/settings/privacy-notice")) assertEquals(p, safeRoute(p))
    }
}
