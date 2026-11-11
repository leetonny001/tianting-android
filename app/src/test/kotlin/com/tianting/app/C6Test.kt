// FE-694（PRD-chat C6：举报、4510）的纯函数部分：理由文案、补充说明的码点限制、错误文案、菜单项。界面部分在 androidTest 的 C6UiTest。
package com.tianting.app

import com.tianting.app.core.AccountPage
import com.tianting.app.core.CONTENT_BLOCKED_FORM
import com.tianting.app.core.CONTENT_BLOCKED_SEND
import com.tianting.app.core.NETWORK_ERROR_TEXT
import com.tianting.app.core.REPORT_REASONS
import com.tianting.app.core.accountErrorText
import com.tianting.app.core.clampCodePoints
import com.tianting.app.core.defaultMessage
import com.tianting.app.core.messageFor
import com.tianting.app.core.reportErrorFinal
import com.tianting.app.core.reportErrorText
import com.tianting.app.core.reportNote
import com.tianting.app.core.reportNoteInput
import com.tianting.app.ui.pages.MenuAction
import com.tianting.app.ui.pages.menuItemsFor
import com.tianting.sdk.AccountError
import com.tianting.sdk.IMError
import com.tianting.sdk.Message
import com.tianting.sdk.MessageAction
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgBody
import com.tianting.sdk.MsgType
import com.tianting.sdk.ReportReason
import com.tianting.sdk.TimeoutError
import com.tianting.sdk.countCodePoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C6Test {
    @Test fun issue_78_reasons_and_copy_in_order() { // frontend-chat 第 78 条：6 个理由，三端和后台一致
        assertEquals(
            listOf("spam" to "垃圾广告", "abuse" to "辱骂骚扰", "porn" to "色情低俗", "fraud" to "诈骗", "illegal" to "违法违规", "other" to "其他"),
            REPORT_REASONS.map { it.first.wire to it.second },
        )
        assertEquals(ReportReason.entries.toSet(), REPORT_REASONS.map { it.first }.toSet(), "SDK 的理由都有文案")
    }

    @Test fun issue_84_note_counts_code_points_typing_stops_paste_is_clipped() { // frontend-chat 第 84 条
        val full = "字".repeat(199) + "😀" // 200 个码点（最后一个是代理对）
        assertEquals(200, countCodePoints(full))
        assertEquals(full, reportNoteInput("", full).value, "200 个码点可以")
        assertFalse(reportNoteInput("", full).clipped)
        // 已经 200，再输入 1 个：不能再输入，保留原内容，不提示
        val typed = reportNoteInput(full, full + "a")
        assertEquals(full, typed.value)
        assertFalse(typed.clipped)
        // 粘贴超长：截断到 200 个码点并提示，不拆开代理对
        val pasted = reportNoteInput("", "😀".repeat(250))
        assertEquals(200, countCodePoints(pasted.value))
        assertEquals("😀".repeat(200), pasted.value)
        assertTrue(pasted.clipped)
        assertEquals("ab", clampCodePoints("abc", 2))
    }

    @Test fun note_trimmed_and_empty_not_sent() {
        assertNull(reportNote("  \n "))
        assertNull(reportNote(""))
        assertEquals("说明", reportNote("  说明 "))
    }

    @Test fun issue_83_report_errors_and_which_close_the_sheet() { // frontend-chat 第 83 条、FE-693 第 2 条
        assertEquals("无法举报该内容", reportErrorText(IMError(4040, "x")))
        assertEquals("举报功能暂不可用", reportErrorText(IMError(4030, "x")))
        assertEquals("今天的举报次数已用完", reportErrorText(IMError(4292, "x")))
        assertEquals("服务异常，请稍后重试", reportErrorText(IMError(5000, "x")))
        assertEquals("操作太频繁，请稍后再试", reportErrorText(IMError(4290, "x")))
        assertEquals(NETWORK_ERROR_TEXT, reportErrorText(TimeoutError("report")), "超时按网络问题提示")
        for (c in listOf(4040, 4030, 4292)) assertTrue(reportErrorFinal(IMError(c, "x")), "$c 关闭举报单")
        for (c in listOf(5000, 4290, 4000)) assertFalse(reportErrorFinal(IMError(c, "x")), "$c 留着可以再提交")
        assertFalse(reportErrorFinal(TimeoutError("report")))
        assertFalse("x" in reportErrorText(IMError(5000, "x")), "不展示服务端的 msg")
    }

    @Test fun ac_mod_02_9_and_p48_4510_copy_by_command() { // AC-MOD-02-9、P-48 ⑥（frontend-chat 第 82 条）
        assertEquals("消息包含不当内容，无法发送", CONTENT_BLOCKED_SEND)
        assertEquals("内容包含不当信息，请修改后重试", CONTENT_BLOCKED_FORM)
        assertEquals(CONTENT_BLOCKED_SEND, messageFor(4510, "send"))
        for (cmd in listOf("groupCreate", "groupUpdate", "friendApply")) assertEquals(CONTENT_BLOCKED_FORM, messageFor(4510, cmd), cmd)
        assertEquals(CONTENT_BLOCKED_FORM, defaultMessage(4510), "没有命令时按非消息内容")
        // 账号接口（注册的用户名、昵称；改昵称）：AccountError(451, 4510)
        for (page in listOf(AccountPage.REGISTER, AccountPage.NICKNAME)) assertEquals(CONTENT_BLOCKED_FORM, accountErrorText(AccountError(451, 4510, "server says x"), page), "$page")
    }

    private fun m(status: MessageStatus = MessageStatus.SENT, type: MsgType = MsgType.TEXT) =
        Message(clientMsgId = "c1", msgId = "m1", seq = 1, convId = "c2c:alice:bob", from = "bob", type = type, body = MsgBody(text = "x"), ts = 0, status = status)

    @Test fun issue_77_report_item_after_recall_and_independent_of_msg_actions() { // 第 13.3 节顺序、frontend-chat 第 76、77 条（条件由 SDK canReport 给）
        val all = { listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.RECALL) }
        assertEquals(listOf(MenuAction.COPY, MenuAction.QUOTE, MenuAction.RECALL, MenuAction.REPORT), menuItemsFor(m(), true, report = true, sdkActions = all))
        assertEquals(listOf(MenuAction.COPY, MenuAction.REPORT), menuItemsFor(m(), false, report = true, sdkActions = all), "msgActions 关闭时也有举报")
        assertEquals(listOf(MenuAction.REPORT), menuItemsFor(m(type = MsgType.IMAGE), false, report = true, sdkActions = all), "图片只有举报")
        assertEquals(listOf(MenuAction.COPY, MenuAction.QUOTE, MenuAction.RECALL), menuItemsFor(m(), true, report = false, sdkActions = all))
        assertEquals("举报", MenuAction.REPORT.text)
    }

    @Test fun issue_81_blocked_failure_has_copy_and_delete_but_no_retry() { // frontend-chat 第 81 条（canRetry 由 SDK 给）
        val failed = m(status = MessageStatus.FAILED)
        assertEquals(listOf(MenuAction.COPY, MenuAction.DELETE), menuItemsFor(failed, true, canRetry = false) { listOf(MessageAction.COPY) })
        assertEquals(listOf(MenuAction.COPY, MenuAction.DELETE), menuItemsFor(failed, false, canRetry = false) { emptyList() })
        assertEquals(listOf(MenuAction.COPY, MenuAction.RETRY, MenuAction.DELETE), menuItemsFor(failed, true, canRetry = true) { listOf(MessageAction.COPY) }, "其他失败照旧可以重发")
    }
}
