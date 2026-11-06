package com.tianting.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tianting.sdk.BlockedUser
import com.tianting.sdk.ConnState
import com.tianting.sdk.Conversation
import com.tianting.sdk.FatalReason
import com.tianting.sdk.Friend
import com.tianting.sdk.FriendRequests
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMEvent
import com.tianting.sdk.PinyinCollator
import com.tianting.sdk.SendError
import com.tianting.sdk.SocialSettings
import com.tianting.sdk.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * 一次登录的界面状态：把 SDK 的 StateFlow / 事件搬进 Compose 的 snapshot state，页面直接读。
 * 事件收集在 connect 之前开始（SDK 的 events 没有回放）。退出登录换一个新实例，草稿、提示条随之清空（F-UI-24、F-UI-31）。
 */
class ImSession(
    val client: IMClient,
    val self: String,
    /** 账号会话（Session mode=account）：有"账号安全"、能改昵称。演示会话隐藏（F-ACC-07 规则 5）。 */
    val isAccount: Boolean,
    val collator: PinyinCollator?,
    private val isOnlineNow: () -> Boolean,
    private val toast: (String) -> Unit,
    noticePrefs: NoticePrefs,
    private val onRecoveryCodeUnacked: () -> Unit = {},
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 系统通知（F-UI-44）。 */
    val notices = NoticeStore(noticePrefs, self) { ms, fn ->
        val job = scope.launch {
            delay(ms)
            fn()
        }
        Closeable { job.cancel() }
    }

    var noticeState by mutableStateOf(NoticeSnapshot())
        private set

    var convs by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var truncated by mutableStateOf(false)
        private set
    var state by mutableStateOf(ConnState.DISCONNECTED)
        private set
    var banner by mutableStateOf(false)
        private set
    var totalUnread by mutableStateOf(0L)
        private set
    var pendingCount by mutableIntStateOf(0)
        private set
    var friends by mutableStateOf<List<Friend>>(emptyList())
        private set
    var requests by mutableStateOf(FriendRequests())
        private set
    var blocks by mutableStateOf<List<BlockedUser>>(emptyList())
        private set
    var settings by mutableStateOf(SocialSettings())
        private set
    var socialLoaded by mutableStateOf(false)
        private set
    var official by mutableStateOf(false)
        private set
    var fatal by mutableStateOf<FatalReason?>(null)
        private set

    /** 禁言状态的版本号：MuteChanged 时加一，取值用 client.muteState（按服务端时间判断）。 */
    var muteTick by mutableIntStateOf(0)
        private set

    /** ConvRemoved 的版本号：快照要重新取。 */
    var removedTick by mutableIntStateOf(0)
        private set

    /** 草稿（F-UI-24）：只在内存，按 convId。输入区离开时存进来、回来时取走。 */
    val drafts = mutableStateMapOf<String, String>()

    /** 资料缓存的界面副本（F-SOC-10）：getUsers 拿到的都放这里。不可见、不存在都没有这一项。 */
    val profiles = mutableStateMapOf<String, UserProfile>()

    /** 私聊权限提示条的来源（F-UI-31）：clientMsgId → 错误码。只在内存。 */
    val sendTips = mutableStateMapOf<String, Int>()

    private val _events = MutableSharedFlow<IMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<IMEvent> = _events

    /** 第一轮会话列表是否到了。SDK 没有单独的流：列表非空，或者已经轮到关系同步（SDK 先 convs 再 socialSync）。 */
    val convsLoaded: Boolean get() = convs.isNotEmpty() || socialLoaded

    val isOnline: Boolean get() = isOnlineNow()

    private var network: Closeable? = null

    private fun <T> bind(flow: StateFlow<T>, set: (T) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { set(it) } }
    }

    fun start(bindNetwork: (IMClient) -> Closeable?, connect: (IMClient) -> Unit) {
        val c = client
        bind(c.conversations) { convs = it }
        bind(c.truncated) { truncated = it }
        bind(c.state) { state = it }
        bind(c.banner) { banner = it }
        bind(c.totalUnread) { totalUnread = it }
        bind(c.pendingCount) { pendingCount = it }
        bind(c.friends) { friends = it }
        bind(c.friendRequests) { requests = it }
        bind(c.blocks) { blocks = it }
        bind(c.socialSettings) { settings = it }
        bind(c.socialLoaded) { socialLoaded = it }
        bind(c.isOfficial) { official = it }
        bind(c.fatal) { fatal = it }
        bind(notices.state) { noticeState = it }
        // 先订阅再连接：auth 成功时就要补拉（PRD-live C12）。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { c.pushes.collect { notices.onPush(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { c.pushSynced.collect { notices.onSynced(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            c.events.collect { e ->
                when (e) {
                    is IMEvent.SendFailed -> onSendFailed(e)
                    is IMEvent.MuteChanged -> muteTick++
                    is IMEvent.ConvRemoved -> removedTick++
                    is IMEvent.SecurityNotice -> toast(SECURITY_NOTICE_NEW_DEVICE) // 这一批 SDK 只发 newDeviceLogin（AC-ACC-02-13）
                    is IMEvent.RecoveryCodeUnacked -> onRecoveryCodeUnacked()
                    is IMEvent.ProfileChanged -> fetchProfiles(listOf(e.userId)) // SDK 已经更新缓存，这里取到的就是新昵称（AC-ACC-06-5）
                    else -> Unit
                }
                _events.emit(e)
            }
        }
        network = bindNetwork(c)
        connect(c)
    }

    /** F-UI-08 规则 3：每条消息每次失败只提示一次。4035 / 4036 / 4137 插提示条，4031 有禁用条；超时、被踢都不提示。 */
    private fun onSendFailed(e: IMEvent.SendFailed) {
        val code = (e.error as? SendError.Code)?.code ?: return
        when (code) {
            4035, 4036, 4137 -> sendTips[e.clientMsgId] = code
            4031 -> Unit
            else -> toast(messageFor(code, "send"))
        }
    }

    /** 显示名：好友备注 → 昵称 → userId。 */
    fun nameOf(userId: String): String {
        val f = friends.firstOrNull { it.userId == userId }
        if (f != null && f.remark.isNotEmpty()) return f.remark
        return profiles[userId]?.nickname?.takeIf { it.isNotEmpty() } ?: f?.profile?.nickname?.takeIf { it.isNotEmpty() } ?: userId
    }

    /** 补资料。SDK 合并同一轮的请求、按 TTL 缓存，重复调用没有代价。 */
    fun fetchProfiles(ids: List<String>) {
        val want = ids.filter { it.isNotEmpty() }.distinct()
        if (want.isEmpty()) return
        scope.launch {
            try {
                for (p in client.getUsers(want)) profiles[p.userId] = p
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.debug("getUsers failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 在这次登录的 scope 里执行一个命令：离开页面不取消。失败时给错误码。 */
    fun launchCmd(onError: (Throwable) -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun stop() {
        notices.stop()
        network?.close()
        network = null
        scope.cancel()
    }
}
