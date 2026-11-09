package com.tianting.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.tianting.app.core.PickKind
import com.tianting.app.ui.DataStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 图片和文件的页面状态（FE-635、FE-636）：假服务端 + 假账号接口 + 假对象存储（AutoObjects），只验页面对 SDK 结果的处理。
 * 真实 im-server + 假 S3 的流程在 FilesE2eTest。结果是模拟器、本地。
 */
class FilesUiTest : UiTest() {
    private val objs = AutoObjects()
    private var nextFile = 0
    private val picks = ArrayList<android.net.Uri>()

    @Before fun files() {
        http.files = true
        objects = objs
        pickForTest = { _ -> picks.toList() }
        TestMedia.dir().deleteRecursively()
        fileHandlers()
    }

    private fun fileHandlers() {
        // 申请上传：每次一个新的 fileId，同时登记它的 complete（真实服务端按记录回宽高等）。
        http.handlers["/v1/files/uploads"] = { r ->
            val id = "F${++nextFile}"
            val kind = r.body["kind"]!!.jsonPrimitive.content
            val mime = r.body["mime"]!!.jsonPrimitive.content
            http.handlers["/v1/files/$id/complete"] = { FakeAccountHttp.Reply.Json(obj("fileId" to id, "size" to 10, "mime" to mime, "width" to 40, "height" to 30)) }
            val put = obj("url" to "https://s3.test/chat/tmp/$id-o?X-Amz-Signature=sig", "headers" to obj("Content-Type" to mime), "expiresAt" to 1)
            val thumb = obj("url" to "https://s3.test/chat/tmp/$id-t?X-Amz-Signature=sig", "headers" to obj("Content-Type" to "image/jpeg"), "expiresAt" to 1)
            FakeAccountHttp.Reply.Json(if (kind == "image") obj("fileId" to id, "put" to put, "thumbPut" to thumb) else obj("fileId" to id, "put" to put))
        }
        http.handlers["/v1/files/urls"] = { r ->
            val items = (r.body["items"] as kotlinx.serialization.json.JsonArray).map { it as JsonObject }
            FakeAccountHttp.Reply.Json(
                obj(
                    "items" to items.map { i ->
                        val id = i["fileId"]!!.jsonPrimitive.content
                        val v = i["variant"]!!.jsonPrimitive.content
                        obj("fileId" to id, "variant" to v, "url" to "https://s3.test/chat/$id/$v?X-Amz-Signature=sig", "expiresAt" to System.currentTimeMillis() + 120_000)
                    },
                ),
            )
        }
    }

    private fun imageMsg(seq: Long, fileId: String, from: String = "bob", expired: Boolean = false, mime: String = "image/jpeg", w: Int = 400, h: Int = 300) = obj(
        "msgId" to "$BOB_CONV#$seq", "clientMsgId" to "$from-$seq", "convId" to BOB_CONV, "seq" to seq, "from" to from, "type" to "image",
        "body" to obj("image" to obj("fileId" to fileId, "width" to w, "height" to h, "size" to 1234, "mime" to mime, "expired" to expired)), "ts" to T0 + seq * 1000,
    )

    private fun fileMsg(seq: Long, fileId: String, name: String, from: String = "bob", expired: Boolean = false, mime: String = "text/plain") = obj(
        "msgId" to "$BOB_CONV#$seq", "clientMsgId" to "$from-$seq", "convId" to BOB_CONV, "seq" to seq, "from" to from, "type" to "file",
        "body" to obj("file" to obj("fileId" to fileId, "name" to if (expired) "" else name, "size" to 2048, "mime" to mime, "expired" to expired)), "ts" to T0 + seq * 1000,
    )

    private fun withBob(vararg ms: JsonObject, last: JsonObject? = null) {
        server.convs = listOf(convJson(BOB_CONV, "bob", maxSeq = ms.size.toLong(), lastMsg = last ?: lastMsg("bob", "x")))
        server.messages[BOB_CONV] = ms.toList()
    }

    private fun openChat() {
        start("tianting://chat/c2c%3Aalice%3Abob")
        rule.waitTag("composer-input")
    }

    private fun pickImage(name: String = "a.jpg", bytes: ByteArray = TestMedia.jpeg(64, 48)) {
        picks.clear()
        picks.add(TestMedia.uri(TestMedia.write(name, bytes)))
        click("attach-button")
        click("attach-image")
    }

