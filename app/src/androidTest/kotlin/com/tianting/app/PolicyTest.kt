package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import com.tianting.app.core.*
import com.tianting.app.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.uiautomator.UiDevice
import com.tianting.app.ui.theme.DarkTokens
import com.tianting.app.ui.theme.LightTokens
import com.tianting.app.core.KeystoreSessionStore
import com.tianting.sdk.AccountSession
import com.tianting.sdk.PendingLogout
import com.tianting.sdk.SessionMode
import kotlinx.coroutines.runBlocking
import java.io.File

/** PRD-ui 3.6 节的原生写法：返回键不关弹窗、凭证和偏好的存放、https 头像。 */
class PolicyTest : UiTest() {
    @Test fun back_and_outside_do_not_close_kicked_dialog() { // AC-UI-18-1
        server.convs = listOf(convJson(BOB_CONV, "bob"))
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitUntil(5000) { model.im?.state == com.tianting.sdk.ConnState.CONNECTED }
        server.pushEvent("kicked", "reason" to "banned")
        server.drop(4012)
        rule.waitTag("kicked-dialog")
        val path = model.nav.current.path
        val device = UiDevice.getInstance(inst)
        device.pressBack()
        rule.waitForIdle()
        n("kicked-dialog").assertExists()
        assertEquals(path, model.nav.current.path)
        device.click(device.displayWidth / 2, device.displayHeight - 300) // 点外部
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        n("kicked-dialog").assertExists()
        assertEquals(path, model.nav.current.path)
        txt("账号已被下线").assertExists()
    }

    @Test fun system_back_pops_pages() { // 3.4 节：系统返回键
        start()
        rule.waitTag("tab-chats")
        go("/me/settings")
        rule.waitTag("logout")
        UiDevice.getInstance(inst).pressBack()
        rule.waitTag("tab-chats")
    }

    @Test fun token_store_is_encrypted_and_cleared() = runBlocking { // AC-UI-16-3、AC-UI-90-2，F-ACC-03 规则 8
        val store = KeystoreSessionStore(testContext)
        store.clear()
        val secret = "ZXQ-TOKEN-" + System.nanoTime()
        val refresh = "ZXQ-REFRESH-" + System.nanoTime()
        val saved = AccountSession(SessionMode.ACCOUNT, "alice", secret, 123, refresh, 456, 789)
        store.save(saved)
        val file = File(testContext.noBackupFilesDir, KeystoreSessionStore.FILE)
        assertTrue(file.isFile)
        assertFalse(secret in file.readText(), "token 明文落盘")
        assertFalse(refresh in file.readText(), "refresh 明文落盘")
        assertEquals(saved, KeystoreSessionStore(testContext).load())
        store.clear()
        assertFalse(file.exists())
        assertEquals(null, store.load())
    }

    @Test fun pending_logout_store_is_encrypted() = runBlocking { // TC-ACC-04-06：待退出只有 sid 和 refresh，也要加密
        val store = KeystorePendingLogoutStore(testContext)
        val refresh = "ZXQ-PENDING-" + System.nanoTime()
        val items = listOf(PendingLogout("sid-a", refresh), PendingLogout("sid-b", refresh + "2"))
        store.save(items)
        val file = File(testContext.noBackupFilesDir, KeystorePendingLogoutStore.FILE)
        assertTrue(file.isFile)
        val raw = file.readText()
        assertFalse(refresh in raw, "refresh 明文落盘")
        assertFalse("sid-a" in raw, "sid 明文落盘")
        assertEquals(items, KeystorePendingLogoutStore(testContext).load())
        store.save(emptyList())
        assertFalse(file.exists())
        assertEquals(emptyList(), store.load())
    }

    @Test fun old_session_file_reads_as_demo() = runBlocking { // TC-ACC-03-18：老版本的文件是 {userId, token}，同样的密钥
        val store = KeystoreSessionStore(testContext)
        store.clear()
        com.tianting.app.core.EncryptedFile(testContext, KeystoreSessionStore.FILE).write("""{"userId":"alice","token":"old-token"}""")
        val s = store.load()
        assertEquals("alice", s?.userId)
        assertEquals("old-token", s?.accessToken)
        assertEquals(null, s?.mode)
        store.clear()
    }

    @Test fun unreadable_session_file_means_logged_out() = runBlocking {
        val store = KeystoreSessionStore(testContext)
        File(testContext.noBackupFilesDir, KeystoreSessionStore.FILE).writeText("not base64 not cipher")
        assertEquals(null, store.load())
        assertFalse(File(testContext.noBackupFilesDir, KeystoreSessionStore.FILE).exists())
    }

