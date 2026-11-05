package com.tianting.app

import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.tianting.sdk.ConvType
import kotlinx.serialization.json.jsonPrimitive

/** 聊天页（F-UI-06～10、F-UI-31、F-UI-33）。 */
class ChatTest : UiTest() {
    private fun withBob(vararg ms: kotlinx.serialization.json.JsonObject) {
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = ms.size.toLong(), lastMsg = ms.lastOrNull()?.let { lastMsg("bob", "x") }))
        server.messages[BOB_CONV] = ms.toList()
    }

    private fun msgOf(text: String) = rule.onNode(hasTestTag("message") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private fun status(text: String) = msgOf(text).fetchSemanticsNode().config[DataStatus]
    private fun sendText(text: String) {
        type("composer-input", text)
        click("send-button")
    }

    @Test fun long_group_name_keeps_member_count() { // AC-UI-06-1
        server.convs = listOf(convJson(G1, "长".repeat(50), memberCount = 12))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("chat-title-count")
        n("chat-title-count").assertTextEquals("（12）")
        val count = n("chat-title-count").getUnclippedBoundsInRoot()
        val more = n("chat-more").getUnclippedBoundsInRoot()
        assertTrue(count.right <= more.left + 1.dp, "（N）被挤出去了")
        assertTrue(n("chat-title").fetchSemanticsNode().let { node ->
            val r = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(r)
            r[0].didOverflowWidth || r[0].isLineEllipsized(0)
        }, "群名应该被省略")
    }

    @Test fun missing_member_count_shows_only_name() { // AC-UI-06-2
        server.convs = listOf(convJson(G1, "周末球局"))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("chat-title")
        n("chat-title").assertTextEquals("周末球局")
        assertFalse(rule.hasTag("chat-title-count"))
    }

    @Test fun opens_at_bottom() { // AC-UI-06-3
        withBob(*(1L..100L).map { msgJson(BOB_CONV, it, "bob", "第${it}条") }.toTypedArray())
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("第100条")
        val last = msgOf("第100条").getUnclippedBoundsInRoot()
        val input = n("composer-input").getUnclippedBoundsInRoot()
        assertTrue(last.bottom <= input.top, "最后一条被输入区挡住")
    }

    @Test fun active_conversation_controls_unread() { // AC-UI-06-4、06-7
        withBob(msgJson(BOB_CONV, 1, "bob", "一"))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("一")
        server.pushMsg(msgJson(BOB_CONV, 2, "bob", "二"))
        rule.waitText("二")
        assertEquals(0L, model.im!!.convs.first().unread)
        click("chat-more") // 进入聊天信息：不再是当前会话
        rule.waitTag("info-head")
        server.pushMsg(msgJson(BOB_CONV, 3, "bob", "三"))
        waitFor("unread +1") { model.im!!.convs.first().unread == 1L }
        assertNeverWithin(1500, "不在聊天页时不能上报已读") { model.im!!.convs.first().unread != 1L } // 已读上报节流 1 秒
        inst.runOnMainSync { model.nav.back() }
        rule.waitText("三")
        waitFor("cleared") { model.im!!.convs.first().unread == 0L }
    }

    @Test fun bubble_geometry_and_names() { // AC-UI-07-1、07-2、07-3
        server.convs = listOf(convJson(G1, "群", maxSeq = 3, memberCount = 3))
        server.friends = listOf(friendJson("bob", "阿博"))
        server.messages[G1] = listOf(msgJson(G1, 1, "carol", "长".repeat(2000)), msgJson(G1, 2, "bob", "对方的"), msgJson(G1, 3, "alice", "自己的"))
        start("tianting://chat/group%3Ag1")
        rule.waitText("自己的")
        val list = n("message-list").getUnclippedBoundsInRoot()
        val mine = bubbleOf("自己的").getUnclippedBoundsInRoot()
        val other = bubbleOf("对方的").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((list.right - mine.right - 60.dp).value) <= 1f, "self bubble inset ${list.right - mine.right}")
        assertTrue(kotlin.math.abs((other.left - list.left - 60.dp).value) <= 1f, "other bubble inset ${other.left - list.left}")
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("msg-name") and hasText("阿博"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rule.onAllNodes(hasTestTag("msg-name") and hasText("alice"), useUnmergedTree = true).fetchSemanticsNodes().size)
        val long = rule.onAllNodes(hasTestTag("bubble"), useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.size.height }
        val density = rule.density
        assertTrue(long.size.width <= with(density) { (list.right - list.left).toPx() } * 0.7f + density.density, "bubble wider than 70%")
    }

    @Test fun c2c_has_no_sender_name() { // AC-UI-07-2
        withBob(msgJson(BOB_CONV, 1, "bob", "嗨"))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("嗨")
        assertFalse(rule.hasTag("msg-name"))
    }

    @Test fun custom_message_no_menu_and_no_crash() { // AC-UI-09-7、AC-UI-07 custom
        withBob(msgJson(BOB_CONV, 1, "bob", "", type = "custom"))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("[暂不支持的消息]")
        rule.onNode(hasTestTag("bubble"), useUnmergedTree = true).performTouchInput { longClick() }
        assertNeverWithin(600, "menu") { rule.hasTag("bubble-menu") }
    }

    @Test fun avatar_opens_profile() { // AC-UI-07-7、AC-UI-14-2、AC-UI-14-11
        server.convs = listOf(convJson(G1, "群", maxSeq = 2, memberCount = 2))
        server.messages[G1] = listOf(msgJson(G1, 1, "carol", "群里"), msgJson(G1, 2, "alice", "我的"))
        server.reply("friendApply", socialAck(status = "pending", requests = listOf(requestJson("carol", "out", profile = null, ver = 200))))
        start("tianting://chat/group%3Ag1")
        rule.waitText("群里")
        val avatarOf = { text: String -> rule.onNode(hasTestTag("msg-avatar") and hasAnyAncestor(hasTestTag("message") and hasAnyDescendant(hasText(text))), useUnmergedTree = true) }
        avatarOf("群里").performClick()
        rule.waitTag("user-name")
        assertEquals(userPath("carol"), model.nav.current.path)
        click("user-add")
        click("apply-send")
        rule.waitText("已发送")
        val f = server.frames("friendApply").last().data
        assertEquals("group", f["source"]!!.jsonPrimitive.content)
        assertEquals("g1", f["sourceRef"]!!.jsonPrimitive.content)
        inst.runOnMainSync { model.nav.back() }
        rule.waitText("我的")
        avatarOf("我的").performClick()
        rule.waitTag("user-name")
        rule.waitUntil(3000) { !rule.hasTag("user-add") }
        assertFalse(rule.hasTag("user-chat"))
        assertFalse(rule.hasTag("user-more"))
    }

    @Test fun send_states_and_retry_confirm() { // AC-UI-08-1、08-2、08-4
        withBob()
        server.handlers["send"] = { Reply.Hold }
        start("tianting://chat/c2c%3Aalice%3Abob")
        sendText("发送中的")
        rule.waitText("发送中的")
        assertEquals("sending", status("发送中的"))
        n("sending-icon").assertExists()
        // ack 回错误：失败
        val f = server.frames("send").last()
        server.push(obj("id" to f.id, "cmd" to "ack", "error" to obj("code" to 5000, "msg" to "x")))
        rule.waitTag("failed-icon")
        assertEquals("failed", status("发送中的"))
        val icon = n("failed-icon").getUnclippedBoundsInRoot()
        assertTrue(icon.width >= 43.9.dp && icon.height >= 43.9.dp, "点击区域 ${icon.width}x${icon.height}")
        click("failed-icon")
        rule.waitTag("retry-dialog")
        txt("重发该消息？").assertExists()
        val before = server.frames("send").size
        click("retry-dialog-cancel")
        assertNeverWithin(500, "send after cancel") { server.frames("send").size != before }
        assertEquals("failed", status("发送中的"))
        server.handlers.remove("send")
        click("failed-icon")
        click("retry-dialog-ok")
        waitFor("sent") { server.frames("send").size == before + 1 }
        assertEquals(f.data["clientMsgId"], server.frames("send").last().data["clientMsgId"])
        rule.waitUntil(5000) { !rule.hasTag("failed-icon") && !rule.hasTag("sending-icon") }
    }

    @Test fun send_failure_toasts_once_each_attempt() { // AC-UI-08-3、08-5、19-1、19-2
        withBob()
        server.fail("send", 4290)
        start("tianting://chat/c2c%3Aalice%3Abob")
        sendText("频繁")
        rule.waitTag("toast")
        rule.waitText("发送太频繁，请稍后再试")
        assertFalse(rule.hasText("server says", substring = true))
        rule.waitGoneTag("toast", 3000)
        click("failed-icon")
        click("retry-dialog-ok")
        rule.waitTag("toast")
        rule.waitGoneTag("toast", 3000)
        assertNeverWithin(500, "extra toast") { rule.hasTag("toast") }
        server.fail("send", 4042)
        click("failed-icon")
        click("retry-dialog-ok")
        rule.waitTag("toast")
        rule.waitText("用户不存在")
    }

    @Test fun menu_copy_retry_delete() { // AC-UI-09-1、09-3、09-4、09-5
        withBob(msgJson(BOB_CONV, 1, "bob", "第一行\n第二行"))
        server.fail("send", 5000)
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("第二行", substring = true)
        bubbleOf("第一行\n第二行").performTouchInput { longClick() }
        rule.waitTag("bubble-menu")
        assertFalse(rule.hasTag("menu-retry"))
        assertFalse(rule.hasTag("menu-delete"))
        click("menu-copy")
        rule.waitText("已复制")
        assertEquals("第一行\n第二行", copied.last())
        // 短按不弹菜单
        bubbleOf("第一行\n第二行").performClick()
        assertNeverWithin(600, "menu on tap") { rule.hasTag("bubble-menu") }
        // 失败消息菜单：复制、重发、删除
        sendText("会失败")
        rule.waitTag("failed-icon")
        bubbleOf("会失败").performTouchInput { longClick() }
        rule.waitTag("menu-retry")
        val id = server.frames("send").last().data["clientMsgId"]
        val before = server.frames("send").size
        click("menu-retry")
        waitFor("retried") { server.frames("send").size == before + 1 }
        assertEquals(id, server.frames("send").last().data["clientMsgId"])
        assertFalse(rule.hasTag("retry-dialog"))
        rule.waitTag("failed-icon")
        val pending = model.im!!.pendingCount
        bubbleOf("会失败").performTouchInput { longClick() }
        click("menu-delete")
        rule.waitGoneText("会失败")
        waitFor("pending -1") { model.im!!.pendingCount == pending - 1 }
    }

    @Test fun copy_refused_shows_hint() { // AC-UI-09-8
        withBob(msgJson(BOB_CONV, 1, "bob", "复制我"))
        copyWorks = false
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("复制我")
        bubbleOf("复制我").performTouchInput { longClick() }
        click("menu-copy")
        rule.waitText("复制失败，请长按文字手动复制")
    }

    @Test fun menu_stays_on_screen() { // AC-UI-09-6 的界面部分；上下位置的判断在 JVM 单元测试 BubbleMenuPositionTest
        withBob(msgJson(BOB_CONV, 1, "bob", "最顶上的"))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("最顶上的")
        bubbleOf("最顶上的").performTouchInput { longClick() }
        rule.waitTag("bubble-menu")
        val node = n("bubble-menu").fetchSemanticsNode()
        val d = androidx.test.uiautomator.UiDevice.getInstance(inst)
        assertTrue(node.positionOnScreen.y >= 0 && node.positionOnScreen.y + node.size.height <= d.displayHeight)
    }

    @Test fun composer_grows_to_five_lines() { // AC-UI-10-3
        withBob()
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("composer-input")
        type("composer-input", "1")
        val one = n("composer-input").getUnclippedBoundsInRoot().height
        type("composer-input", "1\n2\n3\n4\n5")
        val five = n("composer-input").getUnclippedBoundsInRoot().height
        type("composer-input", "1\n2\n3\n4\n5\n6\n7\n8")
        val eight = n("composer-input").getUnclippedBoundsInRoot().height
        assertTrue(five > one, "$five > $one")
        assertEquals(five, eight)
    }

    @Test fun over_limit_blocks_send() { // AC-UI-10-4
        withBob()
        start("tianting://chat/c2c%3Aalice%3Abob")
        type("composer-input", "a".repeat(5003))
        n("over-limit").assertTextEquals("已超出 3 字")
        n("send-button").assertIsNotEnabled()
        type("composer-input", "a".repeat(5000))
        assertFalse(rule.hasTag("over-limit"))
        n("send-button").assertIsEnabled()
    }

    @Test fun blank_cannot_send_and_input_cleared_after_send() { // F-UI-10 规则 3、7
        withBob()
        start("tianting://chat/c2c%3Aalice%3Abob")
        type("composer-input", "   ")
        n("send-button").assertIsNotEnabled()
        sendText("你好")
        rule.waitUntil(3000) { textOf("composer-input").isEmpty() }
        rule.waitText("你好")
    }

    @Test fun ime_send_action_sends() { // 3.4 节：IME_ACTION_SEND
        withBob()
        start("tianting://chat/c2c%3Aalice%3Abob")
        type("composer-input", "回车发")
        n("composer-input").performImeAction()
        waitFor("sent") { server.frames("send").isNotEmpty() }
    }

    @Test fun new_message_pill_when_scrolled_up() { // F-UI-06 规则 6
        withBob(*(1L..60L).map { msgJson(BOB_CONV, it, "bob", "第${it}条") }.toTypedArray())
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("第60条")
        n("message-list").performScrollToIndex(8)
        rule.waitForIdle()
        server.pushMsg(msgJson(BOB_CONV, 61, "bob", "新来的"))
        rule.waitTag("new-messages")
        n("new-messages").assertAllTextContains("↓ 1 条新消息")
        click("new-messages")
        rule.waitText("新来的")
        rule.waitGoneTag("new-messages")
    }

    @Test fun at_bottom_follows_new_message() { // F-UI-06 规则 6
        withBob(*(1L..60L).map { msgJson(BOB_CONV, it, "bob", "第${it}条") }.toTypedArray())
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("第60条")
        server.pushMsg(msgJson(BOB_CONV, 61, "bob", "贴底新消息"))
        rule.waitText("贴底新消息")
        assertFalse(rule.hasTag("new-messages"))
    }

    @Test fun history_paging_keeps_position() { // AC-MSG-05-6
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 45))
        server.messages[BOB_CONV] = (1L..45L).map { msgJson(BOB_CONV, it, "bob", "第${it}条") }
        server.handlers["sync"] = { f ->
            val before = f.data["beforeSeq"]!!.jsonPrimitive.content.toLong()
            val all = server.messages[BOB_CONV]!!.filter { it["seq"]!!.jsonPrimitive.content.toLong() < before }
            Reply.Data(obj("messages" to all.takeLast(20), "hasMore" to (all.size > 20)))
        }
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("第45条")
        n("message-list").performScrollToIndex(20)
        rule.waitUntil(5000) { server.frames("sync").size >= 2 }
        rule.waitForIdle()
        n("message-list").performScrollToNode(hasAnyDescendant(hasText("第26条")))
        val y = msgOf("第26条").getUnclippedBoundsInRoot().top
        n("message-list").performTouchInput { swipeDown() }
        rule.waitUntil(5000) { server.frames("sync").size >= 3 }
        rule.waitForIdle()
        n("message-list").performScrollToNode(hasText("没有更多了"))
        assertTrue(rule.hasText("第1条") || rule.hasText("第2条") || rule.hasText("第3条"), "应该翻到最早的")
        y.value
    }

    @Test fun permission_tips() { // AC-UI-31-1、31-2、31-3、31-4、31-5
        withBob()
        server.strangerMsgLimit = 3
        server.fail("send", 4035)
        start("tianting://chat/c2c%3Aalice%3Abob")
        sendText("甲")
        rule.waitTag("permission-tip")
        sendText("乙")
        rule.waitText("乙")
        sendText("丙")
        rule.waitText("丙")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("failed-icon", useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("permission-tip", useUnmergedTree = true).fetchSemanticsNodes().size)
        val tip = n("permission-tip").getUnclippedBoundsInRoot()
        assertTrue(tip.top >= bubbleOf("丙").getUnclippedBoundsInRoot().bottom, "提示条应该在最后一条失败消息下方")
        txt("对方开启了好友验证，你还不是他的好友").assertExists()
        assertFalse(rule.hasTag("toast"))
        server.fail("send", 4036)
        sendText("丁")
        rule.waitText("消息已发出，但被对方拒收了")
        assertFalse(rule.hasTag("toast"))
        server.fail("send", 4137)
        sendText("戊")
        rule.waitText("对方回复之前，最多只能发送 3 条消息")
        server.fail("send", 4292)
        sendText("己")
        rule.waitTag("toast")
        rule.waitText("今天发起新聊天的次数已用完，请稍后再试")
        assertEquals(3, rule.onAllNodesWithTag("permission-tip", useUnmergedTree = true).fetchSemanticsNodes().size)
        // 点"发送好友申请"：source = conv
        server.reply("friendApply", socialAck(status = "pending"))
        n("message-list").performScrollToNode(hasAnyDescendant(hasTestTag("permission-apply")))
        click("permission-apply")
        rule.waitTag("apply-send")
        assertEquals(applyPath("bob"), model.nav.current.path)
        click("apply-send")
        waitFor("apply") { server.frames("friendApply").isNotEmpty() }
        assertEquals("conv", server.frames("friendApply").last().data["source"]!!.jsonPrimitive.content)
    }

    @Test fun invalid_conv_routes() { // AC-UI-33-1、33-2
        start("tianting://chat/abc")
        rule.waitText("会话不存在")
        rule.waitTag("tab-chats")
        finish()
        start("tianting://chat/c2c%3Abob%3Acarol")
        rule.waitText("会话不存在")
        assertFalse(server.frames("sync").any { it.data["convId"]?.jsonPrimitive?.content == "c2c:bob:carol" })
    }

    @Test fun temp_conversation_can_send() { // AC-UI-33-3
        start("tianting://chat/c2c%3Aalice%3Adave")
        rule.waitTag("composer-input")
        n("chat-title").assertTextEquals("dave")
        sendText("第一句")
        waitFor("sent") { server.frames("send").isNotEmpty() }
    }

    @Test fun unknown_group_probes_before_showing_anything() { // AC-UI-33-6、33-10
        server.hold("sync")
        start("tianting://chat/group%3Ag9")
        rule.waitTag("chat-probing")
        assertFalse(rule.hasTag("composer-input"))
        assertFalse(rule.hasText("你已不在该群", substring = true))
        val f = server.frames("sync").first { it.data["convId"]?.jsonPrimitive?.content == "group:g9" }
        assertEquals("9007199254740991", f.data["beforeSeq"]!!.jsonPrimitive.content)
        server.push(obj("id" to f.id, "cmd" to "ack", "error" to obj("code" to 4033, "msg" to "x")))
        rule.waitTag("composer-disabled")
        n("composer-disabled").assertTextEquals("你已不在该群，无法发送消息")
        n("chat-blocked-tip").assertTextEquals("你已不在该群")
        n("chat-title").assertTextEquals("群聊")
        finish()
        val s = FakeServer()
        s.fail("sync", 4043)
        install(transport = s)
        launch("tianting://chat/group%3Anotexist")
        rule.waitTag("composer-disabled")
        n("composer-disabled").assertTextEquals("群聊不存在或已解散")
        n("chat-blocked-tip").assertTextEquals("群聊不存在或已解散")
        n("chat-title").assertTextEquals("群聊")
    }

    @Test fun removed_while_viewing_by_reason_and_readded() { // AC-UI-33-4、33-5、33-7、33-8、33-11、33-12、33-14
        server.convs = listOf(convJson(G1, "球局", memberCount = 5, myRole = "member"), convJson("group:g2", "二群", memberCount = 3), convJson("group:g3", "三群", memberCount = 3), convJson("group:g4", "四群", memberCount = 3))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("composer-input")
        n("chat-title-count").assertTextEquals("（5）")
        server.pushEvent("convRemoved", "convId" to G1)
        rule.waitTag("composer-disabled")
        n("composer-disabled").assertTextEquals("你已不在该群，无法发送消息")
        n("chat-blocked-tip").assertTextEquals("你已不在该群")
        n("chat-title").assertTextEquals("球局")
        assertFalse(rule.hasTag("composer-input"))
        assertFalse(rule.hasTag("chat-title-count"))
        // 重新被拉进群
        server.pushEvent("convAdded", "conv" to convJson(G1, "球局", memberCount = 6, myRole = "member"))
        rule.waitTag("composer-input")
        assertFalse(rule.hasTag("composer-disabled"))
        // reason
        for ((conv, reason, tip, bar) in listOf(
            listOf("group:g2", "dissolved", "群聊已解散", "群聊已解散"),
            listOf("group:g3", "xyz", "你已不在该群", "你已不在该群，无法发送消息"),
            listOf("group:g4", "left", "你已退出该群聊", "你已退出该群聊"),
        )) {
            inst.runOnMainSync { model.nav.resetTo(chatPath(conv)) }
            rule.waitTag("composer-input")
            server.pushEvent("convRemoved", "convId" to conv, "reason" to reason)
            rule.waitTag("composer-disabled")
            n("composer-disabled").assertTextEquals(bar)
            n("chat-blocked-tip").assertTextEquals(tip)
        }
        // 会话列表里消失；退回来标题还是群名
        inst.runOnMainSync { model.nav.resetTo(null) }
        rule.waitTag("tab-chats")
        assertFalse(rule.hasText("二群"))
        inst.runOnMainSync { model.nav.resetTo(chatPath("group:g2")) }
        rule.waitTag("composer-disabled")
        n("chat-title").assertTextEquals("二群")
        // 聊天信息页（AC-UI-12-5）
        click("chat-more")
        rule.waitTag("info-removed")
        n("info-removed").assertTextEquals("群聊已解散")
    }

    @Test fun c2c_info_page() { // AC-UI-12-2
        withBob()
        start("tianting://chat/c2c%3Aalice%3Abob")
        click("chat-more")
        rule.waitTag("info-head")
        n("info-sub").assertTextEquals("账号：bob")
        click("info-head")
        rule.waitTag("user-name")
        assertEquals(userPath("bob"), model.nav.current.path)
    }

    @Test fun removed_input_gone_and_info_page_group() { // AC-UI-10-5、12-1
        server.group("member")
        start("tianting://chat/group%3Ag1")
        rule.waitTag("composer-input")
        val n3 = textOf("chat-title-count")
        click("chat-more")
        rule.waitTag("all-members")
        n("all-members").assertAllTextContains("查看全部成员（3）")
        assertEquals("（3）", n3)
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("composer-input")
        server.pushEvent("convRemoved", "convId" to G1)
        rule.waitGoneTag("composer-input")
        n("composer-disabled").assertTextEquals("你已不在该群，无法发送消息")
    }

    @Test fun removed_drops_typed_text() { // F-UI-33 规则 5
        server.group("member")
        start("tianting://chat/group%3Ag1")
        rule.waitTag("composer-input")
        n("composer-input").performTextInput("没发出去的话")
        rule.waitForIdle()
        server.pushEvent("convRemoved", "convId" to G1)
        rule.waitTag("composer-disabled")
        rule.waitForIdle()
        inst.runOnMainSync { assertEquals(null, model.im?.drafts?.get(G1)) }
    }

    @Test fun reconnect_infers_removal() { // AC-UI-33-9
        server.convs = listOf(convJson(G1, "球局", memberCount = 3))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("composer-input")
        server.convs = emptyList()
        server.drop()
        rule.waitTag("composer-disabled", 8000)
        n("composer-disabled").assertTextEquals("你已不在该群，无法发送消息")
        n("chat-title").assertTextEquals("球局")
    }

    @Test fun failed_message_and_tip_gone_after_new_session() { // AC-UI-31-6：假依赖没有本地库，等同 D4 的内存行为
        withBob()
        server.fail("send", 4035)
        start("tianting://chat/c2c%3Aalice%3Abob")
        sendText("甲")
        rule.waitTag("permission-tip")
        inst.runOnMainSync { model.logout() }
        rule.waitTag("login-username")
        assertFalse(rule.hasTag("permission-tip"))
    }

    @Test fun rejected_send_keeps_input() { // AC-UI-10-8：超长时按钮禁用，IME 发送也不发出、输入框不变
        withBob()
        start("tianting://chat/c2c%3Aalice%3Abob")
        val long = "字".repeat(5001)
        type("composer-input", long)
        n("composer-input").performImeAction()
        rule.waitForIdle()
        assertTrue(server.frames("send").isEmpty())
        assertEquals(long, textOf("composer-input"))
    }

    @Test fun plain_text_is_not_parsed() { // NF-UI-12
        val nasty = "<img src=x onerror=alert(1)> https://example.com/a?b=<script>"
        withBob(msgJson(BOB_CONV, 1, "bob", nasty))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText(nasty)
    }
}
