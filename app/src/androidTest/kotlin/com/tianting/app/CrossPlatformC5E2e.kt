package com.tianting.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.tianting.app.core.chatPath
import com.tianting.sdk.ConnState
import com.tianting.sdk.c2cConvId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FE-686：C5 的三端互发互收（置顶、免打扰、删除的多端同步；H5、Android、iOS 对同一个 im-server）。同一个用户的两台设备分在两端，
 * 两端同时在线：D1（`xp_c5_d1`）在自己的界面上操作，D2（`xp_c5_d2`）在自己的界面上看；`-e ttXpFrom` 是另一端。两端靠协调脚本的
 * 信号板对齐步骤（`-e ttXpCoord`，scripts/run-tests.sh 用 adb reverse 转到 Mac，飞行模式下也通）。共同约定（tianting-web 的
 * xp-c5.spec.ts、tianting-ios 的 CrossPlatformC5E2ETests 照抄这一段）：
 *   - 运行号 R；方向 dir = {D1}2{D2}（如 h52android），每个方向一个用户 U = xp{R}{dir}（昵称就是 userId）；对方 pa、pb、pc =
 *     xp{R}pa、xp{R}pb、xp{R}pc。协调脚本先放好（按这个顺序）：pa 在群 G = xp{R}g{dir}「C5群-{dir}」（pa、U）发「XP5-g-{dir}」；
 *     pc 给 U 发「ZXQ-XP5-c-{dir}」（会话 C）；pb 发「ZXQ-XP5-b-{dir}」（B）；pa 发「XP5-a1-{dir}」「XP5-a2-{dir}」（A，未读 2）。
 *     初始顺序 A、B、C、G。
 *   - D2：① 登录，列表是 A、B、C、G，A 未读 2；打开 C（看到原文）、打开 B 写草稿「XP5草稿-{dir}」，回到列表（B 显示 [草稿]）；
 *     贴 d2-ready。② 等到 pin：2 秒内 A 在最前、置顶（AC-CVS-04-1）；贴 d2-pin。③ 等到 mute：2 秒内 A 免打扰（图标、小红点、
 *     没有数字）；贴 d2-mute。④ 等到 del：2 秒内 B 从列表消失（AC-CVS-04-5）；贴 d2-del。⑤ 协调脚本在 d1-del、d2-del 都到了以后
 *     让 pb 再发「XP5-b-new-{dir}」：B 重新出现，未读 1，摘要是新消息、没有 [草稿]；等到 d1-back 再打开（打开会把已读同步给 D1），只有这 1 条、输入框是空的（AC-CVS-03-2、
 *     第 55 条）；贴 d2-back。⑥ 断网（H5 断 WebSocket、Android 飞行模式、iOS 断中转），贴 d2-offline。⑦ 等到 offline-done：
 *     断网时 C 还在；恢复网络后列表的顺序、置顶、免打扰和 D1 贴的一样（G、A、B，G 和 A 置顶，A 免打扰），C 不在列表，打开 C
 *     没有消息（AC-CVS-04-2）；贴 d2-done。
 *   - D1：① 登录，等 d2-ready。② 长按（H5 右键）A，菜单出来后贴 pin 再点「置顶」；等 d2-pin。③ 同样贴 mute 再点「免打扰」；
 *     等 d2-mute。④ 长按 B 点「删除」，确认框出来后贴 del 再点确认；B 消失后贴 d1-del。⑤ B 重新出现（未读 1）后贴 d1-back；等 d2-offline。
 *     ⑥ 置顶 G、删除 C，列表是 G、A、B 后贴 offline-done（带上自己列表的 order、pinned、muted，用 a/b/c/g 表示）；等 d2-done。
 *   - "2 秒"从 D2 拿到信号算起，D1 是先贴信号再点，所以 D1 的点击和界面也算在 2 秒里。
 *   - 反证：协调脚本 --fake-sender {dir}，没有 D1，d2-ready 后协调脚本自己贴 pin，D2 必须在 ② 失败。
 * Android 另外断言：D2 的草稿在 2 秒内清除（`drafts`），⑦ 之后本地库搜不到 C 的特征串。没有 `ttXp` 时跳过。结果是模拟器、本地。
 */
