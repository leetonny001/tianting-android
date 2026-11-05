package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import androidx.compose.foundation.layout.Box
import com.tianting.app.ui.theme.ThemePref
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/** M4：群管理（F-UI-35～40）、草稿、表情、外观。 */
@OptIn(ExperimentalTestApi::class)
class M4Test : UiTest() {
    private fun loaded() = rule.waitUntil(5000) { model.im?.socialLoaded == true }
    private fun openInfo() {
        start()
        loaded()
        go(infoPath(G1))
        rule.waitTag("member-grid")
    }

    private fun assertEntries(present: List<String>, absent: List<String>) {
        for (t in present) n(t).performScrollTo().assertExists(t)
        for (t in absent) assertFalse(rule.hasTag(t), t)
    }

    @Test fun member_entries() { // AC-UI-36-1
        server.group("member")
        openInfo()
        rule.waitTag("grid-invite")
        assertEntries(listOf("grid-invite", "group-name", "group-notice", "group-leave"), listOf("grid-kick", "manage-admin", "manage-transfer", "group-dissolve"))
        assertEquals("false", n("group-name").fetchSemanticsNode().config[DataEditable])
    }

    @Test fun admin_entries() { // AC-UI-36-2
        server.group("admin")
        openInfo()
        rule.waitTag("grid-kick")
        assertEntries(listOf("grid-kick", "group-leave"), listOf("manage-admin", "manage-transfer", "group-dissolve"))
        assertEquals("true", n("group-name").fetchSemanticsNode().config[DataEditable])
    }

    @Test fun owner_entries_and_managed_owner() { // AC-UI-36-3
        server.group("owner", owner = "alice")
        openInfo()
        rule.waitTag("manage-admin")
        assertEntries(listOf("grid-invite", "grid-kick", "manage-admin", "manage-transfer", "group-dissolve"), emptyList())
        finish()
        val s = FakeServer().apply { group("owner", managed = true, owner = "alice") }
        install(transport = s)
        launch()
        loaded()
        go(infoPath(G1))
        rule.waitTag("manage-admin")
        assertFalse(rule.hasTag("group-dissolve"))
    }

    @Test fun managed_no_owner_member() { // AC-UI-36-4
        server.group("member", managed = true, owner = "")
        openInfo()
        rule.waitTag("group-leave")
        rule.waitTag("grid-member")
        assertEntries(listOf("group-name", "group-notice", "group-leave"), listOf("grid-invite", "grid-kick", "manage-admin", "group-dissolve"))
    }

    @Test fun leave_and_dissolve_texts() { // AC-UI-36-6 的确认框、36-7 的确认框
        server.group("owner", owner = "alice")
        server.reply("groupLeave", obj())
        openInfo()
        n("group-leave").performScrollTo()
        click("group-leave")
        rule.waitText("你是群主，退出后群主将自动转让给其他成员")
        click("leave-confirm-cancel")
        n("group-dissolve").performScrollTo()
        click("group-dissolve")
        rule.waitText("解散后，所有成员将被移出群聊，且不能恢复")
        click("dissolve-confirm-ok")
        rule.waitTag("tab-chats")
        assertEquals("g1", server.frames("groupDissolve").last().data["groupId"]!!.jsonPrimitive.content)
    }

    @Test fun rename_failure_refreshes_info() { // AC-UI-36-8
        server.group("admin")
        server.fail("groupUpdate", 4030)
        openInfo()
        rule.waitTag("group-name")
        val before = server.frames("groupInfo").size
        click("group-name")
        rule.waitTag("rename-input")
        type("rename-input", "新群名")
        click("rename-save")
        rule.waitText("无权限")
        waitFor("groupInfo again") { server.frames("groupInfo").size > before }
    }

    @Test fun title_follows_group_changed() { // AC-UI-06-8、36-5 界面部分
        server.convs = listOf(convJson(G1, "旧名", memberCount = 3))
        server.reply("groupInfo", groupInfoJson(name = "周六球局", memberCount = 4))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("chat-title")
        server.pushEvent("groupChanged", "groupId" to "g1")
        rule.waitUntil(3000) { textOf("chat-title") == "周六球局" && textOf("chat-title-count") == "（4）" }
    }

