package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianting.app.core.*
import com.tianting.app.ui.*
import com.tianting.app.ui.Badge
import com.tianting.app.ui.theme.*
import com.tianting.sdk.*
import com.tianting.sdk.Friend
import kotlinx.coroutines.delay

private val SOURCE_TEXT = mapOf(
    ApplySource.SEARCH to "通过搜索添加",
    ApplySource.GROUP to "通过群聊添加",
    ApplySource.CARD to "通过名片添加",
    ApplySource.CONV to "通过聊天添加",
)

private const val SEEN_DEBOUNCE_MS = 200L

/** 新的朋友（PRD-ui F-UI-25）。进入本页即已读；停留期间收到的新申请同样视为已读（去抖 200ms）。 */
@Composable
fun RequestsPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    var busy by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<FriendRequest?>(null) }
    val unseen = im.requests.unseen
    LaunchedEffect(Unit) { runCatching { onMain { im.client.markFriendRequestsSeen() } } }
    LaunchedEffect(unseen) {
        if (unseen > 0) {
            delay(SEEN_DEBOUNCE_MS)
            runCatching { onMain { im.client.markFriendRequestsSeen() } }
        }
    }
    fun respond(userId: String, action: FriendAction) {
        if (busy != null) return
        busy = userId
        im.launchCmd({ e ->
            busy = null
            val code = errCode(e)
            app.toast.show(messageFor(code, "friendRespond"))
            if (code == 4045) im.launchCmd { im.client.refreshSocial() } // 该项随后显示"已过期"
        }) {
            im.client.respondFriend(userId, action)
            busy = null
        }
    }
    Page {
        TopBar(title = "新的朋友", onBack = { nav.back() })
        if (im.requests.items.isEmpty()) {
            EmptyState("暂无新的朋友", "requests-empty")
        } else {
            LazyColumn(Modifier.weight(1f).tag("requests")) {
                items(im.requests.items, key = { "${it.userId}/${it.direction}" }) { r ->
                    TtRow(
                        Modifier.semantics { dataUser = r.userId },
                        tag = "request-row",
                        onClick = {
                            if (r.direction == RequestDirection.IN && r.status == RequestStatus.PENDING) detail = r else nav.go(userPath(r.userId))
                        },
                        minHeight = 64.dp,
                    ) {
                        Avatar(r.userId, r.displayName, 40.dp, src = r.profile?.avatar)
                        Column(Modifier.weight(1f)) {
                            T(r.displayName, maxLines = 1)
                            T(secondLine(r), Modifier.tag("request-line2"), style = Tt.type.small, color = Tt.colors.textSecondary, maxLines = 1)
                        }
                        RequestState(r, busy == r.userId) { respond(r.userId, FriendAction.ACCEPT) }
                    }
                }
            }
        }
    }
    val d = detail
    if (d != null) {
        TtSheet("request-sheet", { detail = null }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(d.userId, d.displayName, 40.dp, src = d.profile?.avatar)
                Column {
                    T(d.displayName)
                    T("账号：${d.userId}", style = Tt.type.small, color = Tt.colors.textSecondary)
                }
            }
            if (d.message.isNotEmpty()) T(d.message)
            SOURCE_TEXT[d.source]?.let { T(it, Modifier.tag("request-source"), style = Tt.type.small, color = Tt.colors.textSecondary) }
            PrimaryButton("同意", Modifier.fillMaxWidth().tag("sheet-accept")) {
                detail = null
                respond(d.userId, FriendAction.ACCEPT)
            }
            SecondaryButton("拒绝", Modifier.fillMaxWidth().tag("sheet-reject")) {
                detail = null
                respond(d.userId, FriendAction.REJECT)
            }
            SecondaryButton("查看资料", Modifier.fillMaxWidth().tag("sheet-profile")) {
                detail = null
                nav.go(userPath(d.userId))
            }
        }
    }
}

private fun secondLine(r: FriendRequest): String =
    if (r.direction == RequestDirection.IN) r.message.ifEmpty { "请求添加你为朋友" } else if (r.message.isNotEmpty()) "我：${r.message}" else "等待对方验证"

@Composable
private fun RequestState(r: FriendRequest, busy: Boolean, onAccept: () -> Unit) {
    val text = when {
        r.status == RequestStatus.EXPIRED -> "已过期"
        r.status == RequestStatus.ACCEPTED -> "已添加"
        r.direction == RequestDirection.OUT -> "等待验证"
        r.status == RequestStatus.REJECTED -> "已拒绝"
        else -> null
    }
    if (text != null) {
        T(text, Modifier.tag("request-state"), style = Tt.type.small, color = Tt.colors.textTertiary)
    } else {
        PrimaryButton(if (busy) "处理中…" else "同意", Modifier.tag("request-accept"), enabled = !busy, onClick = onAccept)
    }
}
