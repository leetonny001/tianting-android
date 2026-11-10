package com.tianting.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.chatPath
import com.tianting.app.ui.DataConvId
import com.tianting.app.ui.DataMark
import com.tianting.app.ui.DataUser
import com.tianting.sdk.ConnState
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.c2cConvId
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FE-672：C4 的三端互发互收（撤回、引用、@；H5、Android、iOS 对同一个 im-server）。三端不能同时跑，所以先各端用自己的界面发
 * （`xp_c4_send`），再各端用自己的界面收（`xp_c4_receive`）。共同约定（tianting-web 的 xp-c4.spec.ts、tianting-ios 的
 * CrossPlatformC4E2ETests 照抄这一段）：
 *   - 运行号 R（`-e ttXp R`），用户 `xp{R}h5`、`xp{R}android`、`xp{R}ios`（昵称就是 userId）。每个发送端 p 有两个群（三人都在，
 *     群主是 p）：`xp{R}m{p}`「C4引用提及-{p}」、`xp{R}r{p}`「C4撤回-{p}」。others(p) 按 h5、android、ios 的顺序去掉 p，记作 q1、q2。
 *   - 协调脚本先放好：q1 在 m 群发「XP4-q-{q1}@{p}」；q1、q2 依次在 r 群发「XP4-r-{q}@{p}」（r 群最后一条是 q2 的）。
 *   - 发（p）：① 给每个 q 单聊发「XP4-{p}>{q}-撤回原文」，长按撤回；② m 群引用「XP4-q-{q1}@{p}」回复「XP4-{p}-引用回复」；
 *     再输入 @ 选 q1、@ 选 q2，加「XP4-{p}-提及」发送；③ r 群以群主身份撤回 q2、q1 的消息。
 *   - 收（`-e ttXpFrom` 指定发送端）：单聊有「对方撤回了一条消息」、没有原文，列表摘要是撤回文案；m 群列表有「[有人@我]」，
 *     引用回复的气泡顶部是「{q1}: XP4-q-{q1}@{p}」，提及消息标记为提到我；r 群：自己的那条「{p}撤回了你的一条消息」，另一条
 *     「{p}撤回了一条成员消息」，列表摘要按最后一条。
 * 没有 `ttXp` 时跳过。结果是模拟器、本地。引用原文、@ 内容只在内存和界面里断言，不打印。
 */