    @Test fun notice_member_readonly_and_edit_limit() { // AC-UI-37-2、37-3、37-1 界面部分
        server.group("member", notice = "周六 9 点集合")
        server.reply("groupInfo", groupInfoJson("member", notice = "周六 9 点集合", noticeBy = "bob", noticeAt = T0))
        start()
        go(noticePath(G1))
        rule.waitTag("notice-text")
        n("notice-text").assertTextEquals("周六 9 点集合")
        rule.waitText("bob", substring = true)
        n("notice-readonly").assertTextEquals("仅群主和管理员可编辑")
        assertFalse(rule.hasTag("notice-edit"))
        finish()
        val s = FakeServer().apply { group("owner", owner = "alice", notice = "") }
        install(transport = s)
        launch()
        go(noticePath(G1))
        rule.waitTag("notice-edit")
        rule.waitText("暂无群公告")
        click("notice-edit")
        type("notice-input", "字".repeat(1001))
        n("notice-count").assertTextEquals("已输入 1001 / 1000")
        n("notice-save").assertIsNotEnabled()
        type("notice-input", "新公告")
        s.handlers["groupInfo"] = { Reply.Data(groupInfoJson("owner", owner = "alice", notice = "新公告", noticeBy = "alice", noticeAt = T0)) }
        click("notice-save")
        rule.waitTag("notice-text")
        assertEquals("新公告", s.frames("groupUpdate").last().data["notice"]!!.jsonPrimitive.content)
    }

    @Test fun members_paging_and_labels_no_filter() { // AC-UI-38-1 界面部分、38-7
        val all = listOf(memberJson("bob", "owner")) + (1..120).map { memberJson("m$it") }
        server.convs = listOf(convJson(G1, "大群", memberCount = 121))
        server.reply("groupInfo", groupInfoJson(memberCount = 121))
        server.handlers["groupMembers"] = { f ->
            val c = f.data["cursor"]?.jsonPrimitive?.content?.toInt() ?: 0
            val page = all.drop(c).take(50)
            val next = c + page.size
            Reply.Data(obj("members" to page, "nextCursor" to if (next < all.size) next.toString() else ""))
        }
        start()
        go(membersPath(G1))
        rule.waitTag("member-list")
        rule.waitTag("member-role")
        n("member-role").assertTextEquals("群主")
        assertFalse(rule.hasTag("pick-search"))
        n("member-list").performScrollToKey("m50")
        rule.waitUntil(5000) { server.frames("groupMembers").size >= 2 }
        n("member-list").performScrollToKey("m99")
        rule.waitUntil(5000) { server.frames("groupMembers").size >= 3 }
        n("member-list").performScrollToKey("m120")
        rule.waitText("m120")
    }

