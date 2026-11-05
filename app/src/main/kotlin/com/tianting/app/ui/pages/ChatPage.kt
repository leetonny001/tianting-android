package com.tianting.app.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tianting.app.core.EMOJI
import com.tianting.app.core.MESSAGE_DELETED
import com.tianting.app.core.RouteState
import com.tianting.app.core.applyPath
import com.tianting.app.core.defaultMessage
import com.tianting.app.core.errCode
import com.tianting.app.core.formatTime
import com.tianting.app.core.infoPath
import com.tianting.app.core.messageFor
import com.tianting.app.core.mutedText
import com.tianting.app.core.needsDivider
import com.tianting.app.core.noticePath
import com.tianting.app.core.userPath
import com.tianting.app.ui.Avatar
import com.tianting.app.ui.ConnBanner
import com.tianting.app.ui.Confirm
import com.tianting.app.ui.IconBtn
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.LocalNav
import com.tianting.app.ui.OfficialTag
import com.tianting.app.ui.Page
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.Spinner
import com.tianting.app.ui.T
import com.tianting.app.ui.TopBar
import com.tianting.app.ui.dataClientMsgId
import com.tianting.app.ui.dataKind
import com.tianting.app.ui.dataStatus
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtDimen
import com.tianting.app.ui.theme.TtIcons
import com.tianting.app.ui.theme.isDark
import com.tianting.sdk.ApplySource
import com.tianting.sdk.ConvIdError
import com.tianting.sdk.ConvType
import com.tianting.sdk.ConversationSnapshot
import com.tianting.sdk.Message
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgType
import com.tianting.sdk.ParsedConvId
import com.tianting.sdk.SendResult
import com.tianting.sdk.TextCheck
import com.tianting.sdk.TextRejectReason
import com.tianting.sdk.checkText
import com.tianting.sdk.parseConvIdStrict
import com.tianting.sdk.tipText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 聊天页（PRD-ui F-UI-06～F-UI-10）。路由里的会话 ID 先严格解析（G-UI-03），不合法回到会话列表。 */
@Composable
fun ChatPage(convId: String) {
    val im = LocalIm.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val parsed = remember(convId) {
        try {
            parseConvIdStrict(convId, im.self)
        } catch (_: ConvIdError) {
            null
        }
    }
    if (parsed == null) {
        LaunchedEffect(Unit) {
            app.toast.show("会话不存在")
            nav.setRoot("/chats")
        }
        return
    }
    key(convId) { Chat(convId, parsed) }
}

internal sealed interface ChatRow {
    val key: String

    data class Divider(val ts: Long, override val key: String) : ChatRow
    data class Msg(val m: Message) : ChatRow {
        override val key: String get() = m.clientMsgId
    }

    data class Permission(val code: Int, val forId: String) : ChatRow {
        override val key: String get() = "perm-$forId"
    }
}

/** 时间分隔和权限提示条穿插进消息（F-UI-07 规则 6、F-UI-31）。 */
internal fun buildRows(ms: List<Message>, tips: Map<String, Int>): List<ChatRow> {
    val tipFor = permissionTips(ms, tips)
    val out = ArrayList<ChatRow>(ms.size + 8)
    var prev: Long? = null
    for (m in ms) {
        if (needsDivider(prev, m.ts)) out.add(ChatRow.Divider(m.ts, "d-${m.clientMsgId}"))
        out.add(ChatRow.Msg(m))
        tipFor[m.clientMsgId]?.let { out.add(ChatRow.Permission(it, m.clientMsgId)) }
        prev = m.ts
    }
    return out
}

/** 提示条跟在失败消息下面；相邻的提示内容相同时只保留最后一条（F-UI-31 规则 3）。重发成功后消息不再失败，提示条随之消失。 */
internal fun permissionTips(ms: List<Message>, tips: Map<String, Int>): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    var lastId = ""
    var lastCode = 0
    for (m in ms) {
        val code = if (m.status == MessageStatus.FAILED) tips[m.clientMsgId] else null
        if (code == null) continue
        if (lastCode == code) out.remove(lastId)
        out[m.clientMsgId] = code
        lastId = m.clientMsgId
        lastCode = code
    }
    return out
}

private data class Blocked(val tip: String, val bar: String)

