package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.tianting.app.core.ImSession
import com.tianting.app.core.MESSAGE_DELETED
import com.tianting.app.core.RouteState
import com.tianting.app.core.USER_ID_RE
import com.tianting.app.core.applyPath
import com.tianting.app.core.chatPath
import com.tianting.app.core.defaultMessage
import com.tianting.app.core.errCode
import com.tianting.app.core.messageFor
import com.tianting.app.core.formatTime
import com.tianting.app.ui.Avatar
import com.tianting.app.ui.Badge
import com.tianting.app.ui.Confirm
import com.tianting.app.ui.ConnBanner
import com.tianting.app.ui.EmptyState
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.IconBtn
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.LocalNav
import com.tianting.app.ui.OfficialTag
import com.tianting.app.ui.Page
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.T
import com.tianting.app.ui.TopBar
import com.tianting.app.ui.TtDialog
import com.tianting.app.ui.TtInput
import com.tianting.app.ui.badgeText
import com.tianting.app.ui.dataConvId
import com.tianting.app.ui.dataPinned
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtDimen
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.ApplySource
import com.tianting.sdk.ConvType
import com.tianting.sdk.Conversation
import com.tianting.sdk.IMEvent
import com.tianting.sdk.MsgType
import com.tianting.sdk.Relation
import com.tianting.sdk.SocialMode
import com.tianting.sdk.c2cConvId
import com.tianting.sdk.peerId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 会话列表（PRD-ui F-UI-04）。 */
@Composable
fun ChatsPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val app = LocalApp.current
    // 会话菜单（PRD-chat 第 13.3 节，F-UI-04 规则 9 随 C5 改写）：features.convSettings 打开才有（AC-CVS-01-6）。
    // 只记 convId：菜单文字按会话当前的状态（其他端改了也跟着变）；会话不在列表里了菜单就不显示。
    val convMenuOn = im.convSettings
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    // 菜单、确认框开着时会话被其他端删除：关掉，免得会话重新出现时又冒出来。
    LaunchedEffect(im) {
        im.events.collect { e ->
            if (e is IMEvent.ConvDeleted) {
                if (menuFor == e.convId) menuFor = null
                if (deleting == e.convId) deleting = null
            }
        }
    }

    // SDK 乐观更新，失败回滚并抛出，这里只提示，不自动重试（frontend-chat 第 56 条）：4220"最多置顶 20 个会话"，4290"操作过于频繁"，
    // 超时、断线按服务异常。
    fun pick(c: Conversation, action: ConvAction) {
        menuFor = null
        val fail: (Throwable) -> Unit = { app.toast.show(messageFor(errCode(it), if (action == ConvAction.DELETE) "convDelete" else "convSet")) }
        when (action) {
            ConvAction.PIN -> im.launchCmd(fail) { im.client.setConvPinned(c.convId, !c.pinned) }
            ConvAction.NOTIFY -> im.launchCmd(fail) { im.client.setConvNotifyOff(c.convId, !c.notifyOff) }
            ConvAction.DELETE -> deleting = c.convId // 先确认（F-CVS-03 规则 7）
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    Page {
        TopBar(
            title = "消息",
            right = {
                Box {
                    IconBtn(TtIcons.plus, "更多操作", "plus-button", { menu = !menu })
                    DropdownMenu(menu, { menu = false }, Modifier.tag("plus-menu"), containerColor = Tt.colors.surface) {
                        PlusItem("发起群聊", "plus-new-group") {
                            menu = false
                            nav.go("/groups/new")
                        }
                        PlusItem("添加朋友", "plus-add-friend") {
                            menu = false
                            nav.go("/contacts/add")
                        }
                        PlusItem("发起单聊", "plus-start-chat") {
                            menu = false
                            dialog = true
                        }
                    }
                }
            },
        )
        ConnBanner()
        Box(Modifier.weight(1f)) {
            if (!im.convsLoaded && im.convs.isEmpty()) {
                Column(Modifier.tag("chats-skeleton")) { repeat(6) { ConvSkeleton() } }
            } else if (im.convs.isEmpty()) {
                EmptyState("还没有聊天", "chats-empty", art = true) {
                    PrimaryButton("发起单聊", Modifier.tag("chats-empty-start")) { dialog = true }
                }
            } else {
                LazyColumn(Modifier.tag("conv-list"), state = list) {
                    items(im.convs, key = { it.convId }, contentType = { "conv" }) { c ->
                        ConvItem(
                            c,
                            now,
                            menu = if (convMenuOn) ConvMenu(menuFor == c.convId, { menuFor = c.convId }, { menuFor = null }) { pick(c, it) } else null,
                        ) { nav.go(chatPath(c.convId)) }
                    }
                    if (im.truncated) {
                        item("truncated") {
                            T(
                                "仅显示最近 500 个会话",
                                Modifier.tag("convs-truncated").fillMaxWidth().padding(16.dp),
                                style = Tt.type.caption,
                                color = Tt.colors.textTertiary,
                                align = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        TabBar("/chats", onReselectChats = { scope.launch { list.animateScrollToItem(0) } })
    }
    if (dialog) StartChatDialog { dialog = false }
    val target = deleting
    if (target != null) {
        // 确认后才删（F-CVS-03 规则 7，AC-CVS-03-8）：草稿由 ConvDeleted 清（ImSession），失败消息、上传和排队的附件、缓存文件由 SDK 清
        // （第 59 条）。失败时 SDK 让会话恢复，草稿也还在。取消时什么都不变。
        Confirm(DELETE_CONV_CONFIRM, "删除", "conv-delete-dialog", danger = true, onCancel = { deleting = null }) {
            deleting = null
            im.launchCmd({ app.toast.show(messageFor(errCode(it), "convDelete")) }) { im.client.deleteConv(target) }
        }
    }
}

/** 删除会话的确认框（PRD-chat F-CVS-03 规则 7）。 */
internal const val DELETE_CONV_CONFIRM = "删除后聊天记录不再显示，确定删除？"

/** 会话菜单的三项（F-CVS-01～03，第 13.3 节）。 */
internal enum class ConvAction { PIN, NOTIFY, DELETE }

/** 菜单项的文字按会话当前的状态（TC-CVS-01-06）。 */
internal fun convActionLabel(c: Conversation, a: ConvAction): String = when (a) {
    ConvAction.PIN -> if (c.pinned) "取消置顶" else "置顶"
    ConvAction.NOTIFY -> if (c.notifyOff) "取消免打扰" else "免打扰"
    ConvAction.DELETE -> "删除"
}

/** 一个会话项的菜单：open 时显示；features.convSettings 关闭时整个是 null（长按、辅助操作都没有）。 */
internal class ConvMenu(val open: Boolean, val onOpen: () -> Unit, val onDismiss: () -> Unit, val onPick: (ConvAction) -> Unit)

@Composable
private fun PlusItem(text: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { T(text) }, onClick = onClick, modifier = Modifier.tag(tag).heightIn(min = TtDimen.sizeTouchMin.dp))
}

@Composable
private fun ConvSkeleton() {
    Row(
        Modifier.fillMaxWidth().height(TtDimen.sizeConvItem.dp).background(Tt.colors.surface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Tt.colors.divider))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.5f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Tt.colors.divider))
            Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Tt.colors.divider))
        }
    }
}