@OptIn(ExperimentalTestApi::class)
class CrossPlatformC4E2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val ui = E2eUi(rule)
    private val args = InstrumentationRegistry.getArguments()
    private val run: String? = args.getString("ttXp")
    private val model get() = testApp.model

    private fun user(p: String) = "xp${run}$p"
    private val me get() = user(ME)
    private fun others(p: String) = PLATFORMS.filter { it != p }
    private fun mGroup(p: String) = "group:xp${run}m$p"
    private fun rGroup(p: String) = "group:xp${run}r$p"
    private fun recallText(p: String, q: String) = "XP4-$p>$q-撤回原文"
    private fun quoted(p: String) = "XP4-q-${others(p)[0]}@$p"
    private fun reply(p: String) = "XP4-$p-引用回复"
    private fun mention(p: String) = "XP4-$p-提及"
    private fun seed(q: String, p: String) = "XP4-r-$q@$p"

    @Before fun setup() {
        assumeTrue("跨端用例要协调脚本传 ttXp", !run.isNullOrEmpty())
        server.guard()
        server.resetLimits()
    }

    @After fun cleanup() {
        if (run.isNullOrEmpty()) return
        ui.scenario?.close()
        inst.runOnMainSync { testApp.model.logout() }
        offThread { testApp.model.awaitTeardown() }
    }

    private fun appLogin(u: String) {
        replaceWithRealDeps()
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", u)
        val end = System.currentTimeMillis() + 30_000
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("config-retry")) runCatching { ui.tap("config-retry") }
            if (ui.hasTag("login-submit")) runCatching { rule.onNodeWithTag("login-submit", useUnmergedTree = true).performClick() }
            Thread.sleep(700)
        }
        ui.waitTag("tab-chats", 15_000)
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        rule.waitUntil(15_000) { model.im?.msgActions == true }
    }

    private fun open(path: String) {
        inst.runOnMainSync { model.nav.resetTo(path) }
        rule.waitForIdle()
    }

    private fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(50)
        }
    }

    private fun loadAll(conv: String) {
        offThread { runCatching { model.im!!.client.loadHistory(conv) } }
    }

    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)

    private fun menuItem(text: String, item: String) {
        ui.waitText(text, 30_000)
        rule.waitForIdle()
        bubbleOf(text).performTouchInput { longClick() }
        ui.waitTag("bubble-menu", 5000)
        ui.tap(item)
    }

    private fun sendNow() {
        var last: androidx.compose.ui.geometry.Rect? = null
        var stable = 0
        until("发送按钮停稳", 5000) {
            rule.waitForIdle()
            val r = rule.onNodeWithTag("send-button", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            stable = if (r == last) stable + 1 else 0
            last = r
            Thread.sleep(40)
            stable >= 10
        }
        ui.tap("send-button")
    }

    private fun typeAt(text: String) {
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performTextInput(text)
        rule.waitForIdle()
    }

    private fun pickRow(userId: String) {
        ui.waitTag("mention-row", 10_000)
        val end = System.currentTimeMillis() + 10_000
        while (ui.hasTag("mention-picker") && System.currentTimeMillis() < end) {
            runCatching { rule.onNode(hasTestTag("mention-row") and withData(DataUser, userId), useUnmergedTree = true).performClick() }
            Thread.sleep(500)
            rule.waitForIdle()
        }
        check(!ui.hasTag("mention-picker")) { "选择没关" }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) =
        rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun inConv(conv: String) = hasAnyAncestor(hasTestTag("conv-item") and withData(DataConvId, conv))

    // ---- 发送 ----

    @Test fun xp_c4_send() {
        appLogin(me)
        val im = model.im!!.client
        // ① 单聊撤回
        for (q in others(ME)) {
            val conv = c2cConvId(me, user(q))
            open(chatPath(conv))
            ui.waitTag("composer-input")
            ui.type("composer-input", recallText(ME, q))
            sendNow()
            until("发给 $q 的已发送", 30_000) { im.messages(conv).any { it.body.text == recallText(ME, q) && it.status == MessageStatus.SENT } }
            menuItem(recallText(ME, q), "menu-recall")
            until("撤回生效", 10_000) { !ui.hasText(recallText(ME, q)) && ui.hasText("你撤回了一条消息") }
        }
        // ② m 群：引用 q1 的消息回复；@ q1、q2
        val (q1, q2) = others(ME)
        rule.waitUntil(15_000) { model.im!!.convs.any { it.convId == mGroup(ME) } }
        open(chatPath(mGroup(ME)))
        ui.waitTag("composer-input")
        until("预先放的消息", 30_000) {
            if (!ui.hasText(quoted(ME))) loadAll(mGroup(ME))
            ui.hasText(quoted(ME))
        }
        menuItem(quoted(ME), "menu-quote")
        ui.waitTag("quote-bar")
        assertTrue("${user(q1)}: ${quoted(ME)}" in ui.textOf("quote-bar-text"), "引用条")
        ui.type("composer-input", reply(ME))
        sendNow()
        until("引用回复已发送", 30_000) { im.messages(mGroup(ME)).any { it.body.text == reply(ME) && it.status == MessageStatus.SENT } }
        val r = im.messages(mGroup(ME)).first { it.body.text == reply(ME) }
        assertEquals(quoted(ME), r.body.quote?.text)
        rule.waitUntil(10_000) { hasNode(hasTestTag("msg-quote") and hasAnyDescendant(hasText("${user(q1)}: ${quoted(ME)}"))) }
        typeAt("@")
        pickRow(user(q1))
        typeAt("@")
        pickRow(user(q2))
        typeAt(mention(ME))
        val typed = ui.textOf("composer-input")
        assertTrue("@${user(q1)} @${user(q2)} ${mention(ME)}" in typed, "输入框：$typed")
        sendNow()
        until("提及已发送", 30_000) { im.messages(mGroup(ME)).any { it.body.text?.endsWith(mention(ME)) == true && it.status == MessageStatus.SENT } }
        val m = im.messages(mGroup(ME)).first { it.body.text?.endsWith(mention(ME)) == true }
        assertEquals(setOf(user(q1), user(q2)), m.body.mentions!!.userIds.toSet())
        // ③ r 群：群主撤回两个成员的消息（先 q2 的，它是最后一条）
        open(chatPath(rGroup(ME)))
        ui.waitTag("composer-input")
        for (q in listOf(q2, q1)) {
            until("预先放的 $q 的消息", 30_000) {
                if (!ui.hasText(seed(q, ME))) loadAll(rGroup(ME))
                ui.hasText(seed(q, ME))
            }
            menuItem(seed(q, ME), "menu-recall")
            ui.waitText("你撤回了 ${user(q)} 的一条消息", 10_000)
        }
        assertFalse(ui.hasText("XP4-r-"), "原文不在页面上")
        open("/chats")
        rule.waitUntil(10_000) { hasNode(hasTestTag("conv-summary") and inConv(rGroup(ME)) and hasText("你撤回了 ${user(q2)} 的一条消息")) }
    }

    // ---- 接收 ----

    @Test fun xp_c4_receive() {
        val from = (args.getString("ttXpFrom") ?: others(ME).joinToString(",")).split(',').filter { it.isNotEmpty() }
        assertTrue(from.isNotEmpty() && from.all { it in PLATFORMS && it != ME }, "ttXpFrom：$from")
        appLogin(me)
        val im = model.im!!.client
        for (p in from) {
            val (q1, q2) = others(p)
            val c2c = c2cConvId(me, user(p))
            // 列表
            open("/chats")
            rule.waitUntil(30_000) { hasNode(hasTestTag("mention-tag") and inConv(mGroup(p)) and hasText("[有人@我]", substring = true)) }
            rule.waitUntil(10_000) { hasNode(inConv(c2c) and hasText("撤回了一条消息", substring = true)) }
            val last = if (q2 == ME) "${user(p)}撤回了你的一条消息" else "${user(p)}撤回了一条成员消息"
            rule.waitUntil(10_000) { hasNode(hasTestTag("conv-summary") and inConv(rGroup(p)) and hasText(last)) }
            // 单聊
            open(chatPath(c2c))
            ui.waitTag("composer-input")
            until("单聊里 $p 的撤回", 30_000) {
                if (!ui.hasText("对方撤回了一条消息")) loadAll(c2c)
                ui.hasText("对方撤回了一条消息")
            }
            assertFalse(ui.hasText(recallText(p, ME)), "原文不在页面上")
            assertFalse(offThread { model.im!!.client.debugExport() }.toString().contains(recallText(p, ME)), "内存里没有原文")
            // m 群：引用、提及
            open(chatPath(mGroup(p)))
            ui.waitTag("composer-input")
            until("m 群的消息", 30_000) {
                if (!ui.hasText(mention(p))) loadAll(mGroup(p))
                ui.hasText(mention(p))
            }
            rule.waitUntil(10_000) { hasNode(hasTestTag("msg-quote") and hasAnyDescendant(hasText("${user(q1)}: ${quoted(p)}"))) }
            val got = im.messages(mGroup(p)).first { it.body.text?.endsWith(mention(p)) == true }
            assertTrue(me in got.body.mentions!!.userIds, "mentions 里有我")
            assertTrue(hasNode(hasTestTag("message") and withData(DataMark, "mention") and hasAnyDescendant(hasText(mention(p), substring = true))), "提及消息标记为提到我")
            // r 群：规则 1a 第 3 行
            open(chatPath(rGroup(p)))
            ui.waitTag("composer-input")
            until("r 群的撤回", 30_000) {
                if (!ui.hasText("撤回了你的一条消息")) loadAll(rGroup(p))
                ui.hasText("${user(p)}撤回了你的一条消息") && ui.hasText("${user(p)}撤回了一条成员消息")
            }
            assertFalse(ui.hasText("XP4-r-"), "原文不在页面上")
        }
    }

    private companion object {
        const val ME = "android"
        val PLATFORMS = listOf("h5", "android", "ios")
    }
}
