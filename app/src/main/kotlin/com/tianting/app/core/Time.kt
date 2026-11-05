// 时间显示（PRD-core F-MSG-06 规则 4）。一律用本机时区格式化服务端时间。
package com.tianting.app.core

import java.util.Calendar
import java.util.TimeZone

private fun pad(n: Int) = n.toString().padStart(2, '0')

private fun cal(ts: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = ts }

private fun startOfDay(ts: Long, tz: TimeZone) = cal(ts, tz).apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 今天 HH:mm，昨天"昨天 HH:mm"，今年 MM-DD HH:mm，更早 YYYY-MM-DD HH:mm。 */
fun formatTime(ts: Long, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String {
    val d = cal(ts, tz)
    val hm = "${pad(d.get(Calendar.HOUR_OF_DAY))}:${pad(d.get(Calendar.MINUTE))}"
    val today = startOfDay(now, tz)
    if (ts >= today) return hm
    val yesterday = cal(today, tz).apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    if (ts >= yesterday) return "昨天 $hm"
    return if (d.get(Calendar.YEAR) == cal(now, tz).get(Calendar.YEAR)) formatMonthDayTime(ts, tz) else formatDateTime(ts, tz)
}

/** MM-DD HH:mm（本机时区）。 */
fun formatMonthDayTime(ts: Long, tz: TimeZone = TimeZone.getDefault()): String {
    val d = cal(ts, tz)
    return "${pad(d.get(Calendar.MONTH) + 1)}-${pad(d.get(Calendar.DAY_OF_MONTH))} ${pad(d.get(Calendar.HOUR_OF_DAY))}:${pad(d.get(Calendar.MINUTE))}"
}

/** YYYY-MM-DD HH:mm（本机时区）。 */
fun formatDateTime(ts: Long, tz: TimeZone = TimeZone.getDefault()): String = "${cal(ts, tz).get(Calendar.YEAR)}-${formatMonthDayTime(ts, tz)}"

const val DIVIDER_GAP_MS = 5 * 60 * 1000L

/** 第 i 条消息前是否显示时间分隔：第一条显示；与上一条间隔超过 5 分钟显示。 */
fun needsDivider(prevTs: Long?, ts: Long): Boolean = prevTs == null || ts - prevTs > DIVIDER_GAP_MS
