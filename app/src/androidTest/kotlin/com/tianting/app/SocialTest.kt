package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import com.tianting.sdk.ApplySource
import com.tianting.sdk.Profile
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb

/** 通讯录与社交、我 / 设置（F-UI-13～16、F-UI-25～30、F-UI-32）。 */
class SocialTest : UiTest() {
    private fun loaded() = rule.waitUntil(5000) { model.im?.socialLoaded == true }

    @Test fun contacts_groups_and_count() { // AC-UI-13-1、13-3、13-8
        server.friends = listOf(friendJson("bob"), friendJson("alice2", "alice2"), friendJson("zhangsan", "张三"), friendJson("n1", "123"), friendJson("n2", "😀abc"))
        start()
        click("tab-contacts")
        rule.waitTag("contacts-count")
        n("contacts-count").assertTextEquals("5 位朋友")
        val y = { g: String -> n("group-$g").getUnclippedBoundsInRoot().top.value }
        assertTrue(y("A") < y("B") && y("B") < y("Z") && y("Z") < y("#"), "分组顺序")
        n("contacts").performScrollToNode(hasText("张三"))
        assertTrue(txt("张三").getUnclippedBoundsInRoot().top.value > y("Z"))
    }

    @Test fun contacts_empty_and_skeleton() { // AC-UI-13-5、20-2
        server.hold("socialSync")
        start()
        click("tab-contacts")
        rule.waitTag("contacts-skeleton")
        assertFalse(rule.hasText("还没有朋友"))
        n("entry-requests").assertExists()
        n("entry-groups").assertExists()
        val f = server.frames("socialSync").first()
        server.handlers.remove("socialSync")
        server.push(obj("id" to f.id, "cmd" to "ack", "data" to obj("full" to true, "untilVer" to 1, "latestVer" to 1, "hasMore" to false, "cursor" to "", "friends" to emptyList<Any>(), "requests" to emptyList<Any>(), "blocks" to emptyList<Any>(), "requestSeenVer" to 0, "settings" to obj("searchable" to true), "mode" to "open", "strangerMsgLimit" to 0, "official" to false)))
        rule.waitTag("contacts-empty")
        txt("还没有朋友").assertExists()
        n("entry-requests").assertExists()
        click("contacts-empty-add")
        rule.waitTag("search-input")
    }

    @Test fun unseen_badges_and_seen_on_enter() { // AC-UI-13-6、25-1、25-5、25-8
        server.requests = listOf(requestJson("dave", ver = 3, updatedAt = T0 + 2), requestJson("carol", message = "我是 carol", ver = 2, updatedAt = T0 + 1), requestJson("eve", ver = 1, updatedAt = T0))
        server.requestSeenVer = 0
        server.reply("friendRequestsSeen", obj("requestSeenVer" to 100, "ver" to 100, "changed" to true))
        start()
        click("tab-contacts")
        rule.waitTag("requests-badge")
        n("requests-badge").assertAllTextContains("3")
        n("tab-contacts-badge").assertAllTextContains("3")
        click("entry-requests")
        rule.waitTag("request-row")
        val rows = rule.onAllNodesWithTag("request-row", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[DataUser] }
        assertEquals(listOf("dave", "carol", "eve"), rows)
        rule.waitText("请求添加你为朋友")
        rule.waitText("我是 carol")
        rule.waitUntil(3000) { server.frames("friendRequestsSeen").isNotEmpty() }
        inst.runOnMainSync { model.nav.back() }
        rule.waitGoneTag("requests-badge")
        assertFalse(rule.hasTag("tab-contacts-badge"))
    }

