// FE-667、FE-668（PRD-chat C4）的纯函数部分：@ 的输入模型、菜单项、引用跳转、列表摘要、撤回提示。界面部分在 androidTest 的 C4UiTest。
package com.tianting.app

import com.tianting.app.core.Draft
import com.tianting.app.core.EditState
import com.tianting.app.core.MESSAGE_DELETED
import com.tianting.app.core.MentionRange
import com.tianting.app.core.applyEdit
import com.tianting.app.core.backspaceMention
import com.tianting.app.core.clampCaret
import com.tianting.app.core.defaultMessage
import com.tianting.app.core.insertMention
import com.tianting.app.core.messageFor
import com.tianting.app.core.reconcile
import com.tianting.app.core.recallErrorText
import com.tianting.app.core.toMentions
import com.tianting.app.core.triggerAt
import com.tianting.app.ui.pages.JumpPlan
import com.tianting.app.ui.pages.MenuAction
import com.tianting.app.ui.pages.SummaryPrefix
import com.tianting.app.ui.pages.jumpPlan
import com.tianting.app.ui.pages.menuItemsFor
import com.tianting.app.ui.pages.mentionsMe
import com.tianting.app.ui.pages.summaryPrefix
import com.tianting.app.ui.pages.summaryText
import com.tianting.sdk.ConvType
import com.tianting.sdk.Conversation
import com.tianting.sdk.IMError
import com.tianting.sdk.LastMsg
import com.tianting.sdk.Mentions
import com.tianting.sdk.Message
import com.tianting.sdk.MessageAction
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgBody
import com.tianting.sdk.MsgType
import com.tianting.sdk.TimeoutError
import com.tianting.sdk.recallNoticeOf
import com.tianting.sdk.recallText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentionModelTest {
    private val wang = MentionRange(3, 4, "wang") // "你好 @小王 " 里的"@小王 "

    @Test fun tc_men_01_08_trigger_only_at_line_start_or_after_space() { // AC-MEN-01-8
        assertNull(triggerAt("a", "a@"), "a@b.com 的 @ 前面是字母")
        assertEquals(0, triggerAt("", "＠"), "行首全角＠")
        assertEquals(0, triggerAt("", "@"))
        assertEquals(2, triggerAt("你 ", "你 @"))
        assertEquals(4, triggerAt("第一行\n", "第一行\n@"), "换行后")
        assertNull(triggerAt("", "@u2"), "粘贴的 @u2（一次多个字）不打开")
        assertNull(triggerAt("ab", "ab#"))
        assertNull(triggerAt("ab", "xab@"), "不是单纯插入一个字")
    }

    @Test fun tc_men_01_05_deleting_part_of_the_mention_drops_it() { // AC-MEN-01-5
        val text = "你好 @小王 "
        val r = listOf(wang)
        assertEquals(emptyList(), reconcile(text, "你好 @小 ", r), "删掉其中一个字")
        assertEquals(emptyList(), reconcile(text, "你好 ", r), "删掉整段")
        assertNull(toMentions("你好 ", reconcile(text, "你好 ", r)), "发出去没有 mentions")
        assertEquals(listOf(MentionRange(4, 4, "wang")), reconcile(text, "嗨$text", r), "前面插入：平移")
        assertEquals(listOf(wang), reconcile(text, "${text}吃饭", r), "后面追加：不动")
    }

    @Test fun tc_men_01_12_one_backspace_removes_the_whole_mention() { // AC-MEN-01-12
        val text = "你好 @小王 "
        val r = backspaceMention(text, text.length, "你好 @小王", listOf(wang))!!
        assertEquals("你好 ", r.text)
        assertEquals(emptyList(), r.ranges)
        assertEquals(3, r.caret)
        // 光标不在 @ 后面：普通退格
        assertNull(backspaceMention("${text}啊", text.length + 1, text, listOf(wang)))
        // 向后删（Delete 键）不算退格
        assertNull(backspaceMention("${text}啊", text.length, text, listOf(wang)))
        // 整个输入框经由 applyEdit：退格后没有 mentions
        val s = EditState(text, text.length, ranges = listOf(wang))
        val after = applyEdit(s, "你好 @小王", 6, 6, composing = false, canMention = true).state
        assertEquals("你好 ", after.text)
        assertNull(toMentions(after.text, after.ranges))
    }

    @Test fun caret_never_lands_inside_a_mention() { // F-MEN-01 规则 5
        assertEquals(7, clampCaret(5, listOf(wang)), "点进去：去后端")
        assertEquals(3, clampCaret(6, listOf(wang), prev = 7), "从后面往左移进来：去前端")
        assertEquals(7, clampCaret(4, listOf(wang), prev = 3), "从前面往右移：去后端")
        assertEquals(3, clampCaret(3, listOf(wang)), "两端不动")
        val s = EditState("你好 @小王 ", 7, ranges = listOf(wang))
        assertEquals(3, applyEdit(s, s.text, 6, 6, composing = false, canMention = true).state.selStart)
    }

    @Test fun tc_men_01_07_insert_uses_the_nickname_and_records_the_range() { // AC-MEN-01-7（插入的是传进来的公开昵称）
        val r = insertMention("你好 @", emptyList(), 3, "小王", "wang")
        assertEquals("你好 @小王 ", r.text)
        assertEquals(listOf(wang), r.ranges)
        assertEquals(7, r.caret)
        assertEquals(Mentions(listOf("wang")), toMentions(r.text, r.ranges))
        val all = insertMention(r.text + "@", r.ranges, 7, "所有人", null)
        assertEquals("你好 @小王 @所有人 ", all.text)
        assertEquals(Mentions(listOf("wang"), all = true), toMentions(all.text, all.ranges))
        // 同一个人 @ 两次：userIds 去重
        val twice = insertMention(r.text + "@", r.ranges, 7, "小王", "wang")
        assertEquals(Mentions(listOf("wang")), toMentions(twice.text, twice.ranges))
    }

    @Test fun tc_men_01_09_composition_does_not_change_the_model_or_open_the_picker() { // AC-MEN-01-9 的自动化部分（手工部分未执行）
        val s0 = EditState("你好 @小王 ", 7, ranges = listOf(wang))
        // 组字中：输入法给的文字里有 @，不打开、区间不动
        val c1 = applyEdit(s0, "你好 @小王 n@", 9, 9, composing = true, canMention = true)
        assertNull(c1.trigger)
        assertEquals(listOf(wang), c1.state.ranges)
        assertEquals("你好 @小王 ", c1.state.composeBase)
        val c2 = applyEdit(c1.state, "你好 @小王 ni@", 10, 10, composing = true, canMention = true)
        assertNull(c2.trigger)
        // 上屏：按组字前的文字算一次；上屏的不是"一个 @"，不打开，区间还在
        val done = applyEdit(c2.state, "你好 @小王 你@", 9, 9, composing = false, canMention = true)
        assertNull(done.trigger)
        assertEquals(listOf(wang), done.state.ranges)
        assertNull(done.state.composeBase)
        // 之后在空白后敲 @：正常打开
        val sp = applyEdit(done.state, "你好 @小王 你@ ", 10, 10, composing = false, canMention = true).state
        assertEquals(10, applyEdit(sp, "你好 @小王 你@ @", 11, 11, composing = false, canMention = true).trigger)
    }

    @Test fun tc_men_01_09_at_typed_inside_composition_opens_only_after_commit() { // AC-MEN-01-9：组字中不打开，组字结束再看（同 H5 的 compositionend）
        val s0 = EditState("你好 ", 3)
        val c = applyEdit(s0, "你好 ＠", 4, 4, composing = true, canMention = true)
        assertNull(c.trigger, "组字中敲的 ＠ 不打开")
        val done = applyEdit(c.state, "你好 ＠", 4, 4, composing = false, canMention = true)
        assertEquals(3, done.trigger, "上屏后按组字前的文字看：空白后一个 ＠，打开")
    }

    @Test fun mentions_only_from_ranges_that_still_start_with_at() { // F-MEN-01 规则 5：区间里的文字不是 @ 开头的不算
        assertNull(toMentions("你好", listOf(MentionRange(0, 2, "wang"))))
        assertNull(toMentions("@小", listOf(MentionRange(0, 4, "wang"))), "区间超出文字")
        assertEquals(Mentions(listOf("wang")), toMentions("＠小王 ", listOf(MentionRange(0, 4, "wang"))), "全角也算")
    }

    @Test fun tc_men_01_06_single_chat_never_opens_the_picker() { // AC-MEN-01-6
        assertNull(applyEdit(EditState("", 0), "@", 1, 1, composing = false, canMention = false).trigger)
        assertEquals(0, applyEdit(EditState("", 0), "@", 1, 1, composing = false, canMention = true).trigger)
    }

    @Test fun tc_men_01_10_draft_keeps_text_mentions_and_quote() { // AC-MEN-01-10（草稿结构）
        assertTrue(Draft("  ").isEmpty)
        assertTrue(!Draft("", quote = "m1").isEmpty, "只有引用也是草稿")
        val d = Draft("你好 @小王 ", listOf(wang), "m1")
        assertEquals(d, d.copy())
    }
}

