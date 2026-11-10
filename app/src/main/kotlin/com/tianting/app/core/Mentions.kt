// @ 的输入模型（PRD-chat F-MEN-01 规则 5，R-CF-16）：文本 + [{start, len, userId}]。纯函数，输入区和 JVM 单测共用。
// 语义和 tianting-web 的 src/lib/mentions.ts 相同（FE-666）。位置按 UTF-16 下标（和 TextFieldValue 的 selection 一致）。
package com.tianting.app.core

import com.tianting.sdk.Mentions

/** 输入框里的一段 @：文本里 [start, start + len) 对应 userId；all 是"@所有人"（F-MEN-02），这时 userId 为空。 */
data class MentionRange(val start: Int, val len: Int, val userId: String, val all: Boolean = false) {
    val end: Int get() = start + len
}

/**
 * 草稿（PRD-ui F-UI-24，PRD-chat F-MEN-01 规则 5"草稿改为结构化"）：文字、@ 区间、引用（只记 msgId，显示时取已加载的那条，
 * 被撤回后不会留着原文，问题 41）。只在内存（问题 15），按 convId；退出登录换一个 ImSession，随之清空。
 */
data class Draft(val text: String, val mentions: List<MentionRange> = emptyList(), val quote: String? = null) {
    /** 文字去掉首尾空白后为空、也没有引用：不算草稿。 */
    val isEmpty: Boolean get() = text.isBlank() && quote == null
}

private fun isAt(c: Char?) = c == '@' || c == '＠'

/**
 * 文本从 prev 变成 next 之后的 @ 区间：改动范围前面的不动，后面的按长度差平移，和改动范围有交叠的整段去掉
 * （删掉"@小王 "里任何一个字，这个 @ 就不算了，AC-MEN-01-5）。
 */
fun reconcile(prev: String, next: String, ranges: List<MentionRange>): List<MentionRange> {
    if (prev == next) return ranges
    val max = minOf(prev.length, next.length)
    var head = 0
    while (head < max && prev[head] == next[head]) head++
    var tail = 0
    while (tail < max - head && prev[prev.length - 1 - tail] == next[next.length - 1 - tail]) tail++
    val oldEnd = prev.length - tail // prev 里被替换的是 [head, oldEnd)
    val delta = next.length - prev.length
    val out = ArrayList<MentionRange>(ranges.size)
    for (r in ranges) {
        if (r.end <= head) out.add(r) else if (r.start >= oldEnd) out.add(r.copy(start = r.start + delta))
    }
    return out
}

/**
 * 这次改动是不是"在行首或空白后敲了一个 @ / ＠"（AC-MEN-01-8）：是就返回 @ 的位置，否则 null。
 * 一次插入多个字（粘贴"@u2"）不算；a@b.com 的 @ 前面是字母，不算。
 */
fun triggerAt(prev: String, next: String): Int? {
    if (next.length != prev.length + 1) return null
    var i = 0
    while (i < prev.length && prev[i] == next[i]) i++
    if (next.substring(0, i) + next.substring(i + 1) != prev) return null
    if (!isAt(next[i])) return null
    return if (i == 0 || next[i - 1].isWhitespace()) i else null
}

/**
 * 光标落在某段 @ 内部（不含两端）时挪到边上（规则 5：光标不能落进 mention 内部）：从后面往左移进来的去前端，
 * 其余（点进去、从前面往右移）去后端。prev 是上一次的光标位置。
 */
fun clampCaret(pos: Int, ranges: List<MentionRange>, prev: Int? = null): Int {
    for (r in ranges) {
        if (pos > r.start && pos < r.end) return if (prev != null && prev >= r.end) r.start else r.end
    }
    return pos
}

/** 改动后的结果：文本、区间、光标。 */
data class MentionEdit(val text: String, val ranges: List<MentionRange>, val caret: Int)

/**
 * 退格：光标（没有选区）正好在一段 @ 后面，这次改动只是删掉了它前面的一部分（软键盘、硬键盘的退格），就把整段删掉（AC-MEN-01-12）。
 * prevCaret 是改动前的光标；不是这种情况返回 null。
 */
