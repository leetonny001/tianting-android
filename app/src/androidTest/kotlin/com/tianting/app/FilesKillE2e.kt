package com.tianting.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.PickKind
import com.tianting.app.core.chatPath
import com.tianting.sdk.ConnState
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgType
import com.tianting.sdk.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TC-FILE-07-01、AC-FILE-07-1：上传中真实杀进程（scripts/run-tests.sh kill FilesKillE2e.uploadKilled）。
 * phase1 在 PUT 进行中（假 S3 拖住响应）写标记后 halt；phase2 是新进程：消息显示失败、不自动上传，点重发从头上传，对方只收到一条，clientMsgId 不变。
 */
class FilesKillE2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val s3 = FakeS3()
    private val ui = E2eUi(rule)
    private val marker get() = File(testContext.filesDir, "kill-marker-files.json")
    private val model get() = testApp.model

    @Before fun guard() {
        server.guard()
        s3.guard()
    }

    private fun token(u: String) = server.admin("POST", "/admin/users/token", obj("userId" to u))["token"]!!.jsonPrimitive.content

    private fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(30)
        }
    }

    @Test fun uploadKilled_phase1() {
        marker.delete()
        s3.reset()
        server.resetLimits()
        val a = "fk" + System.currentTimeMillis().toString(36)
        val b = a + "p"
        server.admin("POST", "/admin/users/import", obj("users" to listOf(obj("userId" to b))))
        val src = TestMedia.write("kill.jpg", TestMedia.jpeg(320, 240))
        replaceWithRealDeps(realDepsWithPicks({ _ -> listOf(TestMedia.uri(src)) }))
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", a)
        val end = System.currentTimeMillis() + 15_000
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("login-submit")) runCatching { ui.tap("login-submit") }
            Thread.sleep(700)
        }
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED && model.im?.filesEnabled == true }
        val conv = com.tianting.sdk.c2cConvId(a, b)
        inst.runOnMainSync { model.nav.resetTo(chatPath(conv)) }
        s3.ctl("put_delay_ms" to 120_000) // PUT 的响应一直不回：停在"上传中"
        ui.tap("attach-button")
        ui.tap("attach-image")
        until("uploading", 30_000) { s3.stats().second >= 1 && model.im!!.client.messages(conv).any { it.status == MessageStatus.UPLOADING } }
        val id = model.im!!.client.messages(conv).single { it.type == MsgType.IMAGE }.clientMsgId
        marker.writeText(JSONObject().put("a", a).put("b", b).put("conv", conv).put("id", id).toString())
        Runtime.getRuntime().halt(0)
    }

    @Test fun uploadKilled_phase2() {
        assertTrue(marker.isFile, "phase1 没有走到暂停点")
        val m = JSONObject(marker.readText()).also { marker.delete() }
        val conv = m.getString("conv")
        val id = m.getString("id")
        s3.reset() // 计数清零，PUT 不再拖
        // 新进程：App 模型由 Application 用真实依赖建好，演示会话和本地库都在磁盘上。
        ui.openApp()
        ui.waitTag("tab-chats", 15_000)
        inst.runOnMainSync { model.nav.resetTo(chatPath(conv)) }
        ui.waitTag("attachment-error", 15_000)
        assertEquals("failed", model.im!!.client.messages(conv).single { it.clientMsgId == id }.status.name.lowercase())
        assertEquals("图片上传失败，请检查网络后重发", ui.textOf("attachment-error"))
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        Thread.sleep(3000)
        assertEquals(0, s3.stats().second, "没有自动上传（F-FILE-07 规则 3）")
        ui.tap("failed-icon")
        ui.tap("retry-dialog-ok")
        until("sent", 30_000) { model.im!!.client.messages(conv).single { it.clientMsgId == id }.status == MessageStatus.SENT }
        assertEquals(2, s3.stats().second, "从头上传原图和缩略图")
        val peer = IMClient(IMClientOptions(url = "${server.base.replace("http://", "ws://")}/ws", platform = Platform.WEB))
        try {
            peer.connect(token(m.getString("b")))
            until("peer connected") { peer.state.value == ConnState.CONNECTED }
            until("peer got it") {
                offThread { runCatching { peer.loadHistory(conv) } }
                peer.messages(conv).any { it.type == MsgType.IMAGE }
            }
            val got = peer.messages(conv).filter { it.type == MsgType.IMAGE }
            assertEquals(1, got.size, "对方只收到一条")
            assertEquals(id, got.single().clientMsgId, "clientMsgId 不变")
        } finally {
            offThread {
                runCatching { peer.disconnect() }
                runCatching { peer.close() }
            }
            inst.runOnMainSync { model.logout() }
            offThread { model.awaitTeardown() }
        }
    }
}
