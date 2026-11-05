package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tianting.app.core.ImSession
import com.tianting.app.core.MESSAGE_DELETED
import com.tianting.app.core.RouteState
import com.tianting.app.core.USER_ID_RE
import com.tianting.app.core.applyPath
import com.tianting.app.core.chatPath
import com.tianting.app.core.defaultMessage
import com.tianting.app.core.formatTime
import com.tianting.app.ui.Avatar
import com.tianting.app.ui.Badge
import com.tianting.app.ui.ConnBanner
import com.tianting.app.ui.EmptyState
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.IconBtn
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
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtDimen
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.ApplySource
import com.tianting.sdk.ConvType
import com.tianting.sdk.Conversation
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
                        ConvItem(c, now) { nav.go(chatPath(c.convId)) }
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
}

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

/** 摘要（F-UI-04 规则 5）：提示消息用服务端摘要；custom 和不认识的类型统一文案；被删除的不带前缀。 */
internal fun summaryText(c: Conversation): String {
    val m = c.lastMsg ?: return ""
    if (m.deleted) return MESSAGE_DELETED
    return if (m.type == MsgType.TEXT || m.type == MsgType.TIP) m.text.orEmpty() else "[暂不支持的消息]"
}

@Composable
private fun ConvItem(c: Conversation, now: Long, onClick: () -> Unit) {
    val im = LocalIm.current
    val group = c.type == ConvType.GROUP
    val peer = peerId(c, im.self)
    val id = peer ?: c.convId.substringAfter(':')
    val remark = peer?.let { p -> im.friends.firstOrNull { it.userId == p }?.remark?.takeIf(String::isNotEmpty) }
    val name = remark ?: c.name
    val last = c.lastMsg
    val draft = im.drafts[c.convId]
    val prefix = last?.from?.takeIf { group && it != im.self && last.type != MsgType.TIP && !last.deleted }
    val prefixName = prefix?.let { rememberDisplayName(it) }
    val peerProfile = peer?.let { rememberProfile(it) }
    Row(
        Modifier
            .tag("conv-item")
            .semantics { dataConvId = c.convId }
            .fillMaxWidth()
            .heightIn(min = TtDimen.sizeConvItem.dp)
            .background(Tt.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Avatar(id, name, 48.dp, group = group, src = peerProfile?.avatar)
            if (c.unread > 0) Badge(badgeText(c.unread), "conv-badge", Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
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
            val summary = when {
                draft != null -> buildAnnotatedString {
                    withStyle(SpanStyle(color = danger)) { append("[草稿]") }
                    append(" ")
                    append(draft)
                }
                else -> buildAnnotatedString {
                    if (prefixName != null) append("$prefixName: ")
                    append(summaryText(c))
                }
            }
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    Modifier.tag(if (draft != null) "draft-tag" else "conv-summary"),
                    style = Tt.type.small,
                    color = Tt.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            val warn = im.settings.mode == SocialMode.FRIENDS && target != im.self && im.client.relationOf(target) != Relation.FRIEND
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

