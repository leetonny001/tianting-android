package com.tianting.app

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.tianting.app.core.Draft
import com.tianting.app.ui.DataConvId
import com.tianting.app.ui.DataPinned
import com.tianting.app.ui.theme.DarkTokens
import com.tianting.app.ui.theme.LightTokens
import com.tianting.app.ui.theme.ThemePref
import com.tianting.sdk.MessageStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FE-684（PRD-chat C5：会话菜单、置顶和免打扰显示、删除、总未读）的页面状态：假服务端 + 假账号接口（features.convSettings），
 * 只验页面对 SDK 结果的处理。真实 im-server（IM_CONV_SETTINGS_ENABLED=1）的流程在 C5E2eTest、C5KillE2e。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class C5UiTest : UiTest() {
    private val carolConv = "c2c:alice:carol"
    private val daveConv = "c2c:alice:dave"

    @Before fun on() {
        http.convSettings = true
        http.msgActions = true
        serveSettings()
    }

    // ---- 假服务端的 convSet、convDelete：和真实服务端一样回新状态，之后的 convs 也按它 ----

    private fun idOf(o: JsonObject) = o["convId"]!!.jsonPrimitive.content

    private fun serveSettings() {
        server.handlers["convSet"] = { f ->
            val id = f.data["convId"]!!.jsonPrimitive.content
            val cur = server.convs.first { idOf(it) == id }
            val m = LinkedHashMap<String, JsonElement>(cur)
            f.data["pinned"]?.jsonPrimitive?.booleanOrNull?.let { p ->
                if (p) {
                    m["pinned"] = JsonPrimitive(true)
                    m["pinnedAt"] = cur["pinnedAt"] ?: JsonPrimitive(System.currentTimeMillis())
                } else {
                    m["pinned"] = JsonPrimitive(false)
                    m.remove("pinnedAt")
                }
            }
            f.data["notifyOff"]?.let { m["notifyOff"] = it }
            val next = JsonObject(m)
            server.convs = server.convs.map { if (idOf(it) == id) next else it }
            Reply.Data(obj("conv" to next))
        }
        server.handlers["convDelete"] = { f ->
            val id = f.data["convId"]!!.jsonPrimitive.content
            server.convs = server.convs.filter { idOf(it) != id }
            server.messages[id] = emptyList()
            Reply.Data(obj("clearedSeq" to f.data["upToSeq"]!!.jsonPrimitive.longOrNull!!))
        }
    }

    private fun openList() {
        start()
        waitFor("config") { model.im?.convSettings == true }
        rule.waitTag("conv-item")
    }

    private val itemOf = { id: String -> hasTestTag("conv-item") and withData(DataConvId, id) }
    private fun item(id: String) = rule.onNode(itemOf(id), useUnmergedTree = true)
    private fun inItem(id: String, tag: String) = rule.onNode(hasTestTag(tag) and hasAnyAncestor(itemOf(id)), useUnmergedTree = true)
    private fun hasInItem(id: String, tag: String) = rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(itemOf(id)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun order(): List<String> = rule.onAllNodesWithTag("conv-item", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[DataConvId] }
    private fun pinnedMark(id: String) = item(id).fetchSemanticsNode().config.getOrNull(DataPinned)

    private fun longPress(id: String) {
        rule.waitUntil(5000) { rule.onAllNodes(itemOf(id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        item(id).performTouchInput { longClick() }
        rule.waitTag("conv-menu")
    }

    private fun menuTexts(): List<String> = listOf("conv-menu-pin", "conv-menu-notify", "conv-menu-delete").map { textOf(it) }

    private fun closeMenu() {
        androidx.test.espresso.Espresso.pressBack()
        rule.waitGoneTag("conv-menu")
    }

    /** 会话项背景的颜色：取右上角的内边距（没有文字）。按下的水波纹消失之前会变，所以等它等于期望值。 */
    private fun bgOf(id: String): Int = item(id).captureToImage().asAndroidBitmap().let { it.getPixel(it.width - 4, 4) }
    private fun waitBg(id: String, argb: Int, what: String) {
        var last = 0
        try {
            waitFor(what, 3000) { bgOf(id).also { last = it } == argb }
        } catch (e: AssertionError) {
            throw AssertionError("$what：期望 ${Integer.toHexString(argb)}，实际 ${Integer.toHexString(last)}", e)
        }
    }

    private fun customActions(id: String): List<androidx.compose.ui.semantics.CustomAccessibilityAction> =
        item(id).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun perform(id: String, label: String) {
        val a = customActions(id).first { it.label == label }
        inst.runOnMainSync { a.action() }
        rule.waitForIdle()
    }

    private fun threeConvs(gPinned: Boolean = true) {
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 2, lastMsg = lastMsg("bob", "最新的"), updatedAt = T0 + 3000),
            convJson(carolConv, "carol", maxSeq = 1, lastMsg = lastMsg("carol", "中间的"), updatedAt = T0 + 2000),
            convJson(G1, "周末球局", maxSeq = 1, lastMsg = lastMsg("bob", "最旧的"), updatedAt = T0 + 1000, pinnedAt = if (gPinned) T0 else null, notifyOff = gPinned),
        )
    }

    // ---- 会话菜单（F-CVS-01、AC-CVS-01-6） ----

    @Test fun tc_cvs_01_06_long_press_menu_by_state_pin_moves_first_with_token_background() { // TC-CVS-01-06、AC-CVS-01-6
        threeConvs()
        openList()
        assertEquals(listOf(G1, BOB_CONV, carolConv), order(), "置顶的在最前（F-CVS-01 规则 3）")
        assertEquals("true", pinnedMark(G1))
        longPress(carolConv)
        assertEquals(listOf("置顶", "免打扰", "删除"), menuTexts())
        closeMenu()
        longPress(G1)
        assertEquals(listOf("取消置顶", "取消免打扰", "删除"), menuTexts(), "文字按当前状态")
        closeMenu()
        waitBg(G1, LightTokens.surfacePinned.toArgb(), "置顶项是 color.surface.pinned")
        waitBg(BOB_CONV, LightTokens.surface.toArgb(), "普通项是 color.surface")
        assertTrue(LightTokens.surfacePinned != LightTokens.surface && DarkTokens.surfacePinned != DarkTokens.surface)
        // 选"置顶"：发 convSet，这一项移到最前（新置顶的 pinnedAt 最大），背景变成置顶色
        longPress(carolConv)
        click("conv-menu-pin")
        rule.waitGoneTag("conv-menu")
        waitFor("convSet") { server.frames("convSet").isNotEmpty() }
        val f = server.frames("convSet").single()
        assertEquals(carolConv, f.data["convId"]!!.jsonPrimitive.content)
        assertEquals(true, f.data["pinned"]!!.jsonPrimitive.booleanOrNull)
        assertNull(f.data["notifyOff"], "只改置顶")
        waitFor("carol 移到最前", 2000) { order() == listOf(carolConv, G1, BOB_CONV) }
        waitBg(carolConv, LightTokens.surfacePinned.toArgb(), "新置顶的背景")
        // 深色：同一个 token 的深色值，仍和普通项不同
        inst.runOnMainSync { model.setTheme(ThemePref.DARK) }
        waitBg(carolConv, DarkTokens.surfacePinned.toArgb(), "深色的置顶背景")
        waitBg(BOB_CONV, DarkTokens.surface.toArgb(), "深色的普通背景")
        // 取消置顶：回到按时间排
        longPress(carolConv)
        assertEquals("取消置顶", textOf("conv-menu-pin"))
        click("conv-menu-pin")
        waitFor("取消置顶后按时间") { order() == listOf(G1, BOB_CONV, carolConv) }
        assertEquals(false, server.frames("convSet").last().data["pinned"]!!.jsonPrimitive.booleanOrNull)
        waitBg(carolConv, DarkTokens.surface.toArgb(), "取消置顶后的背景")
    }

    @Test fun tc_cvs_01_06_switch_off_no_menu_but_pinned_still_shown() { // TC-CVS-01-06 预期 3、P-46 ②
        for (flag in listOf<Boolean?>(false, null)) { // 关闭；老服务端没有这个字段
            http.convSettings = flag
            threeConvs()
            start()
            rule.waitTag("conv-item")
            Thread.sleep(500) // 配置到了也还是关
            assertFalse(model.im!!.convSettings)
            val node = item(carolConv).fetchSemanticsNode()
            assertNull(node.config.getOrNull(SemanticsActions.OnLongClick), "没有长按动作")
            assertTrue(node.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty(), "没有辅助操作")
            // 已有的置顶、免打扰照常显示
            assertEquals(G1, order().first())
            waitBg(G1, LightTokens.surfacePinned.toArgb(), "开关关闭时置顶背景照常")
            assertTrue(hasInItem(G1, "notify-off-icon"))
            // 长按没有菜单（松手按点击处理，和一期一样进入聊天页）
            item(carolConv).performTouchInput { longClick() }
            assertNeverWithin(1000, "开关关闭时长按出菜单") { rule.hasTag("conv-menu") }
            finish()
        }
    }

    @Test fun tc_chat_90_37_accessibility_actions_pin_notify_delete() { // TC-CHAT-90-37 第 2 步、第 13.4 节"长按菜单要有辅助操作"
        threeConvs(gPinned = false)
        openList()
        assertEquals("会话操作", item(carolConv).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)?.label)
        assertEquals(listOf("置顶", "免打扰", "删除"), customActions(carolConv).map { it.label })
        perform(carolConv, "置顶")
        waitFor("convSet") { server.frames("convSet").size == 1 }
        waitFor("置顶了", 2000) { order().first() == carolConv && pinnedMark(carolConv) == "true" }
        waitFor("文字跟着变") { customActions(carolConv).map { it.label } == listOf("取消置顶", "免打扰", "删除") }
        perform(carolConv, "免打扰")
        waitFor("免打扰了", 2000) { hasInItem(carolConv, "notify-off-icon") }
        assertEquals(true, server.frames("convSet").last().data["notifyOff"]!!.jsonPrimitive.booleanOrNull)
        perform(carolConv, "删除")
        rule.waitTag("conv-delete-dialog")
        assertTrue(server.frames("convDelete").isEmpty(), "辅助操作的删除也先确认")
        click("conv-delete-dialog-cancel")
    }

    // ---- 免打扰显示（F-CVS-02） ----

    @Test fun tc_cvs_02_03_dot_count_prefix_icon_and_priority() { // TC-CVS-02-03、AC-CVS-02-3；frontend-chat 第 61 条
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "晚上好"), updatedAt = T0 + 3000, notifyOff = true),
            convJson(G1, "周末球局", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "@爱丽丝 来吗"), updatedAt = T0 + 2000, notifyOff = true, mentionUnread = 1, firstMentionSeq = 3),
            convJson(carolConv, "carol", maxSeq = 120, unread = 120, lastMsg = lastMsg("carol", "很多"), updatedAt = T0 + 1000, notifyOff = true),
            convJson(daveConv, "dave", maxSeq = 2, unread = 2, lastMsg = lastMsg("dave", "普通"), updatedAt = T0),
        )
        openList()
        // 小红点（没有数字）、"[5 条]"、免打扰图标（有可读名称）
        rule.waitUntil(5000) { hasInItem(BOB_CONV, "conv-dot") }
        assertFalse(hasInItem(BOB_CONV, "conv-badge"), "免打扰没有数字角标")
        assertEquals("[5 条] 晚上好", textOf(BOB_CONV, "unread-count-tag"))
        assertEquals("免打扰", inItem(BOB_CONV, "notify-off-icon").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        // N 照实显示
        assertEquals("[120 条] 很多", textOf(carolConv, "unread-count-tag"))
        assertTrue(hasInItem(carolConv, "conv-dot"))
        // 有 @ 我：仍是小红点，前缀"[有人@我]"
        assertTrue(hasInItem(G1, "conv-dot"))
        assertFalse(hasInItem(G1, "conv-badge"))
        assertTrue(textOf(G1, "mention-tag").startsWith("[有人@我] "))
        assertFalse(hasInItem(G1, "unread-count-tag"))
        // 普通会话：数字角标，没有前缀、没有图标
        assertEquals("2", allText(inItem(daveConv, "conv-badge").fetchSemanticsNode()))
        assertFalse(hasInItem(daveConv, "conv-dot") || hasInItem(daveConv, "notify-off-icon") || hasInItem(daveConv, "unread-count-tag"))
        // 有草稿："[草稿]"盖过"[N 条]"
        inst.runOnMainSync { model.im!!.drafts[BOB_CONV] = Draft("明天见") }
        rule.waitUntil(3000) { hasInItem(BOB_CONV, "draft-tag") }
        assertEquals("[草稿] 明天见", textOf(BOB_CONV, "draft-tag"))
        assertFalse(hasInItem(BOB_CONV, "unread-count-tag"))
        assertTrue(hasInItem(BOB_CONV, "conv-dot") && hasInItem(BOB_CONV, "notify-off-icon"))
    }

    private fun textOf(id: String, tag: String) = allText(inItem(id, tag).fetchSemanticsNode())
    private fun tabBadge(): String? = rule.onAllNodesWithTag("tab-badge", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { allText(it) }

    @Test fun tc_cvs_02_02_tab_badge_excludes_notify_off_then_10_after_turning_it_off() { // TC-CVS-02-02、AC-CVS-02-2
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 2, unread = 2, lastMsg = lastMsg("bob", "a"), updatedAt = T0 + 3000),
            convJson(carolConv, "carol", maxSeq = 3, unread = 3, lastMsg = lastMsg("carol", "b"), updatedAt = T0 + 2000),
            convJson(G1, "群", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "c"), updatedAt = T0 + 1000, notifyOff = true),
        )
        openList()
        waitFor("角标 5") { tabBadge() == "5" }
        longPress(G1)
        assertEquals("取消免打扰", textOf("conv-menu-notify"))
        click("conv-menu-notify")
        waitFor("取消免打扰后 10", 2000) { tabBadge() == "10" }
        assertEquals(false, server.frames("convSet").single().data["notifyOff"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(hasInItem(G1, "conv-badge") && !hasInItem(G1, "conv-dot"), "回到数字角标")
    }

    @Test fun tc_cvs_02_05_mention_in_notify_off_counts_and_02_04_none_when_all_muted() { // TC-CVS-02-05、AC-CVS-02-5；TC-CVS-02-04 的角标部分
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 2, unread = 2, lastMsg = lastMsg("bob", "a"), updatedAt = T0 + 3000),
            convJson(G1, "群", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "c"), updatedAt = T0 + 1000, notifyOff = true, mentionUnread = 1, firstMentionSeq = 4),
        )
        openList()
        waitFor("角标 3") { tabBadge() == "3" }
        finish()
        // 未读全在免打扰会话、没有 @：不显示角标
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 2, unread = 0, lastMsg = lastMsg("bob", "a"), updatedAt = T0 + 3000),
            convJson(G1, "群", maxSeq = 7, unread = 7, lastMsg = lastMsg("bob", "c"), updatedAt = T0 + 1000, notifyOff = true),
        )
        openList()
        rule.waitUntil(5000) { hasInItem(G1, "conv-dot") }
        assertNeverWithin(1000, "免打扰的未读进了角标") { tabBadge() != null }
    }

    // ---- 失败（F-CVS-01 异常，frontend-chat 第 56 条） ----

    @Test fun issue_56_failures_roll_back_toast_and_never_retry() { // 4220、4290、5000：回滚、提示、不自动重试
        threeConvs(gPinned = false)
        openList()
        for ((code, text) in listOf(4220 to "最多置顶 20 个会话", 4290 to "操作过于频繁", 5000 to "服务异常，请稍后重试")) {
            server.fail("convSet", code)
            val before = server.frames("convSet").size
            longPress(carolConv)
            click("conv-menu-pin")
            rule.waitText(text)
            waitFor("回滚") { pinnedMark(carolConv) == "false" && order() == listOf(BOB_CONV, carolConv, G1) }
            assertNeverWithin(1500, "自动重试 $code") { server.frames("convSet").size > before + 1 }
            rule.waitGoneText(text, 8000)
        }
    }

    // ---- 删除（F-CVS-03、AC-CVS-03-8） ----

    private fun withBobHistory() {
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 2, lastMsg = lastMsg("bob", "第二条"), updatedAt = T0 + 3000), convJson(carolConv, "carol", maxSeq = 1, lastMsg = lastMsg("carol", "x"), updatedAt = T0))
        server.messages[BOB_CONV] = listOf(msgJson(BOB_CONV, 1, "bob", "第一条"), msgJson(BOB_CONV, 2, "bob", "第二条"))
    }

    /** 和 bob 的会话：一条发送失败的消息，输入框里留草稿，回到列表。 */
    private fun failedAndDraft() {
        go("/chat/" + android.net.Uri.encode(BOB_CONV))
        rule.waitText("第一条")
        server.fail("send", 5000)
        type("composer-input", "会失败的")
        clickSendWhenSettled()
        waitFor("失败") { model.im!!.client.messages(BOB_CONV).any { it.status == MessageStatus.FAILED } }
        server.handlers.remove("send")
        type("composer-input", "明天见")
        inst.runOnMainSync { model.nav.setRoot("/chats") }
        rule.waitTag("conv-item")
        rule.waitUntil(3000) { hasInItem(BOB_CONV, "draft-tag") }
    }

    @Test fun tc_cvs_03_08_confirm_cancel_keeps_everything_ok_clears_draft_and_failed() { // TC-CVS-03-08（假服务端部分；缓存文件见 C5E2eTest）
        withBobHistory()
        openList()
        failedAndDraft()
        longPress(BOB_CONV)
        click("conv-menu-delete")
        rule.waitTag("conv-delete-dialog")
        rule.waitText("删除后聊天记录不再显示，确定删除？")
        assertEquals("删除", textOf("conv-delete-dialog-ok"))
        click("conv-delete-dialog-cancel")
        rule.waitGoneTag("conv-delete-dialog")
        // 取消：都不变
        assertNeverWithin(800, "取消也删了") { server.frames("convDelete").isNotEmpty() }
        assertTrue(hasInItem(BOB_CONV, "draft-tag"))
        assertEquals(Draft("明天见"), model.im!!.drafts[BOB_CONV])
        assertTrue(model.im!!.client.messages(BOB_CONV).any { it.status == MessageStatus.FAILED })
        // 确认：upToSeq 是本地看到的最大 seq；会话消失，草稿、失败消息都没有了
        longPress(BOB_CONV)
        click("conv-menu-delete")
        click("conv-delete-dialog-ok")
        waitFor("convDelete") { server.frames("convDelete").isNotEmpty() }
        assertEquals(2L, server.frames("convDelete").single().data["upToSeq"]!!.jsonPrimitive.longOrNull)
        waitFor("会话消失", 2000) { order() == listOf(carolConv) }
        waitFor("草稿清除") { model.im!!.drafts[BOB_CONV] == null }
        waitFor("失败消息清除") { model.im!!.client.messages(BOB_CONV).isEmpty() }
        // 从通讯录重新进入：空的聊天页，输入框没有草稿（第 3 步）
        go("/chat/" + android.net.Uri.encode(BOB_CONV))
        rule.waitTag("composer-input")
        assertNeverWithin(1000, "旧消息又出现") { rule.hasText("第一条") || rule.hasText("会失败的") }
        assertEquals("", textOf("composer-input"))
    }

    @Test fun delete_failure_restores_conversation_and_draft() { // F-CVS-03 异常"写入失败 5000，客户端恢复显示"
        withBobHistory()
        openList()
        failedAndDraft()
        server.fail("convDelete", 5000)
        longPress(BOB_CONV)
        click("conv-menu-delete")
        click("conv-delete-dialog-ok")
        rule.waitText("服务异常，请稍后重试")
        waitFor("恢复") { BOB_CONV in order() }
        assertEquals(Draft("明天见"), model.im!!.drafts[BOB_CONV])
        assertTrue(hasInItem(BOB_CONV, "draft-tag"))
        assertNeverWithin(1500, "自动重试") { server.frames("convDelete").size > 1 }
    }

    @Test fun f_cvs_03_rule_4_deleted_group_opens_empty_and_can_send() { // F-CVS-03 规则 4、AC-CVS-03-5 的页面部分：不是"你已不在该群"
        server.group(myRole = "member")
        server.convs = listOf(convJson(G1, "周末球局", maxSeq = 1, memberCount = 3, myRole = "member", lastMsg = lastMsg("bob", "群里旧的")))
        server.messages[G1] = listOf(msgJson(G1, 1, "bob", "群里旧的"))
        openList()
        longPress(G1)
        click("conv-menu-delete")
        click("conv-delete-dialog-ok")
        waitFor("会话消失", 2000) { G1 !in order() }
        go("/chat/" + android.net.Uri.encode(G1)) // 从"我的群聊"进入
        rule.waitTag("composer-input", 8000)
        assertFalse(rule.hasText("你已不在该群"))
        assertFalse(rule.hasText("群里旧的"))
        type("composer-input", "删除后照常发言")
        clickSendWhenSettled()
        waitFor("发出") { server.frames("send").any { it.data["convId"]?.jsonPrimitive?.content == G1 || it.data["to"]?.jsonPrimitive?.content == "g1" } }
    }

    // ---- 多端（F-CVS-04 的页面部分） ----

    @Test fun tc_cvs_04_01_settings_changed_event_updates_within_2s() { // TC-CVS-04-01 预期 1（页面部分，D1 是假服务端的事件）
        threeConvs(gPinned = false)
        openList()
        server.pushEvent("convSettingsChanged", "convId" to carolConv, "pinned" to true, "pinnedAt" to System.currentTimeMillis(), "notifyOff" to true)
        waitFor("2 秒内移到最前、有图标", 2000) { order().first() == carolConv && pinnedMark(carolConv) == "true" && hasInItem(carolConv, "notify-off-icon") }
        waitBg(carolConv, LightTokens.surfacePinned.toArgb(), "其他端置顶的背景")
    }

    @Test fun tc_cvs_04_05_other_device_delete_clears_draft_and_closes_menu() { // TC-CVS-04-05 预期 2 的草稿（frontend-chat 第 55 条）
        withBobHistory()
        openList()
        inst.runOnMainSync { model.im!!.drafts[BOB_CONV] = Draft("还没发") }
        rule.waitUntil(3000) { hasInItem(BOB_CONV, "draft-tag") }
        longPress(BOB_CONV) // 菜单开着时其他端删除
        server.pushEvent("convDeleted", "convId" to BOB_CONV, "clearedSeq" to 2)
        waitFor("2 秒内消失", 2000) { BOB_CONV !in order() }
        rule.waitGoneTag("conv-menu", 2000)
        assertNull(model.im!!.drafts[BOB_CONV])
        // 之后对方再发：重新出现，没有草稿，只有新消息（AC-CVS-03-2 的页面部分）
        server.pushMsg(msgJson(BOB_CONV, 3, "bob", "删除后的新消息"))
        waitFor("重新出现", 3000) { BOB_CONV in order() }
        assertFalse(hasInItem(BOB_CONV, "draft-tag"))
        assertEquals("1", allText(inItem(BOB_CONV, "conv-badge").fetchSemanticsNode()))
        assertNeverWithin(800, "删除前开着的菜单又冒出来") { rule.hasTag("conv-menu") }
    }

    @Test fun issue_55_open_composer_cleared_when_other_device_deletes() { // frontend-chat 第 55 条：打开着的输入框也清
        withBobHistory()
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("第二条")
        type("composer-input", "正在输入")
        server.pushEvent("convDeleted", "convId" to BOB_CONV, "clearedSeq" to 2)
        waitFor("输入框清空", 2000) { textOf("composer-input") == "" }
        rule.waitGoneText("第二条", 2000)
        inst.runOnMainSync { model.nav.setRoot("/chats") }
        rule.waitTag("conv-item")
        assertNull(model.im!!.drafts[BOB_CONV], "离开时不把清掉之前的内容存回草稿")
    }

    // ---- AC-CVS-02-6：前台收到免打扰会话的消息 ----

    @Test fun tc_cvs_02_06_foreground_notify_off_message_updates_list_no_alert_no_notification() { // TC-CVS-02-06、AC-CVS-02-6
        server.group(myRole = "member")
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", "旧的"), updatedAt = T0 + 2000, notifyOff = true),
            convJson(G1, "周末球局", maxSeq = 1, memberCount = 3, myRole = "member", lastMsg = lastMsg("bob", "群旧的"), updatedAt = T0 + 1000, notifyOff = true),
        )
        openList()
        val nm = testContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(0, nm.activeNotifications.size, "用例开始时没有通知")
        server.pushMsg(msgJson(BOB_CONV, 2, "bob", "单聊新消息"))
        server.pushMsg(msgJson(G1, 2, "bob", "群里新消息"))
        waitFor("2 秒内列表更新", 2000) {
            hasInItem(BOB_CONV, "unread-count-tag") && textOf(BOB_CONV, "unread-count-tag") == "[1 条] 单聊新消息" &&
                hasInItem(G1, "unread-count-tag") && textOf(G1, "unread-count-tag").startsWith("[1 条] ") && textOf(G1, "unread-count-tag").endsWith("群里新消息")
        }
        assertTrue(hasInItem(BOB_CONV, "conv-dot") && hasInItem(G1, "conv-dot"))
        assertNeverWithin(1500, "响铃或弹通知") { alerts.isNotEmpty() || nm.activeNotifications.isNotEmpty() }
        assertEquals(null, tabBadge(), "免打扰的消息不进角标")
    }

    // ---- 可读名称、深色（AC-CHAT-90-8、90-9） ----

    private fun assertClickablesHaveNames(where: String) {
        rule.waitForIdle()
        fun walk(n: SemanticsNode, out: MutableList<SemanticsNode>) {
            out.add(n)
            n.children.forEach { walk(it, out) }
        }
        val all = ArrayList<SemanticsNode>()
        rule.onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes().forEach { walk(it, all) }
        var clickable = 0
        for (node in all) {
            if (node.config.getOrNull(SemanticsActions.OnClick) == null) continue
            clickable++
            val named = node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true || node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            assertTrue(named, "$where: 能点的节点没有名字：${node.config}")
        }
        assertTrue(clickable > 0, "$where: 没有找到能点的节点")
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val dir = java.io.File(testContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val bmp = inst.uiAutomation.takeScreenshot()
        java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun c5PagesNamed(prefix: String, pref: ThemePref) {
        server.convs = listOf(
            convJson(G1, "周末球局", maxSeq = 5, unread = 5, lastMsg = lastMsg("bob", "置顶且免打扰"), updatedAt = T0 + 1000, pinnedAt = T0, notifyOff = true),
            convJson(BOB_CONV, "bob", maxSeq = 2, unread = 2, lastMsg = lastMsg("bob", "普通"), updatedAt = T0 + 3000),
            convJson(carolConv, "carol", maxSeq = 3, unread = 3, lastMsg = lastMsg("carol", "免打扰"), updatedAt = T0 + 2000, notifyOff = true),
        )
        openList()
        inst.runOnMainSync { model.setTheme(pref) }
        val tokens = if (pref == ThemePref.DARK) DarkTokens else LightTokens
        waitBg(G1, tokens.surfacePinned.toArgb(), "$prefix 置顶背景来自 token")
        waitBg(BOB_CONV, tokens.surface.toArgb(), "$prefix 普通背景来自 token")
        // 会话项的名字里有免打扰图标的名字
        val merged = rule.onNode(itemOf(carolConv), useUnmergedTree = false).fetchSemanticsNode()
        assertTrue(merged.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("免打扰"), "$prefix 免打扰图标进了会话项的可读名称")
        assertClickablesHaveNames("$prefix 会话列表（置顶、免打扰、小红点、[N 条]）")
        shot("$prefix-c5-01-list")
        longPress(G1)
        assertClickablesHaveNames("$prefix 会话菜单")
        shot("$prefix-c5-02-menu")
        click("conv-menu-delete")
        rule.waitTag("conv-delete-dialog")
        assertClickablesHaveNames("$prefix 删除确认框")
        shot("$prefix-c5-03-delete")
        click("conv-delete-dialog-cancel")
    }

    @Test fun tc_chat_90_36_37_c5_pages_named_light() = c5PagesNamed("light", ThemePref.LIGHT) // TC-CHAT-90-36、90-37，AC-CHAT-90-8、90-9

    @Test fun tc_chat_90_36_37_c5_pages_named_dark() = c5PagesNamed("dark", ThemePref.DARK) // TC-CHAT-90-36、90-37，AC-CHAT-90-8、90-9
}