fun backspaceMention(prev: String, prevCaret: Int, next: String, ranges: List<MentionRange>): MentionEdit? {
    if (next.length >= prev.length) return null
    val removed = prev.length - next.length
    val from = prevCaret - removed
    if (from < 0 || prev.substring(0, from) + prev.substring(prevCaret) != next) return null
    val hit = ranges.firstOrNull { it.end == prevCaret && from >= it.start } ?: return null
    val text = prev.substring(0, hit.start) + prev.substring(prevCaret)
    return MentionEdit(text, reconcile(prev, text, ranges), hit.start)
}

/** 在 at 位置把那个 @ 换成"@{名字} "，记下区间（R-CF-03：名字是公开昵称，没有用 userId，不用备注）。userId 为 null 是 @所有人。 */
fun insertMention(text: String, ranges: List<MentionRange>, at: Int, name: String, userId: String?): MentionEdit {
    val replaced = if (isAt(text.getOrNull(at))) 1 else 0
    val piece = "@$name "
    val without = text.substring(0, at) + text.substring(at + replaced)
    val next = text.substring(0, at) + piece + text.substring(at + replaced)
    val moved = reconcile(text, without, ranges).map { if (it.start >= at) it.copy(start = it.start + piece.length) else it }
    val range = if (userId == null) MentionRange(at, piece.length, "", all = true) else MentionRange(at, piece.length, userId)
    return MentionEdit(next, (moved + range).sortedBy { it.start }, at + piece.length)
}

/** 发送时带的 mentions：区间里的文字还是以 @ 开头的才算；userIds 去重；都没有时 null（不带）。 */
fun toMentions(text: String, ranges: List<MentionRange>): Mentions? {
    val ids = ArrayList<String>()
    var all = false
    for (r in ranges) {
        if (!isAt(text.getOrNull(r.start)) || r.end > text.length) continue
        if (r.all) all = true else if (r.userId !in ids) ids.add(r.userId)
    }
    if (!all && ids.isEmpty()) return null
    return Mentions(ids, all)
}

/**
 * 输入框的状态（不依赖 Compose，单测直接用）：文字、选区、@ 区间。composeBase 是组字开始前的文字（组字中为非 null）。
 */
data class EditState(
    val text: String,
    val selStart: Int,
    val selEnd: Int = selStart,
    val ranges: List<MentionRange> = emptyList(),
    val composeBase: String? = null,
)

/** 一次输入的结果：新状态；trigger 不为 null 时要打开成员选择，值是 @ 的位置。 */
data class EditResult(val state: EditState, val trigger: Int?)

/**
 * 输入框的一次变化（Composer 的 onValueChange）。composing 是输入法正在组字（TextFieldValue.composition 不为 null）。
 * - 组字中不改模型、不打开选择（AC-MEN-01-9，规则 5）：只记下组字开始前的文字，组字结束（上屏）时按它算一次；
 * - 没有选区、光标正好在一段 @ 后面时的退格：整段删掉，区间一起去掉（AC-MEN-01-12）；
 * - 其余：区间按改动平移或去掉（AC-MEN-01-5）；只移动光标时，光标不落进 @ 内部；
 * - canMention（群聊且 features.msgActions 打开）时，行首或空白后敲的 @ / ＠ 打开选择（AC-MEN-01-6、01-8）。
 */
fun applyEdit(old: EditState, text: String, selStart: Int, selEnd: Int, composing: Boolean, canMention: Boolean): EditResult {
    if (composing) return EditResult(old.copy(text = text, selStart = selStart, selEnd = selEnd, composeBase = old.composeBase ?: old.text), null)
    val fromComposition = old.composeBase != null
    val prev = old.composeBase ?: old.text
    if (!fromComposition && selStart == selEnd && old.selStart == old.selEnd) {
        backspaceMention(old.text, old.selStart, text, old.ranges)?.let { r ->
            return EditResult(EditState(r.text, r.caret, r.caret, r.ranges), null)
        }
    }
    val ranges = reconcile(prev, text, old.ranges)
    var s = selStart
    var e = selEnd
    if (s == e && prev == text) {
        val c = clampCaret(s, ranges, old.selStart)
        s = c
        e = c
    }
    val trigger = if (canMention) triggerAt(prev, text) else null
    return EditResult(EditState(text, s, e, ranges), trigger)
}