class C4MenuTest {
    private fun m(type: MsgType = MsgType.TEXT, status: MessageStatus = MessageStatus.SENT, deleted: Boolean = false, recalled: Boolean = false, from: String = "bob") =
        Message(clientMsgId = "c1", msgId = "m1", seq = 1, convId = "c2c:alice:bob", from = from, type = type, body = MsgBody(text = if (type == MsgType.TEXT) "x" else null), ts = 0, status = status, deleted = deleted, recalled = recalled)

    @Test fun tc_ui_09_01_switch_off_only_copy() { // AC-UI-09-1（v1.4 改写）：关闭时只有"复制"
        assertEquals(listOf(MenuAction.COPY), menuItemsFor(m(), false) { listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.RECALL) })
        assertEquals(emptyList(), menuItemsFor(m(MsgType.IMAGE), false) { listOf(MessageAction.QUOTE) }, "关闭时图片没有菜单")
    }

    @Test fun tc_ui_09_01_switch_on_follows_the_sdk() { // AC-UI-09-1：开启时按消息状态（SDK messageActions）
        assertEquals(listOf(MenuAction.COPY, MenuAction.QUOTE, MenuAction.RECALL), menuItemsFor(m(), true) { listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.RECALL) })
        assertEquals(listOf(MenuAction.QUOTE), menuItemsFor(m(MsgType.FILE), true) { listOf(MessageAction.QUOTE) })
        assertEquals(listOf(MenuAction.COPY, MenuAction.RETRY, MenuAction.DELETE), menuItemsFor(m(status = MessageStatus.FAILED), true) { listOf(MessageAction.COPY) })
    }

    @Test fun tc_rcl_03_10_recalled_and_deleted_rows_have_no_menu() { // AC-RCL-03-10、F-RCL-03 规则 6
        assertEquals(emptyList(), menuItemsFor(m(deleted = true, recalled = true), true) { listOf(MessageAction.COPY) })
        assertEquals(emptyList(), menuItemsFor(m(deleted = true), false) { listOf(MessageAction.COPY) })
        assertEquals(emptyList(), menuItemsFor(m(MsgType.TIP), true) { listOf(MessageAction.COPY) })
    }

    @Test fun mentioned_bubble_only_for_me_and_not_my_own() { // F-MEN-01 规则 4、F-MEN-03 规则 6
        fun men(from: String, x: Mentions) = m(from = from).copy(convId = "group:g1", body = MsgBody(text = "x", mentions = x))
        assertTrue(mentionsMe(men("bob", Mentions(listOf("alice"))), "alice"))
        assertTrue(mentionsMe(men("bob", Mentions(all = true)), "alice"))
        assertTrue(!mentionsMe(men("bob", Mentions(listOf("carol"))), "alice"))
        assertTrue(!mentionsMe(men("alice", Mentions(listOf("alice"))), "alice"), "自己 @ 自己不算")
    }
}

