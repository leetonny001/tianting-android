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
import com.tianting.app.core.Draft
import com.tianting.app.core.EMOJI
import com.tianting.app.core.EditState
import com.tianting.app.core.MESSAGE_DELETED
import com.tianting.app.core.MESSAGE_RECALLED
import com.tianting.app.core.applyEdit
import com.tianting.app.core.insertMention
import com.tianting.app.core.recallErrorText
import com.tianting.app.core.toMentions
import com.tianting.app.ui.DataMark
import com.tianting.app.ui.dataMark
import com.tianting.app.ui.dataUser
import com.tianting.sdk.GroupMemberInfo
import com.tianting.sdk.GroupRole
import com.tianting.sdk.MessageAction
import com.tianting.sdk.QuoteRef
import com.tianting.sdk.ReportTarget
import com.tianting.sdk.QuoteState
import com.tianting.sdk.QuoteView
import com.tianting.sdk.quoteLabel
import com.tianting.sdk.quoteSummary
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.onLongClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.Dispatchers
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

/** 消息菜单项（PRD-ui F-UI-09 规则 3，PRD-chat 第 13.3 节："复制""引用""撤回""举报"按状态显示）。 */
internal enum class MenuAction(val text: String, val tag: String) {
    COPY("复制", "menu-copy"), QUOTE("引用", "menu-quote"), RECALL("撤回", "menu-recall"), REPORT("举报", "menu-report"),
    RETRY("重发", "menu-retry"), DELETE("删除", "menu-delete"),
}

/**
 * 菜单项（F-UI-09 规则 3～5）：features.msgActions 关闭时只有"复制"（文本）和失败消息的"重发""删除"；打开时按 SDK 的 messageActions
 * （复制、引用、撤回：撤回按 ts 和校正后的服务端时间，撤回、删除的消息和提示行为空）。为空时长按没有反应。
 * report：features.moderation 打开并且 SDK 的 canReport 为真（C6，frontend-chat 第 76、77、88 条，页面不自己判断），排在"撤回"后面，
 * 和 msgActions 无关。canRetry：SDK 的 canRetry，4510 被拦截的（第 81 条）、原文件已不可用的失败消息没有"重发"，只有"复制""删除"。
 */
internal fun menuItemsFor(m: Message, actionsOn: Boolean, report: Boolean = false, canRetry: Boolean = true, sdkActions: () -> List<MessageAction>): List<MenuAction> {
    if (m.deleted || m.type == MsgType.TIP) return emptyList()
    val out = ArrayList<MenuAction>()
    if (actionsOn) {
        for (a in sdkActions()) {
            out.add(
                when (a) {
                    MessageAction.COPY -> MenuAction.COPY
                    MessageAction.QUOTE -> MenuAction.QUOTE
                    MessageAction.RECALL -> MenuAction.RECALL
                },
            )
        }
    } else if (m.type == MsgType.TEXT) {
        out.add(MenuAction.COPY)
    }
    if (report) out.add(MenuAction.REPORT)
    if (m.status == MessageStatus.FAILED) {
        if (canRetry) out.add(MenuAction.RETRY)
        out.add(MenuAction.DELETE)
    }
    return out
}

/** 被 @ 的是我（含 @ 所有人，自己发的不算，F-MEN-03 规则 6）：气泡整体用样式提示（F-MEN-01 规则 4）。 */
internal fun mentionsMe(m: Message, self: String): Boolean {
    val x = m.body.mentions ?: return false
    return m.from != self && (x.all || self in x.userIds)
}

/** 引用跳转的范围（F-QUO-02 规则 5）：距已加载范围 200 条以内才翻页；一次最多 200 条（sync 的 limit 上限）。 */
internal const val QUOTE_JUMP_MAX = 200L

/**
 * 跳到 seq 之前要做什么（F-QUO-02 规则 5，问题 40）：已加载的直接跳；更早的，差距在 limit 以内往上翻页（已加载范围保持连续）；
 * 更远的提示"消息太早，请翻页查看"；没有 seq，或在已加载范围里却没有，提示"消息不存在"。
 */
internal sealed interface JumpPlan {
    data object Here : JumpPlan
    data object TooEarly : JumpPlan
    data object Missing : JumpPlan
    data class Load(val count: Int) : JumpPlan
}

