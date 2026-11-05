package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** 第二批：禁言条、已删除占位（F-UI-41、F-UI-42）。封禁弹窗见 SocialTest、PolicyTest。 */
class ModerationUiTest : UiTest() {
    @Test fun user_mute_with_time_and_unmute() { // AC-UI-41-1、41-3
        val until = System.currentTimeMillis() + 600_000
        server.convs = listOf(convJson(BOB_CONV, "bob"), convJson(G1, "群", memberCount = 3))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("composer-input")
        server.pushEvent("muted", "scope" to "user", "mutedUntil" to until)
        rule.waitTag("composer-disabled")
        n("composer-disabled").assertTextEquals("你已被禁言，解除时间：${formatMonthDayTime(until)}")
        assertFalse(rule.hasTag("composer-input"))
        inst.runOnMainSync { model.nav.resetTo(chatPath(G1)) }
        rule.waitTag("composer-disabled")
        server.pushEvent("unmuted", "scope" to "user")
        rule.waitTag("composer-input")
        assertFalse(rule.hasTag("composer-disabled"))
    }

    @Test fun group_mute_only_affects_that_group() { // AC-UI-41-2
        server.convs = listOf(convJson(G1, "群一", memberCount = 3), convJson("group:g2", "群二", memberCount = 3), convJson(BOB_CONV, "bob"))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("composer-input")
        server.pushEvent("muted", "scope" to "group", "groupId" to "g1", "mutedUntil" to null)
        rule.waitTag("composer-disabled")
        n("composer-disabled").assertTextEquals("你已被禁言") // AC-UI-41-5
        inst.runOnMainSync { model.nav.resetTo(chatPath("group:g2")) }
        rule.waitTag("composer-input")
        inst.runOnMainSync { model.nav.resetTo(chatPath(BOB_CONV)) }
        rule.waitTag("composer-input")
    }

    @Test fun mute_expires_by_timer() { // AC-UI-41-4：SDK 的定时器到点恢复（真实时间短禁言）
        server.convs = listOf(convJson(BOB_CONV, "bob"))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("composer-input")
        server.pushEvent("muted", "scope" to "user", "mutedUntil" to System.currentTimeMillis() + 1500)
        rule.waitTag("composer-disabled")
        rule.waitTag("composer-input", 5000)
    }

    @Test fun send_4031_marks_failed_without_toast() { // F-UI-41 规则 5
        server.convs = listOf(convJson(BOB_CONV, "bob"))
        server.fail("send", 4031, details = obj("scope" to "user", "mutedUntil" to null))
        start("tianting://chat/c2c%3Aalice%3Abob")
        type("composer-input", "说话")
        click("send-button")
        rule.waitTag("composer-disabled")
        n("composer-disabled").assertTextEquals("你已被禁言")
        rule.waitTag("failed-icon")
        assertNeverWithin(500, "toast") { rule.hasTag("toast") }
    }

    @Test fun deleted_message_placeholder() { // AC-UI-42-1、42-4
        server.convs = listOf(convJson(G1, "群", maxSeq = 2, memberCount = 3, lastMsg = lastMsg("bob", "特征串ZXQ-DEL-1")))
        server.messages[G1] = listOf(msgJson(G1, 1, "bob", "特征串ZXQ-DEL-1"), msgJson(G1, 2, "bob", "别的"))
        start("tianting://chat/group%3Ag1")
        rule.waitText("特征串ZXQ-DEL-1")
        server.pushEvent("msgDeleted", "convId" to G1, "msgIds" to listOf("group:g1#1"), "seqs" to listOf(1))
        rule.waitTag("msg-deleted")
        n("msg-deleted").assertTextEquals("该消息已被删除")
        assertFalse(rule.hasText("ZXQ-DEL-1", substring = true))
        n("msg-deleted").performTouchInput { longClick() }
        assertNeverWithin(600, "menu") { rule.hasTag("bubble-menu") }
    }

    @Test fun sync_returning_deleted_shows_placeholder() { // AC-UI-42-3
        server.convs = listOf(convJson(G1, "群", maxSeq = 1, memberCount = 3))
        server.messages[G1] = listOf(deletedJson(G1, 1, "bob"))
        start("tianting://chat/group%3Ag1")
        rule.waitTag("msg-deleted")
        assertFalse(rule.hasTag("bubble"))
    }
}