    @Test fun accept_reject_and_detail_sheet() { // AC-UI-25-2、25-3
        server.requests = listOf(requestJson("carol", message = "我是 carol", ver = 2, updatedAt = T0 + 1), requestJson("dave", ver = 1, source = "search"))
        start()
        go("/contacts/requests")
        rule.waitTag("request-accept")
        server.reply("friendRespond", socialAck(friends = listOf(friendJson("carol", ver = 201)), requests = listOf(requestJson("carol", status = "accepted", ver = 200))))
        rule.onAllNodesWithTag("request-accept", useUnmergedTree = true)[0].performClick()
        rule.waitText("已添加")
        // 点行打开底部菜单
        member("dave", "request-row", DataUser).performClick()
        rule.waitTag("request-sheet")
        n("request-source").assertTextEquals("通过搜索添加")
        txt("账号：dave").assertExists()
        server.reply("friendRespond", socialAck(ver = 300, requests = listOf(requestJson("dave", status = "rejected", ver = 300))))
        click("sheet-reject")
        rule.waitText("已拒绝")
    }

    @Test fun expired_and_out_requests() { // AC-UI-25-4、25-6
        server.requests = listOf(
            requestJson("old", expireAt = System.currentTimeMillis() - 1000, ver = 2, updatedAt = T0 + 5),
            requestJson("eve", "out", message = "", ver = 1, profile = null, updatedAt = T0),
        )
        start()
        go("/contacts/requests")
        rule.waitText("已过期")
        assertFalse(rule.hasTag("request-accept"))
        rule.waitText("等待验证")
        rule.waitText("等待对方验证")
        txt("eve").assertExists()
        assertFalse(rule.hasTag("tab-contacts-badge"))
    }

    @Test fun respond_expired_refreshes() { // AC-UI-25-7
        server.requests = listOf(requestJson("carol"))
        server.fail("friendRespond", 4045)
        start()
        go("/contacts/requests")
        click("request-accept")
        rule.waitText("该申请已过期")
    }

    @Test fun profile_header_and_actions() { // AC-UI-14-1、14-3、14-4、14-5、14-6
        server.friends = listOf(friendJson("bob", "阿博", "老板"))
        server.mode = "friends"
        server.reply("block", socialAck(blocks = listOf(blockJson("bob", ver = 200))))
        server.reply("unblock", socialAck(ver = 210, blocks = listOf(obj("userId" to "bob", "createdAt" to T0, "ver" to 210, "deleted" to true))))
        start()
        loaded()
        go(userPath("bob"))
        rule.waitTag("user-chat")
        n("user-name").assertTextEquals("老板")
        n("user-nickname").assertTextEquals("昵称：阿博")
        n("user-id").assertTextEquals("账号：bob")
        click("user-chat")
        rule.waitTag("chat-title")
        assertEquals(chatPath(BOB_CONV), model.nav.current.path)
        inst.runOnMainSync { model.nav.back() }
        // 删除确认框：好友制带第二句
        click("user-more")
        click("menu-delete-friend")
        rule.waitTag("delete-dialog")
        rule.waitText("删除后你们将无法继续私聊", substring = true)
        click("delete-dialog-cancel")
        // 拉黑
        click("user-more")
        click("menu-block")
        txt("加入黑名单后，你将不再收到对方的私聊消息和好友申请；在共同的群聊里仍能看到对方的发言").assertExists()
        click("block-dialog-ok")
        rule.waitTag("user-blocked")
        click("user-more")
        rule.waitTag("menu-unblock")
        assertFalse(rule.hasTag("menu-block"))
        click("menu-unblock")
        rule.waitGoneTag("user-blocked")
        // 删除朋友：停留在资料卡，变成"添加到通讯录"
        server.reply("friendDelete", socialAck(ver = 220, friends = listOf(obj("userId" to "bob", "ver" to 220, "deleted" to true))))
        click("user-more")
        click("menu-delete-friend")
        click("delete-dialog-ok")
        rule.waitTag("user-add")
        assertEquals(userPath("bob"), model.nav.current.path)
    }

    @Test fun delete_dialog_open_mode_no_extra_line() { // AC-UI-14-6
        server.friends = listOf(friendJson("bob"))
        start()
        loaded()
        go(userPath("bob"))
        click("user-more")
        click("menu-delete-friend")
        rule.waitTag("delete-dialog")
        assertFalse(rule.hasText("删除后你们将无法继续私聊", substring = true))
    }

