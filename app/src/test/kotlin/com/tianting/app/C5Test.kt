// FE-684（PRD-chat C5）的纯函数部分：摘要前缀的优先级、"[N 条]"、菜单项文字、错误文案。界面部分在 androidTest 的 C5UiTest。
package com.tianting.app

import com.tianting.app.core.messageFor
import com.tianting.app.ui.pages.ConvAction
import com.tianting.app.ui.pages.DELETE_CONV_CONFIRM
import com.tianting.app.ui.pages.SummaryPrefix
import com.tianting.app.ui.pages.convActionLabel
import com.tianting.app.ui.pages.summaryPrefix
import com.tianting.app.ui.pages.unreadCountText
import com.tianting.sdk.ConvType
import com.tianting.sdk.Conversation
import com.tianting.sdk.LastMsg
import com.tianting.sdk.MsgType
import kotlin.test.Test
import kotlin.test.assertEquals

class C5Test {
    private val last = LastMsg("m5", "bob", MsgType.TEXT, "hi", 0)

    private fun conv(unread: Long = 5, notifyOff: Boolean = false, pinned: Boolean = false, mention: Long? = null) = Conversation(
        convId = "group:g1", type = ConvType.GROUP, name = "g", maxSeq = 9, readSeq = 9 - unread, minSeq = 1, unread = unread,
        lastMsg = last, updatedAt = 0, mentionUnread = mention, pinned = pinned, pinnedAt = if (pinned) 1 else null, notifyOff = notifyOff,
    )

    @Test fun tc_cvs_02_03_prefix_priority_mention_draft_count() { // TC-CVS-02-03 第 2 步、PRD-ui F-UI-04 规则 5（"[有人@我]" > "[草稿]" > "[N 条]"）
        assertEquals(SummaryPrefix.COUNT, summaryPrefix(conv(notifyOff = true), hasDraft = false))
        assertEquals(SummaryPrefix.DRAFT, summaryPrefix(conv(notifyOff = true), hasDraft = true))
        assertEquals(SummaryPrefix.MENTION, summaryPrefix(conv(notifyOff = true, mention = 1), hasDraft = true))
        assertEquals(SummaryPrefix.MENTION, summaryPrefix(conv(notifyOff = true, mention = 1), hasDraft = false))
        assertEquals(SummaryPrefix.COUNT, summaryPrefix(conv(notifyOff = true, mention = 0), hasDraft = false), "mentionUnread=0 不算有人 @ 我")
    }

    @Test fun count_only_for_notify_off_with_unread() { // F-CVS-02 规则 5
        assertEquals(SummaryPrefix.NONE, summaryPrefix(conv(notifyOff = false), hasDraft = false), "普通会话用数字角标，没有前缀")
        assertEquals(SummaryPrefix.NONE, summaryPrefix(conv(unread = 0, notifyOff = true), hasDraft = false), "没有未读")
    }

    @Test fun issue_61_count_is_not_capped() { // frontend-chat 第 61 条：摘要不是角标，N 照实显示
        assertEquals("[5 条]", unreadCountText(5))
        assertEquals("[120 条]", unreadCountText(120))
    }

    @Test fun tc_cvs_01_06_menu_labels_follow_state() { // TC-CVS-01-06 预期 1
        val plain = conv()
        assertEquals(listOf("置顶", "免打扰", "删除"), ConvAction.entries.map { convActionLabel(plain, it) })
        val both = conv(pinned = true, notifyOff = true)
        assertEquals(listOf("取消置顶", "取消免打扰", "删除"), ConvAction.entries.map { convActionLabel(both, it) })
    }

    @Test fun copy_4220_4290_and_confirm() { // F-CVS-01 规则 2、frontend-chat 第 56 条、F-CVS-03 规则 7
        assertEquals("最多置顶 20 个会话", messageFor(4220, "convSet"))
        assertEquals("操作过于频繁", messageFor(4290, "convSet"))
        assertEquals("操作过于频繁", messageFor(4290, "convDelete"))
        assertEquals("发送太频繁，请稍后再试", messageFor(4290, "send"), "发送的 4290 不变")
        assertEquals("服务异常，请稍后重试", messageFor(5000, "convSet"))
        assertEquals("删除后聊天记录不再显示，确定删除？", DELETE_CONV_CONFIRM)
    }
}