/** 不能发送的原因（F-UI-33 规则 2～4）：快照按 reason 映射（不认识的值按"被移出"）。 */
private fun blockedBy(snapshot: ConversationSnapshot?, probe: Int?): Blocked? = when {
    snapshot != null -> when (snapshot.reason) {
        "dissolved" -> Blocked("群聊已解散", "群聊已解散")
        "left" -> Blocked("你已退出该群聊", "你已退出该群聊")
        else -> Blocked("你已不在该群", "你已不在该群，无法发送消息")
    }
    probe == 4033 -> Blocked("你已不在该群", "你已不在该群，无法发送消息")
    probe == 4043 -> Blocked("群聊不存在或已解散", "群聊不存在或已解散")
    else -> null
}

private enum class HistoryState { IDLE, LOADING, END, ERROR }

private val BOTTOM_SLACK = 100.dp // 距底部不超过 100 算"在底部"（F-MSG-06 规则 5）

@Composable
private fun Chat(convId: String, parsed: ParsedConvId) {
    val im = LocalIm.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val isGroup = parsed.convType == ConvType.GROUP
    val to = parsed.to
    val conv = im.convs.firstOrNull { it.convId == convId }
    val messages by remember(convId) { im.client.messagesFlow(convId) }.collectAsState()
    val connected = im.state == com.tianting.sdk.ConnState.CONNECTED

    // 快照（G-UI-21）：SDK 接口是 suspend，取到之前（Loaded = null）不下结论。convAdded 时 SDK 清快照，会话也回到列表。
    val loadedSnapshot by produceState<Loaded<ConversationSnapshot?>?>(null, convId, conv == null, im.removedTick) {
        value = Loaded(im.client.conversationSnapshot(convId))
    }
    val snapshot = loadedSnapshot?.value

    var probe by remember { mutableStateOf<Int?>(null) } // 4033 / 4043
    var probeDone by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(HistoryState.IDLE) }
    var newCount by remember { mutableIntStateOf(0) }
    var retryId by remember { mutableStateOf<String?>(null) }
    var menuId by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 只有聊天页实际可见时才是当前会话（F-UI-06 规则 8）；这个页面被盖住时已经离开组合。
    DisposableEffect(convId) {
        im.client.setActiveConversation(convId)
        onDispose { im.client.setActiveConversation(null) }
    }

    // 打开本地没有的会话：向服务端要最新一页（F-UI-33 规则 1、G-UI-26）。确认前只显示加载状态，不显示输入框和"不在该群"。
    val unknown = loadedSnapshot != null && conv == null && snapshot == null && messages.isEmpty()
    LaunchedEffect(convId, unknown, connected) {
        if (!unknown || !connected || probeDone) return@LaunchedEffect
        try {
            im.client.loadHistory(convId)
            probeDone = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (val code = errCode(e)) {
                4033, 4043 -> {
                    probe = code
                    probeDone = true
                }
                4032 -> {
                    app.toast.show("会话不存在")
                    nav.setRoot("/chats")
                }
                else -> probeDone = true
            }
        }
    }
    // 重新被拉进群 / 会话回到列表：确认结果作废（AC-UI-33-11）。
    LaunchedEffect(conv != null) { if (conv != null) probe = null }

    val blocked = blockedBy(snapshot, probe)
    val probing = isGroup && (loadedSnapshot == null || (unknown && !probeDone)) && blocked == null

    suspend fun loadMore() {
        if (history == HistoryState.LOADING || history == HistoryState.END) return
        history = HistoryState.LOADING
        history = try {
            if (im.client.loadHistory(convId)) HistoryState.IDLE else HistoryState.END
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HistoryState.ERROR
        }
    }

    // 打开会话时加载最新一页（F-MSG-05 规则 3）。
    LaunchedEffect(conv?.maxSeq != null) {
        if (conv != null && conv.maxSeq > 0 && im.client.messages(convId).isEmpty()) loadMore()
    }

    // 标题（F-UI-06 规则 2）：备注 > 昵称 > 会话名；群名省略、（N）和官方标识始终完整。
    val peerProfile = rememberProfile(if (isGroup) im.self else to)
    val remarkOrNick = if (isGroup) null else im.nameOf(to).takeIf { it != to }
    val title = remarkOrNick ?: conv?.name ?: snapshot?.name ?: if (isGroup && probe != null) "群聊" else to
    val count = if (isGroup) conv?.memberCount?.let { "（$it）" } else null

    val rows = remember(messages, im.sendTips.toMap()) { buildRows(messages, im.sendTips) }
    val reversed = remember(rows) { rows.asReversed() }

    // 跟随新消息：在底部或自己发的时滚到底，否则累计"↓ N 条新消息"（F-MSG-06 规则 5）。
    // 判断要在 LazyColumn 用新数据测量之前做，否则列表已经按 key 锚定到旧项上了。
    val slackPx = with(LocalDensity.current) { BOTTOM_SLACK.roundToPx() }
    val newestId = messages.lastOrNull()?.clientMsgId
    val tracker = remember { NewestTracker() }
    if (tracker.id != newestId) {
        val atBottom = Snapshot.withoutReadObservation { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= slackPx }
        val grew = messages.size > tracker.size
        val mine = messages.lastOrNull()?.from == im.self
        val first = tracker.size == 0
        tracker.pending = Follow(follow = atBottom || mine || first, added = if (grew && !first) messages.size - tracker.size else 0)
        tracker.id = newestId
    }
    tracker.size = messages.size
    LaunchedEffect(newestId) {
        val f = tracker.pending ?: return@LaunchedEffect
        tracker.pending = null
        if (f.follow) {
            list.scrollToItem(0)
            newCount = 0
        } else {
            newCount += f.added
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= slackPx }
            .distinctUntilChanged()
            .collect { if (it) newCount = 0 }
    }
    // 滚到最早的几条时翻页（F-MSG-05）。列表是反向的：最后一项是最早的消息。
    LaunchedEffect(list, convId) {
        snapshotFlow {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearTop ->
            if (nearTop && im.client.messages(convId).isNotEmpty() && history == HistoryState.IDLE) loadMore()
        }
    }

    val liveMessage = { id: String? -> id?.let { i -> messages.firstOrNull { it.clientMsgId == i && !it.deleted } } }
    val retry = liveMessage(retryId)
    val userState = if (isGroup) RouteState(source = ApplySource.GROUP, sourceRef = to) else RouteState(source = ApplySource.CONV)

    Page {
        TopBar(
            onBack = { nav.back() },
            titleContent = {
                T(title, Modifier.weight(1f, fill = false).tag("chat-title"), style = Tt.type.title, maxLines = 1)
                if (!isGroup && peerProfile?.official == true) {
                    Box(Modifier.padding(start = 4.dp)) { OfficialTag() }
                }
                if (count != null) T(count, Modifier.tag("chat-title-count"), style = Tt.type.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
            },
            right = { IconBtn(TtIcons.more, "聊天信息", "chat-more", { nav.go(infoPath(convId)) }) },
        )
        ConnBanner()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Tt.colors.chatBg)) {
            val bubbleMax = maxWidth * 0.7f
            var listTop by remember { mutableStateOf(0f) }
            LazyColumn(
                Modifier.tag("message-list").fillMaxWidth().onGloballyPositioned { listTop = it.boundsInWindow().top },
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            ) {
                if (blocked != null) {
                    item("blocked-tip") {
                        T(blocked.tip, Modifier.tag("chat-blocked-tip").fillMaxWidth(), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
                    }
                }
                if (probing) {
                    item("probing") {
                        T("加载中…", Modifier.tag("chat-probing").fillMaxWidth(), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
                    }
                }
                items(reversed, key = { it.key }, contentType = { it::class }) { r ->
                    when (r) {
                        is ChatRow.Divider -> T(formatTime(r.ts), Modifier.fillMaxWidth(), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
                        is ChatRow.Permission -> PermissionTip(r.code, to)
                        is ChatRow.Msg -> when {
                            r.m.deleted -> CenterTip(MESSAGE_DELETED, "msg-deleted")
                            r.m.type == MsgType.TIP -> TipRow(r.m, convId)
                            else -> Bubble(
                                m = r.m,
                                mine = r.m.from == im.self,
                                showName = isGroup && r.m.from != im.self,
                                maxWidth = bubbleMax,
                                listTop = listTop,
                                userState = userState,
                                menuOpen = menuId == r.m.clientMsgId,
                                onRetry = { retryId = r.m.clientMsgId },
                                onMenu = { menuId = r.m.clientMsgId },
                                onMenuClose = { menuId = null },
                                convId = convId,
                            )
                        }
                    }
                }
                if (messages.isNotEmpty()) {
                    item("history") {
                        val text = when (history) {
                            HistoryState.LOADING -> "加载中…"
                            HistoryState.END -> "没有更多了"
                            else -> ""
                        }
                        if (history == HistoryState.ERROR) {
                            T(
                                "加载失败，点击重试",
                                Modifier.tag("history-state").fillMaxWidth().clickable { scope.launch { history = HistoryState.IDLE; loadMore() } }.padding(8.dp),
                                style = Tt.type.caption,
                                color = Tt.colors.brand,
                                align = TextAlign.Center,
                            )
                        } else if (text.isNotEmpty()) {
                            T(text, Modifier.tag("history-state").fillMaxWidth(), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
                        }
                    }
                }
            }
            if (newCount > 0) {
                Surface(
                    onClick = {
                        scope.launch { list.scrollToItem(0) }
                        newCount = 0
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).tag("new-messages").heightIn(min = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Tt.colors.brandFill,
                    contentColor = Tt.colors.onBrand,
                ) {
                    T("↓ $newCount 条新消息", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = Tt.type.caption, color = Tt.colors.onBrand)
                }
            }
        }
        val mute by produceState<com.tianting.sdk.MuteInfo?>(null, convId, im.muteTick) { value = im.client.muteState(convId) }
        when {
            probing -> Unit
            blocked != null -> DisabledBar(blocked.bar)
            mute != null -> DisabledBar(mutedText(mute!!.mutedUntil))
            else -> Composer(convId) { newCount = 0 }
        }
    }
    if (retry != null) {
        Confirm(
            "重发该消息？",
            "重发",
            "retry-dialog",
            onCancel = { retryId = null },
            onOk = {
                val id = retry.clientMsgId
                retryId = null
                im.launchCmd { im.client.retry(convId, id) }
            },
        )
    }
}

private class Loaded<T>(val value: T)

private data class Follow(val follow: Boolean, val added: Int)

private class NewestTracker {
    var id: String? = null
    var size = 0
    var pending: Follow? = null
}

@Composable
private fun DisabledBar(text: String) {
    Column(Modifier.fillMaxWidth().background(Tt.colors.surface)) {
        HorizontalDivider(color = Tt.colors.divider)
        T(text, Modifier.tag("composer-disabled").fillMaxWidth().padding(16.dp), style = Tt.type.small, color = Tt.colors.textTertiary, align = TextAlign.Center)
    }
}

/** 居中小字（F-UI-40、F-UI-42）：没有头像和气泡，不可长按。 */
@Composable
private fun CenterTip(text: String, tag: String, modifier: Modifier = Modifier, kind: String? = null, onClick: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val base = modifier.tag(tag).semantics { if (kind != null) dataKind = kind }
            .clip(RoundedCornerShape(TtDimen.radiusSm.dp)).background(Tt.colors.surface)
        T(
            text,
            (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 8.dp, vertical = 2.dp),
            style = Tt.type.caption,
            color = Tt.colors.textTertiary,
            align = TextAlign.Center,
        )
    }
}

/** 群提示消息（F-UI-40）：文案用本地显示名渲染。 */
@Composable
private fun TipRow(m: Message, convId: String) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val tip = m.body.tip
    LaunchedEffect(m.clientMsgId) {
        if (tip != null) im.fetchProfiles(listOf(tip.actor) + tip.targets)
    }
    val text = if (tip != null) tipText(tip, im.self) { im.nameOf(it) } else "[群通知]"
    CenterTip(text, "tip", kind = tip?.kind, onClick = if (tip?.kind == "noticeUpdated") ({ nav.go(noticePath(convId)) }) else null)
}

/** 私聊权限提示条（F-UI-31 规则 1、2）：不是消息，不可长按。 */
@Composable
private fun PermissionTip(code: Int, to: String) {
    val im = LocalIm.current
    val nav = LocalNav.current
    Column(Modifier.fillMaxWidth().tag("permission-tip"), horizontalAlignment = Alignment.CenterHorizontally) {
        T(messageFor(code, "send", strangerMsgLimit = im.settings.strangerMsgLimit), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
        if (code == 4035) {
            T(
                "发送好友申请",
                Modifier.tag("permission-apply").heightIn(min = TtDimen.sizeTouchMin.dp).clickable { nav.go(applyPath(to), RouteState(source = ApplySource.CONV)) }.padding(horizontal = 8.dp, vertical = 12.dp),
                style = Tt.type.caption,
                color = Tt.colors.brand,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    mine: Boolean,
    showName: Boolean,
    maxWidth: Dp,
    listTop: Float,
    userState: RouteState,
    menuOpen: Boolean,
    onRetry: () -> Unit,
    onMenu: () -> Unit,
    onMenuClose: () -> Unit,
    convId: String,
) {
    val nav = LocalNav.current
    val senderName = rememberDisplayName(m.from)
    val sender = rememberProfile(m.from)
    val supported = m.type == MsgType.TEXT
    val failed = m.status == MessageStatus.FAILED
    val hasMenu = supported || failed
    val avatar: @Composable () -> Unit = {
        Box(
            Modifier.tag("msg-avatar").size(40.dp).clickable { nav.go(userPath(m.from), userState) }.semantics { contentDescription = "查看 $senderName 的资料" },
        ) { Avatar(m.from, senderName, 40.dp, src = sender?.avatar) }
    }
    val bubbleShape = if (mine) RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp) else RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)
    Row(
        Modifier.fillMaxWidth().tag("message").semantics {
            dataStatus = m.status.name.lowercase()
            dataClientMsgId = m.clientMsgId
        },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) avatar()
        Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showName) T(senderName, Modifier.tag("msg-name").padding(bottom = 2.dp), style = Tt.type.caption, color = Tt.colors.textSecondary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mine && m.status != MessageStatus.SENT) {
                    if (failed) {
                        IconButton(onRetry, Modifier.tag("failed-icon").size(44.dp)) {
                            Icon(TtIcons.failed, "发送失败，点击重发", Modifier.size(20.dp), tint = Tt.colors.danger)
                        }
                    } else {
                        Spinner(16.dp, Tt.colors.textTertiary, Modifier.tag("sending-icon").padding(end = 4.dp))
                    }
                }
                Box {
                    val border = if (!mine && !Tt.colors.isDark()) Modifier.border(BorderStroke(1.dp, Tt.colors.divider), bubbleShape) else Modifier
                    val bg = if (mine) Tt.colors.bubbleSelfBg else Tt.colors.bubbleOtherBg
                    val fg = when {
                        !supported -> if (mine) Tt.colors.bubbleSelfText else Tt.colors.textSecondary
                        mine -> Tt.colors.bubbleSelfText
                        else -> Tt.colors.bubbleOtherText
                    }
                    Box(
                        Modifier.widthIn(max = maxWidth).tag("bubble").clip(bubbleShape).background(bg).then(border)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = hasMenu,
                                onClick = {},
                                onLongClick = onMenu,
                            ).padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        T(if (supported) m.body.text.orEmpty() else "[暂不支持的消息]", color = fg)
                    }
                    if (menuOpen) BubbleMenu(m, convId, listTop, onMenuClose)
                }
            }
        }
        if (mine) avatar()
    }
}