    @Test fun relation_none_open_vs_friends_mode() { // AC-UI-14-7、14-10
        start()
        loaded()
        go(userPath("carol"))
        rule.waitTag("user-add")
        rule.waitTag("user-chat")
        n("user-name").assertTextEquals("carol") // 不可见的用户：只显示 id，不显示"用户不存在"
        assertFalse(rule.hasText("用户不存在"))
        finish()
        val s = FakeServer().apply { mode = "friends" }
        install(transport = s)
        launch()
        loaded()
        go(userPath("carol"))
        rule.waitTag("user-add")
        assertFalse(rule.hasTag("user-chat"))
    }

    @Test fun blocked_non_friend_has_no_add() { // AC-UI-14-8
        server.blocks = listOf(blockJson("eve"))
        start()
        loaded()
        go(userPath("eve"))
        rule.waitTag("user-blocked")
        n("user-blocked").assertTextEquals("已加入黑名单")
        assertFalse(rule.hasTag("user-add"))
    }

    @Test fun pending_in_accept_on_profile() { // AC-UI-14-9
        server.requests = listOf(requestJson("dave"))
        server.reply("friendRespond", socialAck(friends = listOf(friendJson("dave", ver = 201)), requests = listOf(requestJson("dave", status = "accepted", ver = 200))))
        start()
        loaded()
        go(userPath("dave"))
        click("user-accept")
        rule.waitUntil(5000) { rule.hasTag("user-chat") && !rule.hasTag("user-accept") }
    }

    @Test fun me_page_copy_and_no_edit() { // AC-UI-15-1、15-2、15-3
        server.users["alice"] = obj("userId" to "alice", "nickname" to "爱丽丝")
        start()
        click("tab-me")
        rule.waitText("爱丽丝")
        n("me-id").assertTextEquals("账号：alice")
        click("me-copy")
        rule.waitText("已复制")
        assertEquals("alice", copied.last())
        assertFalse(rule.hasText("编辑"))
    }