/**
 * 摘要（F-UI-04 规则 5）：提示消息用服务端摘要；图片"[图片]"、文件"[文件]"（不带文件名，F-FILE-02 规则 5、F-FILE-03 规则 5）；
 * custom 和不认识的类型统一文案；被删除的不带前缀。最后一条被撤回：recall 给出的撤回文案（F-RCL-02 规则 1a，F-RCL-03 规则 4）。
 */
internal fun summaryText(c: Conversation, recall: String? = null): String {
    val m = c.lastMsg ?: return ""
    if (m.deleted) return if (m.recalled && recall != null) recall else MESSAGE_DELETED
    if (m.type == MsgType.TEXT || m.type == MsgType.TIP) return m.text.orEmpty()
    return com.tianting.app.core.attachmentSummary(m.type) ?: "[暂不支持的消息]"
}

/**
 * 摘要前缀（F-MEN-03 规则 2，R-CF-20，PRD-ui F-UI-04 规则 5）：只显示一个，"[有人@我]" > "[草稿]" > "[N 条]"（免打扰且有未读，
 * F-CVS-02 规则 5，C5）。有提及时后面跟最后一条的摘要，不显示草稿（问题 38）。
 */
internal enum class SummaryPrefix { MENTION, DRAFT, COUNT, NONE }