private val MENU_GAP = 4.dp

/** 气泡菜单（F-UI-09 规则 2）：在气泡上方；到消息列表顶边的空间不够时在下方。点菜单外关闭。 */
@Composable
private fun BubbleMenu(m: Message, convId: String, listTop: Float, onClose: () -> Unit) {
    val im = LocalIm.current
    val app = LocalApp.current
    val gap = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val failed = m.status == MessageStatus.FAILED
    Popup(popupPositionProvider = BubbleMenuPosition(listTop.toInt(), gap), onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Surface(
            Modifier.tag("bubble-menu").widthIn(min = 120.dp),
            shape = RoundedCornerShape(TtDimen.radiusMd.dp),
            color = Tt.colors.surface,
            border = BorderStroke(1.dp, Tt.colors.divider),
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (m.type == MsgType.TEXT) {
                    MenuItem("复制", "menu-copy") {
                        app.copy(m.body.text.orEmpty())
                        onClose()
                    }
                }
                if (failed) {
                    MenuItem("重发", "menu-retry") {
                        im.launchCmd { im.client.retry(convId, m.clientMsgId) } // 菜单里的"重发"不再二次确认（规则 3）
                        onClose()
                    }
                    MenuItem("删除", "menu-delete") {
                        im.launchCmd { im.client.removeFailed(convId, m.clientMsgId) } // 只从本地移除（规则 3）
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.tag(tag).fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        T(text)
    }
}

internal class BubbleMenuPosition(private val listTop: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above < listTop) minOf(anchorBounds.bottom + gap, windowSize.height - popupContentSize.height) else above
        val x = (anchorBounds.left + anchorBounds.width / 2 - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        return IntOffset(x, y)
    }
}

/**
 * 输入区（F-UI-10）：自动增高到 5 行，超过 5000 码点显示"已超出 N 字"并禁用发送（C-28）；发送按钮 + IME 发送动作（3.4 节）。
 * 输入法组字期间的回车由输入法自己上屏，不到这里；万一到了也不发送（AC-UI-10-2）。
 * 进入时取走这个会话的草稿，离开时把没发出去的内容存回去（F-UI-24）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Composer(convId: String, onSent: () -> Unit) {
    val im = LocalIm.current
    val app = LocalApp.current
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(im.drafts[convId].orEmpty()).let { it.copy(selection = TextRange(it.text.length)) }) }
    var emoji by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val latest = rememberUpdated(value.text)
    DisposableEffect(convId) {
        im.drafts.remove(convId)
        onDispose {
            val text = latest.value
            if (text.isBlank()) im.drafts.remove(convId) else im.drafts[convId] = text
        }
    }
    val check = checkText(value.text)
    val over = (check as? TextCheck.Rejected)?.takeIf { it.reason == TextRejectReason.TOO_LONG }?.over ?: 0
    val canSend = check is TextCheck.Ok && !sending

    fun send() {
        if (!canSend || value.composition != null) return
        sending = true
        val text = value.text
        im.launchCmd({ e ->
            sending = false
            app.toast.show(defaultMessage(errCode(e)))
        }) {
            val r = im.client.send(convId, text)
            sending = false
            if (r is SendResult.Ok) { // 返回 Rejected 时不清空（规则 7）
                value = TextFieldValue("")
                onSent()
            }
        }
    }

    fun insert(e: String) {
        val s = value.selection.min
        val t = value.selection.max
        val text = value.text.substring(0, s) + e + value.text.substring(t)
        value = TextFieldValue(text, TextRange(s + e.length))
    }

    Column(Modifier.fillMaxWidth().background(Tt.colors.surface)) {
        HorizontalDivider(color = Tt.colors.divider)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton({
                if (!emoji) keyboard?.hide()
                emoji = !emoji
            }, Modifier.tag("emoji-button").size(44.dp)) {
                Icon(TtIcons.smile, "表情", tint = Tt.colors.textSecondary)
            }
            BasicTextField(
                value,
                { value = it },
                Modifier.weight(1f).tag("composer-input").heightIn(min = 44.dp).onFocusChanged { if (it.isFocused) emoji = false },
                textStyle = Tt.type.body.copy(color = Tt.colors.textPrimary),
                cursorBrush = SolidColor(Tt.colors.brand),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, Tt.colors.borderInput, RoundedCornerShape(TtDimen.radiusMd.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { inner() }
                },
            )
            PrimaryButton("发送", Modifier.tag("send-button"), enabled = canSend) { send() }
        }
        if (over > 0) T("已超出 $over 字", Modifier.tag("over-limit").padding(start = 16.dp, bottom = 8.dp), style = Tt.type.small, color = Tt.colors.danger)
        if (emoji) {
            FlowRow(
                Modifier.tag("emoji-panel").fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState()).padding(8.dp),
            ) {
                for (e in EMOJI) {
                    Box(Modifier.size(44.dp).tag("emoji-item").clickable { insert(e) }, contentAlignment = Alignment.Center) {
                        Text(e, style = Tt.type.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> rememberUpdated(v: T): androidx.compose.runtime.State<T> {
    val s = remember { mutableStateOf(v) }
    s.value = v
    return s
}