    /** 唯一一条附件消息的状态（列表是反向的，不按位置找）。 */
    private fun mineStatus(): String? = rule.onAllNodes(hasTestTag("message") and hasAnyDescendant(hasTestTag("image-thumb") or hasTestTag("file-bubble")), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(DataStatus)

    private fun uploads() = http.to("/v1/files/uploads").size

    @Test fun plus_button_follows_features_files() { // 第 13.3 节："+"在 features.files 关闭时隐藏
        http.files = false
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        rule.waitText("你好")
        assertFalse(rule.hasTag("attach-button"), "features.files 关闭时没有\"+\"")
    }

    @Test fun plus_menu_has_image_and_file() { // 第 13.3 节
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        click("attach-button")
        rule.waitTag("attach-panel")
        assertTrue(rule.hasTag("attach-image") && rule.hasTag("attach-file"))
        rule.onNodeWithTag("attach-button").assertContentDescriptionContains("发送图片或文件") // 只有图标的按钮有可读名称（NF-UI-7）
    }

    @Test fun tc_file_01_12_quota_4292_fails_without_retry() { // TC-FILE-01-12、AC-FILE-01-12（原生 UI 测试部分）
        http.err("/v1/files/uploads", 429, 4292)
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        pickImage()
        rule.waitTag("attachment-error", 10_000)
        assertEquals("今天的上传额度已用完", textOf("attachment-error"))
        assertEquals("failed", mineStatus())
        assertNeverWithin(3000, "不自动重试") { uploads() != 1 }
        assertTrue(server.frames("send").isEmpty(), "不发 send")
    }

    @Test fun tc_file_01_17_upload_pending_limit_and_cancel_sends_delete() { // TC-FILE-01-17、AC-FILE-01-17（原生 UI 测试部分）
        http.err("/v1/files/uploads", 429, 4290, reason = "uploadPendingLimit")
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        picks.clear()
        picks.add(TestMedia.uri(TestMedia.write("a.zip", ByteArray(3000) { 7 })))
        click("attach-button")
        click("attach-file")
        rule.waitTag("attachment-error", 10_000)
        assertEquals("上传任务太多，请稍后再试", textOf("attachment-error"))
        assertNeverWithin(3000, "不自动重试") { uploads() != 1 }
        // 用户取消一个上传中的文件：发 DELETE（释放名额，F-FILE-01 规则 6a），消息消失，不发 send。
        fileHandlers() // 恢复正常的申请应答
        objs.holdPuts = true
        click("attach-button")
        click("attach-file")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("attachment-status", useUnmergedTree = true).fetchSemanticsNodes().any { allText(it).startsWith("上传中") } }
        click("attachment-cancel")
        waitFor("DELETE") { http.requests.any { it.method == "DELETE" && it.path == "/v1/files/uploads/F1" } }
        rule.waitGoneTag("attachment-cancel")
        assertTrue(server.frames("send").isEmpty(), "取消后不发 send")
    }

