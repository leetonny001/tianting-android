package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import com.tianting.app.ui.theme.TtDimen
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width

/** 会话列表与发起单聊（F-UI-04、F-UI-05、F-UI-20、F-UI-23）。 */
class ConvListTest : UiTest() {
    private fun row(convId: String) = member(convId, "conv-item", DataConvId)
    private fun summaryOf(convId: String) = allText(row(convId).fetchSemanticsNode())

    @Test fun summary_prefix_rules() { // AC-UI-04-1、04-2、04-3、04-4、40-6
        server.convs = listOf(
            convJson(G1, "球局", maxSeq = 1, lastMsg = lastMsg("bob", "晚上好")),
            convJson("group:g2", "自己发", maxSeq = 1, lastMsg = lastMsg("alice", "收到")),
            convJson("group:g3", "空群", lastMsg = null),
            convJson("group:g4", "提示", maxSeq = 1, lastMsg = lastMsg("bob", "bob 邀请了 carol", type = "tip")),
            convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", "单聊内容")),
            convJson("c2c:alice:carol", "carol", maxSeq = 1, lastMsg = lastMsg("carol", null, type = "custom")),
        )
        server.users["bob"] = obj("userId" to "bob", "nickname" to "bob")
        start()
        waitMember(G1, "conv-item", DataConvId)
        rule.waitText("bob: 晚上好")
        assertTrue("收到" in summaryOf("group:g2"))
        assertFalse("alice:" in summaryOf("group:g2"))
        assertFalse(rule.onAllNodes(hasTestTag("conv-time") and hasAnyAncestor(tagged("conv-item", DataConvId, "group:g3")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("bob 邀请了 carol" in summaryOf("group:g4"))
        assertFalse("bob: " in summaryOf("group:g4"))
        assertFalse("bob: " in summaryOf(BOB_CONV))
        assertTrue("[暂不支持的消息]" in summaryOf("c2c:alice:carol"))
    }

    @Test fun deleted_last_message_summary() { // AC-UI-42-2
        server.convs = listOf(convJson(G1, "球局", maxSeq = 1, lastMsg = lastMsg("bob", null, deleted = true)))
        start()
        waitMember(G1, "conv-item", DataConvId)
        rule.waitText("该消息已被删除")
        assertFalse(rule.hasText("bob: ", substring = true))
    }

    @Test fun long_summary_keeps_height_and_single_line() { // AC-UI-04-5
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", "长".repeat(100))))
        start()
        waitMember(BOB_CONV, "conv-item", DataConvId)
        val b = row(BOB_CONV).getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((b.height - TtDimen.sizeConvItem.dp).value) <= 1f, "item height ${b.height}")
    }

    @Test fun unread_badge_position_and_99_plus() { // AC-UI-04-6、AC-UI-23-3 的角标部分
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "x")), convJson("c2c:alice:carol", "carol", maxSeq = 120, unread = 120, lastMsg = lastMsg("carol", "y")))
        start()
        waitMember(BOB_CONV, "conv-item", DataConvId)
        val badge = rule.onNode(hasTestTag("conv-badge") and hasAnyAncestor(tagged("conv-item", DataConvId, BOB_CONV)), useUnmergedTree = true)
        val avatar = rule.onNode(hasTestTag("avatar") and hasAnyAncestor(tagged("conv-item", DataConvId, BOB_CONV)), useUnmergedTree = true)
        val bb = badge.getUnclippedBoundsInRoot()
        val ab = avatar.getUnclippedBoundsInRoot()
        val cx = (bb.left + bb.right) / 2
        val cy = (bb.top + bb.bottom) / 2
        assertTrue(cx > (ab.left + ab.right) / 2 && cy < (ab.top + ab.bottom) / 2, "badge center not in top-right quarter")
        badge.assertAllTextContains("5")
        rule.waitText("99+")
        n("tab-badge").assertAllTextContains("99+")
    }

    @Test fun truncated_footer() { // AC-UI-04-7
        server.convs = listOf(convJson(BOB_CONV, "bob"))
        server.truncated = true
        start()
        rule.waitTag("convs-truncated")
        txt("仅显示最近 500 个会话").assertExists()
    }

    @Test fun summary_prefix_uses_remark_over_nickname() { // AC-UI-04-9
        server.convs = listOf(convJson(G1, "球局", maxSeq = 1, lastMsg = lastMsg("carol", "早")))
        server.friends = listOf(friendJson("carol", "小卡", "卡姐"))
        start()
        rule.waitText("卡姐: 早")
    }

    @Test fun plus_menu_order_and_add_friend() { // AC-UI-05-5、05-8
        start()
        click("plus-button")
        rule.waitTag("plus-new-group")
        val tops = listOf("plus-new-group", "plus-add-friend", "plus-start-chat").map { n(it).getUnclippedBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops)
        click("plus-add-friend")
        rule.waitTag("search-input")
    }

    @Test fun start_chat_dialog_same_from_both_entries_and_open_mode() { // AC-UI-05-1、05-2、20-1 的空状态按钮
        start()
        rule.waitTag("chats-empty")
        txt("还没有聊天").assertExists()
        click("chats-empty-start")
        rule.waitTag("start-chat-dialog")
        n("start-chat-submit").assertIsNotEnabled()
        click("start-chat-cancel")
        click("plus-button")
        click("plus-start-chat")
        rule.waitTag("start-chat-dialog")
        type("start-chat-input", "bob")
        click("start-chat-submit")
        rule.waitTag("chat-title")
        assertEquals(chatPath(BOB_CONV), model.nav.current.path)
        assertFalse(rule.hasTag("start-chat-dialog"))
    }

    @Test fun start_chat_self_and_friends_mode_warning() { // AC-UI-05-3、05-6
        server.mode = "friends"
        server.friends = listOf(friendJson("bob"))
        start()
        rule.waitUntil(5000) { model.im?.socialLoaded == true }
        rule.waitTag("chats-empty")
        click("chats-empty-start")
        type("start-chat-input", "bob")
        click("start-chat-submit")
        rule.waitTag("chat-title")
        assertFalse(rule.hasTag("start-chat-warn"))
        inst.runOnMainSync { model.nav.back() }
        // 非好友
        click("chats-empty-start")
        type("start-chat-input", "carol")
        click("start-chat-submit")
        rule.waitTag("start-chat-warn")
        txt("对方不是你的好友，可能无法发送消息").assertExists()
        n("start-chat-apply").assertExists()
        click("start-chat-anyway")
        rule.waitTag("chat-title")
        assertEquals(chatPath("c2c:alice:carol"), model.nav.current.path)
        inst.runOnMainSync { model.nav.back() }
        // 自己
        click("chats-empty-start")
        type("start-chat-input", "alice")
        click("start-chat-submit")
        rule.waitTag("chat-title")
        assertEquals(chatPath("c2c:alice:alice"), model.nav.current.path)
    }

    @Test fun skeleton_until_convs_arrive() { // AC-UI-20-1
        server.hold("convs")
        start()
        rule.waitTag("chats-skeleton")
        assertFalse(rule.hasTag("chats-empty"))
        val f = server.frames("convs").first()
        server.handlers.remove("convs")
        server.push(obj("id" to f.id, "cmd" to "ack", "data" to obj("convs" to emptyList<Any>(), "truncated" to false)))
        rule.waitTag("chats-empty")
    }

    @Test fun outside_tap_closes_dialog_route_unchanged() { // AC-UI-05-7
        start()
        rule.waitTag("chats-empty")
        click("chats-empty-start")
        rule.waitTag("start-chat-dialog")
        val d = androidx.test.uiautomator.UiDevice.getInstance(inst)
        d.click(d.displayWidth / 2, d.displayHeight - 400)
        rule.waitGoneTag("start-chat-dialog", 3000)
        assertEquals("/chats", model.nav.current.path)
    }

    @Test fun total_unread_badge_clears_when_opened() { // AC-UI-23-3
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "x")))
        server.messages[BOB_CONV] = (1L..5L).map { msgJson(BOB_CONV, it, "bob", "m$it") }
        start()
        rule.waitTag("tab-badge")
        n("tab-badge").assertAllTextContains("5")
        member(BOB_CONV, "conv-item", DataConvId).performClick()
        rule.waitTag("chat-title")
        rule.waitText("m5")
        inst.runOnMainSync { model.nav.back() }
        rule.waitGoneTag("tab-badge")
    }
}