internal fun jumpPlan(seq: Long?, loaded: List<Long>, limit: Long): JumpPlan {
    if (seq == null) return JumpPlan.Missing
    if (seq in loaded) return JumpPlan.Here
    val oldest = loaded.minOrNull() ?: return JumpPlan.Missing
    if (seq > oldest) return JumpPlan.Missing
    val gap = oldest - seq
    if (gap > limit) return JumpPlan.TooEarly
    return JumpPlan.Load(minOf(QUOTE_JUMP_MAX, gap).toInt())
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
    // SDK 在自己的线程上更新这个流：在主线程收（问题 A16）。不依赖组合所在的调度器会不会切线程：在 SDK 线程上恢复的话，
    // 重组和消息列表的测量会跑在 SDK 线程上，和主线程的绘制同时进行（LazyColumn IndexOutOfBoundsException）。
    val messages by remember(convId) { im.client.messagesFlow(convId) }.collectAsState(context = Dispatchers.Main)
    val connected = im.state == com.tianting.sdk.ConnState.CONNECTED

    // 快照（G-UI-21）：SDK 接口是 suspend，取到之前（Loaded = null）不下结论。convAdded 时 SDK 清快照，会话也回到列表。
    val loadedSnapshot by produceState<Loaded<ConversationSnapshot?>?>(null, convId, conv == null, im.removedTick) {
        value = Loaded(onMain { im.client.conversationSnapshot(convId) })
    }
    val snapshot = loadedSnapshot?.value

    var probe by remember { mutableStateOf<Int?>(null) } // 4033 / 4043
    var probeDone by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(HistoryState.IDLE) }
    var newCount by remember { mutableIntStateOf(0) }
    var retryId by remember { mutableStateOf<String?>(null) }
    // 菜单项在打开时算好：菜单开着的时候过了撤回时限，"撤回"还在，点了按服务端 4097 提示（AC-RCL-01-8，问题 37）。
    var menu by remember { mutableStateOf<Pair<String, List<MenuAction>>?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 撤回、引用、@（C4）：GET /v1/config 的 features.msgActions 打开才有（PRD-ui F-UI-09 规则 4）。
    val actionsOn = im.msgActions
    // 举报（C6）：features.moderation 关闭时消息菜单、撤回提示行都没有"举报"（frontend-chat 第 76 条）；显示哪些按 SDK 的 canReport。
    val reportOn = im.moderation
    var reporting by remember { mutableStateOf<ReportRequest?>(null) }
    // 引用条（F-QUO-01，AC-QUO-01-5）：只记 msgId；草稿里也只有 msgId（问题 41）。
    var quoteId by remember { mutableStateOf(im.drafts[convId]?.quote) }
    var quoteFocus by remember { mutableIntStateOf(0) }
    // 全屏看的图片（按 clientMsgId）：被撤回、删除时退出全屏并提示（AC-RCL-03-8）。
    var viewer by remember { mutableStateOf<String?>(null) }
    // 跳转（引用、"有人@我"）后高亮的 seq，2 秒后消失。
    var highlight by remember { mutableStateOf<Long?>(null) }
    var jumpSeq by remember { mutableStateOf<Long?>(null) }
    // "有人@我"（F-MEN-03 规则 3）：打开会话时只取一次 firstMentionSeq（读到最新后服务端就清零了，问题 40）。
    var mentionAt by remember { mutableStateOf<Long?>(null) }
    var mentionTaken by remember { mutableStateOf(false) }

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
            onMain { im.client.loadHistory(convId) }
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
    // 不能发送时输入内容不保留（F-UI-33 规则 5）。Composer 离开组合时存的草稿先于这里运行；禁言不走这条，内容保留。
    LaunchedEffect(blocked != null, convId) {
        if (blocked != null) {
            im.drafts.remove(convId)
            quoteId = null
        }
    }
    val probing = isGroup && (loadedSnapshot == null || (unknown && !probeDone)) && blocked == null

    suspend fun loadMore() {
        if (history == HistoryState.LOADING || history == HistoryState.END) return
        history = HistoryState.LOADING
        history = try {
            if (onMain { im.client.loadHistory(convId) }) HistoryState.IDLE else HistoryState.END
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

    // 列表里消息行前面的固定项（"不能发送"提示、加载中）：跳转时按下标滚动要加上。
    val leading = (if (blocked != null) 1 else 0) + (if (probing) 1 else 0)
    // 跳到 jumpSeq：等列表用新数据重组后再按下标滚动，滚到后高亮（AC-QUO-02-3、AC-MEN-03-6）。
    LaunchedEffect(jumpSeq, reversed) {
        val seq = jumpSeq ?: return@LaunchedEffect
        val i = reversed.indexOfFirst { it is ChatRow.Msg && it.m.seq == seq }
        if (i < 0) return@LaunchedEffect
        jumpSeq = null
        list.scrollToItem(leading + i)
        highlight = seq
    }
    LaunchedEffect(highlight) {
        if (highlight == null) return@LaunchedEffect
        delay(2000)
        highlight = null
    }

    /** 跳到 seq（F-QUO-02 规则 5）：limit 为 Long.MAX_VALUE 时一页页翻到为止（"有人@我"，问题 40）。 */
    fun jumpTo(seq: Long?, limit: Long) {
        scope.launch {
            while (true) {
                val loaded = im.client.messages(convId).mapNotNull { it.seq }
                when (val p = jumpPlan(seq, loaded, limit)) {
                    JumpPlan.Here -> {
                        jumpSeq = seq
                        return@launch
                    }
                    JumpPlan.TooEarly -> {
                        app.toast.show("消息太早，请翻页查看")
                        return@launch
                    }
                    JumpPlan.Missing -> {
                        app.toast.show("消息不存在")
                        return@launch
                    }
                    is JumpPlan.Load -> {
                        if (history == HistoryState.END) {
                            app.toast.show("消息不存在")
                            return@launch
                        }
                        if (history == HistoryState.LOADING) { // 翻页正在进行：等它结束再看
                            delay(100)
                            continue
                        }
                        history = HistoryState.LOADING
                        val more = try {
                            onMain { im.client.loadHistory(convId, p.count) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            history = HistoryState.ERROR
                            return@launch
                        }
                        history = if (more) HistoryState.IDLE else HistoryState.END
                        if (!more && im.client.messages(convId).none { it.seq == seq }) {
                            app.toast.show("消息不存在")
                            return@launch
                        }
                    }
                }
            }
        }
    }

    // 记下第一条提及（会话列表到了以后只取一次）。
    LaunchedEffect(conv != null) {
        if (mentionTaken || conv == null) return@LaunchedEffect
        mentionTaken = true
        if (actionsOn && (conv.mentionUnread ?: 0) > 0) mentionAt = conv.firstMentionSeq
    }
    // 那条进了视口就不再显示按钮（问题 40）。
    LaunchedEffect(mentionAt, messages) {
        val seq = mentionAt ?: return@LaunchedEffect
        val key = messages.firstOrNull { it.seq == seq }?.clientMsgId ?: return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
        mentionAt = null
    }

    // 正在全屏看的图片被撤回、删除：退出全屏并提示（AC-RCL-03-8）。
    val viewing = viewer?.let { id -> messages.firstOrNull { it.clientMsgId == id } }
    LaunchedEffect(viewer, viewing?.deleted) {
        if (viewer == null || viewing?.deleted != true) return@LaunchedEffect
        viewer = null
        app.toast.show(if (viewing.recalled) MESSAGE_RECALLED else MESSAGE_DELETED)
    }

    /** 举报单条消息（第一批只举报单条，frontend-chat 第 85 条）；单聊成功后询问拉黑，群消息不问（第 79 条）。 */
    fun reportMessage(m: Message) {
        val id = m.msgId ?: return
        reporting = ReportRequest(ReportTarget.Messages(convId, listOf(id)), askBlock = if (isGroup) null else m.from)
    }

    fun act(item: MenuAction, m: Message) {
        menu = null
        when (item) {
            MenuAction.COPY -> app.copy(m.body.text.orEmpty())
            MenuAction.RETRY -> im.launchCmd { im.client.retry(convId, m.clientMsgId) } // 菜单里的"重发"不再二次确认（规则 3）
            MenuAction.DELETE -> im.launchCmd { im.client.removeFailed(convId, m.clientMsgId) } // 只从本地移除（规则 3）
            MenuAction.QUOTE -> {
                quoteId = m.msgId
                quoteFocus++
            }
            // 4097 按 windowSeconds；4030"你没有权限撤回这条消息"（AC-RCL-02-5）；不自动重试（问题 26）。成功后 SDK 本地立即按撤回显示。
            MenuAction.RECALL -> {
                val id = m.msgId ?: return
                im.launchCmd({ e -> app.toast.show(recallErrorText(e)) }) { im.client.recall(convId, id) }
            }
            MenuAction.REPORT -> reportMessage(m)
        }
    }

    val liveMessage = { id: String? -> id?.let { i -> messages.firstOrNull { it.clientMsgId == i && !it.deleted } } }
    // 4510 被拦截的不能重发（frontend-chat 第 81 条）：确认框也不出
    val retry = liveMessage(retryId)?.takeIf { im.client.canRetry(it) }
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
                        is ChatRow.Msg -> HighlightBox(r.m.seq != null && r.m.seq == highlight) {
                            val recall = if (r.m.recalled) im.client.recallNotice(r.m) else null
                            when {
                                // 撤回按 F-RCL-02 规则 1a 的表（F-UI-42 规则 4）；撤回、删除的提示行没有菜单（F-RCL-03 规则 6）
                                // 别人撤回的（canReport）后面跟"举报"（F-MOD-03 规则 2a，AC-MOD-03-11）
                                r.m.deleted && recall != null -> RecallTip(
                                    rememberRecallText(recall),
                                    r.m.clientMsgId,
                                    onReport = if (reportOn && im.client.canReport(r.m)) ({ reportMessage(r.m) }) else null,
                                )
                                r.m.deleted -> CenterTip(MESSAGE_DELETED, "msg-deleted")
                                r.m.type == MsgType.TIP -> TipRow(r.m, convId)
                                else -> {
                                    // 长按时重新算（撤回按当时的服务端时间，AC-RCL-01-8）；组合时算的只决定长按有没有反应。
                                    val itemsNow = {
                                        menuItemsFor(r.m, actionsOn, report = reportOn && im.client.canReport(r.m), canRetry = im.client.canRetry(r.m)) {
                                            im.client.messageActions(r.m, conv?.myRole)
                                        }
                                    }
                                    val items = itemsNow()
                                    val open = menu?.takeIf { it.first == r.m.clientMsgId }
                                    Bubble(
                                        m = r.m,
                                        mine = r.m.from == im.self,
                                        showName = isGroup && r.m.from != im.self,
                                        maxWidth = bubbleMax,
                                        listTop = listTop,
                                        userState = userState,
                                        hasMenu = items.isNotEmpty(),
                                        mentioned = actionsOn && mentionsMe(r.m, im.self),
                                        menuItems = open?.second,
                                        onRetry = if (im.client.canRetry(r.m)) ({ retryId = r.m.clientMsgId }) else null,
                                        onMenu = { itemsNow().takeIf { it.isNotEmpty() }?.let { menu = r.m.clientMsgId to it } },
                                        onMenuClose = { menu = null },
                                        onPick = { act(it, r.m) },
                                        onQuote = { q -> jumpTo(q.seq, QUOTE_JUMP_MAX) },
                                        onOpenImage = { viewer = r.m.clientMsgId },
                                        convId = convId,
                                    )
                                }
                            }
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
            if (mentionAt != null) {
                // "有人@我"（F-MEN-03 规则 3，AC-MEN-03-6）：点了滚动到那条并高亮，按钮消失。
                Surface(
                    onClick = {
                        val seq = mentionAt
                        mentionAt = null
                        jumpTo(seq, Long.MAX_VALUE)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp).tag("mention-jump").heightIn(min = TtDimen.sizeTouchMin.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Tt.colors.brandFill,
                    contentColor = Tt.colors.onBrand,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        T("有人@我", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = Tt.type.caption, color = Tt.colors.onBrand)
                    }
                }
            }
        }
        val mute by produceState<com.tianting.sdk.MuteInfo?>(null, convId, im.muteTick) { value = onMain { im.client.muteState(convId) } }
        when {
            probing -> Unit
            blocked != null -> DisabledBar(blocked.bar)
            mute != null -> DisabledBar(mutedText(mute!!))
            else -> Composer(
                convId = convId,
                messages = messages,
                actionsOn = actionsOn,
                mentionGroup = if (isGroup && actionsOn) MentionGroup(to, conv?.myRole) else null,
                quoteId = quoteId,
                quoteFocus = quoteFocus,
                onQuoteCancel = { quoteId = null },
            ) { newCount = 0 }
        }
    }
    viewing?.body?.image?.fileId?.takeIf { it.isNotEmpty() && !viewing.deleted }?.let { fileId ->
        ImageViewer(fileId, viewing.body.image?.mime) { viewer = null }
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
    reporting?.let { ReportSheet(it) { reporting = null } }
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

/**
 * 撤回提示行（F-RCL-02 规则 1a 的表，F-RCL-03 规则 6）：没有菜单。onReport 有值时（别人撤回的，C6）后面跟一个"举报"
 * （F-MOD-03 规则 2a，AC-MOD-03-11），可读名称"举报这条消息"，是按钮；msg-recalled 仍然只有撤回文案（同 H5）。
 */
@Composable
private fun RecallTip(text: String, clientMsgId: String, onReport: (() -> Unit)?) {
    if (onReport == null) {
        CenterTip(text, "msg-recalled", Modifier.semantics { dataClientMsgId = clientMsgId })
        return
    }
    Row(Modifier.fillMaxWidth().tag("recall-row").semantics { dataClientMsgId = clientMsgId }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        T(
            text,
            Modifier.tag("msg-recalled").clip(RoundedCornerShape(TtDimen.radiusSm.dp)).background(Tt.colors.surface).padding(horizontal = 8.dp, vertical = 2.dp),
            style = Tt.type.caption,
            color = Tt.colors.textTertiary,
            align = TextAlign.Center,
        )
        Box(
            Modifier.tag("recall-report").heightIn(min = TtDimen.sizeTouchMin.dp).widthIn(min = TtDimen.sizeTouchMin.dp) // 点击区域不小于 44 dp（PRD-ui），文字不变
                .clickable(onClickLabel = "举报", role = androidx.compose.ui.semantics.Role.Button, onClick = onReport)
                .semantics { contentDescription = "举报这条消息" }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            T("举报", style = Tt.type.caption, color = Tt.colors.brand)
        }
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

/** 跳转后的高亮（AC-QUO-02-3、AC-MEN-03-6）：整行用 surfaceSelected 底色，2 秒后消失。 */
@Composable
private fun HighlightBox(on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    Box(
        Modifier.fillMaxWidth().tag("msg-highlight").semantics { dataMark = "highlight" }
            .clip(RoundedCornerShape(TtDimen.radiusMd.dp)).background(Tt.colors.surfaceSelected),
    ) { content() }
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
    hasMenu: Boolean,
    mentioned: Boolean,
    menuItems: List<MenuAction>?,
    onRetry: (() -> Unit)?,
    onMenu: () -> Unit,
    onMenuClose: () -> Unit,
    onPick: (MenuAction) -> Unit,
    onQuote: (QuoteView) -> Unit,
    onOpenImage: () -> Unit,
    convId: String,
) {
    val nav = LocalNav.current
    val senderName = rememberDisplayName(m.from)
    val sender = rememberProfile(m.from)
    val im = LocalIm.current
    val attachment = m.isAttachment()
    val supported = m.type == MsgType.TEXT || attachment
    val failed = m.status == MessageStatus.FAILED
    // 长按（F-UI-09）：有菜单项才响应；图片、文件里能点的地方也接长按（C4：已发送的图片、文件可以引用、撤回）。
    val longPress: (() -> Unit)? = if (hasMenu) onMenu else null
    val avatar: @Composable () -> Unit = {
        Box(
            Modifier.tag("msg-avatar").size(40.dp).clickable { nav.go(userPath(m.from), userState) }.semantics { contentDescription = "查看 $senderName 的资料" },
        ) { Avatar(m.from, senderName, 40.dp, src = sender?.avatar, local = if (m.from == im.self) im.media?.localAvatar else null) }
    }
    val bubbleShape = if (mine) RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp) else RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)
    Row(
        Modifier.fillMaxWidth().tag("message").semantics {
            dataStatus = m.status.name.lowercase()
            dataClientMsgId = m.clientMsgId
            if (mentioned) dataMark = "mention"
        },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) avatar()
        Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showName) T(senderName, Modifier.tag("msg-name").padding(bottom = 2.dp), style = Tt.type.caption, color = Tt.colors.textSecondary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mine && m.status != MessageStatus.SENT) {
                    if (failed && onRetry != null) {
                        IconButton(onRetry, Modifier.tag("failed-icon").size(44.dp)) {
                            Icon(TtIcons.failed, "发送失败，点击重发", Modifier.size(20.dp), tint = Tt.colors.danger)
                        }
                    } else if (failed) {
                        // 不能重发的（4510 被拦截、原文件已不可用，SDK canRetry 为 false）：只有标记，点了没有反应（frontend-chat 第 81 条）
                        Box(Modifier.tag("failed-icon").size(44.dp).semantics { dataMark = "no-retry" }, contentAlignment = Alignment.Center) {
                            Icon(TtIcons.failed, "发送失败", Modifier.size(20.dp), tint = Tt.colors.danger)
                        }
                    } else {
                        Spinner(16.dp, Tt.colors.textTertiary, Modifier.tag("sending-icon").padding(end = 4.dp))
                    }
                }
                Box {
                    // 被 @ 的是我：气泡整体加品牌色描边（F-MEN-01 规则 4，颜色来自 token，深色跟着变）
                    val border = when {
                        mentioned -> Modifier.border(BorderStroke(2.dp, Tt.colors.brand), bubbleShape)
                        !mine && !Tt.colors.isDark() -> Modifier.border(BorderStroke(1.dp, Tt.colors.divider), bubbleShape)
                        else -> Modifier
                    }
                    val bg = if (mine) Tt.colors.bubbleSelfBg else Tt.colors.bubbleOtherBg
                    val fg = when {
                        !supported -> if (mine) Tt.colors.bubbleSelfText else Tt.colors.textSecondary
                        mine -> Tt.colors.bubbleSelfText
                        else -> Tt.colors.bubbleOtherText
                    }
                    val image = m.type == MsgType.IMAGE
                    Box(
                        Modifier.widthIn(max = maxWidth).tag("bubble").clip(bubbleShape).background(bg).then(border)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = hasMenu,
                                onClick = {},
                                onLongClickLabel = "消息操作",
                                onLongClick = onMenu,
                            ).padding(horizontal = if (image) 4.dp else 12.dp, vertical = if (image) 4.dp else 8.dp),
                    ) {
                        Column {
                            m.body.quote?.let { q -> QuoteBlock(q, fg, longPress) { onQuote(q) } }
                            when (m.type) {
                                MsgType.IMAGE -> ImageContent(m, onLongClick = longPress, onOpen = onOpenImage)
                                MsgType.FILE -> FileContent(m, mine, convId, onLongClick = longPress)
                                MsgType.TEXT -> T(m.body.text.orEmpty(), color = fg)
                                else -> T("[暂不支持的消息]", color = fg)
                            }
                        }
                    }
                    if (menuItems != null) BubbleMenu(menuItems, listTop, onPick, onMenuClose)
                }
            }
            if (mine && attachment) AttachmentStatus(m, convId)
        }
        if (mine) avatar()
    }
}

