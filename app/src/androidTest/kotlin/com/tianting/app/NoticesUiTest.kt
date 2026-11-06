package com.tianting.app

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.tianting.app.core.AppDeps
import com.tianting.app.core.AppModel
import com.tianting.app.core.NOTICE_LIST_MAX
import com.tianting.app.core.UiPrefs
import com.tianting.app.ui.pages.NOTICES_FOOTER
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 系统通知（PRD-ui F-UI-44、PRD-live F-PUSH-12）：横幅、列表、未查看数、ui.noticeSeen。假服务端。 */
class NoticesUiTest : UiTest() {
    private val prefs get() = testContext.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE)
    private val seenKey = "ui.noticeSeen.alice"

    /** 比 auth 的 serverTime 晚：replayed=false。 */
    private fun live(pushId: String, text: String, seq: Long? = null, type: String = "notice", ts: Long = System.currentTimeMillis() + 60_000): JsonObject {
        val m = linkedMapOf<String, Any?>("pushId" to pushId, "type" to type, "data" to obj("text" to text), "ts" to ts, "target" to if (seq != null) "user" else "all")
        seq?.let { m["pushSeq"] = it }
        return js(m) as JsonObject
    }

    /** 早于 auth 的 serverTime：replayed=true。 */
    private fun old(pushId: String, text: String, seq: Long, ts: Long = T0) = live(pushId, text, seq, ts = ts)

    private fun waitSynced() = waitFor("第一轮补拉") { server.frames("pushSync").isNotEmpty() }

    private fun openMe() {
        go("/me")
        rule.waitTag("me-notices")
    }

    private fun countText(): String? = if (rule.hasTag("me-notices-count")) textOf("me-notices-count") else null

    private fun openNotices() {
        go("/me/notices")
        rule.waitTag("notices-footer")
    }

    private fun itemTexts(): List<String> =
        rule.onAllNodesWithTag("notice-text", useUnmergedTree = true).fetchSemanticsNodes().map { allText(it) }

    @Test fun live_notice_shows_banner_and_list_and_tap_opens_page() { // AC-UI-44-1、F-UI-44 规则 2
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        server.pushFrame(live("p1", "今晚 22 点维护", 1))
        rule.waitTag("notice-banner", 3000)
        n("notice-banner-text").assertTextEquals("系统通知：今晚 22 点维护")
        n("notice-banner").performClick()
        rule.waitTag("notices-footer")
        assertEquals("/me/notices", model.nav.current.path)
        assertFalse(rule.hasTag("notice-banner"), "点击后横幅消失")
        assertEquals(listOf("今晚 22 点维护"), itemTexts())
    }

    @Test fun banner_disappears_after_5_seconds_and_newer_replaces_older() { // F-UI-44 规则 2
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        server.pushFrame(live("p1", "第一条"))
        rule.waitTag("notice-banner", 3000)
        val t = System.currentTimeMillis()
        Thread.sleep(3000)
        server.pushFrame(live("p2", "第二条"))
        rule.waitText("系统通知：第二条", 3000)
        assertFalse(rule.hasText("系统通知：第一条"))
        assertTrue(System.currentTimeMillis() - t < 5000, "第二条要在第一条到期前到")
        Thread.sleep(3500)
        assertTrue(rule.hasTag("notice-banner"), "新的重新计时，第一条的 5 秒到了也不消失")
        rule.waitGoneTag("notice-banner", 3000)
    }

    @Test fun banner_cuts_text_at_50_code_points_without_splitting_emoji() { // F-UI-44 规则 2
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        val emoji = "😀".repeat(60)
        server.pushFrame(live("p1", emoji))
        rule.waitTag("notice-banner", 3000)
        n("notice-banner-text").assertTextEquals("系统通知：" + "😀".repeat(50))
        openNotices()
        assertEquals(listOf(emoji), itemTexts())
    }

    @Test fun other_types_show_nothing() { // AC-UI-44-2
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        server.pushFrame(live("p1", "促销", 1, type = "promo"))
        server.pushFrame(obj("pushId" to "p2", "type" to "notice", "data" to obj("x" to 1), "ts" to T0, "target" to "all"))
        assertNeverWithin(1500, "横幅") { rule.hasTag("notice-banner") }
        openMe()
        assertNull(countText())
        openNotices()
        rule.waitTag("notices-empty")
    }

    @Test fun text_is_plain() { // AC-UI-44-3 的原生对应：标记原样显示
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        val text = "<img src=x onerror=alert(1)> **b**"
        server.pushFrame(live("p1", text))
        rule.waitTag("notice-banner", 3000)
        openNotices()
        assertEquals(listOf(text), itemTexts())
    }

    @Test fun replayed_goes_to_list_without_banner() { // AC-PUSH-12-1
        server.inbox = listOf(old("p1", "旧通知", 1))
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        openNotices()
        rule.waitText("旧通知")
        assertNeverWithin(1000, "横幅") { rule.hasTag("notice-banner") }
    }

    @Test fun list_order_is_ts_desc_then_seq_desc() { // AC-PUSH-12-2、规则 1
        val now = System.currentTimeMillis()
        server.inbox = listOf(old("r1", "ts100", 1, now - 300_000), old("r3", "ts300", 3, now - 100_000), old("r4", "ts300b", 4, now - 100_000))
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        server.pushFrame(old("l1", "ts200", 2, now - 200_000))
        openNotices()
        rule.waitText("ts200")
        waitFor("4 条") { itemTexts().size == 4 }
        assertEquals(listOf("ts300b", "ts300", "ts200", "ts100"), itemTexts())
    }

    @Test fun list_keeps_newest_100_and_counts_the_rest() { // 规则 1
        prefs.edit().putString(seenKey, "0").commit()
        server.inbox = (1L..120L).map { old("p$it", "n$it", it, T0 + it) }
        start()
        rule.waitTag("tab-chats")
        openMe()
        rule.waitTag("me-notices-count")
        waitFor("计满 120") { countText() == "120" }
        openNotices()
        waitFor("列表 100 条") { model.im!!.noticeState.list.size == NOTICE_LIST_MAX }
        assertEquals("n120", model.im!!.noticeState.list.first().text)
        assertEquals("n21", model.im!!.noticeState.list.last().text)
    }

    @Test fun replay_counts_only_above_last_seen_and_opening_clears() { // AC-PUSH-12-3、规则 4
        prefs.edit().putString(seenKey, "40").commit()
        server.inbox = listOf(old("p39", "a", 39), old("p41", "b", 41), old("p42", "c", 42))
        start()
        rule.waitTag("tab-chats")
        waitFor("补拉") { model.im!!.noticeState.list.size == 3 }
        openMe()
        assertEquals("2", countText())
        click("me-notices")
        rule.waitTag("notices-footer")
        waitFor("lastSeenSeq") { prefs.getString(seenKey, null) == "42" }
        go("/me")
        rule.waitTag("me-notices")
        assertNull(countText())
    }

    @Test fun first_use_sets_baseline_and_next_replay_counts() { // AC-PUSH-12-5
        server.inbox = listOf(old("p1", "a", 1), old("p2", "b", 2), old("p3", "c", 3))
        start()
        rule.waitTag("tab-chats")
        waitFor("基线") { prefs.getString(seenKey, null) == "3" }
        openMe()
        assertNull(countText(), "前 3 条不计")
        server.pushFrame(old("p4", "d", 4))
        rule.waitTag("me-notices-count")
        assertEquals("1", countText())
        assertFalse(rule.hasTag("notice-banner"))
    }

    @Test fun live_counts_with_or_without_seq() { // 规则 3
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        openMe()
        server.pushFrame(live("p1", "a", 1))
        rule.waitTag("me-notices-count")
        server.pushFrame(live("p2", "b"))
        waitFor("2 条") { countText() == "2" }
    }

    @Test fun server_restore_rolls_last_seen_back() { // AC-PUSH-12-8
        prefs.edit().putString(seenKey, "100").commit()
        server.latestOverride = 55
        start()
        rule.waitTag("tab-chats")
        waitFor("回退") { prefs.getString(seenKey, null) == "55" }
        openMe()
        server.pushFrame(old("p56", "新", 56))
        rule.waitTag("me-notices-count")
        assertEquals("1", countText())
    }

    @Test fun staying_on_page_does_not_count_and_raises_last_seen() { // AC-PUSH-12-11
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        openNotices()
        server.pushFrame(live("p1", "停留时到", 1))
        rule.waitText("停留时到")
        waitFor("lastSeenSeq") { prefs.getString(seenKey, null) == "1" }
        go("/me")
        rule.waitTag("me-notices")
        assertNull(countText())
    }

    @Test fun prefs_only_hold_ui_keys_and_numbers() { // AC-PUSH-12-10、AC-UI-90-2、NF-UI-13
        val secret = "ZXQ-NOTICE-PREF-TEXT"
        start()
        rule.waitTag("tab-chats")
        waitSynced()
        server.pushFrame(live("p1", secret, 1))
        rule.waitTag("notice-banner", 3000)
        openNotices()
        waitFor("lastSeenSeq") { prefs.getString(seenKey, null) == "1" }
        for ((k, v) in prefs.all) {
            assertTrue(k.startsWith("ui."), k)
            assertFalse(secret in v.toString())
        }
        assertTrue(prefs.getString(seenKey, null)!!.all { it.isDigit() })
    }

    @Test fun logout_deletes_key_and_kick_keeps_it() { // AC-PUSH-12-6
        prefs.edit().putString(seenKey, "9").commit()
        start()
        rule.waitTag("tab-chats")
        waitFor("第一轮补拉结束") { prefs.getString(seenKey, null) == "0" }
        prefs.edit().putString(seenKey, "9").commit()
        inst.runOnMainSync { model.logout() }
        waitFor("退出后删除") { prefs.getString(seenKey, null) == null }
        runBlocking { model.awaitTeardown() }

        prefs.edit().putString(seenKey, "9").commit()
        server.latestOverride = 9 // 否则 9 > latestSeq 会按序号回退覆盖（规则 4）
        install()
        scenarioRestart()
        rule.waitTag("tab-chats")
        waitFor("第二次补拉") { server.frames("pushSync").size >= 2 }
        server.pushEvent("kicked", "reason" to "replaced")
        server.drop(4011)
        click("kicked-ok")
        rule.waitTag("login-username")
        assertEquals("9", prefs.getString(seenKey, null), "被踢不删")
    }

    @Test fun after_restart_only_new_notices_show_with_footer() { // AC-PUSH-12-9
        prefs.edit().putString(seenKey, "1").commit()
        server.inbox = listOf(old("p1", "上次的", 1))
        start()
        rule.waitTag("tab-chats")
        waitFor("上次的") { model.im!!.noticeState.list.size == 1 }
        // 重启：同一个游标存储（内存实现模拟文件）下，新实例不回放已收到的。
        val store = com.tianting.sdk.PushCursorState(1, listOf("p1"))
        val memory = object : com.tianting.sdk.PushCursorStore {
            override fun load(userId: String) = store
            override fun save(userId: String, cursor: Long, recentIds: List<String>) = Unit
        }
        val old = model
        inst.runOnMainSync { old.im!!.stop() }
        runBlocking { old.im!!.client.close() }
        server.inbox = listOf(old("p1", "上次的", 1), old("p2", "离线到的", 2))
        replaceModel(newDeps { v -> com.tianting.sdk.IMClientOptions(url = "ws://fake/ws", transport = server, isVisible = v, pushCursorStore = memory) })
        scenarioRestart()
        rule.waitTag("tab-chats")
        openMe()
        rule.waitTag("me-notices-count")
        assertEquals("1", countText(), "离线到的按规则 3 计入")
        assertFalse(rule.hasTag("notice-banner"))
        click("me-notices")
        rule.waitTag("notices-footer")
        n("notices-footer").assertTextEquals(NOTICES_FOOTER)
        assertEquals(listOf("离线到的"), itemTexts(), "只显示本次打开以来收到的")
        waitFor("lastSeenSeq") { prefs.getString(seenKey, null) == "2" }
    }

    @Test fun deep_link_opens_notices_empty_state_with_footer() { // 路由表（PRD-ui 3.2 节）
        start("tianting://me/notices")
        rule.waitTag("notices-empty")
        n("notices-footer").assertTextEquals(NOTICES_FOOTER)
    }
}