internal fun summaryPrefix(c: Conversation, hasDraft: Boolean): SummaryPrefix = when {
    (c.mentionUnread ?: 0) > 0 -> SummaryPrefix.MENTION
    hasDraft -> SummaryPrefix.DRAFT
    c.notifyOff && c.unread > 0 -> SummaryPrefix.COUNT
    else -> SummaryPrefix.NONE
}

/** 免打扰会话的"[N 条]"：N 照实显示，不按角标的"99+"（这是摘要不是角标，frontend-chat 第 61 条）。 */
internal fun unreadCountText(unread: Long): String = "[$unread 条]"

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ConvItem(c: Conversation, now: Long, menu: ConvMenu?, onClick: () -> Unit) {
    val im = LocalIm.current
    val group = c.type == ConvType.GROUP
    val peer = peerId(c, im.self)
    val id = peer ?: c.convId.substringAfter(':')
    val remark = peer?.let { p -> im.friends.firstOrNull { it.userId == p }?.remark?.takeIf(String::isNotEmpty) }
    val name = remark ?: c.name
    val last = c.lastMsg
    val draft = im.drafts[c.convId]?.takeIf { !it.isEmpty }?.text
    val recallNotice = c.lastMsg?.let { im.client.recallNotice(c.convId, it) }
    val recall = recallNotice?.let { rememberRecallText(it) }
    val head = summaryPrefix(c, draft != null)
    val prefix = last?.from?.takeIf { group && it != im.self && last.type != MsgType.TIP && !last.deleted }
    val prefixName = prefix?.let { rememberDisplayName(it) }
    val peerProfile = peer?.let { rememberProfile(it) }
    // 长按菜单的辅助操作（第 13.4 节）：三项直接可选，"删除"同样先弹确认框。
    val actions = menu?.let { m -> ConvAction.entries.map { a -> CustomAccessibilityAction(convActionLabel(c, a)) { m.onPick(a); true } } }
    Box {
    Row(
        Modifier
            .tag("conv-item")
            .semantics {
                dataConvId = c.convId
                dataPinned = c.pinned.toString()
                if (actions != null) customActions = actions
            }
            .fillMaxWidth()
            .heightIn(min = TtDimen.sizeConvItem.dp)
            // 置顶项的背景区分（AC-CVS-01-6，frontend-chat 第 57 条）：color.surface.pinned，有深色值
            .background(if (c.pinned) Tt.colors.surfacePinned else Tt.colors.surface)
            .combinedClickable(onLongClickLabel = if (menu != null) "会话操作" else null, onLongClick = menu?.onOpen, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Avatar(id, name, 48.dp, group = group, src = peerProfile?.avatar)
            if (c.unread > 0) {
                if (c.notifyOff) {
                    // 免打扰：小红点、没有数字（F-CVS-02 规则 5；有 @ 我也是小红点，frontend-chat 第 61 条）。数量在摘要的"[N 条]"里
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).tag("conv-dot").size(10.dp).clip(CircleShape).background(Tt.colors.badgeBg))
                } else {
                    Badge(badgeText(c.unread), "conv-badge", Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(name, Modifier.weight(1f, fill = false).tag("conv-name"), maxLines = 1)
                    if (peerProfile?.official == true) OfficialTag()
                }
                if (last != null) T(formatTime(last.ts, now), Modifier.tag("conv-time"), style = Tt.type.caption, color = Tt.colors.textTertiary, maxLines = 1)
            }
            val danger = Tt.colors.danger
            val summary = when (head) {
                SummaryPrefix.DRAFT -> buildAnnotatedString {
                    withStyle(SpanStyle(color = danger)) { append("[草稿]") }
                    append(" ")
                    append(draft.orEmpty())
                }
                else -> buildAnnotatedString {
                    // "[有人@我]"和"[草稿]"同样用 danger（问题 38）；"[N 条]"和摘要同色（和 H5 相同）
                    if (head == SummaryPrefix.MENTION) {
                        withStyle(SpanStyle(color = danger)) { append("[有人@我]") }
                        append(" ")
                    }
                    if (head == SummaryPrefix.COUNT) append(unreadCountText(c.unread) + " ")
                    if (prefixName != null) append("$prefixName: ")
                    append(summaryText(c, recall))
                }
            }
            if (summary.isNotEmpty() || c.notifyOff) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        summary,
                        Modifier.weight(1f).tag(
                            when (head) {
                                SummaryPrefix.DRAFT -> "draft-tag"
                                SummaryPrefix.MENTION -> "mention-tag"
                                SummaryPrefix.COUNT -> "unread-count-tag"
                                SummaryPrefix.NONE -> "conv-summary"
                            },
                        ),
                        style = Tt.type.small,
                        color = Tt.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 免打扰图标（F-CVS-02 规则 5），有可读名称（AC-CVS-02-3）
                    if (c.notifyOff) Icon(TtIcons.notifyOff, "免打扰", Modifier.size(16.dp).tag("notify-off-icon"), tint = Tt.colors.textTertiary)
                }
            }
        }
    }
    if (menu != null) {
        DropdownMenu(menu.open, menu.onDismiss, Modifier.tag("conv-menu"), offset = DpOffset(72.dp, (-8).dp), containerColor = Tt.colors.surface) {
            for (a in ConvAction.entries) {
                DropdownMenuItem(
                    text = { T(convActionLabel(c, a), color = if (a == ConvAction.DELETE) Tt.colors.danger else Tt.colors.textPrimary) },
                    onClick = { menu.onPick(a) },
                    modifier = Modifier.tag("conv-menu-" + a.name.lowercase()).heightIn(min = TtDimen.sizeTouchMin.dp),
                )
            }
        }
    }
    }
    HorizontalDivider(color = Tt.colors.divider)
}