/**
 * 气泡顶部的引用（F-QUO-02 规则 3）：四种状态的文字来自 SDK 的 quoteLabel，正常时前面加原发送者的显示名；
 * Compose 的 Text 按纯文本显示，不解析 HTML（AC-QUO-02-5）。点击跳到原消息（规则 5）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuoteBlock(q: QuoteView, color: androidx.compose.ui.graphics.Color, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    val from = q.from?.takeIf { it.isNotEmpty() }
    val name = if (from != null) rememberDisplayName(from) else ""
    val label = quoteLabel(q)
    val text = if (q.state == QuoteState.NORMAL && from != null) "$name: $label" else label
    Row(
        Modifier.padding(bottom = 4.dp).tag("msg-quote").semantics { dataKind = q.state.wire }
            .clip(RoundedCornerShape(TtDimen.radiusSm.dp))
            .combinedClickable(onClickLabel = "查看引用的消息", onLongClick = onLongClick, onClick = onClick)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 20.dp).background(color))
        T(text, Modifier.padding(start = 6.dp, end = 4.dp), style = Tt.type.caption, color = color, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private val MENU_GAP = 4.dp

/** 气泡菜单（F-UI-09 规则 2）：在气泡上方；到消息列表顶边的空间不够时在下方。点菜单外关闭。项是打开时算好的（AC-RCL-01-8）。 */
@Composable
private fun BubbleMenu(items: List<MenuAction>, listTop: Float, onPick: (MenuAction) -> Unit, onClose: () -> Unit) {
    val gap = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    Popup(popupPositionProvider = BubbleMenuPosition(listTop.toInt(), gap), onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Surface(
            Modifier.tag("bubble-menu").widthIn(min = 120.dp).semantics { contentDescription = "消息操作" },
            shape = RoundedCornerShape(TtDimen.radiusMd.dp),
            color = Tt.colors.surface,
            border = BorderStroke(1.dp, Tt.colors.divider),
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                for (item in items) MenuItem(item.text, item.tag) { onPick(item) }
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
 * 进入时取走这个会话的草稿，离开时把没发出去的内容存回去（F-UI-24）：文字、@ 区间、引用条（结构化草稿，AC-MEN-01-10）。
 *
 * @（PRD-chat F-MEN-01 规则 5，core/Mentions.kt 的 applyEdit）：群聊里在行首或空白后敲 @ / ＠ 才打开成员选择；组字中不改模型、
 * 不打开（上屏后再看）；光标不落进 @ 内部，退格一次删整段；粘贴的 @xxx 只是文字。单聊、features.msgActions 关闭时不打开。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    convId: String,
    messages: List<Message>,
    actionsOn: Boolean,
    mentionGroup: MentionGroup?,
    quoteId: String?,
    quoteFocus: Int,
    onQuoteCancel: () -> Unit,
    onSent: () -> Unit,
) {
    val im = LocalIm.current
    val app = LocalApp.current
    val keyboard = LocalSoftwareKeyboardController.current
    val initial = remember { im.drafts[convId] }
    var value by remember { mutableStateOf(TextFieldValue(initial?.text.orEmpty()).let { it.copy(selection = TextRange(it.text.length)) }) }
    var edit by remember { mutableStateOf(EditState(value.text, value.text.length, ranges = if (mentionGroup != null) initial?.mentions.orEmpty() else emptyList())) }
    var picker by remember { mutableStateOf<Int?>(null) } // 打开选择时 @ 的位置
    val focus = remember { FocusRequester() }
    var emoji by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var keepInfo by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    var sending by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(com.tianting.app.core.MAX_PICK),
    ) { uris -> picked(im, app, context, convId, com.tianting.app.core.PickKind.IMAGES, uris) { keepInfo = it } }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> picked(im, app, context, convId, com.tianting.app.core.PickKind.FILES, uris) { keepInfo = it } }

    fun pick(kind: com.tianting.app.core.PickKind) {
        attach = false
        val hook = app.deps.pickForTest
        if (hook != null) {
            hook(kind)?.let { picked(im, app, context, convId, kind, it) { u -> keepInfo = u } }
            return
        }
        // 系统照片选择器（不要存储权限）、SAF（第 13.2 节）。
        if (kind == com.tianting.app.core.PickKind.IMAGES) {
            pickImages.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            pickFiles.launch(arrayOf("*/*"))
        }
    }
    val latest = rememberUpdated(Draft(value.text, edit.ranges, quoteId))
    DisposableEffect(convId) {
        im.drafts.remove(convId)
        onDispose {
            val d = latest.value
            if (d.isEmpty) im.drafts.remove(convId) else im.drafts[convId] = d
        }
    }
    // 这个会话被删除（本端，或同一账号的其他设备，C5）：这台设备的草稿也清除，打开着的输入框一样（F-CVS-03 规则 7，frontend-chat 第 55 条）。
    LaunchedEffect(convId) {
        im.events.collect { e ->
            if (e is com.tianting.sdk.IMEvent.ConvDeleted && e.convId == convId) {
                value = TextFieldValue("")
                edit = EditState("", 0, ranges = emptyList())
                picker = null
                onQuoteCancel()
            }
        }
    }
    // 选了"引用"、选好了 @ 的人：焦点回到输入框（F-UI-10 规则 7）。
    LaunchedEffect(quoteFocus) { if (quoteFocus > 0) runCatching { focus.requestFocus() } }

    fun onChange(nv: TextFieldValue) {
        val r = applyEdit(edit, nv.text, nv.selection.start, nv.selection.end, composing = nv.composition != null, canMention = mentionGroup != null)
        edit = r.state
        value = if (r.state.text == nv.text && r.state.selStart == nv.selection.start && r.state.selEnd == nv.selection.end) {
            nv
        } else {
            TextFieldValue(r.state.text, TextRange(r.state.selStart, r.state.selEnd))
        }
        if (r.trigger != null) picker = r.trigger
    }

    fun pickMention(name: String, userId: String?) {
        val at = picker ?: return
        picker = null
        val r = insertMention(value.text, edit.ranges, at, name, userId)
        edit = EditState(r.text, r.caret, r.caret, r.ranges)
        value = TextFieldValue(r.text, TextRange(r.caret))
        runCatching { focus.requestFocus() }
    }
    val check = checkText(value.text)
    val over = (check as? TextCheck.Rejected)?.takeIf { it.reason == TextRejectReason.TOO_LONG }?.over ?: 0
    val canSend = check is TextCheck.Ok && !sending

    fun send() {
        if (!canSend || value.composition != null) return
        sending = true
        val text = value.text
        // C4：引用只带 msgId；@ 只在群聊，区间里还以 @ 开头的才算（AC-MEN-01-5）。开关关闭时都不带。
        val quote = if (actionsOn) quoteId?.let { QuoteRef(it) } else null
        val mentions = if (mentionGroup != null) toMentions(text, edit.ranges) else null
        im.launchCmd({ e ->
            sending = false
            app.toast.show(defaultMessage(errCode(e)))
        }) {
            val r = im.client.send(convId, text, quote, mentions)
            sending = false
            if (r is SendResult.Ok) { // 返回 Rejected 时不清空（规则 7）
                value = TextFieldValue("")
                edit = EditState("", 0)
                onQuoteCancel()
                onSent()
            }
        }
    }

    fun insert(e: String) {
        val s = value.selection.min
        val t = value.selection.max
        val text = value.text.substring(0, s) + e + value.text.substring(t)
        onChange(TextFieldValue(text, TextRange(s + e.length)))
    }

    Column(Modifier.fillMaxWidth().background(Tt.colors.surface)) {
        HorizontalDivider(color = Tt.colors.divider)
        if (actionsOn && quoteId != null) QuoteBar(messages, quoteId, onQuoteCancel)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton({
                if (!emoji) keyboard?.hide()
                emoji = !emoji
                attach = false
            }, Modifier.tag("emoji-button").size(44.dp)) {
                Icon(TtIcons.smile, "表情", tint = Tt.colors.textSecondary)
            }
            // "+"：图片、文件（第 13.3 节）；features.files 关闭时没有。
            if (im.filesEnabled) {
                IconButton({
                    if (!attach) keyboard?.hide()
                    attach = !attach
                    emoji = false
                }, Modifier.tag("attach-button").size(44.dp)) {
                    Icon(TtIcons.plus, "发送图片或文件", tint = Tt.colors.textSecondary)
                }
            }
            BasicTextField(
                value,
                { onChange(it) },
                Modifier.weight(1f).tag("composer-input").heightIn(min = 44.dp).focusRequester(focus).onFocusChanged {
                    if (it.isFocused) {
                        emoji = false
                        attach = false
                    }
                },
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
        if (attach && im.filesEnabled) {
            Row(Modifier.tag("attach-panel").fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AttachTile(TtIcons.image, "图片", "attach-image") { pick(com.tianting.app.core.PickKind.IMAGES) }
                AttachTile(TtIcons.file, "文件", "attach-file") { pick(com.tianting.app.core.PickKind.FILES) }
            }
        }
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
    if (mentionGroup != null && picker != null) {
        MentionPicker(mentionGroup, onPick = { name, userId -> pickMention(name, userId) }) {
            picker = null // @ 留作普通文字（问题 39）
            runCatching { focus.requestFocus() }
        }
    }
    keepInfo?.let { uris ->
        Confirm(com.tianting.app.core.KEEP_METADATA_TIP, "发送", "keep-info-dialog", onCancel = { keepInfo = null }, onOk = {
            keepInfo = null
            sendPicked(im, app, context, convId, com.tianting.app.core.PickKind.FILES, uris)
        })
    }
}

@Composable
private fun AttachTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.tag(tag).clip(RoundedCornerShape(TtDimen.radiusMd.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(TtDimen.radiusMd.dp)).background(Tt.colors.bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Tt.colors.textSecondary)
        }
        T(label, style = Tt.type.caption, color = Tt.colors.textSecondary)
    }
}

