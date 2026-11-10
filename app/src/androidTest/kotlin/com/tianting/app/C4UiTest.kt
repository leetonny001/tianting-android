package com.tianting.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import com.tianting.app.ui.DataMark
import com.tianting.app.ui.DataUser
import com.tianting.sdk.MessageStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FE-667（撤回、引用）、FE-668（@ 成员、提及提醒）的页面状态：假服务端 + 假账号接口（features.msgActions），只验页面对 SDK 结果的处理。
 * 真实 im-server（IM_MSG_ACTIONS_ENABLED=1）的流程在 C4E2eTest。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class C4UiTest : UiTest() {
    @Before fun on() {
        http.msgActions = true
    }

    // ---- 准备 ----

    /** 服务端时间（auth ack 的 serverTime 用这个，默认就是设备时间）。 */
    private var serverNow: () -> Long = { System.currentTimeMillis() }

    private fun textMsg(conv: String, seq: Long, from: String, text: String, ts: Long = T0 + seq * 1000, quote: JsonObject? = null, mentions: JsonObject? = null): JsonObject {
        val body = linkedMapOf<String, Any?>("text" to text)
        quote?.let { body["quote"] = it }
        mentions?.let { body["mentions"] = it }
        return obj("msgId" to "$conv#$seq", "clientMsgId" to "$from-$conv-$seq", "convId" to conv, "seq" to seq, "from" to from, "type" to "text", "body" to js(body), "ts" to ts)
    }

    private fun recalledMsg(conv: String, seq: Long, from: String, by: String, ts: Long = T0 + seq * 1000) = obj(
        "msgId" to "$conv#$seq", "clientMsgId" to "$from-$conv-$seq", "convId" to conv, "seq" to seq, "from" to from, "type" to "text", "ts" to ts,
        "deleted" to true, "deletedAt" to ts + 1, "recalled" to true, "recalledAt" to ts + 1, "recalledBy" to by,
    )

    private fun quoteOf(conv: String, seq: Long?, from: String?, state: String, text: String? = null, type: String = "text") =
        js(linkedMapOf<String, Any?>("msgId" to "$conv#${seq ?: 999}", "seq" to seq, "from" to from, "type" to type, "state" to state, "text" to text).filterValues { it != null }).jsonObject

    private fun withBob(vararg ms: JsonObject) {
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = ms.size.toLong(), lastMsg = lastMsg("bob", "x")))
        server.messages[BOB_CONV] = ms.toList()
    }

    private fun group(myRole: String, vararg ms: JsonObject, members: List<JsonObject> = listOf(memberJson("bob", "owner", "鲍勃"), memberJson("alice", nickname = "爱丽丝"), memberJson("carol", nickname = "卡罗尔"), memberJson("wang", nickname = "小王"))) {
        server.group(myRole = myRole, members = members)
        server.convs = listOf(convJson(G1, "周末球局", maxSeq = ms.size.toLong(), memberCount = members.size, myRole = myRole, lastMsg = lastMsg("bob", "x")))
        server.messages[G1] = ms.toList()
    }

    /** 打开 App（已登录），等 features.msgActions 拿到。 */
    private fun open(deepLink: String) {
        server.authExtra = mapOf("serverTime" to serverNow())
        start(deepLink)
        waitFor("config") { model.im?.msgActions == true }
        rule.waitTag("composer-input")
    }

    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
    private fun menuOf(text: String): List<String> {
        bubbleOf(text).performTouchInput { longClick() }
        rule.waitTag("bubble-menu")
        return listOf("menu-copy", "menu-quote", "menu-recall", "menu-retry", "menu-delete").filter { rule.hasTag(it) }
    }

    private fun closeMenu() {
        androidx.test.espresso.Espresso.pressBack()
        rule.waitGoneTag("bubble-menu")
    }

    private fun sends() = server.frames("send")

    /** 等列表自己的翻页（屏幕高、一页填不满时会接着翻）停下来：sync 帧数 1 秒不变。 */
    private fun settleSyncs() {
        var last = -1
        var stable = 0
        waitFor("翻页停下", 15_000) {
            val n = server.frames("sync").size
            stable = if (n == last) stable + 1 else 0
            last = n
            Thread.sleep(100)
            stable >= 10
        }
    }
    private fun lastSendBody(): JsonObject = sends().last().data["body"]!!.jsonObject
    private fun typeAt(text: String) {
        n("composer-input").performTextInput(text)
        rule.waitForIdle()
    }
    private fun input() = textOf("composer-input")

    // ---- 菜单（PRD-ui F-UI-09 规则 3～5） ----

    @Test fun tc_ui_09_01_switch_on_copy_quote_recall_by_state() { // TC-UI-09-01、AC-UI-09-1（v1.4：开启时按消息状态）
        val now = serverNow()
        withBob(textMsg(BOB_CONV, 1, "bob", "对方的", ts = now - 30_000), textMsg(BOB_CONV, 2, "alice", "自己的", ts = now - 30_000))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("自己的")
        assertEquals(listOf("menu-copy", "menu-quote"), menuOf("对方的"), "别人的单聊消息：复制、引用")
        closeMenu()
        assertEquals(listOf("menu-copy", "menu-quote", "menu-recall"), menuOf("自己的"), "自己 2 分钟内的：复制、引用、撤回")
        assertEquals(listOf("复制", "引用", "撤回"), listOf("menu-copy", "menu-quote", "menu-recall").map { textOf(it) })
    }

    @Test fun issue_36_old_server_without_msg_actions_only_copy() { // 问题 36：没有 features.msgActions 按关闭
        http.msgActions = null
        withBob(textMsg(BOB_CONV, 1, "alice", "自己的", ts = System.currentTimeMillis() - 10_000))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("自己的")
        Thread.sleep(500) // 配置到了也还是关
        assertEquals(listOf("menu-copy"), menuOf("自己的"))
    }

    @Test fun tc_rcl_01_08_recall_by_corrected_server_time_and_4097_while_open() { // TC-RCL-01-08、AC-RCL-01-8
        // 设备时钟比服务端快 10 分钟：按设备钟两条都超过 2 分钟，按校正后的服务端时间 60 秒前那条还能撤回
        val skew = 600_000L
        serverNow = { System.currentTimeMillis() - skew }
        val now = serverNow()
        withBob(textMsg(BOB_CONV, 1, "alice", "121 秒前", ts = now - 121_000), textMsg(BOB_CONV, 2, "alice", "60 秒前", ts = now - 60_000))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("60 秒前")
        assertFalse("menu-recall" in menuOf("121 秒前"), "121 秒后没有撤回")
        closeMenu()
        assertTrue("menu-recall" in menuOf("60 秒前"), "60 秒内有撤回")
        // 菜单开着过了时限：服务端回 4097（windowSeconds=120），提示，消息不变
        server.fail("recall", 4097, details = obj("windowSeconds" to 120))
        click("menu-recall")
        rule.waitText("发送时间超过 2 分钟，无法撤回")
        assertTrue(rule.hasText("60 秒前"))
        assertFalse(rule.hasTag("msg-recalled"))
        assertEquals("c2c:alice:bob#2", server.frames("recall").single().data["msgId"]!!.jsonPrimitive.content)
    }

    @Test fun tc_rcl_01_08_menu_items_are_computed_when_long_pressed() { // AC-RCL-01-8：页面没有重组，过了 2 分钟再长按也没有"撤回"
        val now = serverNow()
        withBob(textMsg(BOB_CONV, 1, "alice", "快到 2 分钟", ts = now - 116_000))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("快到 2 分钟")
        Thread.sleep(6000)
        assertFalse("menu-recall" in menuOf("快到 2 分钟"))
    }

    @Test fun tc_rcl_02_05_admin_recalling_another_admin_4030() { // TC-RCL-02-05、AC-RCL-02-5
        group("admin", textMsg(G1, 1, "carol", "另一个管理员的", ts = System.currentTimeMillis() - 3600_000))
        open("tianting://chat/group%3Ag1")
        rule.waitText("另一个管理员的")
        assertTrue("menu-recall" in menuOf("另一个管理员的"), "客户端不知道对方角色：有撤回")
        server.fail("recall", 4030)
        click("menu-recall")
        rule.waitText("你没有权限撤回这条消息")
        assertTrue(rule.hasText("另一个管理员的"))
        assertFalse(rule.hasTag("msg-recalled"))
    }

    @Test fun recall_menu_by_role_member_owner() { // F-RCL-02 规则 1、1b：普通成员对别人的没有；群主 7 天内任何人的都有
        group("member", textMsg(G1, 1, "carol", "成员看别人的", ts = System.currentTimeMillis() - 30_000))
        open("tianting://chat/group%3Ag1")
        rule.waitText("成员看别人的")
        assertFalse("menu-recall" in menuOf("成员看别人的"))
    }

    @Test fun recall_menu_owner_six_days() { // F-RCL-02 规则 2：群主 7 天内
        group("owner", textMsg(G1, 1, "carol", "六天前的", ts = System.currentTimeMillis() - 6 * 86_400_000L), textMsg(G1, 2, "carol", "八天前的", ts = System.currentTimeMillis() - 8 * 86_400_000L))
        open("tianting://chat/group%3Ag1")
        rule.waitText("八天前的")
        assertTrue("menu-recall" in menuOf("六天前的"))
        closeMenu()
        assertFalse("menu-recall" in menuOf("八天前的"))
    }

    // ---- 撤回的显示（F-RCL-02 规则 1a、F-RCL-03，F-UI-42 规则 4） ----

    @Test fun tc_rcl_01_01_and_03_10_c2c_recall_both_sides_and_no_menu_on_tip() { // TC-RCL-01-01（页面部分）、TC-RCL-03-10、AC-RCL-01-1、AC-RCL-03-10
        withBob(textMsg(BOB_CONV, 1, "bob", "ZXQ-RCL-对方原文"), textMsg(BOB_CONV, 2, "alice", "ZXQ-RCL-自己原文", ts = System.currentTimeMillis() - 5000))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("ZXQ-RCL-自己原文")
        menuOf("ZXQ-RCL-自己原文")
        click("menu-recall")
        rule.waitText("你撤回了一条消息")
        assertEquals("c2c:alice:bob#2", server.frames("recall").single().data["msgId"]!!.jsonPrimitive.content)
        assertFalse(rule.hasText("ZXQ-RCL-自己原文", substring = true), "页面上没有原文")
        // 对方撤回：msgDeleted 带 recalled
        server.pushEvent("msgDeleted", "convId" to BOB_CONV, "msgIds" to listOf("c2c:alice:bob#1"), "seqs" to listOf(1), "recalled" to obj("by" to "bob", "at" to System.currentTimeMillis()))
        rule.waitText("对方撤回了一条消息")
        assertFalse(rule.hasText("ZXQ-RCL-对方原文", substring = true))
        assertFalse(rule.hasText("该消息已被删除"), "撤回不显示\"该消息已被删除\"")
        // 提示行长按没有菜单（F-RCL-03 规则 6）
        txt("对方撤回了一条消息").performTouchInput { longClick() }
        assertNeverWithin(600, "menu") { rule.hasTag("bubble-menu") }
        assertFalse(model.im!!.client.messages(BOB_CONV).any { "ZXQ-RCL" in (it.body.text ?: "") }, "内存里也没有")
    }

    @Test fun tc_rcl_02_04_admin_recall_three_views_chat_and_list() { // TC-RCL-02-04（页面部分）、AC-RCL-02-4：规则 1a 第 3 行的三种看法
        server.friends = listOf(friendJson("bob", "鲍勃"), friendJson("carol", "卡罗尔"))
        group(
            "admin",
            recalledMsg(G1, 1, "carol", by = "alice"), // 我（管理员）撤回了 carol 的
            recalledMsg(G1, 2, "alice", by = "bob"), // 群主撤回了我的
            recalledMsg(G1, 3, "carol", by = "bob"), // 群主撤回了别人的
        )
        server.convs = listOf(convJson(G1, "周末球局", maxSeq = 3, memberCount = 4, myRole = "admin", lastMsg = obj("msgId" to "$G1#3", "from" to "carol", "type" to "text", "ts" to T0 + 3000, "deleted" to true, "recalled" to true, "recalledBy" to "bob")))
        open("tianting://chat/group%3Ag1")
        rule.waitText("你撤回了 卡罗尔 的一条消息")
        rule.waitText("鲍勃撤回了你的一条消息")
        rule.waitText("鲍勃撤回了一条成员消息")
        assertEquals(3, rule.onAllNodesWithTag("msg-recalled", useUnmergedTree = true).fetchSemanticsNodes().size)
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("conv-list")
        rule.waitUntil(5000) { textOf("conv-summary") == "鲍勃撤回了一条成员消息" } // 列表摘要同一张表，不加发送者前缀
    }

    @Test fun tc_rcl_03_06_group_own_last_recall_summary() { // TC-RCL-03-06（页面部分）、AC-RCL-03-6
        server.users["alice"] = obj("userId" to "alice", "nickname" to "爱丽丝", "avatar" to "", "official" to false)
        server.convs = listOf(
            convJson(G1, "我撤回的", maxSeq = 1, memberCount = 3, updatedAt = T0 + 2, lastMsg = obj("msgId" to "$G1#1", "from" to "alice", "type" to "text", "ts" to T0, "deleted" to true, "recalled" to true, "recalledBy" to "alice")),
            convJson("group:g2", "别人撤回的", maxSeq = 1, memberCount = 3, updatedAt = T0 + 1, lastMsg = obj("msgId" to "group:g2#1", "from" to "carol", "type" to "text", "ts" to T0, "deleted" to true, "recalled" to true, "recalledBy" to "carol")),
        )
        server.users["carol"] = obj("userId" to "carol", "nickname" to "卡罗尔", "avatar" to "", "official" to false)
        start()
        rule.waitTag("conv-list")
        rule.waitUntil(5000) {
            rule.onAllNodesWithTag("conv-summary", useUnmergedTree = true).fetchSemanticsNodes().map { allText(it) } == listOf("你撤回了一条消息", "卡罗尔撤回了一条消息")
        }
    }

    @Test fun tc_rcl_03_03_recalled_while_offline_shows_after_reconnect() { // TC-RCL-03-03（页面部分）、AC-RCL-03-3
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", "x"), modVer = 1))
        server.messages[BOB_CONV] = listOf(textMsg(BOB_CONV, 1, "bob", "ZXQ-RCL-2 断网前"))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("ZXQ-RCL-2 断网前")
        // 断网期间对方撤回：重连后 convs 的 modVer 变了，只重拉这个会话
        online = false
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", null, deleted = true), modVer = 2))
        server.messages[BOB_CONV] = listOf(recalledMsg(BOB_CONV, 1, "bob", by = "bob"))
        val syncs = server.frames("sync").size
        server.drop()
        online = true
        rule.waitText("对方撤回了一条消息", 15_000)
        assertFalse(rule.hasText("ZXQ-RCL-2", substring = true))
        assertTrue(server.frames("sync").size > syncs, "重连后重拉了")
    }

    // ---- 引用（F-QUO-01、F-QUO-02） ----

    @Test fun tc_quo_01_05_quote_bar_cancel_then_send_shows_on_bubble() { // TC-QUO-01-05、AC-QUO-01-5
        server.friends = listOf(friendJson("bob", "鲍勃"))
        withBob(textMsg(BOB_CONV, 1, "bob", "被引用的话"))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("被引用的话")
        menuOf("被引用的话")
        click("menu-quote")
        rule.waitTag("quote-bar")
        assertEquals("鲍勃: 被引用的话", textOf("quote-bar-text"))
        rule.onNodeWithTag("quote-cancel").assertContentDescriptionEquals("取消引用") // 只有图标的按钮有可读名称
        click("quote-cancel")
        rule.waitGoneTag("quote-bar")
        type("composer-input", "不带引用")
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        assertNull(lastSendBody()["quote"], "取消后没有发出 quote")
        menuOf("被引用的话")
        click("menu-quote")
        rule.waitTag("quote-bar")
        type("composer-input", "我的回复")
        clickSendWhenSettled()
        waitFor("sent") { sends().size == 2 }
        assertEquals(obj("msgId" to "c2c:alice:bob#1"), lastSendBody()["quote"], "上行只带 msgId")
        rule.waitGoneTag("quote-bar")
        // 气泡顶部显示引用内容
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("msg-quote") and hasAnyDescendant(hasText("鲍勃: 被引用的话")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun quote_bar_and_bubble_four_states() { // 第 13.4 节"引用条"：正常、已撤回、已删除、已过期；不认识的"引用内容不可用"（F-QUO-02 规则 3）
        server.friends = listOf(friendJson("bob", "鲍勃"))
        withBob(
            textMsg(BOB_CONV, 1, "bob", "正常的", quote = quoteOf(BOB_CONV, 1, "bob", "normal", "原文摘要")),
            textMsg(BOB_CONV, 2, "bob", "撤回的", quote = quoteOf(BOB_CONV, 2, "bob", "recalled")),
            textMsg(BOB_CONV, 3, "bob", "删除的", quote = quoteOf(BOB_CONV, null, null, "deleted")),
            textMsg(BOB_CONV, 4, "bob", "过期图", quote = quoteOf(BOB_CONV, 4, "bob", "expired", type = "image")),
            textMsg(BOB_CONV, 5, "bob", "过期文件", quote = quoteOf(BOB_CONV, 5, "bob", "expired", type = "file")),
            textMsg(BOB_CONV, 6, "bob", "以后的", quote = quoteOf(BOB_CONV, 6, "bob", "edited")),
        )
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("以后的")
        val labels = rule.onAllNodesWithTag("msg-quote", useUnmergedTree = true).fetchSemanticsNodes().map { allText(it) }.sorted()
        assertEquals(listOf("[图片] 已过期", "[文件] 已过期", "引用内容不可用", "引用内容已被删除", "引用内容已被撤回", "鲍勃: 原文摘要").sorted(), labels)
    }

    @Test fun quote_bar_follows_a_recall_of_the_quoted_message() { // F-QUO-02 规则 4、问题 41：引用条跟着撤回变，不留原文
        withBob(textMsg(BOB_CONV, 1, "bob", "ZXQ-QUO-1 原文"))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("ZXQ-QUO-1 原文")
        menuOf("ZXQ-QUO-1 原文")
        click("menu-quote")
        rule.waitTag("quote-bar")
        server.pushEvent("msgDeleted", "convId" to BOB_CONV, "msgIds" to listOf("c2c:alice:bob#1"), "seqs" to listOf(1), "recalled" to obj("by" to "bob", "at" to System.currentTimeMillis()))
        rule.waitUntil(5000) { textOf("quote-bar-text") == "引用内容已被撤回" }
        assertFalse(rule.hasText("ZXQ-QUO-1", substring = true))
    }

    @Test fun tc_quo_02_05_quote_with_html_is_plain_text() { // TC-QUO-02-05、AC-QUO-02-5
        val html = "<img src=x onerror=alert(1)><b>粗</b>"
        withBob(textMsg(BOB_CONV, 1, "bob", html))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText(html)
        menuOf(html)
        click("menu-quote")
        rule.waitTag("quote-bar")
        assertTrue(textOf("quote-bar-text").endsWith(html), "引用条按原样显示")
        type("composer-input", "回")
        clickSendWhenSettled()
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("msg-quote") and hasAnyDescendant(hasText(html, substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(rule.hasText("粗") && !rule.hasText(html), "没有被当成标记解析")
    }

    @Test fun tc_quo_02_03_jump_within_200_highlights_beyond_is_too_early() { // TC-QUO-02-03、AC-QUO-02-3
        // 700 条：已加载最近 20 条（681～700）；q1 引用 581（差 100），q2 引用 181（差 500）
        val all = (1L..698L).map { textMsg(BOB_CONV, it, "bob", "第${it}条") } +
            textMsg(BOB_CONV, 699, "bob", "q2", quote = quoteOf(BOB_CONV, 181, "bob", "normal", "第181条")) +
            textMsg(BOB_CONV, 700, "bob", "q1", quote = quoteOf(BOB_CONV, 581, "bob", "normal", "第581条"))
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 700, lastMsg = lastMsg("bob", "q1")))
        server.messages[BOB_CONV] = all
        server.handlers["sync"] = { f ->
            val before = f.data["beforeSeq"]?.jsonPrimitive?.content?.toLongOrNull() ?: Long.MAX_VALUE
            val limit = f.data["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            val older = all.filter { it["seq"]!!.jsonPrimitive.content.toLong() < before }
            val out = older.takeLast(limit)
            Reply.Data(obj("messages" to out, "hasMore" to (older.size > out.size)))
        }
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("q1")
        val seqs = { model.im!!.client.messages(BOB_CONV).mapNotNull { it.seq } }
        settleSyncs()
        assertTrue(seqs().min() > 581 && seqs().min() - 181 > 200, "q1 在已加载范围外 200 条以内，q2 在 200 条外")
        // q2：500 条外，提示太早，不翻页
        val before = server.frames("sync").size
        rule.onNode(hasTestTag("msg-quote") and hasAnyDescendant(hasText("bob: 第181条")), useUnmergedTree = true).performClick()
        rule.waitText("消息太早，请翻页查看")
        assertEquals(before, server.frames("sync").size, "没有翻页")
        // q1：100 条以内，翻页后滚动到原消息并高亮
        rule.onNode(hasTestTag("msg-quote") and hasAnyDescendant(hasText("bob: 第581条")), useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("msg-highlight") and hasAnyDescendant(hasText("第581条")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val loaded = seqs().sorted()
        assertEquals((loaded.first()..700L).toList(), loaded, "已加载范围连续")
        assertTrue(loaded.first() <= 581)
        rule.waitGoneTag("msg-highlight", 5000) // 高亮 2 秒后消失
    }

    @Test fun quote_target_gone_says_missing() { // F-QUO-02 规则 5：拉不到提示"消息不存在"
        withBob(textMsg(BOB_CONV, 1, "bob", "引用了没了的", quote = quoteOf(BOB_CONV, null, null, "deleted")))
        open("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitText("引用了没了的")
        n("msg-quote").performClick()
        rule.waitText("消息不存在")
    }

    // ---- @ 成员（F-MEN-01、F-MEN-02） ----

    private fun openGroupAt(myRole: String = "member") {
        server.friends = listOf(friendJson("wang", "小王", remark = "老板"))
        group(myRole, textMsg(G1, 1, "bob", "群里的话"))
        open("tianting://chat/group%3Ag1")
        rule.waitText("群里的话")
    }

    private fun pickWang() {
        typeAt("@")
        rule.waitTag("mention-row")
        rule.onNode(hasTestTag("mention-row") and withData(DataUser, "wang"), useUnmergedTree = true).performClick()
        rule.waitGoneTag("mention-picker")
    }

    @Test fun tc_men_01_06_single_chat_at_does_not_open_picker() { // TC-MEN-01-06、AC-MEN-01-6
        withBob(textMsg(BOB_CONV, 1, "bob", "你好"))
        open("tianting://chat/c2c%3Aalice%3Abob")
        typeAt("@")
        assertNeverWithin(1000, "单聊打开了成员选择") { rule.hasTag("mention-picker") }
        assertEquals("@", input())
    }

    @Test fun tc_men_01_07_picker_shows_remark_inserts_nickname() { // TC-MEN-01-07（页面部分）、AC-MEN-01-7
        openGroupAt()
        typeAt("@")
        rule.waitTag("mention-row")
        val row = rule.onNode(hasTestTag("mention-row") and withData(DataUser, "wang"), useUnmergedTree = true)
        row.assertAllTextContains("老板") // 选择列表显示备注
        row.assertAllTextContains("wang") // 和 userId（同名靠它区分）
        assertTrue(rule.onAllNodes(hasTestTag("mention-row") and withData(DataUser, "alice"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "列表不含自己")
        row.performClick()
        rule.waitGoneTag("mention-picker")
        assertEquals("@小王 ", input(), "插入公开昵称，不用备注")
        typeAt("看一下")
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        val body = lastSendBody()
        assertEquals("@小王 看一下", body["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("wang"), body["mentions"]!!.jsonObject["userIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("老板" in sends().last().data.toString(), "上行搜不到备注")
    }

    @Test fun tc_men_01_05_deleting_the_inserted_text_drops_the_mention() { // TC-MEN-01-05、AC-MEN-01-5
        openGroupAt()
        pickWang()
        assertEquals("@小王 ", input())
        n("composer-input").performTextReplacement("你好") // 删掉"@小王 "
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        assertNull(lastSendBody()["mentions"], "上行没有 mentions")
    }

    @Test fun tc_men_01_12_one_backspace_removes_the_whole_mention() { // TC-MEN-01-12、AC-MEN-01-12
        openGroupAt()
        typeAt("你好 ")
        pickWang()
        assertEquals("你好 @小王 ", input())
        n("composer-input").performKeyInput { pressKey(Key.Backspace) }
        rule.waitUntil(3000) { input() == "你好 " }
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        assertNull(lastSendBody()["mentions"], "上行没有 mentions")
    }

    @Test fun tc_men_01_08_email_no_full_width_yes_paste_no() { // TC-MEN-01-08、AC-MEN-01-8
        openGroupAt()
        for (c in "a@b.com") typeAt(c.toString())
        assertNeverWithin(800, "a@b.com 打开了选择") { rule.hasTag("mention-picker") }
        n("composer-input").performTextReplacement("")
        typeAt("＠")
        rule.waitTag("mention-picker") // 行首全角＠打开
        click("mention-cancel")
        rule.waitGoneTag("mention-picker")
        assertEquals("＠", input(), "关掉选择时 @ 留作普通文字")
        n("composer-input").performTextReplacement("")
        typeAt("@u2") // 粘贴：一次进来多个字
        assertNeverWithin(800, "粘贴打开了选择") { rule.hasTag("mention-picker") }
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        assertNull(lastSendBody()["mentions"], "粘贴的 @u2 不产生 mentions")
    }

    @Test fun tc_men_02_04_only_owner_and_admin_see_all() { // TC-MEN-02-04、AC-MEN-02-4（成员）
        openGroupAt("member")
        typeAt("@")
        rule.waitTag("mention-row")
        assertFalse(rule.hasTag("mention-all"), "普通成员看不到\"所有人\"")
    }

    @Test fun tc_men_02_04_admin_sees_all_and_sends_all() { // TC-MEN-02-04、AC-MEN-02-4（管理员）
        openGroupAt("admin")
        typeAt("@")
        rule.waitTag("mention-all")
        click("mention-all")
        assertEquals("@所有人 ", input())
        typeAt("开会")
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        assertEquals(obj("all" to true), lastSendBody()["mentions"])
    }

    @Test fun picker_states_loading_empty_error_and_plain_text_still_sends() { // 第 13.4 节"@ 选择"：加载中、无结果、加载失败（可以继续发普通文本）
        openGroupAt()
        server.hold("groupMembers")
        typeAt("@")
        rule.waitTag("mention-loading")
        server.fail("groupMembers", 5000)
        click("mention-cancel")
        typeAt(" ")
        typeAt("@")
        rule.waitTag("mention-error")
        assertEquals("加载失败，点击重试", textOf("mention-error"))
        server.reply("groupMembers", obj("members" to emptyList<JsonObject>(), "nextCursor" to ""))
        click("mention-error")
        rule.waitTag("mention-empty")
        // 搜索：带 keyword
        server.reply("groupMembers", obj("members" to listOf(memberJson("wang", nickname = "小王")), "nextCursor" to ""))
        n("mention-search").performTextReplacement("xiao")
        rule.waitTag("mention-row", 3000)
        assertEquals("xiao", server.frames("groupMembers").last().data["keyword"]!!.jsonPrimitive.content)
        click("mention-cancel")
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        assertEquals("@ @", lastSendBody()["text"]!!.jsonPrimitive.content)
        assertNull(lastSendBody()["mentions"])
    }

    @Test fun tc_men_01_10_draft_keeps_text_mention_and_quote() { // TC-MEN-01-10、AC-MEN-01-10
        openGroupAt()
        bubbleOf("群里的话").performTouchInput { longClick() }
        click("menu-quote")
        rule.waitTag("quote-bar")
        typeAt("你好 ")
        pickWang()
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("conv-list")
        rule.waitUntil(5000) { rule.hasTag("draft-tag") }
        assertEquals("[草稿] 你好 @小王 ", textOf("draft-tag"))
        inst.runOnMainSync { model.nav.go(com.tianting.app.core.chatPath(G1)) }
        rule.waitTag("quote-bar")
        assertEquals("你好 @小王 ", input())
        assertEquals("鲍勃: 群里的话", textOf("quote-bar-text"))
        clickSendWhenSettled()
        waitFor("sent") { sends().isNotEmpty() }
        val body = lastSendBody()
        assertEquals(listOf("wang"), body["mentions"]!!.jsonObject["userIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(obj("msgId" to "$G1#1"), body["quote"])
    }

    @Test fun tc_men_02_05_4293_fails_toasts_and_never_resends() { // TC-MEN-02-05（页面部分）、AC-MEN-02-5
        openGroupAt("owner")
        server.fail("send", 4293, details = obj("retryAfterSeconds" to 600))
        typeAt("@")
        click("mention-all")
        typeAt("开会")
        clickSendWhenSettled()
        rule.waitText("@所有人次数已用完")
        rule.waitTag("failed-icon")
        assertEquals(MessageStatus.FAILED, model.im!!.client.messages(G1).last().status)
        val n = sends().size
        server.handlers.remove("send")
        server.drop()
        waitFor("重连", 10_000) { server.connections.size >= 2 && model.im!!.state == com.tianting.sdk.ConnState.CONNECTED }
        assertNeverWithin(2000, "重连后自动重发了") { sends().size != n }
    }

    // ---- 提及提醒（F-MEN-03） ----

    @Test fun tc_men_01_01_list_prefix_and_mentioned_bubble() { // TC-MEN-01-01（页面部分）、AC-MEN-01-1
        server.group("member")
        server.convs = listOf(
            convJson(G1, "被@的群", maxSeq = 1, unread = 1, memberCount = 3, updatedAt = T0 + 2, lastMsg = lastMsg("bob", "@爱丽丝 来"), mentionUnread = 1, firstMentionSeq = 1),
            convJson("group:g2", "没被@的群", maxSeq = 1, unread = 1, memberCount = 3, updatedAt = T0 + 1, lastMsg = lastMsg("bob", "@卡罗尔 来"), mentionUnread = 0),
        )
        server.messages[G1] = listOf(textMsg(G1, 1, "bob", "@爱丽丝 来", mentions = obj("userIds" to listOf("alice"))))
        start()
        rule.waitTag("mention-tag")
        assertEquals(1, rule.onAllNodesWithTag("mention-tag", useUnmergedTree = true).fetchSemanticsNodes().size, "没被 @ 的群没有前缀")
        assertTrue(textOf("mention-tag").startsWith("[有人@我] "))
        inst.runOnMainSync { model.nav.go(com.tianting.app.core.chatPath(G1)) }
        rule.waitText("@爱丽丝 来")
        waitFor("config") { model.im?.msgActions == true }
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("message") and withData(DataMark, "mention"), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    }

    @Test fun tc_men_03_05_mention_beats_draft() { // TC-MEN-03-05、AC-MEN-03-5
        server.group("member")
        server.convs = listOf(convJson(G1, "群", maxSeq = 1, unread = 0, memberCount = 3, lastMsg = lastMsg("bob", "最后一条"), mentionUnread = 0))
        start("tianting://chat/group%3Ag1")
        waitFor("config") { model.im?.msgActions == true }
        type("composer-input", "我的草稿")
        inst.runOnMainSync { model.nav.back() }
        rule.waitTag("draft-tag")
        // 被 @ 了：实时消息带 mentions，SDK 本地 +1（服务端给过计数）
        server.pushMsg(textMsg(G1, 2, "bob", "@爱丽丝 看", ts = System.currentTimeMillis(), mentions = obj("userIds" to listOf("alice"))))
        rule.waitTag("mention-tag")
        assertFalse(rule.hasTag("draft-tag"), "不是\"[草稿]\"")
        assertTrue(textOf("mention-tag").startsWith("[有人@我]"))
        assertFalse("我的草稿" in textOf("mention-tag"), "有提及时显示最后一条，不显示草稿（问题 38）")
    }

    @Test fun tc_men_03_06_jump_button_scrolls_highlights_and_disappears() { // TC-MEN-03-06、AC-MEN-03-6
        val all = (1L..100L).map { if (it == 10L) textMsg(G1, it, "bob", "第10条@爱丽丝", mentions = obj("userIds" to listOf("alice"))) else textMsg(G1, it, "bob", "第${it}条") }
        server.group("member")
        server.convs = listOf(convJson(G1, "群", maxSeq = 100, unread = 100, memberCount = 3, lastMsg = lastMsg("bob", "第100条"), mentionUnread = 1, firstMentionSeq = 10))
        server.handlers["sync"] = { f ->
            val before = f.data["beforeSeq"]?.jsonPrimitive?.content?.toLongOrNull() ?: Long.MAX_VALUE
            val limit = f.data["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            val older = all.filter { it["seq"]!!.jsonPrimitive.content.toLong() < before }
            val out = older.takeLast(limit)
            Reply.Data(obj("messages" to out, "hasMore" to (older.size > out.size)))
        }
        open("tianting://chat/group%3Ag1")
        rule.waitText("第100条")
        settleSyncs()
        rule.waitTag("mention-jump")
        n("mention-jump").assertAllTextContains("有人@我")
        click("mention-jump")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("msg-highlight") and hasAnyDescendant(hasText("第10条@爱丽丝")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(rule.hasTag("mention-jump"), "点了以后按钮消失")
        val loaded = model.im!!.client.messages(G1).mapNotNull { it.seq }.sorted()
        assertEquals((loaded.first()..100L).toList(), loaded, "已加载范围连续")
    }

    // ---- AC-CHAT-90-8、90-9：可读名称、辅助操作、浅色和深色 ----

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

    private fun c4PagesNamed(prefix: String, pref: com.tianting.app.ui.theme.ThemePref) {
        server.friends = listOf(friendJson("wang", "小王", remark = "老板"))
        val now = System.currentTimeMillis()
        server.group("admin", members = listOf(memberJson("bob", "owner", "鲍勃"), memberJson("alice", "admin"), memberJson("wang", nickname = "小王")))
        server.convs = listOf(convJson(G1, "群", maxSeq = 4, unread = 0, memberCount = 3, myRole = "admin", lastMsg = lastMsg("bob", "x"), mentionUnread = 1, firstMentionSeq = 1))
        server.messages[G1] = listOf(
            textMsg(G1, 1, "bob", "@爱丽丝 有事", mentions = obj("userIds" to listOf("alice"))),
            recalledMsg(G1, 2, "bob", by = "bob"),
            textMsg(G1, 3, "bob", "带引用", quote = quoteOf(G1, 1, "bob", "normal", "@爱丽丝 有事")),
            textMsg(G1, 4, "alice", "我刚发的", ts = now - 5000),
        )
        open("tianting://chat/group%3Ag1")
        inst.runOnMainSync { model.setTheme(pref) }
        rule.waitText("我刚发的")
        rule.waitTag("msg-recalled")
        // 长按菜单有辅助操作（第 13.4 节）：气泡有带名字的长按动作
        val bubble = bubbleOf("我刚发的").fetchSemanticsNode()
        assertEquals("消息操作", bubble.config.getOrNull(SemanticsActions.OnLongClick)?.label)
        assertClickablesHaveNames("$prefix 聊天页（撤回提示、引用、被 @ 的气泡）")
        shot("$prefix-c4-01-chat")
        menuOf("我刚发的")
        assertClickablesHaveNames("$prefix 消息菜单")
        shot("$prefix-c4-02-menu")
        click("menu-quote")
        rule.waitTag("quote-bar")
        assertClickablesHaveNames("$prefix 引用条")
        shot("$prefix-c4-03-quote")
        typeAt("@")
        rule.waitTag("mention-row")
        assertClickablesHaveNames("$prefix @ 选择")
        shot("$prefix-c4-04-picker")
    }

    @Test fun tc_chat_90_31_c4_pages_named_light() = c4PagesNamed("light", com.tianting.app.ui.theme.ThemePref.LIGHT) // TC-CHAT-90-30、90-31，AC-CHAT-90-8、90-9

    @Test fun tc_chat_90_31_c4_pages_named_dark() = c4PagesNamed("dark", com.tianting.app.ui.theme.ThemePref.DARK) // TC-CHAT-90-30、90-31，AC-CHAT-90-8、90-9
}
