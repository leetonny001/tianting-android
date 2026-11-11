// 举报和内容拦截（PRD-chat 第 10 节 F-MOD-02、F-MOD-03，C6，FE-694）的文案和纯函数。界面在 ui/pages/ReportSheet.kt。
package com.tianting.app.core

import com.tianting.sdk.IMError
import com.tianting.sdk.REPORT_NOTE_MAX
import com.tianting.sdk.ReportReason
import com.tianting.sdk.countCodePoints
import com.tianting.sdk.trimText

/** 4510（P-48 ⑥）：消息被拦截。文案来自 SDK 的"send:4510"，这里只用于测试和对照。 */
const val CONTENT_BLOCKED_SEND = "消息包含不当内容，无法发送"

/** 4510（P-48 ⑥）：昵称、用户名、群名、群公告、好友申请附言被拦截，表单内容保留可修改。 */
const val CONTENT_BLOCKED_FORM = "内容包含不当信息，请修改后重试"

/** 提交前的提示（F-MOD-03 规则 8）。 */
const val REPORT_HINT = "所选内容将提交给平台审核"

/** 提交成功的轻提示（AC-MOD-03-9）。 */
const val REPORT_DONE = "已提交，感谢你的反馈"

/** 举报成功后询问拉黑（单聊，frontend-chat 第 79 条）；下面接资料卡拉黑确认框的说明，文案同 H5。 */
const val REPORT_ASK_BLOCK = "要把对方加入黑名单吗？"

/** 6 个理由和界面文案（frontend-chat 第 78 条，三端和后台一致）：默认不选，必须选一个才能提交。 */
val REPORT_REASONS: List<Pair<ReportReason, String>> = listOf(
    ReportReason.SPAM to "垃圾广告",
    ReportReason.ABUSE to "辱骂骚扰",
    ReportReason.PORN to "色情低俗",
    ReportReason.FRAUD to "诈骗",
    ReportReason.ILLEGAL to "违法违规",
    ReportReason.OTHER to "其他",
)

/** 补充说明被截断时的提示（frontend-chat 第 84 条，文案同 H5）。 */
const val REPORT_NOTE_CLIPPED = "补充说明最多 $REPORT_NOTE_MAX 个字，超出的部分已去掉"

/** 截断到 max 个码点（不拆开代理对）。 */
fun clampCodePoints(s: String, max: Int = REPORT_NOTE_MAX): String =
    if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

/** 补充说明输入后的结果：value 是输入框的新内容，clipped 为真时显示"已去掉"的提示。 */
data class NoteInput(val value: String, val clipped: Boolean)

/**
 * 补充说明的输入（frontend-chat 第 84 条）：按码点数，最多 200；超出时不能再输入（一次只多出 1 个码点，保留原内容），
 * 粘贴超长的（一次多出不止 1 个码点）截断到 200 并提示。
 */
fun reportNoteInput(old: String, new: String): NoteInput {
    val n = countCodePoints(new)
    if (n <= REPORT_NOTE_MAX) return NoteInput(new, false)
    if (n - countCodePoints(old) <= 1) return NoteInput(old, false)
    return NoteInput(clampCodePoints(new), true)
}

/** 提交的 note：去首尾空白，空的不带。 */
fun reportNote(note: String): String? = trimText(note).ifEmpty { null }

/**
 * 举报失败的提示（frontend-chat 第 83 条）：4040"无法举报该内容"、4030"举报功能暂不可用"、4292"今天的举报次数已用完"，
 * 其他码按"命令 + 码"表；没有拿到服务端错误码的（超时、断线）是网络问题。不自动重试，不带 note。
 */
fun reportErrorText(e: Throwable): String = if (e is IMError) messageFor(e.code, "report") else NETWORK_ERROR_TEXT

/** 这些码再点提交也没有用：提示后关掉举报单（同 H5）。其他的（5000、4290、超时、断线）留着举报单，可以再点提交。 */
fun reportErrorFinal(e: Throwable): Boolean = e is IMError && e.code in setOf(4040, 4030, 4292)