    @Test fun settings_order_and_logout_dialog() { // AC-UI-16-1、16-2、16-3、16-6（M4 有外观）
        server.convs = listOf(convJson(BOB_CONV, "bob"))
        server.fail("send", 5000)
        start()
        rule.waitTag("tab-chats")
        go("/me/settings")
        rule.waitTag("logout")
        val tops = listOf("settings-privacy", "settings-blocks", "settings-appearance", "about", "logout").map { n(it).getUnclippedBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops)
        click("logout")
        rule.waitTag("logout-dialog")
        txt("确定退出登录？").assertExists()
        click("logout-dialog-cancel")
        assertEquals(1, server.frames("auth").size)
        assertTrue(model.im != null)
        // 2 条失败消息
        go(chatPath(BOB_CONV))
        repeat(2) {
            type("composer-input", "fail$it")
            click("send-button")
            rule.waitText("fail$it")
        }
        waitFor("failed") { model.im!!.pendingCount == 2 && rule.onAllNodesWithTag("failed-icon", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("logout")
        click("logout")
        rule.waitText("有 2 条消息未发送，退出后将丢失")
        click("logout-dialog-ok")
        rule.waitTag("login-username")
        assertEquals(null, sessions.session) // AC-UI-16-3：token 存储里没有 token
    }

    @Test fun search_results_by_relation() { // AC-UI-26-1、26-3、26-4、26-5、26-6、26-7
        server.friends = listOf(friendJson("friend1"))
        server.blocks = listOf(blockJson("blocked1"))
        val user = { id: String, rel: String, official: Boolean -> obj("user" to obj("userId" to id, "nickname" to id, "avatar" to "", "official" to official, "relation" to rel)) }
        start()
        loaded()
        go("/contacts/add")
        type("search-input", "a b")
        n("search-input").performImeAction()
        n("search-error").assertTextEquals("账号格式不正确")
        assertTrue(server.frames("userSearch").isEmpty())
        for ((id, rel, text) in listOf(
            Triple("alice", "self", "这是你自己"),
            Triple("friend1", "friend", "发消息"),
            Triple("outp", "pendingOut", "已发送申请"),
            Triple("blocked1", "blocked", "已加入黑名单"),
        )) {
            server.reply("userSearch", user(id, rel, false))
            type("search-input", id)
            n("search-input").performImeAction()
            rule.waitText(text)
            assertFalse(rule.hasTag("search-add"), id)
        }
        rule.waitTag("search-profile")
        server.reply("userSearch", user("kefu", "none", true))
        type("search-input", "kefu")
        n("search-input").performImeAction()
        rule.waitTag("official")
        click("search-add")
        rule.waitTag("apply-send")
        assertEquals(ApplySource.SEARCH, model.nav.current.state.source)
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("search-input")
        server.fail("userSearch", 4292)
        n("search-input").performImeAction()
        rule.waitText("今天的次数已用完，明天再试")
        // 拦截：连续提交只发 1 次
        server.hold("userSearch")
        val before = server.frames("userSearch").size
        repeat(3) { n("search-input").performImeAction() }
        rule.waitForIdle()
        assertEquals(before + 1, server.frames("userSearch").size)
    }

    @Test fun not_found_is_uniform() { // AC-UI-26-2
        server.reply("userSearch", obj("user" to null))
        start()
        go("/contacts/add")
        type("search-input", "nobody")
        n("search-input").performImeAction()
        rule.waitText("未找到该用户")
        assertEquals("未找到该用户", textOf("search-result"))
    }

    @Test fun apply_form_defaults_and_limits() { // AC-UI-27-1、27-3、27-6、27-7、27-8
        server.reply("friendApply", socialAck(status = "pending", requests = listOf(requestJson("bob", "out", profile = null, ver = 200))))
        start()
        go(applyPath("bob"))
        rule.waitTag("apply-count")
        n("apply-count").assertTextEquals("7 / 50")
        rule.onNode(hasTestTag("apply-message") and hasText("我是alice"), useUnmergedTree = true).assertExists()
        type("apply-message", "字".repeat(51))
        n("apply-message-error").assertTextEquals("最多 50 个字")
        n("apply-send").assertIsNotEnabled()
        type("apply-message", "球友")
        type("apply-remark", "字".repeat(33))
        n("apply-remark-error").assertTextEquals("最多 32 个字")
        n("apply-send").assertIsNotEnabled()
        type("apply-remark", "B哥")
        server.hold("friendApply")
        click("apply-send")
        n("apply-send").assertIsNotEnabled()
        n("apply-send").performClick()
        rule.waitForIdle()
        assertEquals(1, server.frames("friendApply").size)
        val f = server.frames("friendApply").last().data
        assertEquals("other", f["source"]!!.jsonPrimitive.content)
        assertEquals("球友", f["message"]!!.jsonPrimitive.content)
        assertEquals("B哥", f["remark"]!!.jsonPrimitive.content)
    }

    @Test fun apply_results() { // AC-UI-27-2 的界面部分、27-4、27-5、27-8
        server.reply("friendApply", socialAck(status = "pending"))
        start()
        rule.waitTag("tab-chats")
        go(applyPath("nobody"))
        click("apply-send")
        rule.waitText("已发送")
        rule.waitTag("tab-chats")
        server.reply("friendApply", socialAck(ver = 300, status = "accepted", friends = listOf(friendJson("bob", ver = 300))))
        go(applyPath("bob"))
        click("apply-send")
        rule.waitText("已添加到通讯录")
        rule.waitTag("user-chat")
        assertEquals(userPath("bob"), model.nav.current.path)
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("tab-chats")
        go(applyPath("alice"))
        rule.waitText("不能添加自己")
        rule.waitTag("tab-chats")
        assertFalse(server.frames("friendApply").any { it.data["userId"]?.jsonPrimitive?.content == "alice" })
    }

    @Test fun remark_page() { // AC-UI-28-1 界面部分、28-2、28-3、28-4、28-5
        server.friends = listOf(friendJson("bob", "阿博", "旧备注"))
        start()
        loaded()
        go(userPath("bob"))
        rule.waitTag("user-remark")
        click("user-remark")
        rule.waitTag("remark-input")
        click("remark-done") // 没改
        rule.waitTag("user-name")
        assertTrue(server.frames("friendRemark").isEmpty())
        click("user-remark")
        type("remark-input", "字".repeat(33))
        n("remark-error").assertTextEquals("最多 32 个字")
        n("remark-done").assertIsNotEnabled()
        server.reply("friendRemark", socialAck(friends = listOf(friendJson("bob", "阿博", "", ver = 200))))
        type("remark-input", "  ")
        click("remark-done")
        rule.waitTag("user-name")
        rule.waitUntil(3000) { textOf("user-name") == "阿博" }
        assertEquals("", server.frames("friendRemark").last().data["remark"]!!.jsonPrimitive.content)
        server.fail("friendRemark", 4040)
        click("user-remark")
        type("remark-input", "新")
        click("remark-done")
        rule.waitText("对方已不在你的通讯录中")
        rule.waitTag("user-name")
    }

    @Test fun blocks_page() { // AC-UI-29-1 顺序部分、29-2 界面部分、29-3、20-3
        server.blocks = listOf(blockJson("bob", ver = 1, createdAt = T0, profile = profileJson("bob")), blockJson("eve", ver = 2, createdAt = T0 + 1))
        server.reply("unblock", socialAck(blocks = listOf(obj("userId" to "bob", "createdAt" to T0, "ver" to 200, "deleted" to true))))
        start()
        loaded()
        go("/me/settings")
        rule.waitTag("settings-blocks-count")
        n("settings-blocks-count").assertTextEquals("2")
        click("settings-blocks")
        rule.waitTag("block-row")
        assertEquals(listOf("eve", "bob"), rule.onAllNodesWithTag("block-row", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[DataUser] })
        rule.onNode(hasTestTag("block-remove") and hasAnyAncestor(tagged("block-row", DataUser, "bob")), useUnmergedTree = true).performClick()
        rule.waitText("已移出黑名单")
        rule.waitGoneText("bob")
    }

    @Test fun empty_lists() { // AC-UI-20-3
        server.reply("myGroups", obj("groups" to emptyList<Any>(), "nextCursor" to ""))
        start()
        go("/contacts/requests")
        rule.waitText("暂无新的朋友")
        inst.runOnMainSync { model.nav.back() }
        go("/me/settings/blocks")
        rule.waitText("黑名单为空")
        inst.runOnMainSync { model.nav.back() }
        go("/contacts/groups")
        rule.waitText("还没有加入群聊")
    }

    @Test fun privacy_switch() { // AC-UI-30-2、30-3
        server.hold("setPrivacy")
        start()
        loaded()
        go("/me/settings/privacy")
        rule.waitTag("privacy-switch")
        n("privacy-switch").assertIsOn()
        click("privacy-switch")
        n("privacy-switch").assertIsNotEnabled()
        val f = server.frames("setPrivacy").last()
        server.push(obj("id" to f.id, "cmd" to "ack", "error" to obj("code" to 4290, "msg" to "x")))
        rule.waitText("操作太频繁，请稍后再试")
        n("privacy-switch").assertIsOn()
        n("privacy-switch").assertIsEnabled()
    }

    @Test fun groups_list_sorted_and_retry() { // AC-UI-32-1、32-3
        server.fail("myGroups", 5000)
        start()
        rule.waitUntil(5000) { model.im?.state == com.tianting.sdk.ConnState.CONNECTED }
        go("/contacts/groups")
        rule.waitTag("groups-retry")
        txt("加载失败，点击重试").assertExists()
        server.reply("myGroups", obj("groups" to listOf(obj("groupId" to "g2", "name" to "周末球局", "memberCount" to 12, "joinedAt" to T0), obj("groupId" to "g1", "name" to "Alpha", "memberCount" to 3, "joinedAt" to T0)), "nextCursor" to ""))
        click("groups-retry")
        rule.waitTag("groups-count")
        n("groups-count").assertTextEquals("2 个群聊")
        assertEquals(listOf("g1", "g2"), rule.onAllNodesWithTag("group-row", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[DataUser] })
        txt("3 人").assertExists()
        txt("12 人").assertExists()
        member("g1", "group-row", DataUser).performClick()
        rule.waitTag("chat-title")
        assertEquals(chatPath("group:g1"), model.nav.current.path)
    }

    @Test fun kicked_dialog_texts() { // AC-UI-18-2、18-3、43-3、43-4
        server.convs = listOf(convJson(BOB_CONV, "bob"))
        server.friends = listOf(friendJson("bob"))
        start()
        rule.waitUntil(5000) { model.im?.state == com.tianting.sdk.ConnState.CONNECTED }
        server.pushEvent("kicked", "reason" to "tooManyConnections")
        server.drop(4429)
        rule.waitTag("kicked-dialog")
        txt("登录设备过多，当前设备已下线").assertExists()
        click("kicked-ok")
        rule.waitTag("login-username")
        assertEquals(null, sessions.session)
    }

    @Test fun kicked_suspended_texts() { // AC-UI-43-3、43-4
        for ((frame, code, text) in listOf(
            Triple(obj("reason" to "suspended", "until" to null), 4012, "账号已被封禁"),
            Triple(obj("reason" to "xyz"), 4012, "账号已被下线"),
        )) {
            val s = FakeServer()
            install(transport = s)
            launch()
            rule.waitUntil(5000) { model.im?.state == com.tianting.sdk.ConnState.CONNECTED }
            s.pushEvent("kicked", *frame.entries.map { it.key to it.value }.toTypedArray())
            s.drop(code)
            rule.waitTag("kicked-dialog")
            txt(text).assertExists()
            finish()
        }
    }

    @Test fun contacts_only_friends_and_live_updates() { // AC-UI-13-2、13-4、13-7
        server.friends = listOf(friendJson("bob", "bob"))
        server.convs = listOf(convJson("c2c:alice:carol", "carol"), convJson("c2c:alice:alice", "alice"))
        start()
        click("tab-contacts")
        rule.waitTag("contacts-count")
        n("contacts-count").assertTextEquals("1 位朋友")
        assertFalse(rule.hasText("carol"))
        assertFalse(rule.hasText("alice"))
        // carol 同意了我的申请：socialChanged 后出现在 C 组
        server.friends = listOf(friendJson("bob", "bob"), friendJson("carol", "carol", ver = 150))
        server.socialVer = 150
        server.pushEvent("socialChanged", "ver" to 150)
        rule.waitTag("group-C", 4000)
        // 备注"阿博"后移到 A 组
        server.reply("friendRemark", socialAck(ver = 200, friends = listOf(friendJson("bob", "bob", "阿博", ver = 200))))
        member("bob", "friend-row", DataUser).performClick()
        click("user-remark")
        type("remark-input", "阿博")
        click("remark-done")
        rule.waitTag("user-name")
        inst.runOnMainSync { model.nav.setRoot("/contacts") }
        rule.waitTag("group-A")
        assertFalse(rule.hasTag("group-B"))
        txt("阿博").assertExists()
    }

    @Test fun pinyin_groups_use_android_icu() { // AC-UI-13-9：真机 ICU 上跑 PRD 列出的用例（完整向量由 SDK 的 connected 测试跑）
        val c = com.tianting.sdk.android.AndroidPinyin.collator()
        assertTrue(c != null, "设备 ICU 不支持拼音排序")
        val cases = mapOf("张三" to "Z", "李四" to "L", "王五" to "W", "阿强" to "A", "欧阳" to "O", "白" to "B", "Alice" to "A", "zoe" to "Z", "123" to "#", "😀" to "#")
        for ((name, g) in cases) assertEquals(g, com.tianting.sdk.pinyinGroup(name, c), name)
    }

    @Test fun avatar_initials_and_colors_same_everywhere() { // AC-UI-21-1、21-2、21-3
        server.friends = listOf(friendJson("bob", "bob"), friendJson("zs", "张三"), friendJson("em", "😀abc"))
        server.convs = listOf(convJson(BOB_CONV, "bob"), convJson(G1, "群"))
        start()
        waitMember(BOB_CONV, "conv-item", DataConvId)
        val avatar = { conv: String -> rule.onNode(hasTestTag("avatar") and hasAnyAncestor(tagged("conv-item", DataConvId, conv)), useUnmergedTree = true) }
        val pixel = { node: SemanticsNodeInteraction -> node.captureToImage().asAndroidBitmap().let { it.getPixel(it.width / 2, 2) } }
        val corner = { node: SemanticsNodeInteraction -> node.captureToImage().asAndroidBitmap().getPixel(1, 1) }
        val listColor = pixel(avatar(BOB_CONV))
        assertEquals(com.tianting.app.ui.theme.AvatarPalette[com.tianting.sdk.avatarColorIndex("bob")].toArgb(), listColor)
        // 单聊圆形：左上角露出背景；群圆角方形：角上 1px 还看不到（圆角 10）但第 4px 已经是头像色
        assertTrue(corner(avatar(BOB_CONV)) != listColor, "单聊头像应该是圆形")
        val gb = avatar(G1).captureToImage().asAndroidBitmap()
        val gc = gb.getPixel(gb.width / 2, 2)
        assertEquals(com.tianting.app.ui.theme.AvatarPalette[com.tianting.sdk.avatarColorIndex("g1")].toArgb(), gc)
        val d = rule.density.density
        assertEquals(gc, gb.getPixel((5 * d).toInt(), (5 * d).toInt()), "群头像是圆角方形，不是圆")
        click("tab-contacts")
        rule.waitTag("friend-row")
        n("contacts").performScrollToNode(hasAnyDescendant(hasText("B")))
        val inContacts = rule.onNode(hasTestTag("avatar") and hasAnyAncestor(tagged("friend-row", DataUser, "bob")), useUnmergedTree = true)
        assertEquals(listColor, pixel(inContacts)) // 同一个 userId 颜色相同
        assertTrue(rule.hasText("B"))
        assertTrue(rule.hasText("张"))
        assertTrue(rule.hasText("😀"))
    }

    @Test fun group_outside_conv_list_can_open() { // AC-UI-32-4 界面部分
        server.reply("myGroups", obj("groups" to listOf(obj("groupId" to "g9", "name" to "不在列表的群", "memberCount" to 3, "joinedAt" to T0)), "nextCursor" to ""))
        server.messages["group:g9"] = listOf(msgJson("group:g9", 1, "bob", "历史消息"))
        start()
        rule.waitUntil(5000) { model.im?.state == com.tianting.sdk.ConnState.CONNECTED }
        go("/contacts/groups")
        waitMember("g9", "group-row", DataUser)
        member("g9", "group-row", DataUser).performClick()
        rule.waitText("历史消息")
        rule.waitTag("composer-input")
    }

    @Test fun command_error_table_applies() { // AC-UI-19-4、19-5
        assertEquals("今天的次数已用完，明天再试", messageFor(4292, "friendApply"))
        assertEquals("今天发起新聊天的次数已用完，请稍后再试", messageFor(4292, "send"))
        assertEquals("当前版本过低，请刷新页面", messageFor(4001, "whatever"))
        assertEquals("请求出错", messageFor(4008, "whatever"))
    }
}