/** 发起单聊（F-UI-05、F-CONV-03）：好友制下对方不是好友时提示，不阻断（规则 4）。 */
@Composable
private fun StartChatDialog(onClose: () -> Unit) {
    val im: ImSession = LocalIm.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var warnFor by rememberSaveable { mutableStateOf<String?>(null) }
    val target = value.trim()

    fun open(id: String) {
        onClose()
        nav.go(chatPath(c2cConvId(im.self, id)))
    }

    fun start() {
        if (target.isEmpty()) return
        if (!USER_ID_RE.matches(target)) {
            error = defaultMessage(4004)
            return
        }
        scope.launch {
            val warn = im.settings.mode == SocialMode.FRIENDS && target != im.self && onMain { im.client.relationOf(target) } != Relation.FRIEND
            if (warn) warnFor = target else open(target)
        }
    }

    val warned = warnFor
    TtDialog(
        tag = "start-chat-dialog",
        onDismiss = onClose,
        title = "发起单聊",
        confirm = {
            if (warned == null) {
                TextButton({ start() }, Modifier.tag("start-chat-submit"), enabled = target.isNotEmpty()) {
                    T("开始聊天", color = if (target.isNotEmpty()) Tt.colors.brand else Tt.colors.textTertiary)
                }
            } else {
                TextButton({
                    onClose()
                    nav.go(applyPath(warned), RouteState(source = ApplySource.OTHER))
                }, Modifier.tag("start-chat-apply")) { T("添加朋友", color = Tt.colors.brand) }
                Spacer(Modifier.width(4.dp))
                TextButton({ open(warned) }, Modifier.tag("start-chat-anyway")) { T("仍然发起", color = Tt.colors.brand) }
            }
        },
        dismiss = {
            if (warned == null) TextButton(onClose, Modifier.tag("start-chat-cancel")) { T("取消", color = Tt.colors.textSecondary) }
        },
    ) {
        TtInput(
            value,
            {
                value = it
                error = ""
                warnFor = null
            },
            "start-chat-input",
            placeholder = "输入对方账号",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { start() }),
        )
        if (error.isNotEmpty()) FieldError(error)
        if (warned != null) T("对方不是你的好友，可能无法发送消息", Modifier.tag("start-chat-warn"), style = Tt.type.small, color = Tt.colors.textSecondary)
    }
}

