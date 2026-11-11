package com.tianting.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.CONTENT_BLOCKED_SEND
import com.tianting.app.core.MESSAGE_DELETED
import com.tianting.app.core.REPORT_ASK_BLOCK
import com.tianting.app.core.REPORT_DONE
import com.tianting.app.core.chatPath
import com.tianting.app.ui.DataClientMsgId
import com.tianting.app.ui.DataStatus
import com.tianting.sdk.IMEvent
import com.tianting.sdk.c2cConvId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FE-698：C6 的三端跨端（举报和 4510；H5、Android、iOS 对同一个 im-server，管理后台连同一个服务端）。共同约定（tianting-web 的
 * xp-c6.spec.ts、tianting-ios 的 CrossPlatformC6E2ETests、tianting-admin 的 e2e/xp-c6.spec.ts 照抄这一段）：
 *   - 运行号 R；端 P = h5、android、ios。每个举报端 X 一个方向（信号的命名空间就是 X）：举报人 rX = xp{R}r{X}，被举报人 vX = xp{R}v{X}
 *     （昵称就是 userId）；vX 同时登录在另外两端（接收端 Y、Z，同一个用户的两台设备）。另有 o = xp{R}o（群里发言、撤回）、
 *     s = xp{R}s（哨兵）、群 G = xp{R}g「C6群-{R}」（o、rh5、randroid、rios），特征词 W = zxqword{R}（测试里现场加进词库，不是真实词）。
 *     协调脚本先放好：vX 给 rX 发「XP6-v-{X}」；o 在 G 发「XP6-M-{R}」，再按 h5、android、ios 的顺序发「XP6-R-{X}」；等 W 生效。
 *   - 接收端（vX 在 Y）：① 登录，列表里 rX 的会话摘要是「XP6-v-{X}」、未读 1；停在列表；贴 ready-{Y}，等另一个接收端的 ready
 *     （它登录会给这边发新设备提醒，要在开始记录之前），开始记录下行（H5 记 WebSocket 帧、Android 记 SDK 事件、iOS 在中转上记帧），
 *     贴 armed-{Y}。② 等到 reported（t0）：协调脚本紧接着让 s 给 vX 发哨兵「XP6-s1-{X}」：2 秒内哨兵出现在列表；等到 t0 + 2 秒，
 *     记录到的下行都只和哨兵的会话有关（AC-MOD-03-1、F-MOD-03 规则 7：被举报人收不到任何帧），rX 的会话不变；贴 s1-ok-{Y}，重新开始记录。
 *     ③ 等到 blocked（t0）：哨兵「XP6-s2-{X}」，同样 2 秒内出现、到 t0 + 2 秒只有哨兵，rX 的会话不变；打开 rX 的会话，只有
 *     「XP6-v-{X}」，没有含 W 的消息（AC-MOD-02-1 的客户端部分）；贴 obs-done-{Y}。
 *   - 举报端（rX 在 X）：① 登录，打开 vX 的会话看到「XP6-v-{X}」；等两个接收端的 armed。② 消息菜单「举报」→ 辱骂骚扰 → 提交，
 *     轻提示「已提交，感谢你的反馈」、询问拉黑（点取消）；贴 reported。③ 等两个接收端的 s1-ok；发「XP6 含 {W}」：失败、提示
 *     「消息包含不当内容，无法发送」、输入框清空；贴 blocked。④ 等两个接收端的 obs-done；打开 G，看到「XP6-M-{R}」「XP6-R-{X}」，
 *     停 2 秒让已读报上去，贴 g-seen；协调脚本让 o 撤回「XP6-R-{X}」，贴 recalled（带 msgId）；最后一条撤回提示行的「举报」→ 其他 →
 *     提交成功、不问拉黑（AC-MOD-03-11）；「XP6-M-{R}」的菜单「举报」→ 垃圾广告 → 提交成功（AC-MOD-04-1 的三端各举报一次）；贴 r-done。
 *   - 最后一段（命名空间 final，三端同时在线）：各端以 rX 登录、打开 G、看到「XP6-M-{R}」，贴 f-ready-{X}；管理后台（审核员）看队列里
 *     「XP6-M-{R}」那一行聚合"3 次"、授权看证据（水印）、删除这条消息，点最后一下之前贴 deleting；各端等到 deleting（t0）后
 *     「XP6-M-{R}」消失、显示「该消息已被删除」，贴 f-done-{X}（带 ms）；后台结案。
 *   - 反证：协调脚本 --fake X:report（没有举报端，协调脚本以 rX 给 vX 发一条再贴 reported）→ 接收端必须在 ② 失败；
 *     --fake X:block（协调脚本真的举报，再以 rX 发一条普通消息后贴 blocked）→ 接收端必须在 ③ 失败。
 * `-e ttXp` 运行号、`-e ttXpCoord` 信号板（scripts/run-tests.sh 用 adb reverse 转到 Mac）、`-e ttXpFrom` 是举报端 X（接收端用）。
 * 用例：xp_c6_reporter、xp_c6_observer、xp_c6_final。Android 的"下行"是 App 里 SDK 的事件（`IMClient.events`）。没有 `ttXp` 时跳过。
 * 结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class CrossPlatformC6E2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val r = C5Real(rule)
    private val ui get() = r.ui
    private val model get() = testApp.model
    private val args = InstrumentationRegistry.getArguments()
    private val run: String? = args.getString("ttXp")
    private val from: String = args.getString("ttXpFrom").orEmpty()
    private val coord: String = args.getString("ttXpCoord").orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(920, TimeUnit.SECONDS).callTimeout(920, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val me = "android"
    private val p = listOf("h5", "android", "ios")
    private var ns = ""
    private fun rr(x: String) = "xp${run}r$x"
    private fun vv(x: String) = "xp${run}v$x"
    private val s get() = "xp${run}s"
    private val g get() = "group:xp${run}g"
    private val word get() = "zxqword$run"

    @Before fun setup() {
        assumeTrue("跨端用例要协调脚本传 ttXp、ttXpCoord", !run.isNullOrEmpty() && coord.isNotEmpty())
        r.server.guard()
    }

    @After fun cleanup() {
        scope.cancel()
        if (run.isNullOrEmpty() || coord.isEmpty()) return
        r.close()
    }

    // ---- 信号板 ----

    private fun post(name: String, body: Map<String, String> = emptyMap()) {
        val json = JsonObject(body.mapValues { JsonPrimitive(it.value) }).toString()
        val req = Request.Builder().url("$coord/s/$ns/$name").post(json.toRequestBody("application/json".toMediaType())).build()
        offThread { http.newCall(req).execute().use { check(it.isSuccessful) { "signal $name ${it.code}" } } }
    }

    private fun wait(name: String, ms: Long = 300_000): Pair<Long, Map<String, String>> {
        val req = Request.Builder().url("$coord/s/$ns/$name?t=$ms").build()
        val body = offThread {
            http.newCall(req).execute().use {
                check(it.code == 200) { "等信号 $name：${it.code}" }
                it.body!!.string()
            }
        }
        val seen = System.currentTimeMillis()
        val b = Json.parseToJsonElement(body).jsonObject["body"]?.jsonObject.orEmpty()
        return seen to b.mapValues { it.value.jsonPrimitive.content }
    }

    private fun log(what: String, t0: Long) = android.util.Log.i("TT-E2E-XP6", "$ns $me $what ms=${System.currentTimeMillis() - t0}")

    // ---- 页面 ----

    private fun login(u: String) {
        r.appLogin(u)
        rule.waitUntil(15_000) { model.im?.moderation == true } // 服务端 features.moderation（IM_MOD_ENABLED=1）
    }

    private fun bubbleOf(text: String) = rule.onNode(hasTestTag("bubble") and hasAnyDescendant(hasText(text, substring = true)), useUnmergedTree = true)

    private fun hasBubble(text: String) =
        rule.onAllNodes(hasTestTag("bubble") and hasAnyDescendant(hasText(text, substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun reportFromMenu(text: String) {
        ui.waitText(text, 15_000)
        rule.waitForIdle()
        bubbleOf(text).performTouchInput { longClick() }
        ui.waitTag("bubble-menu", 5000)
        ui.tap("menu-report")
    }

    private fun submit(reason: String) {
        ui.waitTag("report-sheet", 5000)
        assertTrue("所选内容将提交给平台审核" in ui.textOf("report-hint"))
        ui.tap("report-reason-$reason")
        rule.onNodeWithTag("report-submit", useUnmergedTree = true).performScrollTo()
        ui.tap("report-submit")
        ui.waitText(REPORT_DONE, 5000)
    }

    private fun summary(conv: String) = if (r.hasItem(conv)) r.textInItem(conv, "conv-summary") else null

    // ---- 举报端 ----

    @Test fun xp_c6_reporter() {
        ns = me
        val conv = c2cConvId(rr(ns), vv(ns))
        login(rr(ns))
        r.until("vX 的会话", 30_000) { rule.waitForIdle(); summary(conv) == "XP6-v-$ns" }
        r.open(chatPath(conv))
        ui.waitText("XP6-v-$ns", 15_000)
        for (y in p - ns) wait("armed-$y", 600_000)
        // ② 举报这条单聊消息
        reportFromMenu("XP6-v-$ns")
        submit("abuse")
        ui.waitTag("report-block-dialog", 5000)
        assertTrue(REPORT_ASK_BLOCK in ui.textOf("report-block-dialog"))
        ui.tap("report-block-dialog-cancel")
        post("reported")
        // ③ 4510
        for (y in p - ns) wait("s1-ok-$y")
        ui.type("composer-input", "XP6 含 $word")
        ui.tap("send-button")
        ui.waitText(CONTENT_BLOCKED_SEND, 5000)
        rule.waitUntil(5000) {
            rule.onAllNodes(hasTestTag("message") and withData(DataStatus, "failed") and hasAnyDescendant(hasText(word, substring = true)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("", ui.textOf("composer-input"), "输入框清空")
        post("blocked")
        // ④ 撤回的群消息、AC-MOD-04-1 的目标
        for (y in p - ns) wait("obs-done-$y")
        r.open(chatPath(g))
        ui.waitText("XP6-M-$run", 15_000)
        ui.waitText("XP6-R-$ns", 15_000)
        Thread.sleep(2000) // 已读每秒最多报一次（服务端按 readSeq 判断看过没有）
        post("g-seen")
        val msgId = wait("recalled", 60_000).second["msgId"]!!
        r.until("撤回提示行", 10_000) { rule.waitForIdle(); !hasBubble("XP6-R-$ns") }
        val cid = model.im!!.client.messages(g).first { it.msgId == msgId }.clientMsgId
        val tip = hasTestTag("recall-report") and hasAnyAncestor(hasTestTag("recall-row") and withData(DataClientMsgId, cid))
        rule.waitUntil(10_000) { rule.onAllNodes(tip, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(tip, useUnmergedTree = true).performClick()
        submit("other")
        Thread.sleep(800)
        assertFalse(ui.hasTag("report-block-dialog"), "群消息不问拉黑")
        reportFromMenu("XP6-M-$run")
        submit("spam")
        Thread.sleep(800)
        assertFalse(ui.hasTag("report-block-dialog"), "群消息不问拉黑")
        post("r-done")
    }

    // ---- 接收端 ----

    @Test fun xp_c6_observer() {
        assumeTrue("ttXpFrom 是举报端（h5 或 ios）", from in listOf("h5", "ios"))
        ns = from
        val rc = c2cConvId(rr(ns), vv(ns))
        val sc = c2cConvId(s, vv(ns))
        login(vv(ns))
        r.until("rX 的会话", 30_000) { rule.waitForIdle(); summary(rc) == "XP6-v-$ns" }
        assertFalse(r.hasInItem(rc, "conv-badge"), "vX 自己发的，没有未读")
        post("ready-$me")
        for (y in p - ns - me) wait("ready-$y", 600_000)
        Thread.sleep(1500) // 另一端登录时的新设备提醒落在记录之前
        val events = CopyOnWriteArrayList<IMEvent>()
        val client = model.im!!.client
        var job: Job = scope.launch { client.events.collect { events.add(it) } }
        Thread.sleep(200)
        post("armed-$me")
        /** 从 t0 到 t0 + 2 秒：哨兵出现，SDK 事件只和哨兵的会话（或哨兵这个人的资料）有关，rX 的会话不变。 */
        fun window(t0: Long, sentinel: String, step: String) {
            while (true) {
                rule.waitForIdle()
                if (summary(sc) == sentinel) break
                check(System.currentTimeMillis() - t0 < 2000) { "$step：2 秒内没有哨兵" }
                Thread.sleep(30)
            }
            log("$step sentinel", t0)
            Thread.sleep(maxOf(0, t0 + 2000 - System.currentTimeMillis()))
            val got = events.toList()
            val stray = got.filterNot { e ->
                (e is IMEvent.MessagesChanged && e.convId == sc) || (e is IMEvent.ProfileChanged && e.userId == s)
            }
            assertTrue(stray.isEmpty(), "$step：被举报人收到了哨兵以外的事件：${stray.map { it::class.simpleName }}")
            assertTrue(got.any { it is IMEvent.MessagesChanged && it.convId == sc }, "$step：哨兵的事件")
            rule.waitForIdle()
            assertEquals("XP6-v-$ns", summary(rc))
            assertFalse(r.hasInItem(rc, "conv-badge"))
            job.cancel()
            events.clear()
            job = scope.launch { client.events.collect { events.add(it) } }
            Thread.sleep(100)
        }
        // ②
        var t0 = wait("reported").first
        window(t0, "XP6-s1-$ns", "举报之后")
        post("s1-ok-$me")
        // ③
        t0 = wait("blocked").first
        window(t0, "XP6-s2-$ns", "4510 之后")
        r.open(chatPath(rc))
        ui.waitText("XP6-v-$ns", 15_000)
        Thread.sleep(1000)
        assertEquals(1, rule.onAllNodes(hasTestTag("message"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(ui.hasText(word))
        assertTrue(client.messages(rc).none { word in (it.body.text ?: "") })
        post("obs-done-$me")
    }

    // ---- 最后一段 ----

    @Test fun xp_c6_final() {
        ns = "final"
        login(rr(me))
        r.open(chatPath(g))
        ui.waitText("XP6-M-$run", 15_000)
        post("f-ready-$me")
        val t0 = wait("deleting", 900_000).first
        r.until("XP6-M 消失", 10_000) { rule.waitForIdle(); !hasBubble("XP6-M-$run") && ui.hasTag("msg-deleted") }
        val ms = System.currentTimeMillis() - t0
        log("deleted", t0)
        assertTrue(MESSAGE_DELETED in ui.textOf("msg-deleted"))
        assertTrue(model.im!!.client.messages(g).none { "XP6-M-$run" in (it.body.text ?: "") })
        post("f-done-$me", mapOf("ms" to ms.toString()))
    }
}