@OptIn(ExperimentalTestApi::class)
class CrossPlatformC5E2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val r = C5Real(rule)
    private val ui get() = r.ui
    private val model get() = testApp.model
    private val args = InstrumentationRegistry.getArguments()
    private val run: String? = args.getString("ttXp")
    private val with: String = args.getString("ttXpFrom").orEmpty()
    private val coord: String = args.getString("ttXpCoord").orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(320, TimeUnit.SECONDS).callTimeout(320, TimeUnit.SECONDS).build()
    private var dir = ""
    private val u get() = "xp$run$dir"
    private val conv get() = mapOf(
        "a" to c2cConvId(u, "xp${run}pa"),
        "b" to c2cConvId(u, "xp${run}pb"),
        "c" to c2cConvId(u, "xp${run}pc"),
        "g" to "group:xp${run}g$dir",
    )
    private fun key(id: String) = conv.entries.firstOrNull { it.value == id }?.key ?: "?$id"

    @Before fun setup() {
        assumeTrue("跨端用例要协调脚本传 ttXp、ttXpCoord、ttXpFrom", !run.isNullOrEmpty() && coord.isNotEmpty() && with in listOf("h5", "ios"))
        r.server.guard()
        r.server.resetLimits()
    }

    @After fun cleanup() {
        if (run.isNullOrEmpty() || coord.isEmpty()) return
        r.close()
    }

    // ---- 信号板 ----

    private fun post(name: String, body: Map<String, String> = emptyMap()) {
        val json = JsonObject(body.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()
        val req = Request.Builder().url("$coord/s/$dir/$name").post(json.toRequestBody("application/json".toMediaType())).build()
        offThread { http.newCall(req).execute().use { check(it.isSuccessful) { "signal $name ${it.code}" } } }
    }

    /** 等信号，返回拿到它的本机时间和带的内容。 */
    private fun wait(name: String, ms: Long = 180_000): Pair<Long, Map<String, String>> {
        val req = Request.Builder().url("$coord/s/$dir/$name?t=$ms").build()
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

    /** 从 t0 算起 2 秒内成立。 */
    private fun within2s(t0: Long, what: String, cond: () -> Boolean) {
        while (true) {
            rule.waitForIdle()
            if (cond()) {
                android.util.Log.i("TT-E2E-XP5", "$dir $what ms=${System.currentTimeMillis() - t0}")
                return
            }
            check(System.currentTimeMillis() - t0 < 2000) { "2 秒内没有：$what" }
            Thread.sleep(30)
        }
    }

    // ---- 列表 ----

    private fun keys() = r.order().joinToString(",") { key(it) }
    private fun pinned(k: String) = r.hasItem(conv[k]!!) && r.pinnedMark(conv[k]!!) == "true"
    private fun muted(k: String) = r.hasItem(conv[k]!!) && r.hasInItem(conv[k]!!, "notify-off-icon")
    private fun state(): Map<String, String> {
        val ks = r.order().map { key(it) }
        return mapOf(
            "order" to ks.joinToString(","),
            "pinned" to ks.filter { pinned(it) }.joinToString(","),
            "muted" to ks.filter { muted(it) }.joinToString(","),
        )
    }

    /** 长按会话项，菜单出来后（可选）贴信号，再点一项。 */
    private fun menu(k: String, item: String, signal: String? = null) {
        rule.waitUntil(15_000) { r.hasItem(conv[k]!!) }
        rule.onNode(r.itemOf(conv[k]!!), useUnmergedTree = true).performTouchInput { longClick() }
        ui.waitTag("conv-menu", 5000)
        if (item == "delete") {
            ui.tap("conv-menu-delete")
            ui.waitTag("conv-delete-dialog", 5000)
            signal?.let { post(it) }
            ui.tap("conv-delete-dialog-ok")
        } else {
            signal?.let { post(it) }
            ui.tap("conv-menu-$item")
        }
        rule.waitUntil(3000) { !ui.hasTag("conv-menu") }
    }

    // ---- D1 ----

    @Test fun xp_c5_d1() {
        dir = "android2$with"
        r.appLogin(u)
        r.until("初始列表", 30_000) { rule.waitForIdle(); keys() == "a,b,c,g" }
        wait("d2-ready", 300_000)
        // ② 置顶 A
        menu("a", "pin", "pin")
        rule.waitUntil(3000) { pinned("a") }
        wait("d2-pin")
        // ③ 免打扰 A
        menu("a", "notify", "mute")
        rule.waitUntil(3000) { muted("a") }
        wait("d2-mute")
        // ④ 删除 B
        menu("b", "delete", "del")
        rule.waitUntil(3000) { !r.hasItem(conv["b"]!!) }
        Thread.sleep(1000) // ack 之后仍然没有
        assertFalse(r.hasItem(conv["b"]!!))
        post("d1-del")
        // ⑤ pb 再发一条：B 重新出现，未读 1
        rule.waitUntil(30_000) { r.hasItem(conv["b"]!!) && r.hasInItem(conv["b"]!!, "conv-badge") }
        assertEquals("1", r.textInItem(conv["b"]!!, "conv-badge"))
        assertEquals("XP5-b-new-$dir", r.textInItem(conv["b"]!!, "conv-summary"))
        post("d1-back")
        wait("d2-offline", 300_000)
        // ⑥ D2 断网时：置顶 G、删除 C
        menu("g", "pin")
        rule.waitUntil(3000) { pinned("g") }
        menu("c", "delete")
        rule.waitUntil(5000) { keys() == "g,a,b" }
        Thread.sleep(1000)
        val mine = state()
        assertEquals(mapOf("order" to "g,a,b", "pinned" to "g,a", "muted" to "a"), mine)
        post("offline-done", mine)
        wait("d2-done", 300_000)
    }

    // ---- D2 ----

    @Test fun xp_c5_d2() {
        dir = "${with}2android"
        r.appLogin(u)
        // ① 初始状态；打开 C、在 B 写草稿
        r.until("初始列表", 30_000) { rule.waitForIdle(); keys() == "a,b,c,g" }
        assertEquals("2", r.textInItem(conv["a"]!!, "conv-badge"))
        r.open(chatPath(conv["c"]!!))
        ui.waitText("ZXQ-XP5-c-$dir", 15_000)
        r.until("本地库里有 C 的原文（对照）") { r.dbFiles().any { r.contains(it, "ZXQ-XP5-c-$dir") } }
        r.open(chatPath(conv["b"]!!))
        ui.waitText("ZXQ-XP5-b-$dir", 15_000)
        ui.type("composer-input", "XP5草稿-$dir")
        r.open("/chats")
        rule.waitUntil(5000) { r.hasInItem(conv["b"]!!, "draft-tag") }
        r.until("初始列表（草稿之后）", 5000) { rule.waitForIdle(); keys() == "a,b,c,g" }
        assertEquals(mapOf("order" to "a,b,c,g", "pinned" to "", "muted" to ""), state())
        post("d2-ready")
        // ② 置顶
        var t0 = wait("pin").first
        within2s(t0, "A 在最前且置顶") { keys().startsWith("a,") && pinned("a") }
        post("d2-pin")
        // ③ 免打扰：图标、小红点、没有数字
        t0 = wait("mute").first
        within2s(t0, "A 免打扰") { muted("a") && r.hasInItem(conv["a"]!!, "conv-dot") && !r.hasInItem(conv["a"]!!, "conv-badge") }
        assertEquals("[2 条] XP5-a2-$dir", r.textInItem(conv["a"]!!, "unread-count-tag"))
        post("d2-mute")
        // ④ 删除：2 秒内消失，草稿也清（第 55 条）
        t0 = wait("del").first
        within2s(t0, "B 从列表消失、草稿清除") { !r.hasItem(conv["b"]!!) && model.im!!.drafts[conv["b"]!!] == null }
        post("d2-del")
        // ⑤ 重新出现：只有新消息，没有草稿
        rule.waitUntil(30_000) { r.hasItem(conv["b"]!!) && r.hasInItem(conv["b"]!!, "conv-badge") }
        assertEquals("1", r.textInItem(conv["b"]!!, "conv-badge"))
        assertEquals("XP5-b-new-$dir", r.textInItem(conv["b"]!!, "conv-summary"))
        assertFalse(r.hasInItem(conv["b"]!!, "draft-tag"))
        wait("d1-back") // 打开会把已读同步给 D1：D1 先看完"未读 1"
        r.open(chatPath(conv["b"]!!))
        ui.waitText("XP5-b-new-$dir", 15_000)
        offThread { runCatching { model.im!!.client.loadHistory(conv["b"]!!) } }
        Thread.sleep(800)
        assertFalse(ui.hasText("ZXQ-XP5-b-$dir"))
        assertEquals(listOf("XP5-b-new-$dir"), model.im!!.client.messages(conv["b"]!!).mapNotNull { it.body.text })
        assertEquals("", ui.textOf("composer-input"))
        r.open("/chats")
        post("d2-back")
        // ⑥ 断网
        r.setAirplane(true)
        rule.waitUntil(30_000) { model.im?.state != ConnState.CONNECTED }
        post("d2-offline")
        // ⑦ D1 改完后：断网时还是旧的，恢复后和 D1 一样
        val (_, done) = wait("offline-done", 300_000)
        assertTrue(r.hasItem(conv["c"]!!), "断网时 C 还在")
        r.setAirplane(false)
        rule.waitUntil(30_000) { model.im?.state == ConnState.CONNECTED }
        r.until("和 D1 一样", 20_000) { rule.waitForIdle(); state() == done }
        assertEquals(mapOf("order" to "g,a,b", "pinned" to "g,a", "muted" to "a"), done)
        r.open(chatPath(conv["c"]!!))
        ui.waitTag("composer-input")
        Thread.sleep(1000)
        assertFalse(ui.hasText("ZXQ-XP5-c-$dir"))
        assertTrue(model.im!!.client.messages(conv["c"]!!).isEmpty(), "C 的本地消息清除")
        r.until("本地库里搜不到 C 的原文", 15_000) { r.dbFiles().none { r.contains(it, "ZXQ-XP5-c-$dir") } }
        post("d2-done")
    }
}
