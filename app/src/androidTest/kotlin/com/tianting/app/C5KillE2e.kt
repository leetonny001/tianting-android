package com.tianting.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.tianting.app.core.chatPath
import com.tianting.sdk.ConnState
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TC-CVS-03-10 的界面部分、AC-CVS-03-10（scripts/run-tests.sh kill C5KillE2e.deletedWhileKilled）：
 * phase1：App（u1 的 D2）本地库有和 u2 的单聊（正文含 ZXQ-CVS-1），缓存目录有内容含 ZXQ-CVS-2 的图片，写标记后 halt（不走任何清理）；
 * phase2 是新进程：D1（同一进程里的 IMClient）删除这个单聊，u2 再发一条（不引用旧消息），然后 D2 冷启动连接：
 * 只显示新消息，本地库文件（主库、-wal 等）和缓存目录的原始字节搜不到两个特征串。结果是模拟器、本地。
 */
class C5KillE2e {
    @get:Rule val rule = createEmptyComposeRule()
    private val r = C5Real(rule)
    private val ui get() = r.ui
    private val marker get() = File(testContext.filesDir, "kill-marker-c5.json")
    private val model get() = testApp.model

    @Before fun guard() = r.server.guard()

    @Test fun deletedWhileKilled_phase1() {
        marker.delete()
        r.server.resetLimits()
        TestMedia.dir().deleteRecursively()
        val a = "ck5" + System.currentTimeMillis().toString(36)
        val b = a + "p"
        r.users(a, b)
        val conv = com.tianting.sdk.c2cConvId(a, b)
        val text = "ZXQ-CVS-1-" + System.currentTimeMillis().toString(36)
        val img = "ZXQ-CVS-2-" + System.currentTimeMillis().toString(36)
        val bob = r.client(b)
        r.say(bob, conv, text)
        r.image(bob, conv, img)
        offThread {
            bob.disconnect()
            bob.close()
        }
        r.appLogin(a)
        rule.waitUntil(15_000) { model.im?.filesEnabled == true }
        r.open(chatPath(conv))
        ui.waitText(text)
        ui.waitTag("image-loaded", 15_000)
        r.until("本地库里有原文（对照）") { r.dbFiles().any { r.contains(it, text) } }
        r.until("缓存目录里有图片（对照）") { r.cacheFiles().any { r.contains(it, img) } }
        // 第二阶段的进程一启动 App 就会连接：先开飞行模式，让 D2 在 D1 删除、对方再发的时候确实离线（AC-CVS-03-10 的"D2 离线"）。
        // 飞行模式下管理接口（10.0.2.2）不通，D1 和对方的 token 先取好；它们经 adb reverse 连（ttImLoopback），不受飞行模式影响。
        marker.writeText(JSONObject().put("a", a).put("b", b).put("conv", conv).put("text", text).put("img", img).put("ta", r.token(a)).put("tb", r.token(b)).toString())
        r.setAirplane(true)
        Runtime.getRuntime().halt(0)
    }

    @Test fun deletedWhileKilled_phase2() {
        assertTrue(marker.isFile, "phase1 没有走到暂停点")
        val m = JSONObject(marker.readText()).also { marker.delete() }
        val conv = m.getString("conv")
        val text = m.getString("text")
        val img = m.getString("img")
        try {
            // D2（App）离线（第一阶段开了飞行模式）：D1 删除，u2 再发一条
            assertTrue(model.im?.state != ConnState.CONNECTED, "D2 应当离线")
            val d1 = r.client(m.getString("a"), loopback = true, tokenOf = m.getString("ta"))
            r.until("D1 有会话") { d1.conversations.value.any { it.convId == conv } }
            offThread { d1.deleteConv(conv) }
            val bob = r.client(m.getString("b"), loopback = true, tokenOf = m.getString("tb"))
            r.say(bob, conv, "删除之后的新消息")
            assertTrue(model.im?.state != ConnState.CONNECTED, "D2 一直离线")
            // D2 上线：这个进程是冷启动的（App 模型由 Application 用真实依赖建好，演示会话、本地库和缓存都在磁盘上）
            r.setAirplane(false)
            ui.openApp()
            ui.waitTag("tab-chats", 15_000)
            rule.waitUntil(40_000) { model.im?.state == ConnState.CONNECTED }
            rule.waitUntil(15_000) { r.hasItem(conv) && r.hasInItem(conv, "conv-summary") && r.textInItem(conv, "conv-summary") == "删除之后的新消息" }
            r.open(chatPath(conv))
            ui.waitText("删除之后的新消息")
            Thread.sleep(800)
            assertFalse(ui.hasText(text) || ui.hasTag("image-thumb"), "只显示新消息")
            assertEquals(listOf("删除之后的新消息"), model.im!!.client.messages(conv).mapNotNull { it.body.text })
            r.until("本地库文件里搜不到原文", 15_000) { r.dbFiles().none { r.contains(it, text) } }
            r.until("缓存目录搜不到图片", 15_000) { r.cacheFiles().none { r.contains(it, img) } }
        } finally {
            r.close()
        }
    }
}