class QuoteJumpTest {
    @Test fun tc_quo_02_03_within_200_loads_beyond_is_too_early() { // AC-QUO-02-3
        val loaded = (601L..650L).toList()
        assertEquals(JumpPlan.Here, jumpPlan(620, loaded, 200))
        assertEquals(JumpPlan.Load(100), jumpPlan(501, loaded, 200), "差 100 条：翻一页 100 条")
        assertEquals(JumpPlan.Load(200), jumpPlan(401, loaded, 200), "差 200 条以内，一次最多 200")
        assertEquals(JumpPlan.TooEarly, jumpPlan(101, loaded, 200), "差 500 条：太早，不翻页")
        assertEquals(JumpPlan.Missing, jumpPlan(null, loaded, 200), "没有 seq（原消息已不存在）")
        assertEquals(JumpPlan.Missing, jumpPlan(640, loaded.filter { it != 640L }, 200), "在已加载范围里却没有")
        assertEquals(JumpPlan.Load(200), jumpPlan(1, loaded, Long.MAX_VALUE), "\"有人@我\"：不限距离，一页页翻")
    }
}

class C4SummaryTest {
    private fun conv(last: LastMsg?, mention: Long? = null, id: String = "group:g1") = Conversation(
        convId = id, type = if (id.startsWith("group:")) ConvType.GROUP else ConvType.C2C, name = "g", maxSeq = 3, readSeq = 0, minSeq = 1, unread = 3,
        lastMsg = last, updatedAt = 0, mentionUnread = mention, firstMentionSeq = mention?.let { 2 },
    )