/** 可以 @ 的群（群聊且 features.msgActions 打开）：myRole 决定有没有"所有人"（F-MEN-02 规则 3）。 */
internal data class MentionGroup(val groupId: String, val myRole: GroupRole?)

/**
 * 引用条（F-QUO-01、F-QUO-02 规则 3，第 13.4 节"引用条"）：显示已加载的那条消息，被撤回、删除时跟着变；草稿里只记 msgId，
 * 这里不留原文；找不到时"引用内容不可用"（问题 41）。按纯文本显示（AC-QUO-02-5）。
 */
@Composable
private fun QuoteBar(messages: List<Message>, msgId: String, onCancel: () -> Unit) {
    val m = messages.firstOrNull { it.msgId == msgId }
    val name = rememberDisplayName(m?.from ?: LocalIm.current.self)
    val label = when {
        m == null -> "引用内容不可用"
        m.deleted -> if (m.recalled) "引用内容已被撤回" else "引用内容已被删除"
        else -> "$name: ${quoteSummary(m)}"
    }
    Row(
        Modifier.tag("quote-bar").fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp)
            .clip(RoundedCornerShape(TtDimen.radiusSm.dp)).background(Tt.colors.bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 32.dp).background(Tt.colors.brand))
        T(label, Modifier.weight(1f).padding(horizontal = 8.dp).tag("quote-bar-text"), style = Tt.type.small, color = Tt.colors.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        IconButton(onCancel, Modifier.tag("quote-cancel").size(44.dp)) { Icon(TtIcons.close, "取消引用", tint = Tt.colors.textSecondary) }
    }
}

