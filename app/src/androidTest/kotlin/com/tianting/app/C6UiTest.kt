package com.tianting.app

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.tianting.app.core.CONTENT_BLOCKED_FORM
import com.tianting.app.core.CONTENT_BLOCKED_SEND
import com.tianting.app.core.REPORT_ASK_BLOCK
import com.tianting.app.core.REPORT_DONE
import com.tianting.app.core.REPORT_HINT
import com.tianting.app.core.REPORT_NOTE_CLIPPED
import com.tianting.app.core.infoPath
import com.tianting.app.ui.DataClientMsgId
import com.tianting.app.ui.DataMark
import com.tianting.app.ui.DataStatus
import com.tianting.app.ui.theme.DarkTokens
import com.tianting.app.ui.theme.LightTokens
import com.tianting.app.ui.theme.ThemePref
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FE-694（PRD-chat C6：举报入口、举报单、4510 的提示）的页面状态：假服务端 + 假账号接口（features.moderation），只验页面对 SDK
 * 结果的处理；哪些消息能举报、能不能重发由 SDK 的 canReport、canRetry 决定（FE-691 已测）。真实 im-server 的流程在 C6E2eTest。
 * 用例里的词只是特征串，不是真实敏感词。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class C6UiTest : UiTest() {
    @Before fun on() {
        http.moderation = true
        http.msgActions = true
        okReport()
    }

    /** 和真实服务端一样回 reportId。 */
    private fun okReport() = server.reply("report", obj("reportId" to "r-1"))

    // ---- 准备：msgId 要是合法的 ID（report 在本地先校验格式） ----

    private fun textMsg(conv: String, seq: Long, from: String, text: String) = obj(
        "msgId" to "m${conv.hashCode().toUInt()}x$seq", "clientMsgId" to "cm-$from-$seq", "convId" to conv, "seq" to seq, "from" to from,
        "type" to "text", "body" to obj("text" to text), "ts" to System.currentTimeMillis() - 60_000 + seq,
    )

    /** by 为 null 时不带 recalledBy（老服务端，frontend-chat 第 89 条）。 */
    private fun recalledMsg(conv: String, seq: Long, from: String, by: String?): JsonObject {
        val ts = System.currentTimeMillis() - 60_000 + seq
        val m = linkedMapOf<String, Any?>(
            "msgId" to "m${conv.hashCode().toUInt()}x$seq", "clientMsgId" to "cm-$from-$seq", "convId" to conv, "seq" to seq, "from" to from, "type" to "text",
            "ts" to ts, "deleted" to true, "deletedAt" to ts + 1, "recalled" to true, "recalledAt" to ts + 1,
        )
        if (by != null) m["recalledBy"] = by
        return js(m) as JsonObject
    }

    private fun idOf(m: JsonObject) = m["msgId"]!!.jsonPrimitive.content
    private fun cidOf(m: JsonObject) = m["clientMsgId"]!!.jsonPrimitive.content

    private fun withBob(vararg ms: JsonObject) {
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = ms.size.toLong(), lastMsg = lastMsg("bob", "x")))
        server.messages[BOB_CONV] = ms.toList()
    }

    private fun group(myRole: String, vararg ms: JsonObject) {
        server.group(myRole = myRole)
        server.convs = listOf(convJson(G1, "周末球局", maxSeq = ms.size.toLong(), memberCount = 3, myRole = myRole, lastMsg = lastMsg("bob", "x")))
        server.messages[G1] = ms.toList()
    }

    /** 打开 App 到 deepLink，等配置到（features.moderation 打开或关闭由 http.moderation 决定）。 */
    private fun open(deepLink: String, expect: Boolean = true) {
        start(deepLink)
        if (expect) waitFor("config") { model.im?.moderation == true } else Thread.sleep(500)
    }

    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private fun msgOf(text: String) = rule.onNode(hasTestTag("message") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private val allItems = listOf("menu-copy", "menu-quote", "menu-recall", "menu-report", "menu-retry", "menu-delete")
    private fun menuOf(text: String): List<String> {
        bubbleOf(text).performTouchInput { longClick() }
        rule.waitTag("bubble-menu")
        return allItems.filter { rule.hasTag(it) }
    }

    private fun closeMenu() {
        androidx.test.espresso.Espresso.pressBack()
        rule.waitGoneTag("bubble-menu")
    }

    private fun reportFrames() = server.frames("report")
    private fun reasonsText(): List<String> = rule.onAllNodes(hasTestTag("report-reasons").not() and hasAnyAncestor(hasTestTag("report-reasons")) and hasClickAction(), useUnmergedTree = true)
        .fetchSemanticsNodes().map { allText(it) }

    private fun recallRow(cid: String) = rule.onNode(hasTestTag("recall-row") and withData(DataClientMsgId, cid), useUnmergedTree = true)
    private fun hasRecallReport(cid: String) = rule.onAllNodes(hasTestTag("recall-report") and hasAnyAncestor(hasTestTag("recall-row") and withData(DataClientMsgId, cid)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun recalledShown(cid: String) = rule.onAllNodes(hasTestTag("msg-recalled") and (withData(DataClientMsgId, cid) or hasAnyAncestor(withData(DataClientMsgId, cid))), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pick(reason: String) = click("report-reason-$reason")

    /** 说明写长了按钮可能在弹层可见范围外：先滚到它。 */
    private fun submitReport() {
        rule.waitTag("report-submit")
        n("report-submit").performScrollTo()
        click("report-submit")
    }

    // ---- 消息菜单的"举报"（F-MOD-03 规则 8，frontend-chat 第 76、77 条） ----

    @Test fun issue_77_menu_report_only_on_others_sent_messages() { // 第 77、88 条：按 SDK canReport
        val theirs = textMsg(BOB_CONV, 1, "bob", "对方的")
        val mine = textMsg(BOB_CONV, 2, "alice", "自己的")
        withBob(theirs, mine)
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("自己的")
        assertEquals(listOf("menu-copy", "menu-quote", "menu-report"), menuOf("对方的"), "别人的：复制、引用、举报（举报在后面）")
        assertEquals("举报", textOf("menu-report"))
        closeMenu()
        assertFalse("menu-report" in menuOf("自己的"), "自己的没有举报")
        closeMenu()
        // 发送失败的（自己的）没有举报
        server.fail("send", 5000)
        type("composer-input", "发不出去")
        clickSendWhenSettled()
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("message") and withData(DataStatus, "failed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("menu-copy", "menu-retry", "menu-delete"), menuOf("发不出去"), "其他原因失败的照旧有重发")
    }

    @Test fun issue_76_switch_off_or_old_server_no_entries_anywhere() { // frontend-chat 第 76 条：三类入口都不显示
        for (flag in listOf<Boolean?>(false, null)) {
            http.moderation = flag
            server.friends = listOf(friendJson("bob"))
            group("member", textMsg(G1, 1, "bob", "群里的"), recalledMsg(G1, 2, "bob", "bob"))
            open("tianting://chat/group%3Ag1", expect = false)
            rule.waitText("群里的")
            assertFalse(model.im!!.moderation)
            assertFalse("menu-report" in menuOf("群里的"), "开关 $flag：消息菜单没有举报")
            closeMenu()
            rule.waitUntil(5000) { recalledShown("cm-bob-2") }
            assertFalse(rule.hasTag("recall-report"), "开关 $flag：撤回提示行没有举报")
            go(infoPath(G1))
            rule.waitTag("group-leave")
            assertFalse(rule.hasTag("group-report"), "开关 $flag：群信息页没有举报")
            go("/user/bob")
            click("user-more")
            rule.waitTag("menu-block")
            assertFalse(rule.hasTag("menu-report"), "开关 $flag：资料卡没有举报")
            finish()
        }
    }

    // ---- 举报单（AC-MOD-03-9） ----

    @Test fun tc_mod_03_09_c2c_message_report_hint_toast_then_ask_block() { // AC-MOD-03-1（页面部分）、AC-MOD-03-9、第 78、79 条
        val m = textMsg(BOB_CONV, 1, "bob", "要举报的")
        withBob(m)
        server.reply("block", socialAck(blocks = listOf(blockJson("bob"))))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("要举报的")
        menuOf("要举报的")
        click("menu-report")
        rule.waitTag("report-sheet")
        // 提交前有提示；理由 6 项默认不选，没选不能提交
        assertEquals(REPORT_HINT, textOf("report-hint"))
        assertEquals(listOf("垃圾广告", "辱骂骚扰", "色情低俗", "诈骗", "违法违规", "其他"), reasonsText())
        assertTrue(rule.onAllNodes(withData(DataMark, "selected"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "默认不选")
        n("report-submit").assertIsNotEnabled()
        pick("abuse")
        n("report-submit").assertIsEnabled()
        rule.onNode(hasTestTag("report-reason-abuse"), useUnmergedTree = true).assertIsSelected()
        assertEquals("0/200", textOf("report-count"))
        type("report-note", "  补充说明 ZXQ-NOTE-1 ")
        submitReport()
        rule.waitText(REPORT_DONE)
        val f = reportFrames().single()
        assertEquals("message", f.data["targetType"]!!.jsonPrimitive.content)
        assertEquals(BOB_CONV, f.data["convId"]!!.jsonPrimitive.content)
        assertEquals(listOf(idOf(m)), f.data["msgIds"]!!.jsonArray.map { it.jsonPrimitive.content }, "第一批只举报单条（第 85 条）")
        assertEquals("abuse", f.data["reason"]!!.jsonPrimitive.content)
        assertEquals("补充说明 ZXQ-NOTE-1", f.data["note"]!!.jsonPrimitive.content, "去首尾空白")
        // 单聊：成功后询问拉黑
        rule.waitTag("report-block-dialog")
        rule.waitGoneTag("report-sheet")
        assertTrue(REPORT_ASK_BLOCK in textOf("report-block-dialog"))
        assertEquals("加入黑名单", textOf("report-block-dialog-ok"))
        click("report-block-dialog-ok")
        waitFor("block") { server.frames("block").size == 1 }
        assertEquals("bob", server.frames("block").single().data["userId"]!!.jsonPrimitive.content)
        rule.waitGoneTag("report-block-dialog")
        assertEquals(1, reportFrames().size, "不自动重试、不重复提交")
    }

    @Test fun issue_79_block_failure_is_separate_and_cancel_does_not_block() { // 第 79 条：拉黑失败单独提示，不影响举报结果
        withBob(textMsg(BOB_CONV, 1, "bob", "一"), textMsg(BOB_CONV, 2, "bob", "二"))
        server.fail("block", 4134)
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("二")
        menuOf("一")
        click("menu-report")
        pick("spam")
        submitReport()
        rule.waitTag("report-block-dialog")
        click("report-block-dialog-cancel")
        rule.waitGoneTag("report-block-dialog")
        assertNeverWithin(800, "取消也拉黑") { server.frames("block").isNotEmpty() }
        assertNull(reportFrames().single().data["note"], "没写说明时不带 note")
        menuOf("二")
        click("menu-report")
        pick("other")
        submitReport()
        click("report-block-dialog-ok")
        rule.waitText("黑名单已满")
        assertEquals(2, reportFrames().size)
    }

    @Test fun issue_79_group_message_report_does_not_ask_block() { // 第 79 条：群消息不问
        group("member", textMsg(G1, 1, "bob", "群消息"))
        open("tianting://chat/group%3Ag1")
        rule.waitText("群消息")
        menuOf("群消息")
        click("menu-report")
        pick("fraud")
        submitReport()
        rule.waitText(REPORT_DONE)
        rule.waitGoneTag("report-sheet")
        assertNeverWithin(1000, "群消息问拉黑") { rule.hasTag("report-block-dialog") }
        assertEquals(G1, reportFrames().single().data["convId"]!!.jsonPrimitive.content)
    }

    @Test fun submitting_disables_button_and_sheet_cannot_be_closed() { // FE-693 第 2 条：提交中禁用按钮
        withBob(textMsg(BOB_CONV, 1, "bob", "慢的"))
        var held: String? = null
        server.handlers["report"] = { f ->
            held = f.id
            Reply.Hold
        }
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("慢的")
        menuOf("慢的")
        click("menu-report")
        pick("porn")
        submitReport()
        waitFor("report 发出") { held != null }
        rule.waitUntil(2000) { textOf("report-submit") == "提交中…" }
        n("report-submit").assertIsNotEnabled()
        n("report-cancel").assertIsNotEnabled()
        n("report-reason-spam").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        assertTrue(rule.hasTag("report-sheet"), "提交中返回键不关")
        // 服务端回了：成功
        server.push(obj("id" to held, "cmd" to "ack", "data" to obj("reportId" to "r-1")))
        rule.waitText(REPORT_DONE)
        rule.waitTag("report-block-dialog")
        assertEquals(1, reportFrames().size)
    }

    @Test fun issue_83_errors_final_close_others_keep_sheet_for_manual_retry() { // 第 83 条、FE-693 第 2 条（4292、4040、4030 关；5000、超时留着）
        withBob(textMsg(BOB_CONV, 1, "bob", "目标"))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("目标")
        for ((code, text) in listOf(4292 to "今天的举报次数已用完", 4040 to "无法举报该内容", 4030 to "举报功能暂不可用")) {
            server.fail("report", code)
            val before = reportFrames().size
            menuOf("目标")
            click("menu-report")
            pick("spam")
            submitReport()
            rule.waitText(text)
            rule.waitGoneTag("report-sheet")
            assertFalse(rule.hasTag("report-block-dialog"), "$code：失败不问拉黑")
            assertNeverWithin(1000, "$code 自动重试") { reportFrames().size > before + 1 }
            rule.waitGoneText(text, 8000)
        }
        // 5000：留着举报单（理由、说明都在），可以再点提交
        server.fail("report", 5000)
        menuOf("目标")
        click("menu-report")
        pick("illegal")
        type("report-note", "说明")
        submitReport()
        rule.waitText("服务异常，请稍后重试")
        assertTrue(rule.hasTag("report-sheet"))
        rule.onNode(hasTestTag("report-reason-illegal"), useUnmergedTree = true).assertIsSelected()
        assertTrue("说明" in textOf("report-note"))
        val before = reportFrames().size
        assertNeverWithin(1500, "5000 自动重试") { reportFrames().size > before }
        okReport()
        submitReport()
        rule.waitText(REPORT_DONE)
        assertEquals(before + 1, reportFrames().size)
    }

    @Test fun issue_84_note_counter_limit_and_paste_clipped() { // 第 84 条：按码点，超出不能再输入；粘贴超长截断并提示
        withBob(textMsg(BOB_CONV, 1, "bob", "目标"))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("目标")
        menuOf("目标")
        click("menu-report")
        rule.waitTag("report-note")
        type("report-note", "😀".repeat(250)) // 一次放进 250 个（粘贴）
        rule.waitTag("report-clipped")
        assertEquals(REPORT_NOTE_CLIPPED, textOf("report-clipped"))
        assertEquals("200/200", textOf("report-count"))
        assertEquals("😀".repeat(200), textOf("report-note"))
        type("report-note", "字".repeat(199))
        rule.waitGoneTag("report-clipped")
        assertEquals("199/200", textOf("report-count"))
        n("report-note").performTextInput("a")
        n("report-note").performTextInput("b") // 已经 200，再输入不进去
        rule.waitForIdle()
        assertEquals("200/200", textOf("report-count"))
        assertEquals("字".repeat(199) + "a", textOf("report-note"))
        assertFalse(rule.hasTag("report-clipped"), "逐个输入到上限不提示")
        pick("other")
        submitReport()
        rule.waitText(REPORT_DONE)
        assertEquals("字".repeat(199) + "a", reportFrames().single().data["note"]!!.jsonPrimitive.content)
    }

    // ---- 撤回提示行的"举报"（F-MOD-03 规则 2a，AC-MOD-03-11，第 77、89 条） ----

    @Test fun tc_mod_03_11_recall_row_report_only_when_recalled_by_someone_else() { // AC-MOD-03-11（页面部分）、第 89 条
        val byBob = recalledMsg(G1, 1, "bob", "bob") // 别人撤回自己的
        val byMe = recalledMsg(G1, 2, "carol", "alice") // 我（群主）撤回别人的
        val mine = recalledMsg(G1, 3, "alice", "bob") // 我的消息被管理员撤回
        val unknown = recalledMsg(G1, 4, "carol", null) // 老服务端：不知道谁撤回的，按别人撤回
        group("owner", byBob, byMe, mine, unknown)
        open("tianting://chat/group%3Ag1")
        rule.waitUntil(5000) { listOf(byBob, byMe, mine, unknown).all { recalledShown(cidOf(it)) } }
        assertTrue(hasRecallReport(cidOf(byBob)), "别人撤回的有举报")
        assertFalse(hasRecallReport(cidOf(byMe)), "自己以群主身份撤回的没有（第 89 条）")
        assertFalse(hasRecallReport(cidOf(mine)), "自己的消息没有")
        assertTrue(hasRecallReport(cidOf(unknown)), "撤回者未知按别人撤回（第 89 条）")
        // 可读名称、是按钮；撤回提示行本身没有菜单
        val link = rule.onNode(hasTestTag("recall-report") and hasAnyAncestor(withData(DataClientMsgId, cidOf(byBob))), useUnmergedTree = true)
        val node = link.fetchSemanticsNode()
        assertEquals(listOf("举报这条消息"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        // 点击区域不小于最小触控区域 44 dp（PRD-ui，任务单第 92、93 条）
        val b = link.getUnclippedBoundsInRoot()
        assertTrue(b.width >= 44.dp && b.height >= 44.dp, "举报的点击区域 ${b.width} x ${b.height}")
        assertNull(recallRow(cidOf(byBob)).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick), "撤回提示行没有长按")
        link.performClick()
        rule.waitTag("report-sheet")
        pick("abuse")
        submitReport()
        rule.waitText(REPORT_DONE)
        val f = reportFrames().single()
        assertEquals(listOf(idOf(byBob)), f.data["msgIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(G1, f.data["convId"]!!.jsonPrimitive.content)
        assertNeverWithin(800, "群消息问拉黑") { rule.hasTag("report-block-dialog") }
    }

    @Test fun issue_92_report_only_after_read_and_row_updates_when_read() { // 第 92 条：seq ≤ readSeq 才有举报（sdk-kotlin 128e789 的 canReport），读到后页面跟着出现
        group("member", textMsg(G1, 1, "bob", "看过的"))
        open("tianting://chat/group%3Ag1")
        rule.waitText("看过的")
        assertTrue("menu-report" in menuOf("看过的"))
        closeMenu()
        // App 在后台（不可见）：新消息不算读到
        inst.runOnMainSync { model.setForeground(false) }
        val unread = textMsg(G1, 2, "bob", "还没读的")
        server.pushMsg(unread)
        rule.waitText("还没读的")
        waitFor("未读") { model.im!!.client.conversations.value.first { it.convId == G1 }.readSeq == 1L }
        assertFalse("menu-report" in menuOf("还没读的"), "没读到的没有举报")
        closeMenu()
        // 在后台时被撤回：撤回提示行没有举报
        server.pushEvent("msgDeleted", "convId" to G1, "msgIds" to listOf(idOf(unread)), "seqs" to listOf(2), "recalled" to obj("by" to "bob", "at" to System.currentTimeMillis()))
        rule.waitUntil(5000) { recalledShown(cidOf(unread)) }
        assertNeverWithin(800, "没读到的撤回行有举报") { hasRecallReport(cidOf(unread)) }
        // 回到前台、读到了：撤回提示行跟着出现举报
        inst.runOnMainSync { model.setForeground(true) }
        waitFor("已读") { model.im!!.client.conversations.value.first { it.convId == G1 }.readSeq == 2L }
        rule.waitUntil(5000) { hasRecallReport(cidOf(unread)) }
    }

    // ---- 资料卡、群信息页（F-MOD-03 规则 8，第 77、79 条） ----

    @Test fun profile_report_user_asks_block_unless_already_blocked() { // 第 77 条（资料卡对自己不显示）、第 79 条
        server.friends = listOf(friendJson("bob"))
        server.reply("block", socialAck(blocks = listOf(blockJson("bob"))))
        open("tianting://user/bob")
        click("user-more")
        click("menu-report")
        rule.waitTag("report-sheet")
        assertEquals(REPORT_HINT, textOf("report-hint"))
        pick("spam")
        submitReport()
        rule.waitText(REPORT_DONE)
        val f = reportFrames().single()
        assertEquals("user", f.data["targetType"]!!.jsonPrimitive.content)
        assertEquals("bob", f.data["userId"]!!.jsonPrimitive.content)
        assertNull(f.data["convId"])
        click("report-block-dialog-ok")
        waitFor("block") { server.frames("block").size == 1 }
        rule.waitTag("user-blocked") // 资料卡显示已加入黑名单
        // 已经在黑名单：再举报不问
        click("user-more")
        click("menu-report")
        pick("other")
        submitReport()
        rule.waitText(REPORT_DONE)
        rule.waitGoneTag("report-sheet")
        assertNeverWithin(1000, "已拉黑还问") { rule.hasTag("report-block-dialog") }
        // 自己的资料卡没有"更多"，也就没有举报
        go("/user/alice")
        rule.waitTag("user-head")
        assertFalse(rule.hasTag("user-more"))
    }

    @Test fun group_info_report_group_no_block_ask() { // F-MOD-03 规则 8、第 79 条（群举报不问）
        group("member")
        open("tianting://chat/group%3Ag1/info")
        rule.waitTag("group-report")
        n("group-report").performScrollTo()
        click("group-report")
        rule.waitTag("report-sheet")
        pick("illegal")
        submitReport()
        rule.waitText(REPORT_DONE)
        val f = reportFrames().single()
        assertEquals("group", f.data["targetType"]!!.jsonPrimitive.content)
        assertEquals("g1", f.data["groupId"]!!.jsonPrimitive.content)
        rule.waitGoneTag("report-sheet")
        assertNeverWithin(1000, "群举报问拉黑") { rule.hasTag("report-block-dialog") }
    }

    // ---- 4510（AC-MOD-02-9，第 81、82 条） ----

    @Test fun tc_mod_02_09_blocked_send_failed_cleared_copy_and_no_retry() { // AC-MOD-02-9、第 81 条
        withBob(textMsg(BOB_CONV, 1, "bob", "你好"))
        server.fail("send", 4510)
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("你好")
        type("composer-input", "含特征词 ZXQ-BLOCK-1")
        clickSendWhenSettled()
        rule.waitText(CONTENT_BLOCKED_SEND)
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("message") and withData(DataStatus, "failed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", textOf("composer-input"), "输入框清空（可以从气泡复制）")
        assertEquals(CONTENT_BLOCKED_SEND, textOf("toast"), "提示里没有具体的词")
        // 失败标记不能点重发；菜单只有复制、删除
        val icon = rule.onNode(hasTestTag("failed-icon") and hasAnyAncestor(hasTestTag("message") and withData(DataStatus, "failed")), useUnmergedTree = true)
        assertEquals("no-retry", icon.fetchSemanticsNode().config.getOrNull(DataMark))
        assertNull(icon.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick), "失败标记点了没有反应")
        assertEquals(listOf("menu-copy", "menu-delete"), menuOf("含特征词 ZXQ-BLOCK-1"))
        click("menu-copy")
        assertEquals(listOf("含特征词 ZXQ-BLOCK-1"), copied.toList())
        // 不自动重发：断线重连后也不发
        assertNeverWithin(1500, "自动重发") { server.frames("send").size > 1 }
        server.drop()
        waitFor("重连") { server.frames("auth").size >= 2 && model.im?.state == com.tianting.sdk.ConnState.CONNECTED }
        assertNeverWithin(2000, "重连后重发") { server.frames("send").size > 1 }
        // 删除：只从本地移除
        menuOf("含特征词 ZXQ-BLOCK-1")
        click("menu-delete")
        rule.waitGoneText("含特征词 ZXQ-BLOCK-1")
        assertFalse(rule.hasTag("retry-dialog"))
    }

    @Test fun issue_76_blocked_send_copy_shown_even_with_switch_off() { // 第 76 条：4510 的提示和开关无关
        http.moderation = false
        http.msgActions = null
        withBob(textMsg(BOB_CONV, 1, "bob", "你好"))
        server.fail("send", 4510)
        open("tianting://chat/c2c%3Aalice%3Abob", expect = false)
        rule.waitText("你好")
        type("composer-input", "特征 ZXQ-BLOCK-2")
        clickSendWhenSettled()
        rule.waitText(CONTENT_BLOCKED_SEND)
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("message") and withData(DataStatus, "failed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("menu-copy", "menu-delete"), menuOf("特征 ZXQ-BLOCK-2"), "msgActions 关闭时也没有重发")
    }

    @Test fun issue_82_group_name_and_notice_blocked_keep_input() { // 第 82 条（P-48 ⑥）：其他场景的文案，表单内容保留
        group("owner")
        open("tianting://chat/group%3Ag1/info")
        click("group-name")
        rule.waitTag("rename-input")
        type("rename-input", "新群名 ZXQ-NAME")
        server.fail("groupUpdate", 4510)
        click("rename-save")
        rule.waitText(CONTENT_BLOCKED_FORM)
        assertTrue(rule.hasTag("rename-sheet"), "群名弹层保留")
        assertTrue("新群名 ZXQ-NAME" in textOf("rename-input"))
        // 改了再存：成功后关掉
        server.handlers.remove("groupUpdate")
        type("rename-input", "新群名")
        click("rename-save")
        rule.waitGoneTag("rename-sheet")
        assertEquals("新群名", server.frames("groupUpdate").last().data["name"]!!.jsonPrimitive.content)
        // 群公告：草稿保留
        go(com.tianting.app.core.noticePath(G1))
        click("notice-edit")
        type("notice-input", "公告 ZXQ-NOTICE")
        server.fail("groupUpdate", 4510)
        click("notice-save")
        rule.waitText(CONTENT_BLOCKED_FORM)
        assertTrue("公告 ZXQ-NOTICE" in textOf("notice-input"))
    }

    @Test fun issue_82_group_create_and_friend_apply_blocked_keep_input() { // 第 82 条：建群名、好友申请附言
        server.friends = listOf(friendJson("bob"), friendJson("carol"))
        open("tianting://groups/new")
        rule.waitTag("pick-row")
        member("bob", "pick-row", com.tianting.app.ui.DataUser).performClick()
        member("carol", "pick-row", com.tianting.app.ui.DataUser).performClick()
        click("pick-done")
        type("group-name-input", "群名 ZXQ-GROUP")
        server.fail("groupCreate", 4510)
        click("group-create")
        rule.waitText(CONTENT_BLOCKED_FORM)
        assertTrue(rule.hasTag("group-name-sheet"), "建群弹层保留")
        assertTrue("群名 ZXQ-GROUP" in textOf("group-name-input"))
        // 好友申请附言
        go("/contacts/apply/dave")
        rule.waitTag("apply-message")
        type("apply-message", "附言 ZXQ-APPLY")
        server.fail("friendApply", 4510)
        click("apply-send")
        rule.waitText(CONTENT_BLOCKED_FORM)
        assertTrue("附言 ZXQ-APPLY" in textOf("apply-message"))
        assertTrue(model.nav.current.path.startsWith("/contacts/apply/"), "停在申请页")
    }

    @Test fun issue_82_register_blocked_keeps_form() { // 第 82 条：注册的用户名、昵称（AccountError 451 / 4510）
        http.demoLogin = false
        http.registerMode = "invite"
        http.err("/v1/account/register", 451, 4510)
        install(loggedIn = false)
        launch()
        click("login-to-register")
        type("register-username", "zxq_user")
        type("register-password", "Good-pass-1")
        type("register-nickname", "昵称 ZXQ")
        type("register-invite", "INVITE-1")
        click("register-submit")
        rule.waitTag("register-error")
        assertEquals(CONTENT_BLOCKED_FORM, textOf("register-error"))
        assertTrue("zxq_user" in textOf("register-username"))
        assertTrue("昵称 ZXQ" in textOf("register-nickname"))
        n("register-submit").assertIsEnabled()
    }

    // ---- 无障碍和深色（AC-CHAT-90-8、90-9 的 C6 列） ----

    private fun assertClickablesHaveNames(where: String) {
        fun walk(n: SemanticsNode, out: MutableList<SemanticsNode>) {
            out.add(n)
            n.children.forEach { walk(it, out) }
        }
        val all = ArrayList<SemanticsNode>()
        rule.onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes().forEach { walk(it, all) }
        var clickable = 0
        for (node in all) {
            if (node.config.getOrNull(SemanticsActions.OnClick) == null) continue
            if (node.config.getOrNull(SemanticsProperties.EditableText) != null) continue
            clickable++
            val named = node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            assertTrue(named, "$where: 能点的节点没有名字：${node.config}")
        }
        assertTrue(clickable > 0, "$where: 没有找到能点的节点")
    }

    @Test fun tc_chat_90_44_named_buttons_and_dark_tokens() { // AC-CHAT-90-8、90-9（举报入口列）：可读名称、深色 token
        group("member", recalledMsg(G1, 1, "bob", "bob"))
        open("tianting://chat/group%3Ag1")
        rule.waitTag("recall-report")
        assertClickablesHaveNames("聊天页（撤回提示行的举报）")
        click("recall-report")
        rule.waitTag("report-sheet")
        assertClickablesHaveNames("举报单")
        val light = n("report-sheet").captureToImage().asAndroidBitmap().let { it.getPixel(it.width - 2, 2) }
        assertEquals(LightTokens.surface.toArgb(), light, "举报单底色是 color.surface")
        androidx.test.espresso.Espresso.pressBack()
        rule.waitGoneTag("report-sheet")
        inst.runOnMainSync { model.setTheme(ThemePref.DARK) }
        rule.waitForIdle()
        click("recall-report")
        rule.waitTag("report-sheet")
        waitFor("深色底色") { n("report-sheet").captureToImage().asAndroidBitmap().let { it.getPixel(it.width - 2, 2) } == DarkTokens.surface.toArgb() }
        assertTrue(LightTokens.surface != DarkTokens.surface)
        pick("spam")
        rule.waitForIdle()
        assertClickablesHaveNames("举报单（深色、已选理由）")
    }
}
