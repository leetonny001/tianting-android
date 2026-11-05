// 系统通知（PRD-ui F-UI-44、PRD-live F-PUSH-12）：只处理 type=notice。列表只在内存，重启后为空（规则 6，L-4）；
// 偏好里只有 ui.noticeSeen.{userId} 这一个数字（lastSeenSeq），读写失败按"没有"处理。
package com.tianting.app.core

import com.tianting.sdk.Push
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable

data class Notice(val pushId: String, val text: String, val ts: Long, val pushSeq: Long?)

/** n 每条加一：同样的文字也重新显示、重新计时。 */
data class NoticeBanner(val text: String, val n: Int)

data class NoticeSnapshot(val list: List<Notice> = emptyList(), val unseen: Int = 0, val banner: NoticeBanner? = null)

/** UiPrefs 的这一小部分；测试用它造读写失败。 */
interface NoticePrefs {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

const val NOTICE_LIST_MAX = 100
const val NOTICE_BANNER_MS = 5000L
private const val BANNER_CODE_POINTS = 50

fun noticeSeenKey(userId: String) = "ui.noticeSeen.$userId"

/** 前 50 码点（emoji 不被截断成半个）。 */
fun bannerText(text: String): String {
    val end = if (text.codePointCount(0, text.length) <= BANNER_CODE_POINTS) text.length else text.offsetByCodePoints(0, BANNER_CODE_POINTS)
    return "系统通知：${text.substring(0, end)}"
}

/** ts 倒序，ts 相同时 pushSeq 倒序（规则 1）。 */
private val NEWEST_FIRST = compareByDescending<Notice> { it.ts }.thenByDescending { it.pushSeq ?: -1L }

class NoticeStore(
    private val prefs: NoticePrefs,
    private val userId: String,
    private val log: (String) -> Unit = { AppLog.debug(it) },
    private val schedule: (Long, () -> Unit) -> Closeable,
) {
    private val _state = MutableStateFlow(NoticeSnapshot())
    val state: StateFlow<NoticeSnapshot> = _state.asStateFlow()

    private val seenIds = HashSet<String>() // 至少一次语义：业务按 pushId 幂等（PRD-live 4.8 节）
    private var maxSeq = 0L // 本实例收到的 notice 的最大 pushSeq
    private var pages = 0 // 打开着的系统通知页
    private var bannerN = 0
    private var bannerTimer: Closeable? = null

    /** 打开系统通知页：清零，lastSeenSeq 跟上已收到的最大序号（规则 3、4）。 */
    fun enterPage() {
        pages++
        raiseLastSeen()
        _state.value = _state.value.copy(unseen = 0)
    }

    fun leavePage() {
        pages--
    }

    fun dismissBanner() {
        clearBannerTimer()
        _state.value = _state.value.copy(banner = null)
    }

    fun stop() = clearBannerTimer()

    fun onPush(p: Push) {
        if (p.type != "notice") {
            log("push ignored, type=${p.type}") // 只记 type，data 可能含账号（F-PUSH-08 规则 3）
            return
        }
        val text = ((p.data as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null) {
            log("notice without text ignored")
            return
        }
        if (!seenIds.add(p.pushId)) return
        val seq = p.pushSeq
        if (seq != null && seq > maxSeq) maxSeq = seq

        val cur = _state.value
        val list = (cur.list + Notice(p.pushId, text, p.ts, seq)).sortedWith(NEWEST_FIRST).take(NOTICE_LIST_MAX) // 超出的不显示，但仍计数
        var unseen = cur.unseen
        if (pages > 0) {
            raiseLastSeen()
        } else if (!p.replayed) {
            unseen++
        } else if (seq != null) {
            val last = readLastSeen()
            if (last != null && seq > last) unseen++
        }
        if (p.replayed) {
            _state.value = cur.copy(list = list, unseen = unseen)
            return
        }
        clearBannerTimer()
        bannerTimer = schedule(NOTICE_BANNER_MS) { dismissBanner() }
        _state.value = cur.copy(list = list, unseen = unseen, banner = NoticeBanner(bannerText(text), ++bannerN))
    }

    /** 一轮补拉结束：建立基线（L-18 接受的退化：这一轮回放不计未查看）；服务端恢复后序号回退时直接覆盖（规则 4）。 */
    fun onSynced(latestSeq: Long) {
        val last = readLastSeen()
        if (last == null || last > latestSeq) writeLastSeen(latestSeq)
    }

    /** 退出登录时删除；被踢、凭证失效时不删（规则 5）。 */
    fun clearSeen() {
        try {
            prefs.remove(noticeSeenKey(userId))
        } catch (e: RuntimeException) {
            log("notice prefs failed: ${e.javaClass.simpleName}")
        }
    }

    private fun readLastSeen(): Long? = try {
        prefs.get(noticeSeenKey(userId))?.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toLongOrNull()
    } catch (e: RuntimeException) {
        log("notice prefs failed: ${e.javaClass.simpleName}")
        null
    }

    private fun writeLastSeen(value: Long) {
        try {
            prefs.set(noticeSeenKey(userId), value.toString())
        } catch (e: RuntimeException) {
            log("notice prefs failed: ${e.javaClass.simpleName}")
        }
    }

    /** 没有收到带 pushSeq 的通知时不写。 */
    private fun raiseLastSeen() {
        if (maxSeq == 0L) return
        val last = readLastSeen()
        if (last == null || maxSeq > last) writeLastSeen(maxSeq)
    }

    private fun clearBannerTimer() {
        bannerTimer?.close()
        bannerTimer = null
    }
}
