package com.tianting.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.chatPath
import com.tianting.sdk.ConnState
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.Platform
import com.tianting.sdk.SendResult
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TC-RCL-03-04、AC-RCL-03-4：App 被杀死时对方撤回（scripts/run-tests.sh kill C4KillE2e.recallWhileKilled）。
 * phase1：本地库里有含特征串的消息，写标记后 halt（不走任何清理）；phase2 是新进程：先让对方撤回，再冷启动连接，
 * 消息显示撤回文案，本地库文件（主库、-journal、-wal）的原始字节搜不到特征串。结果是模拟器、本地。
 */
class C4KillE2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val server = RealServer()
    private val ui = E2eUi(rule)
    private val marker get() = File(testContext.filesDir, "kill-marker-c4.json")
    private val model get() = testApp.model

    @Before fun guard() = server.guard()

    private fun token(u: String) = server.admin("POST", "/admin/users/token", obj("userId" to u))["token"]!!.jsonPrimitive.content

    private fun until(what: String, t: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + t
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "超时：$what" }
            Thread.sleep(30)
        }
    }

    private fun peer(u: String): IMClient {
        val c = IMClient(IMClientOptions(url = "${server.base.replace("http://", "ws://")}/ws", platform = Platform.WEB))
        c.connect(token(u))
        until("peer connected") { c.state.value == ConnState.CONNECTED }
        return c
    }

    private fun dbFiles(): List<File> = testContext.getDatabasePath("tianting-sdk.db").parentFile!!.listFiles().orEmpty().filter { it.isFile }

    private fun contains(f: File, needle: String): Boolean {
        val h = f.readBytes()
        val n = needle.toByteArray()
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    @Test fun recallWhileKilled_phase1() {
        marker.delete()
        server.resetLimits()
        val a = "ck" + System.currentTimeMillis().toString(36)
        val b = a + "p"
        server.admin("POST", "/admin/users/import", obj("users" to listOf(obj("userId" to a), obj("userId" to b))))
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val secret = "ZXQ-RCL-4-" + System.currentTimeMillis().toString(36)
        val bob = peer(b)
        val r = offThread { bob.send(conv, secret) } as SendResult.Ok
        until("bob sent") { bob.messages(conv).first { it.clientMsgId == r.message.clientMsgId }.status == MessageStatus.SENT }
        val msgId = bob.messages(conv).first { it.clientMsgId == r.message.clientMsgId }.msgId!!
        offThread {
            bob.disconnect()
            bob.close()
        }
        replaceWithRealDeps()
        ui.openApp()
        ui.waitTag("login-username")
        ui.type("login-username", a)
        val end = System.currentTimeMillis() + 15_000
        while (!ui.hasTag("tab-chats") && System.currentTimeMillis() < end) {
            if (ui.hasTag("login-submit")) runCatching { ui.tap("login-submit") }
            Thread.sleep(700)
        }
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        inst.runOnMainSync { model.nav.resetTo(chatPath(conv)) }
        ui.waitText(secret)
        until("本地库里有原文（对照）") { dbFiles().any { contains(it, secret) } }
        marker.writeText(JSONObject().put("b", b).put("conv", conv).put("msgId", msgId).put("secret", secret).toString())
        Runtime.getRuntime().halt(0)
    }

    @Test fun recallWhileKilled_phase2() {
        assertTrue(marker.isFile, "phase1 没有走到暂停点")
        val m = JSONObject(marker.readText()).also { marker.delete() }
        val conv = m.getString("conv")
        val secret = m.getString("secret")
        // App 没在运行时对方撤回
        val bob = peer(m.getString("b"))
        try {
            offThread { bob.recall(conv, m.getString("msgId")) }
        } finally {
            offThread {
                bob.disconnect()
                bob.close()
            }
        }
        // 冷启动：App 模型由 Application 用真实依赖建好，演示会话和本地库都在磁盘上
        ui.openApp()
        ui.waitTag("tab-chats", 15_000)
        rule.waitUntil(15_000) { model.im?.state == ConnState.CONNECTED }
        inst.runOnMainSync { model.nav.resetTo(chatPath(conv)) }
        try {
            ui.waitText("对方撤回了一条消息", 15_000)
            assertFalse(ui.hasText(secret))
            until("本地库文件里搜不到原文", 15_000) { dbFiles().none { contains(it, secret) } }
        } finally {
            inst.runOnMainSync { model.logout() }
            offThread { model.awaitTeardown() }
        }
    }
}