    @Test fun shared_prefs_only_have_ui_keys() { // AC-UI-90-2
        start()
        rule.waitTag("tab-chats")
        go("/me/settings/appearance")
        click("appearance-dark")
        waitFor("saved") { testContext.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE).contains(UiPrefs.THEME) }
        val dir = File(testContext.applicationInfo.dataDir, "shared_prefs")
        // 系统 WebView（隐私说明页，F-ACC-10）自己写的偏好文件不是 App 写的，里面没有账号数据（问题单 A5）。
        val files = dir.listFiles().orEmpty().map { it.nameWithoutExtension }.filterNot { it.startsWith("WebView") || it.startsWith("Aw") }
        assertTrue(files.isNotEmpty())
        for (name in files) {
            val all = testContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
            for (k in all.keys) assertTrue(k.startsWith("ui."), "$name 里有非 ui. 键：$k")
        }
        assertFailsWithIllegal { UiPrefs(testContext).set("token", "x") }
    }

    private fun assertFailsWithIllegal(block: () -> Unit) {
        try {
            block()
            throw AssertionError("应该拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun cleartext_blocked_except_local_test_host() { // AC-UI-21-5：debug 只放开 10.0.2.2；main 的配置不放开
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
    }

    @Test fun http_avatar_never_requested() { // AC-UI-21-4、21-5
        assertEquals(null, avatarImageUrl("http://example.com/a.png"))
        assertEquals(null, avatarImageUrl(""))
        assertEquals(null, avatarImageUrl(null))
        assertEquals("https://x/a.png", avatarImageUrl("https://x/a.png"))
        server.friends = listOf(obj("userId" to "bob", "remark" to "", "source" to "other", "createdAt" to T0, "ver" to 1, "profile" to profileJson("bob", avatar = "http://example.com/a.png")))
        start()
        click("tab-contacts")
        rule.waitTag("friend-row")
        assertFalse(rule.hasTag("avatar-img"))
    }

    @Test fun https_avatar_failure_falls_back() { // AC-UI-21-3、21-4
        server.friends = listOf(obj("userId" to "bob", "remark" to "", "source" to "other", "createdAt" to T0, "ver" to 1, "profile" to profileJson("bob", avatar = "https://invalid.invalid/a.png")))
        start()
        click("tab-contacts")
        rule.waitTag("friend-row")
        rule.waitGoneTag("avatar-img", 8000)
        assertTrue(rule.onAllNodesWithText("B", useUnmergedTree = true).fetchSemanticsNodes().size >= 1)
    }
}

/** 窄屏与字体缩放：320dp 宽（AC-UI-02-5）、200% 字体（3.5 节规则 3）、跟随系统深色（F-UI-22 规则 3）。 */
class LayoutScaleTest {
    @get:org.junit.Rule val rule = createComposeRule()
    private val server = FakeServer()

    private fun setup(width: Int = 320, fontScale: Float = 1f, darkSystem: Boolean = false, block: FakeServer.() -> Unit = {}): AppModel {
        server.block()
        val sessions = MemorySessions(AccountSession(mode = SessionMode.DEMO, userId = "alice", accessToken = "t"))
        lateinit var model: AppModel
        inst.runOnMainSync {
            clearUiPrefs()
            model = AppModel(testContext, AppDeps({ v, _ -> com.tianting.sdk.IMClient(com.tianting.sdk.IMClientOptions("ws://fake/ws", transport = server, isVisible = v)) }, { _, _ -> error("no") }, sessions, { true }, { null }, fakeAccount(sessions)))
            testApp.model = model
        }
        rule.setContent {
            val base = LocalDensity.current
            val conf = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (darkSystem) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale), LocalConfiguration provides conf) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { AppRoot(model) }
            }
        }
        return model
    }

    private fun walk(n: SemanticsNode, out: MutableList<SemanticsNode> = ArrayList()): List<SemanticsNode> {
        out.add(n)
        n.children.forEach { walk(it, out) }
        return out
    }

    private fun assertNoHorizontalOverflow(widthDp: Int, page: String) {
        rule.waitForIdle()
        val limit = with(rule.density) { widthDp.dp.toPx() } + 1
        for (root in rule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().take(1)) {
            for (n in walk(root)) {
                assertTrue(n.boundsInRoot.right <= limit, "$page: 节点超出 $widthDp dp：${n.config}")
            }
        }
    }

    /** 只认真正看不见内容的情况：最后一行没显示到文本末尾、某一行比节点宽、被省略、高度不够。didOverflowWidth 在按钮里有误报。 */
    private fun clipped(r: TextLayoutResult): Boolean {
        val last = r.lineCount - 1
        return r.didOverflowHeight || r.isLineEllipsized(last) || r.getLineEnd(last, visibleEnd = true) < r.layoutInput.text.trimEnd().length ||
            (0..last).any { r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 1f }
    }

    private fun didOverflow(tag: String): Boolean {
        val nodes = walk(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
        return nodes.any { n ->
            val action = n.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action ?: return@any false
            val r = ArrayList<TextLayoutResult>()
            action(r)
            r.isNotEmpty() && clipped(r[0])
        }
    }

    private fun overflowDetail(tag: String): String = walk(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).mapNotNull { n ->
        val action = n.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action ?: return@mapNotNull null
        val r = ArrayList<TextLayoutResult>()
        action(r)
        r.firstOrNull()?.let { "${it.layoutInput.text} size=${it.size} w=${it.didOverflowWidth} h=${it.didOverflowHeight} node=${n.size}" }
    }.joinToString()

    private fun tagExists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun width_320_no_overflow() { // AC-UI-02-5
        val model = setup {
            convs = listOf(convJson(BOB_CONV, "长名字".repeat(20), maxSeq = 1, lastMsg = lastMsg("bob", "长".repeat(100)), unread = 120), convJson(G1, "长".repeat(50), maxSeq = 1, memberCount = 12, lastMsg = lastMsg("bob", "x")))
            messages[BOB_CONV] = listOf(msgJson(BOB_CONV, 1, "bob", "长".repeat(800)), msgJson(BOB_CONV, 2, "alice", "x".repeat(800)))
            friends = listOf(friendJson("bob", "长名字".repeat(20)))
            requests = listOf(requestJson("carol", message = "长".repeat(50)))
        }
        rule.waitUntil(5000) { tagExists("conv-list") }
        assertNoHorizontalOverflow(320, "/chats")
        for (p in listOf("/contacts", "/me")) {
            inst.runOnMainSync { model.nav.setRoot(p) }
            assertNoHorizontalOverflow(320, p)
        }
        for (p in listOf(chatPath(BOB_CONV), chatPath(G1), "/contacts/requests", userPath("bob"), "/contacts/add", "/me/settings")) {
            inst.runOnMainSync { model.nav.resetTo(p) }
            rule.waitForIdle()
            Thread.sleep(300)
            assertNoHorizontalOverflow(320, p)
        }
        inst.runOnMainSync { model.logout() }
    }

    @Test fun font_200_buttons_not_clipped() { // 3.5 节规则 3
        val model = setup(width = 411, fontScale = 2f) {
            convs = listOf(convJson(BOB_CONV, "bob", maxSeq = 2, lastMsg = lastMsg("bob", "x")))
            messages[BOB_CONV] = listOf(msgJson(BOB_CONV, 1, "bob", "消息内容不能被截断，".repeat(30)), msgJson(BOB_CONV, 2, "alice", "我的长消息也不能被截断，".repeat(20)))
            requests = listOf(requestJson("carol", message = "附言"))
        }
        rule.waitUntil(5000) { tagExists("conv-list") }
        inst.runOnMainSync { model.nav.resetTo(chatPath(BOB_CONV)) }
        rule.waitUntil(5000) { tagExists("send-button") }
        rule.onNodeWithTag("composer-input", useUnmergedTree = true).performTextReplacement("有内容")
        rule.waitForIdle()
        rule.onNodeWithTag("send-button", useUnmergedTree = true).assertIsDisplayed().assertIsEnabled()
        saveRoot("font200-chat")
        assertFalse(didOverflow("send-button"), "发送按钮被截断：" + overflowDetail("send-button"))
        rule.waitUntil(5000) { tagExists("bubble") }
        for (n in rule.onAllNodesWithTag("bubble", useUnmergedTree = true).fetchSemanticsNodes()) {
            assertTrue(walk(n).none { c ->
                val action = c.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action ?: return@none false
                val r = ArrayList<TextLayoutResult>()
                action(r)
                r.isNotEmpty() && clipped(r[0])
            }, "消息内容被截断")
        }
        inst.runOnMainSync { model.nav.resetTo("/contacts/requests") }
        rule.waitUntil(5000) { tagExists("request-accept") }
        rule.onNodeWithTag("request-accept", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(didOverflow("request-accept"), "同意按钮被截断")
        inst.runOnMainSync { model.nav.resetTo("/me/settings") }
        rule.waitUntil(5000) { tagExists("logout") }
        rule.onNodeWithTag("logout", useUnmergedTree = true).performClick()
        rule.waitUntil(5000) { tagExists("logout-dialog-ok") }
        rule.onNodeWithTag("logout-dialog-ok", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(didOverflow("logout-dialog-ok"), "确定类按钮被截断")
        rule.onNodeWithTag("logout-dialog-cancel", useUnmergedTree = true).performClick()
        inst.runOnMainSync { model.logout() }
    }

    private fun saveRoot(name: String) {
        val dir = java.io.File(testContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bgArgb() = rule.onRoot().captureToImage().asAndroidBitmap().let { it.getPixel(it.width - 3, it.height / 2) }

    @Test fun follows_system_dark_and_manual_override() { // F-UI-22 规则 3
        val model = setup(width = 411, darkSystem = true)
        rule.waitUntil(5000) { tagExists("tab-chats") }
        inst.runOnMainSync { model.nav.resetTo("/me/settings/privacy") } // 这页下半部分露出 color.bg
        rule.waitForIdle()
        assertEquals(DarkTokens.bg.toArgb(), bgArgb(), "系统深色 + 跟随系统")
        inst.runOnMainSync { model.setTheme(com.tianting.app.ui.theme.ThemePref.LIGHT) }
        rule.waitForIdle()
        assertEquals(LightTokens.bg.toArgb(), bgArgb(), "手动浅色")
        inst.runOnMainSync { model.setTheme(com.tianting.app.ui.theme.ThemePref.DARK) }
        rule.waitForIdle()
        assertEquals(DarkTokens.bg.toArgb(), bgArgb(), "手动深色")
        inst.runOnMainSync { model.logout() }
    }
}