private const val SEARCH_DELAY_MS = 300L // 每人每分钟 30 次（F-MEN-01 规则 6）：停下来再搜

private enum class PickerState { LOADING, OK, ERROR }

/**
 * @ 成员选择（PRD-chat F-MEN-01 规则 4、6，F-MEN-02 规则 3，第 13.4 节"@ 选择"，问题 39）：底部面板，"搜索群成员"（keyword，停 300 毫秒再搜）；
 * 加载中、无结果、加载失败（点了重试；关掉也可以继续发普通文本）；列表显示本机显示名（备注优先）、头像和 userId（同名靠这两样区分），
 * 插入文字用公开昵称，没有昵称用 userId（R-CF-03）。"所有人"只给群主、管理员（AC-MEN-02-4）。列表不含自己（F-MEN-03 规则 6）。
 */
@Composable
private fun MentionPicker(g: MentionGroup, onPick: (name: String, userId: String?) -> Unit, onClose: () -> Unit) {
    val im = LocalIm.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(PickerState.LOADING) }
    var members by remember { mutableStateOf<List<GroupMemberInfo>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(keyword, attempt) {
        state = PickerState.LOADING
        if (keyword.isNotEmpty()) delay(SEARCH_DELAY_MS)
        state = try {
            val page = onMain { im.client.searchGroupMembers(g.groupId, keyword.trim()) }
            members = page.members
            cursor = page.nextCursor
            PickerState.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.tianting.app.core.AppLog.debug("member search failed: ${e.javaClass.simpleName}")
            PickerState.ERROR
        }
    }
    val list = members.filter { it.userId != im.self }
    val canAll = (g.myRole == GroupRole.OWNER || g.myRole == GroupRole.ADMIN) && keyword.isBlank()
    com.tianting.app.ui.TtSheet("mention-picker", onClose) {
        T("选择提醒的人", style = Tt.type.title)
        com.tianting.app.ui.TtInput(keyword, { keyword = it }, "mention-search", placeholder = "搜索群成员", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        if (canAll) {
            Row(
                Modifier.tag("mention-all").fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp).clickable { onPick("所有人", null) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Tt.colors.brandFill), contentAlignment = Alignment.Center) { T("@", style = Tt.type.title, color = Tt.colors.onBrand) }
                T("所有人", maxLines = 1)
            }
        }
        when {
            state == PickerState.LOADING -> T("加载中…", Modifier.tag("mention-loading").fillMaxWidth().padding(16.dp), color = Tt.colors.textSecondary, align = TextAlign.Center)
            state == PickerState.ERROR -> T(
                "加载失败，点击重试",
                Modifier.tag("mention-error").fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp).clickable { attempt++ }.padding(16.dp),
                color = Tt.colors.brand,
                align = TextAlign.Center,
            )
            list.isEmpty() -> T("没有找到群成员", Modifier.tag("mention-empty").fillMaxWidth().padding(16.dp), color = Tt.colors.textSecondary, align = TextAlign.Center)
            else -> for (m in list) {
                val shown = im.nameOf(m.userId).let { if (it == m.userId && m.nickname.isNotEmpty()) m.nickname else it }
                Row(
                    Modifier.tag("mention-row").semantics(mergeDescendants = true) { dataUser = m.userId }.fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp)
                        .clickable { onPick(m.nickname.ifEmpty { m.userId }, m.userId) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(m.userId, shown, 40.dp, src = m.avatar)
                    Column(Modifier.weight(1f)) {
                        T(shown, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        T(m.userId, style = Tt.type.caption, color = Tt.colors.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (state == PickerState.OK && cursor.isNotEmpty()) {
            T(
                "加载更多",
                Modifier.tag("mention-more").fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp).clickable {
                    scope.launch {
                        try {
                            val page = onMain { im.client.searchGroupMembers(g.groupId, keyword.trim(), cursor) }
                            val seen = members.map { it.userId }.toSet()
                            members = members + page.members.filter { it.userId !in seen }
                            cursor = page.nextCursor
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 留着已有的列表，再点一次
                        }
                    }
                }.padding(16.dp),
                color = Tt.colors.brand,
                align = TextAlign.Center,
            )
        }
        androidx.compose.material3.TextButton(onClose, Modifier.tag("mention-cancel").fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp)) { T("取消", color = Tt.colors.textSecondary) }
    }
}

/**
 * 选好了（F-FILE-07 规则 6：一次最多 9 个，多了整批不发，H16）。图片按选择顺序逐个交给 SDK（规则 5）；
 * "文件"方式里有图片时先提示保留原始信息，确认后才发（F-FILE-03 规则 2，AC-FILE-03-6）。
 */
private fun picked(
    im: com.tianting.app.core.ImSession,
    app: com.tianting.app.core.AppModel,
    context: android.content.Context,
    convId: String,
    kind: com.tianting.app.core.PickKind,
    uris: List<android.net.Uri>,
    askKeepInfo: (List<android.net.Uri>) -> Unit,
) {
    if (uris.isEmpty()) return
    if (uris.size > com.tianting.app.core.MAX_PICK) {
        app.toast.show(com.tianting.app.core.PICK_TOO_MANY)
        return
    }
    if (kind == com.tianting.app.core.PickKind.FILES) {
        val anyImage = uris.any { u ->
            val (name, _, mime) = com.tianting.app.core.queryMeta(context.contentResolver, u)
            com.tianting.app.core.looksLikeImage(mime, name)
        }
        if (anyImage) {
            askKeepInfo(uris)
            return
        }
    }
    sendPicked(im, app, context, convId, kind, uris)
}

internal fun sendPicked(
    im: com.tianting.app.core.ImSession,
    app: com.tianting.app.core.AppModel,
    context: android.content.Context,
    convId: String,
    kind: com.tianting.app.core.PickKind,
    uris: List<android.net.Uri>,
) {
    val media = im.media ?: return
    im.launchCmd({ e -> app.toast.show(if (e is com.tianting.sdk.NotConnectedError) "正在连接，请稍后再发送" else defaultMessage(errCode(e))) }) {
        for (u in uris) {
            if (kind == com.tianting.app.core.PickKind.FILES) {
                val size = com.tianting.app.core.queryMeta(context.contentResolver, u).second
                if (size != null && size > com.tianting.app.core.FILE_MAX_BYTES) {
                    app.toast.show("文件过大，最大 100 MB")
                    continue
                }
            }
            val p = try {
                media.copyIn(u)
            } catch (e: com.tianting.app.core.PickFailed) {
                app.toast.show(if (e.storage) com.tianting.app.core.STORAGE_FULL else com.tianting.app.core.SOURCE_MISSING)
                continue
            }
            if (kind == com.tianting.app.core.PickKind.IMAGES) media.sendImage(convId, p) else media.sendFile(convId, p)
        }
    }
}

@Composable
private fun <T> rememberUpdated(v: T): androidx.compose.runtime.State<T> {
    val s = remember { mutableStateOf(v) }
    s.value = v
    return s
}