    @Test fun tc_file_02_05_cancel_while_uploading_removes_message() { // TC-FILE-02-05、AC-FILE-02-5（假对象存储；真实服务端见 FilesE2eTest）
        objs.holdPuts = true
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        pickImage()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("attachment-status", useUnmergedTree = true).fetchSemanticsNodes().any { allText(it).startsWith("上传中") } }
        val before = rule.onAllNodesWithTag("message", useUnmergedTree = true).fetchSemanticsNodes().size
        click("attachment-cancel")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("message", useUnmergedTree = true).fetchSemanticsNodes().size == before - 1 }
        assertNeverWithin(1500, "不发 send") { server.frames("send").isNotEmpty() }
    }

    @Test fun tc_file_06_05_expired_shows_text_without_url_requests() { // TC-FILE-06-05、AC-FILE-06-6
        withBob(imageMsg(1, "F9", expired = true), fileMsg(2, "F10", "", expired = true), msgJson(BOB_CONV, 3, "bob", "之后"))
        openChat()
        rule.waitText("之后")
        rule.waitTag("image-expired")
        assertEquals("图片已过期", textOf("image-expired"))
        assertEquals("文件已过期", textOf("file-expired"))
        assertFalse(rule.hasTag("file-download"), "过期的文件没有下载")
        assertNeverWithin(1500, "不申请地址") { http.to("/v1/files/urls").isNotEmpty() }
        assertTrue(objs.gets.isEmpty())
    }

    @Test fun tc_file_02_12_decode_bomb_shows_placeholder() { // TC-FILE-02-12、AC-FILE-02-12
        objs.bodies["F9/thumb"] = TestMedia.pngBombHeader()
        objs.bodies["F8/thumb"] = TestMedia.jpeg(40, 30)
        withBob(imageMsg(1, "F9"), imageMsg(2, "F8"), msgJson(BOB_CONV, 3, "bob", "其他消息"))
        openChat()
        rule.waitTag("image-undecodable", 10_000)
        rule.waitTag("image-loaded", 10_000) // 另一张正常显示
        assertTrue(rule.hasText("其他消息"))
        assertEquals("图片无法显示", textOf("image-undecodable"))
    }

    @Test fun tc_file_02_07_file_name_is_plain_text() { // TC-FILE-02-07、AC-FILE-02-7
        val evil = "<img src=x onerror=alert(1)>.txt"
        withBob(fileMsg(1, "F9", evil))
        openChat()
        rule.waitTag("file-name")
        assertEquals(evil, textOf("file-name"))
        n("file-bubble").assertContentDescriptionContains("文件 $evil，", substring = true)
    }

    @Test fun tc_file_03_04_long_name_middle_ellipsis_without_overflow() { // TC-FILE-03-04、AC-FILE-03-4
        val name = "长".repeat(196) + ".txt" // 200 个字符
        withBob(fileMsg(1, "F9", name))
        openChat()
        rule.waitTag("file-name")
        assertTrue(rule.hasText("长长长长长长长长.txt"), "结尾和扩展名可见")
        val bubble = n("file-bubble").getUnclippedBoundsInRoot()
        val root = rule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(bubble.right <= root.right + 0.5.dp && bubble.left >= root.left - 0.5.dp, "没有横向溢出：$bubble / $root")
    }

    @Test fun tc_file_02_08_and_03_08_summaries() { // TC-FILE-02-08、TC-FILE-03-08、AC-FILE-02-8、AC-FILE-03-8
        server.convs = listOf(
            convJson(BOB_CONV, "bob", maxSeq = 1, lastMsg = lastMsg("bob", "", type = "image")),
            convJson("c2c:alice:carol", "carol", maxSeq = 1, lastMsg = lastMsg("carol", "", type = "file")),
        )
        start()
        rule.waitText("[图片]")
        rule.waitText("[文件]")
        assertFalse(rule.hasText("暂不支持", substring = true))
    }

    @Test fun tc_file_02_14_gif_first_frame_with_badge() { // TC-FILE-02-14、AC-FILE-02-14（假对象存储；真实服务端见 FilesE2eTest）
        objs.bodies["F9/thumb"] = TestMedia.jpeg(40, 40)
        withBob(imageMsg(1, "F9", mime = "image/gif", w = 40, h = 40))
        openChat()
        rule.waitTag("image-loaded", 10_000)
        assertTrue(rule.hasTag("gif-badge"))
        assertEquals("GIF", textOf("gif-badge"))
    }

    @Test fun tc_file_02_13_undecodable_image_fails_with_prompt() { // TC-FILE-02-13 前半、AC-FILE-02-13
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        pickImage("fake.jpg", "这不是图片".repeat(100).toByteArray())
        rule.waitTag("attachment-error", 10_000)
        assertEquals("无法处理这张图片，请用文件方式发送", textOf("attachment-error"))
        assertEquals(0, uploads(), "处理失败不申请上传")
    }

    @Test fun tc_file_03_06_file_mode_jpeg_asks_first() { // TC-FILE-03-06、AC-FILE-03-6
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        picks.clear()
        picks.add(TestMedia.uri(TestMedia.write("photo.jpg", TestMedia.jpeg(64, 48))))
        click("attach-button")
        click("attach-file")
        rule.waitTag("keep-info-dialog")
        assertTrue(rule.hasText("文件会保留原始信息（可能包含位置）"))
        click("keep-info-dialog-cancel")
        assertNeverWithin(1500, "取消后不发") { uploads() > 0 }
        click("attach-button")
        click("attach-file")
        click("keep-info-dialog-ok")
        waitFor("upload as file") { http.to("/v1/files/uploads").any { it.body["kind"]?.jsonPrimitive?.content == "file" } }
        waitFor("send file") { server.frames("send").any { it.data["type"]?.jsonPrimitive?.content == "file" } }
    }

    @Test fun pick_more_than_nine_sends_none() { // F-FILE-07 规则 6、问题 H16
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        picks.clear()
        repeat(10) { picks.add(TestMedia.uri(TestMedia.write("f$it.txt", "x$it".toByteArray()))) }
        click("attach-button")
        click("attach-file")
        rule.waitText("一次最多选择 9 个")
        assertNeverWithin(1500, "整批不发") { uploads() > 0 }
    }

    @Test fun own_image_shows_processing_then_sent() { // 第 13.4 节：处理中、上传中（进度）→ 已发送；结果来自 SDK
        withBob(msgJson(BOB_CONV, 1, "bob", "你好"))
        openChat()
        pickImage()
        waitFor("sent", 10_000) { mineStatus() == "sent" }
        val up = http.to("/v1/files/uploads").single()
        assertEquals("image", up.body["kind"]!!.jsonPrimitive.content)
        assertEquals(2, objs.puts.size, "原图和缩略图")
        rule.onNode(hasTestTag("message") and hasAnyDescendant(hasTestTag("image-thumb")), useUnmergedTree = true).assertExists()
        assertFalse(rule.hasText("暂不支持", substring = true))
        assertTrue(rule.hasTag("image-thumb") && !rule.hasText("[暂不支持的消息]"))
    }

    // ---- AC-CHAT-90-8、90-9（FE-640）：文件页面的可读名称、浅色和深色 ----

    /** button-name 的等价检查（同 AccountUiTest，问题 A10：没有 espresso-accessibility，不新增依赖）：能点的节点都要有名字。 */
    private fun assertClickablesHaveNames(where: String) {
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode, out: MutableList<androidx.compose.ui.semantics.SemanticsNode>) {
            out.add(n)
            n.children.forEach { walk(it, out) }
        }
        val all = ArrayList<androidx.compose.ui.semantics.SemanticsNode>()
        // 全屏查看、弹窗是另一个窗口（另一个根）：每个根都查
        rule.onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes().forEach { walk(it, all) }
        var clickable = 0
        for (node in all) {
            if (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) continue
            if (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText) != null) continue
            clickable++
            val named = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.isNotEmpty() == true ||
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            assertTrue(named, "$where: 能点的节点没有名字：${node.config}")
        }
        assertTrue(clickable > 0, "$where: 没有找到能点的节点")
    }

    /** 截图放到 App 的外部文件目录 shots/（同 ScreenshotTour），由 adb pull 取出人工看。 */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val dir = java.io.File(testContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val bmp = inst.uiAutomation.takeScreenshot()
        java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun filesPagesNamed(prefix: String, pref: com.tianting.app.ui.theme.ThemePref) {
        objs.bodies["F8/thumb"] = TestMedia.jpeg(400, 300, android.graphics.Color.rgb(40, 160, 60))
        objs.bodies["F8/original"] = TestMedia.jpeg(400, 300, android.graphics.Color.rgb(40, 160, 60))
        objs.bodies["F7/original"] = "%PDF-1.7\n".toByteArray()
        withBob(imageMsg(1, "F8"), fileMsg(2, "F7", "报告.pdf", mime = "application/pdf"), msgJson(BOB_CONV, 3, "bob", "之后"))
        openChat()
        inst.runOnMainSync { model.setTheme(pref) }
        rule.waitText("之后")
        rule.waitTag("image-loaded", 10_000)
        // 替代文本：图片读"图片"，文件读文件名和大小（第 13.4 节、FE-640 内容 4）
        rule.onAllNodes(hasContentDescription("图片"), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "图片有替代文本") }
        n("file-bubble").assertContentDescriptionContains("文件 报告.pdf，2 KB")
        assertClickablesHaveNames("$prefix 聊天页（图片、文件气泡）")
        shot("$prefix-files-01-chat")
        click("attach-button")
        rule.waitTag("attach-panel")
        assertClickablesHaveNames("$prefix \"+\"面板")
        shot("$prefix-files-02-plus")
        click("attach-button")
        click("image-loaded")
        rule.waitTag("viewer-image", 10_000)
        assertClickablesHaveNames("$prefix 全屏查看")
        shot("$prefix-files-03-viewer")
        click("viewer-close")
        click("file-download")
        rule.waitTag("source-dialog")
        assertClickablesHaveNames("$prefix 来源提示")
        shot("$prefix-files-04-source")
        pressBackOnce()
        // 失败状态：4292 的失败图标和原因
        http.err("/v1/files/uploads", 429, 4292)
        pickImage()
        rule.waitTag("attachment-error", 10_000)
        assertClickablesHaveNames("$prefix 发送失败")
        shot("$prefix-files-05-failed")
    }

    private fun pressBackOnce() {
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        rule.waitForIdle()
    }

    @Test fun tc_chat_90_08_files_pages_named_light() = filesPagesNamed("light", com.tianting.app.ui.theme.ThemePref.LIGHT) // TC-CHAT-90-08、AC-CHAT-90-8、90-9

    @Test fun tc_chat_90_08_files_pages_named_dark() = filesPagesNamed("dark", com.tianting.app.ui.theme.ThemePref.DARK) // TC-CHAT-90-08、AC-CHAT-90-8、90-9
}
