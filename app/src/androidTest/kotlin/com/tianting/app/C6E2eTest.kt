package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.CONTENT_BLOCKED_FORM
import com.tianting.app.core.CONTENT_BLOCKED_SEND
import com.tianting.app.core.REPORT_ASK_BLOCK
import com.tianting.app.core.REPORT_DONE
import com.tianting.app.core.chatPath
import com.tianting.app.core.infoPath
import com.tianting.app.ui.DataClientMsgId
import com.tianting.app.ui.DataMark
import com.tianting.app.ui.DataStatus
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMEvent
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.SendError
import com.tianting.sdk.SendResult
import com.tianting.sdk.c2cConvId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FE-694 对真实 im-server（scripts/test-server.sh，IM_MOD_ENABLED=1，演示登录）的端到端：
 * scripts/run-tests.sh e2e com.tianting.app.C6E2eTest。App 是真实依赖。词库只放特征串（不是真实敏感词），不打印。
 * 举报（report）要 BE-495，发送拦截（4510）要 BE-494；服务端没做完时这些用例按原断言失败，不跳过、不放宽。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class C6E2eTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val r = C5Real(rule)
    private val ui get() = r.ui
    private val model get() = testApp.model
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setup() {
        r.server.guard()
        r.server.resetLimits()
    }

    @After fun cleanup() {
        scope.cancel()
        r.close()
    }

    private fun login(a: String) {
        r.appLogin(a)
        rule.waitUntil(15_000) { model.im?.moderation == true } // 服务端 features.moderation（IM_MOD_ENABLED=1）
    }

    /** 记下对方收到的所有事件（AC-MOD-03-1：被举报人 2 秒内收不到任何帧，页面这边看 SDK 的事件和消息）。 */
    private fun recordEvents(c: IMClient): CopyOnWriteArrayList<IMEvent> {
        val out = CopyOnWriteArrayList<IMEvent>()
        scope.launch { c.events.collect { out.add(it) } }
        return out
    }

    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private fun menuOf(text: String): List<String> {
        ui.waitText(text)
        rule.waitForIdle()
        bubbleOf(text).performTouchInput { longClick() }
        ui.waitTag("bubble-menu", 5000)
        return listOf("menu-copy", "menu-quote", "menu-recall", "menu-report", "menu-retry", "menu-delete").filter { ui.hasTag(it) }
    }

    private fun closeMenu() {
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(3000) { !ui.hasTag("bubble-menu") }
    }

    private fun submitReport(reason: String) {
        ui.tap("report-reason-$reason")
        ui.waitTag("report-submit")
        rule.onNodeWithTag("report-submit", useUnmergedTree = true).performScrollTo()
        ui.tap("report-submit")
    }

    private fun sendNow() {
        var last: androidx.compose.ui.geometry.Rect? = null
        var stable = 0
        r.until("发送按钮停稳", 5000) {
            rule.waitForIdle()
            val b = rule.onNodeWithTag("send-button", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            stable = if (b == last) stable + 1 else 0
            last = b
            Thread.sleep(40)
            stable >= 10
        }
        ui.tap("send-button")
    }

    /** 词库加一个特征词（BE-493 管理接口，scope moderation.words），等它生效（10 秒内，F-MOD-01 规则 2）：用一个测试客户端发，直到回 4510。 */
    private fun addWord(word: String, probe: IMClient, probeConv: String) {
        r.server.signed("POST", "/admin/moderation/words", obj("word" to word))
        r.until("词库生效（发送回 4510，要 BE-494）", 12_000) {
            val res = offThread { probe.send(probeConv, "probe $word") }
            check(res is SendResult.Ok) { "send rejected locally" }
            val id = res.message.clientMsgId
            r.until("probe ack") { probe.messages(probeConv).first { it.clientMsgId == id }.status != MessageStatus.SENDING }
            probe.messages(probeConv).first { it.clientMsgId == id }.error == SendError.Code(4510)
        }
    }

    @Test fun issue_76_real_switch_on_entries_shown() { // 第 76、77 条：真实服务端 features.moderation=true，入口按 canReport
        val (a, b) = r.uid("ma") to r.uid("mb")
        r.users(a, b)
        val bob = r.client(b)
        val conv = c2cConvId(a, b)
        r.say(bob, conv, "对方说的 E1")
        login(a)
        r.open(chatPath(conv))
        ui.type("composer-input", "我说的 E1")
        sendNow()
        rule.waitUntil(15_000) { model.im!!.client.messages(conv).any { it.body.text == "我说的 E1" && it.status == MessageStatus.SENT } }
        assertTrue("menu-report" in menuOf("对方说的 E1"))
        closeMenu()
        assertFalse("menu-report" in menuOf("我说的 E1"))
        closeMenu()
        // 资料卡
        r.open("/user/$b")
        ui.tap("user-more")
        ui.waitTag("menu-report", 5000)
    }

    @Test fun tc_mod_03_09_real_report_c2c_message_then_ask_block() { // AC-MOD-03-1、AC-MOD-03-9（要 BE-495）
        val (a, b) = r.uid("na") to r.uid("nb")
        r.users(a, b)
        val bob = r.client(b)
        val conv = c2cConvId(a, b)
        val target = "被举报的 E2 " + System.currentTimeMillis().toString(36)
        r.say(bob, conv, target)
        login(a)
        r.open(chatPath(conv))
        val events = recordEvents(bob)
        val before = bob.messages(conv).size
        menuOf(target)
        ui.tap("menu-report")
        ui.waitTag("report-sheet", 5000)
        submitReport("abuse")
        ui.waitText(REPORT_DONE, 5000)
        ui.waitTag("report-block-dialog", 5000)
        assertTrue(REPORT_ASK_BLOCK in ui.textOf("report-block-dialog"))
        ui.tap("report-block-dialog-cancel")
        // 被举报人 2 秒内收不到任何东西
        Thread.sleep(2000)
        assertTrue(events.isEmpty(), "被举报人收到了：${events.map { it::class.simpleName }}")
        assertEquals(before, bob.messages(conv).size)
    }

    @Test fun tc_mod_03_11_real_report_recalled_group_message() { // AC-MOD-03-11（页面部分，要 BE-495；审核员读隔离原文随 FE-698）
        val (a, b) = r.uid("oa") to r.uid("ob")
        r.users(a, b)
        val g = "g" + r.uid("")
        r.server.signed("POST", "/admin/groups", obj("groupId" to g, "name" to "C6 群", "members" to listOf(b, a), "owner" to b))
        val bob = r.client(b)
        val conv = "group:$g"
        login(a)
        r.open(chatPath(conv))
        val text = "要撤回的 E3 " + System.currentTimeMillis().toString(36)
        val m = r.say(bob, conv, text)
        ui.waitText(text)
        offThread { bob.recall(conv, m.msgId!!) }
        rule.waitUntil(5000) { ui.hasTag("recall-report") }
        val cid = model.im!!.client.messages(conv).first { it.msgId == m.msgId }.clientMsgId
        rule.onNode(hasTestTag("recall-report") and hasAnyAncestor(hasTestTag("recall-row") and withData(DataClientMsgId, cid)), useUnmergedTree = true).performClick()
        ui.waitTag("report-sheet", 5000)
        submitReport("spam")
        ui.waitText(REPORT_DONE, 5000)
        Thread.sleep(800)
        assertFalse(ui.hasTag("report-block-dialog"), "群消息不问拉黑")
    }

    @Test fun real_report_user_and_group() { // F-MOD-03 规则 8 的资料卡、群信息页入口（要 BE-495）
        val (a, b) = r.uid("pa") to r.uid("pb")
        r.users(a, b)
        val g = "g" + r.uid("")
        r.server.signed("POST", "/admin/groups", obj("groupId" to g, "name" to "C6 群 2", "members" to listOf(b, a), "owner" to b))
        login(a)
        r.open(infoPath("group:$g"))
        ui.waitTag("group-report")
        rule.onNodeWithTag("group-report", useUnmergedTree = true).performScrollTo()
        ui.tap("group-report")
        submitReport("illegal")
        ui.waitText(REPORT_DONE, 5000)
        rule.waitUntil(5000) { !ui.hasTag("report-sheet") }
        r.open("/user/$b") // 共同群的成员可以举报（F-MOD-03 规则 3）
        ui.tap("user-more")
        ui.tap("menu-report")
        submitReport("spam")
        ui.waitText(REPORT_DONE, 5000)
        ui.waitTag("report-block-dialog", 5000)
    }

    @Test fun tc_mod_02_09_real_blocked_send() { // AC-MOD-02-9（要 BE-494；词库接口 BE-493 已有）
        val (a, b, p) = Triple(r.uid("qa"), r.uid("qb"), r.uid("qp"))
        r.users(a, b, p)
        val bob = r.client(b)
        val probe = r.client(p)
        val conv = c2cConvId(a, b)
        r.say(bob, conv, "你好 E4")
        val word = "zxqblk" + System.currentTimeMillis().toString(36) // 特征串，不是真实敏感词
        addWord(word, probe, c2cConvId(p, b))
        login(a)
        r.open(chatPath(conv))
        ui.waitText("你好 E4")
        val events = recordEvents(bob)
        val text = "含 $word 的消息"
        ui.type("composer-input", text)
        sendNow()
        ui.waitText(CONTENT_BLOCKED_SEND, 5000)
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("message") and withData(DataStatus, "failed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", ui.textOf("composer-input"), "输入框清空")
        assertFalse(word in ui.textOf("toast"), "提示里没有具体的词")
        val icon = rule.onNode(hasTestTag("failed-icon") and hasAnyAncestor(hasTestTag("message") and withData(DataStatus, "failed")), useUnmergedTree = true)
        assertEquals("no-retry", icon.fetchSemanticsNode().config[DataMark])
        assertEquals(listOf("menu-copy", "menu-delete"), menuOf(text))
        closeMenu()
        // 对方 2 秒内收不到；不自动重发
        Thread.sleep(2000)
        assertTrue(events.filterIsInstance<IMEvent.MessagesChanged>().none { e -> e.messages.any { word in (it.body.text ?: "") } }, "对方收到了被拦截的消息")
        assertTrue(bob.messages(conv).none { word in (it.body.text ?: "") })
    }

    @Test fun real_group_rename_blocked_keeps_sheet() { // 第 82 条（P-48 ⑥）：群名被拦截（要 BE-494）
        val (a, b, p) = Triple(r.uid("sa"), r.uid("sb"), r.uid("sp"))
        r.users(a, b, p)
        val probe = r.client(p)
        val word = "zxqgrp" + System.currentTimeMillis().toString(36)
        addWord(word, probe, c2cConvId(p, b))
        val g = "g" + r.uid("")
        r.server.signed("POST", "/admin/groups", obj("groupId" to g, "name" to "C6 群 3", "members" to listOf(a, b), "owner" to a))
        login(a)
        r.open(infoPath("group:$g"))
        ui.tap("group-name")
        ui.type("rename-input", "新名字 $word")
        ui.tap("rename-save")
        ui.waitText(CONTENT_BLOCKED_FORM, 5000)
        assertTrue(ui.hasTag("rename-sheet"))
        assertTrue(word in ui.textOf("rename-input"))
    }
}
