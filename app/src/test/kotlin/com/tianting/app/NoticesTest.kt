package com.tianting.app

import com.tianting.app.core.NoticePrefs
import com.tianting.app.core.NoticeStore
import com.tianting.app.core.bannerText
import com.tianting.app.core.noticeSeenKey
import com.tianting.sdk.Push
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.Closeable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** F-PUSH-12 的计数规则（界面无关的部分）。界面见 androidTest 的 NoticesUiTest。 */
class NoticesTest {
    private class MapPrefs : NoticePrefs {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private object BrokenPrefs : NoticePrefs {
        override fun get(key: String): String? = throw IllegalStateException("x")
        override fun set(key: String, value: String) = throw IllegalStateException("x")
        override fun remove(key: String) = throw IllegalStateException("x")
    }

    private var timers = ArrayList<() -> Unit>()
    private fun store(p: NoticePrefs) = NoticeStore(p, "u1", log = {}) { _, fn ->
        timers.add(fn)
        Closeable { timers.remove(fn) }
    }

    private fun push(id: String, seq: Long? = null, replayed: Boolean = false, ts: Long = 100, text: String = id) =
        Push(id, "notice", JsonObject(mapOf("text" to JsonPrimitive(text))), ts, "user", null, seq, replayed)

    @Test fun `AC-PUSH-12-7：偏好读写抛异常时不报错，回放不计，实时计入`() {
        val s = store(BrokenPrefs)
        s.onPush(push("a", 1, replayed = true))
        s.onSynced(1)
        assertEquals(0, s.state.value.unseen)
        s.onPush(push("b", 2))
        assertEquals(1, s.state.value.unseen)
        assertEquals(2, s.state.value.list.size)
        s.enterPage()
        assertEquals(0, s.state.value.unseen)
        s.clearSeen()
    }

    @Test fun `规则 3：没有 pushSeq 的回放不计；序号不大于 lastSeenSeq 的不计；同一 pushId 只算一次`() {
        val p = MapPrefs()
        p.map[noticeSeenKey("u1")] = "5"
        val s = store(p)
        s.onPush(push("a", null, replayed = true))
        s.onPush(push("b", 5, replayed = true))
        assertEquals(0, s.state.value.unseen)
        s.onPush(push("c", 6, replayed = true))
        s.onPush(push("c", 6, replayed = true))
        assertEquals(1, s.state.value.unseen)
        assertEquals(3, s.state.value.list.size)
    }

    @Test fun `规则 4：没有带序号的通知时打开页面不写；lastSeenSeq 不是纯数字按没有`() {
        val p = MapPrefs()
        val s = store(p)
        s.onPush(push("a"))
        s.enterPage()
        assertNull(p.map[noticeSeenKey("u1")])
        p.map[noticeSeenKey("u1")] = "x"
        s.onSynced(7)
        assertEquals("7", p.map[noticeSeenKey("u1")])
    }

    @Test fun `其他 type 和没有 text 的不进列表；横幅到时消失`() {
        val s = store(MapPrefs())
        s.onPush(Push("x", "promo", JsonObject(mapOf("text" to JsonPrimitive("t"))), 1, "user"))
        s.onPush(Push("y", "notice", JsonNull, 1, "user"))
        s.onPush(Push("z", "notice", JsonObject(mapOf("text" to JsonPrimitive(1))), 1, "user"))
        assertEquals(0, s.state.value.list.size)
        s.onPush(push("a"))
        assertNotNull(s.state.value.banner)
        assertEquals(1, timers.size)
        timers.toList().single().invoke()
        assertNull(s.state.value.banner)
    }

    @Test fun `bannerText 按码点截断`() {
        assertEquals("系统通知：短", bannerText("短"))
        assertEquals("系统通知：" + "😀".repeat(50), bannerText("😀".repeat(51)))
        assertEquals("系统通知：" + "a".repeat(50), bannerText("a".repeat(50)))
    }
}