    @Test fun admin_kick_mode_cannot_pick_managers() { // AC-UI-38-3
        val ms = listOf(memberJson("bob", "owner"), memberJson("carol", "admin"), memberJson("alice", "admin"), memberJson("dave"))
        server.group("admin", members = ms)
        server.reply("groupKick", obj("removed" to listOf("dave")))
        start()
        loaded()
        go(membersPath(G1), RouteState(mode = MemberMode.KICK))
        rule.waitUntil(5000) { rule.hasTag("kick-submit") }
        rule.waitTag("member-check")
        member("bob", "member-row", DataUser).performClick()
        member("carol", "member-row", DataUser).performClick()
        rule.waitForIdle()
        n("kick-submit").assertIsNotEnabled()
        member("dave", "member-row", DataUser).performClick()
        rule.waitUntil(2000) { textOf("kick-submit") == "移出（1）" }
        click("kick-submit")
        rule.waitText("确定将 1 人移出群聊？")
        click("kick-confirm-ok")
        waitFor("kick") { server.frames("groupKick").isNotEmpty() }
        assertEquals(listOf("dave"), (server.frames("groupKick").last().data["members"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test fun set_admin_error_and_transfer() { // AC-UI-38-6、38-5 确认框
        val ms = listOf(memberJson("alice", "owner"), memberJson("dave"))
        server.group("owner", owner = "alice", members = ms)
        server.fail("groupSetAdmin", 4136)
        start()
        loaded()
        go(membersPath(G1), RouteState(mode = MemberMode.ADMIN))
        rule.waitUntil(5000) { rule.hasText("设置管理员") }
        waitMember("dave", "member-row", DataUser)
        member("dave", "member-row", DataUser).performClick()
        rule.waitText("管理员最多 10 人")
        inst.runOnMainSync { model.nav.back() }
        go(membersPath(G1), RouteState(mode = MemberMode.TRANSFER))
        rule.waitUntil(5000) { rule.hasText("转让群主") }
        waitMember("dave", "member-row", DataUser)
        member("dave", "member-row", DataUser).performClick()
        rule.waitText("确定将群主转让给 dave？转让后你将成为普通成员")
    }

    @Test fun owner_kicks_two_and_set_admin_toggles() { // AC-UI-38-2、38-4 界面部分
        val ms = mutableListOf(memberJson("alice", "owner"), memberJson("bob"), memberJson("carol"), memberJson("dave"))
        server.group("owner", owner = "alice", members = ms)
        server.handlers["groupMembers"] = { Reply.Data(obj("members" to ms.toList(), "nextCursor" to "")) }
        server.handlers["groupKick"] = { ms.removeAll { it["userId"]!!.jsonPrimitive.content in setOf("bob", "carol") }; Reply.Data(obj("removed" to listOf("bob", "carol"))) }
        server.handlers["groupSetAdmin"] = { ms[ms.indexOfFirst { it["userId"]!!.jsonPrimitive.content == "dave" }] = memberJson("dave", "admin"); Reply.Data(obj()) }
        start()
        loaded()
        go(membersPath(G1), RouteState(mode = MemberMode.KICK))
        waitMember("bob", "member-row", DataUser)
        rule.waitTag("member-check")
        member("bob", "member-row", DataUser).performClick()
        member("carol", "member-row", DataUser).performClick()
        rule.waitUntil(2000) { textOf("kick-submit") == "移出（2）" }
        click("kick-submit")
        rule.waitText("确定将 2 人移出群聊？")
        click("kick-confirm-ok")
        rule.waitGoneText("bob")
        assertFalse(rule.hasText("carol"))
        inst.runOnMainSync { model.nav.back() }
        go(membersPath(G1), RouteState(mode = MemberMode.ADMIN))
        waitMember("dave", "member-row", DataUser)
        member("dave", "member-row", DataUser).performClick()
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("member-role") and hasText("管理员") and hasAnyAncestor(tagged("member-row", DataUser, "dave")), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    }

    @Test fun new_group_rules() { // AC-UI-35-2、35-3、35-4、35-5
        server.friends = listOf(friendJson("bob"), friendJson("bobby"), friendJson("carol"))
        start()
        loaded()
        click("plus-button")
        click("plus-new-group")
        rule.waitTag("pick-row")
        n("pick-done").assertIsNotEnabled()
        member("carol", "pick-row", DataUser).performClick()
        rule.waitUntil(2000) { textOf("pick-done") == "完成（1）" }
        n("pick-done").assertIsNotEnabled()
        type("pick-search", "BO")
        rule.waitUntil(2000) { rule.onAllNodesWithTag("pick-row", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        member("bob", "pick-row", DataUser).performClick()
        rule.waitUntil(2000) { textOf("pick-done") == "完成（2）" }
        type("pick-search", "")
        // 4035：标红
        server.fail("groupCreate", 4035, userId = "carol")
        click("pick-done")
        click("group-create")
        rule.waitText("carol 不是你的好友，无法邀请")
        rule.waitUntil(2000) { rule.onAllNodes(hasTestTag("pick-row") and SemanticsMatcher.expectValue(DataError, "true"), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        // 4030
        server.fail("groupCreate", 4030)
        click("pick-done")
        click("group-create")
        rule.waitText("当前不允许创建群聊")
        // 成功：进入新群
        server.reply("groupCreate", obj("groupId" to "g7", "conv" to convJson("group:g7", "bob、carol", memberCount = 3, myRole = "owner")))
        click("pick-done")
        click("group-create")
        rule.waitTag("chat-title")
        assertEquals(chatPath("group:g7"), model.nav.current.path)
        n("chat-title-count").assertTextEquals("（3）")
    }

    @Test fun invite_rules() { // AC-UI-39-1 文案、39-2、39-3、39-4
        server.friends = (1..52).map { friendJson("f$it") } + friendJson("bob")
        server.group("member", members = listOf(memberJson("bob", "owner"), memberJson("alice")))
        start()
        loaded()
        go(invitePath(G1))
        rule.waitTag("pick-list")
        n("pick-list").performScrollToKey("p-bob")
        rule.waitTag("pick-joined")
        member("bob", "pick-row", DataUser).performClick()
        rule.waitForIdle()
        assertEquals("完成（0）", textOf("pick-done"))
        inst.runOnMainSync { }
        for (i in 1..50) {
            n("pick-list").performScrollToKey("p-f$i")
            member("f$i", "pick-row", DataUser).performClick()
        }
        rule.waitUntil(3000) { textOf("pick-done") == "完成（50）" }
        n("pick-list").performScrollToKey("p-f51")
        member("f51", "pick-row", DataUser).performClick()
        rule.waitText("一次最多邀请 50 人")
        assertEquals("完成（50）", textOf("pick-done"))
        server.reply("groupInvite", obj("added" to listOf("f2", "f3"), "failed" to listOf(obj("userId" to "f1", "code" to 4035))))
        click("pick-done")
        rule.waitText("已邀请 2 人；f1 不是你的好友，无法邀请")
    }

    @Test fun tips_render() { // AC-UI-40-1、40-2、40-3、40-4、40-5
        server.convs = listOf(convJson(G1, "群", maxSeq = 6, memberCount = 3))
        server.friends = listOf(friendJson("carol", "carol", "卡姐"))
        server.messages[G1] = listOf(
            tipJson(G1, 1, "joined", "bob", listOf("carol", "dave")),
            tipJson(G1, 2, "ownerChanged"),
            tipJson(G1, 3, "created", "alice"),
            tipJson(G1, 4, "joined", "bob", (1..20).map { "u$it" }, count = 35),
            tipJson(G1, 5, "futureKind", "bob"),
            tipJson(G1, 6, "noticeUpdated", "bob"),
        )
        start("tianting://chat/group%3Ag1")
        rule.waitTag("tip")
        rule.waitText("bob 邀请 卡姐、dave 加入了群聊")
        txt("群主已退出群聊").assertExists()
        txt("你创建了群聊").assertExists()
        txt("bob 邀请 u1、u2、u3 等 35 人 加入了群聊").assertExists()
        txt("[群通知]").assertExists()
        assertFalse(rule.hasTag("msg-avatar"))
        assertFalse(rule.hasTag("bubble"))
        txt("[群通知]").performTouchInput { longClick() }
        assertNeverWithin(600, "menu") { rule.hasTag("bubble-menu") }
        txt("bob 更新了群公告").performClick()
        rule.waitTag("notice-skeleton", 3000)
    }

    @Test fun drafts() { // AC-UI-24-1、24-2、24-3、24-4
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", "原消息"), updatedAt = T0 + 2), convJson("c2c:alice:carol", "carol", maxSeq = 1, lastMsg = lastMsg("carol", "卡罗尔的", ts = T0 + 1), updatedAt = T0 + 1))
        start()
        waitMember("c2c:alice:carol", "conv-item", DataConvId)
        member("c2c:alice:carol", "conv-item", DataConvId).performClick()
        type("composer-input", "明天见")
        inst.runOnMainSync { model.nav.back() }
        rule.waitText("[草稿] 明天见")
        // 不影响排序
        val order = { rule.onAllNodesWithTag("conv-item", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[DataConvId] } }
        assertEquals(listOf(BOB_CONV, "c2c:alice:carol"), order())
        // 新消息置顶，摘要仍显示草稿
        server.pushMsg(msgJson("c2c:alice:carol", 2, "carol", "新消息", ts = System.currentTimeMillis()))
        rule.waitUntil(3000) { order().first() == "c2c:alice:carol" }
        assertTrue(rule.hasText("[草稿] 明天见"))
        // 回到会话恢复
        member("c2c:alice:carol", "conv-item", DataConvId).performClick()
        rule.waitUntil(3000) { textOf("composer-input") == "明天见" }
        type("composer-input", "")
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("conv-list")
        rule.waitGoneText("[草稿] 明天见")
        // 退出登录清空
        member(BOB_CONV, "conv-item", DataConvId).performClick()
        type("composer-input", "留着")
        inst.runOnMainSync { model.nav.back() }
        rule.waitText("[草稿] 留着")
        inst.runOnMainSync { model.logout() }
        rule.waitTag("login-username")
        loginApi = LoginApi { u, _ -> LoginResult.Ok(Session(u, "t")) }
        type("login-username", "alice")
        click("login-submit")
        waitMember(BOB_CONV, "conv-item", DataConvId)
        assertFalse(rule.hasText("[草稿]", substring = true))
    }

    @Test fun emoji_insert_at_cursor() { // AC-UI-11-1、11-4
        assertTrue(EMOJI.size >= 80)
        assertTrue(EMOJI.all { it.codePointAt(0) <= 0x1FAFF && '‍' !in it && it.codePointCount(0, it.length) == 1 })
        start("tianting://chat/c2c%3Aalice%3Abob")
        type("composer-input", "你好世界")
        n("composer-input").performTextInputSelection(androidx.compose.ui.text.TextRange(2))
        click("emoji-button")
        rule.waitTag("emoji-panel")
        rule.onAllNodesWithTag("emoji-item", useUnmergedTree = true)[0].performClick()
        rule.waitUntil(2000) { textOf("composer-input") == "你好😀世界" }
        val sel = n("composer-input").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertEquals(androidx.compose.ui.text.TextRange(4), sel)
        click("emoji-button")
        rule.waitGoneTag("emoji-panel")
    }

    @Test fun theme_pref_persisted_and_survives_logout() { // AC-UI-22-5、16-5 的原生等价
        start()
        go("/me/settings/appearance")
        click("appearance-dark")
        assertEquals(ThemePref.DARK, model.themePref)
        val prefs = testContext.getSharedPreferences(UiPrefs.FILE, android.content.Context.MODE_PRIVATE)
        waitFor("saved") { prefs.getString(UiPrefs.THEME, null) == "dark" }
        click("appearance-light")
        waitFor("saved") { prefs.getString(UiPrefs.THEME, null) == "light" }
        inst.runOnMainSync { model.logout() }
        rule.waitTag("login-username")
        assertEquals("light", prefs.getString(UiPrefs.THEME, null))
    }
}