    private val names = mapOf("u1" to "小一", "a" to "管理员甲", "m" to "成员乙")
    private fun name(u: String) = names[u] ?: u

    private fun summary(c: Conversation, me: String) = summaryText(c, c.lastMsg?.let { recallNoticeOf(c.convId, it, me) }?.let { recallText(it, ::name) })

    @Test fun tc_rcl_03_06_group_own_recall_summary() { // AC-RCL-03-6：规则 1a 第 2 行
        val c = conv(LastMsg("m3", "u1", MsgType.TEXT, null, 0, deleted = true, recalled = true, recalledBy = "u1"))
        assertEquals("你撤回了一条消息", summary(c, "u1"))
        assertEquals("小一撤回了一条消息", summary(c, "n"))
    }

    @Test fun tc_rcl_02_04_admin_recall_summary_three_views() { // AC-RCL-02-4：规则 1a 第 3 行
        val c = conv(LastMsg("m3", "m", MsgType.TEXT, null, 0, deleted = true, recalled = true, recalledBy = "a"))
        assertEquals("你撤回了 成员乙 的一条消息", summary(c, "a"))
        assertEquals("管理员甲撤回了你的一条消息", summary(c, "m"))
        assertEquals("管理员甲撤回了一条成员消息", summary(c, "n"))
    }

    @Test fun tc_rcl_03_10_c2c_recall_summary_and_admin_delete_unchanged() { // AC-RCL-03-10、F-UI-42 规则 2
        val c = conv(LastMsg("m3", "bob", MsgType.TEXT, null, 0, deleted = true, recalled = true, recalledBy = "bob"), id = "c2c:alice:bob")
        assertEquals("对方撤回了一条消息", summary(c, "alice"))
        assertEquals("你撤回了一条消息", summary(c, "bob"))
        assertEquals(MESSAGE_DELETED, summary(conv(LastMsg("m3", "bob", MsgType.TEXT, null, 0, deleted = true)), "alice"), "后台删除仍是\"该消息已被删除\"")
    }

    @Test fun tc_men_03_05_mention_prefix_beats_draft() { // AC-MEN-03-5、F-MEN-03 规则 2
        val last = LastMsg("m3", "bob", MsgType.TEXT, "hi", 0)
        assertEquals(SummaryPrefix.MENTION, summaryPrefix(conv(last, mention = 1), hasDraft = true))
        assertEquals(SummaryPrefix.DRAFT, summaryPrefix(conv(last, mention = 0), hasDraft = true))
        assertEquals(SummaryPrefix.DRAFT, summaryPrefix(conv(last, mention = null), hasDraft = true), "服务端没给提及数")
        assertEquals(SummaryPrefix.NONE, summaryPrefix(conv(last), hasDraft = false))
    }
}

class RecallCopyTest {
    @Test fun tc_rcl_01_08_window_from_details() { // AC-RCL-01-8（4097 按 details.windowSeconds）
        fun e(w: Long?) = IMError(4097, "x", details = w?.let { buildJsonObject { put("windowSeconds", JsonPrimitive(it)) } })
        assertEquals("发送时间超过 2 分钟，无法撤回", recallErrorText(e(120)))
        assertEquals("发送时间超过 7 天，无法撤回", recallErrorText(e(604_800)))
        assertEquals("发送时间超过 2 分钟，无法撤回", recallErrorText(e(null)))
    }

    @Test fun tc_rcl_02_05_no_permission_copy() { // AC-RCL-02-5
        assertEquals("你没有权限撤回这条消息", recallErrorText(IMError(4030, "x")))
    }

    @Test fun recall_timeout_and_4044() { // 问题 26、37
        assertEquals("服务异常，请稍后重试", recallErrorText(TimeoutError("recall")))
        assertEquals("消息不存在或已过期", recallErrorText(IMError(4044, "x")))
    }

    @Test fun tc_men_02_05_mention_all_limit_copy() { // AC-MEN-02-5：4293
        assertEquals("@所有人次数已用完", messageFor(4293, "send"))
        assertEquals("@所有人次数已用完", defaultMessage(4293))
        assertEquals("内容不存在", messageFor(4040, "send"), "问题 41：引用不可用")
    }
}
