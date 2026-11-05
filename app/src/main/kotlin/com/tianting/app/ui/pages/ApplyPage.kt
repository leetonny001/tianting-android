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

private fun textLen(s: String) = countCodePoints(trimText(s))

/** 申请添加朋友（PRD-ui F-UI-27）。不论对方是否存在、是否拉黑了我，pending 时表现完全相同。 */
@Composable
fun ApplyPage(userId: String, state: RouteState) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    if (userId == im.self) {
        LaunchedEffect(Unit) {
            app.toast.show("不能添加自己")
            nav.back()
        }
        return
    }
    val me = rememberProfile(im.self)
    val peer = rememberProfile(userId)
    val nickname = peer?.nickname?.ifEmpty { null } ?: state.profile?.nickname.orEmpty()
    val myName = me?.nickname?.ifEmpty { null } ?: im.self
    var edited by rememberSaveable { mutableStateOf<String?>(null) } // null：用户还没改过，跟随默认值
    var remark by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val text = edited ?: "我是$myName"
    val messageTooLong = textLen(text) > MAX_APPLY_MESSAGE
    val remarkTooLong = textLen(remark) > MAX_REMARK
    val name = nickname.ifEmpty { userId }

    fun send() {
        if (sending || messageTooLong || remarkTooLong) return
        sending = true
        im.launchCmd({ e ->
            sending = false
            app.toast.show(messageFor(errCode(e), "friendApply"))
        }) {
            val status = im.client.applyFriend(
                userId,
                ApplyFriendOptions(
                    message = trimText(text),
                    source = state.source ?: ApplySource.OTHER,
                    sourceRef = state.sourceRef,
                    remark = trimText(remark).ifEmpty { null },
                ),
            )
            sending = false
            if (status == RequestStatus.ACCEPTED) {
                app.toast.show("已添加到通讯录")
                nav.go(userPath(userId), replace = true)
            } else {
                app.toast.show("已发送")
                nav.back()
            }
        }
    }

    Page {
        TopBar(title = "申请添加朋友", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TtRow(minHeight = 72.dp) {
                Avatar(userId, name, 48.dp, src = peer?.avatar ?: state.profile?.avatar)
                T(name, Modifier.tag("apply-name"), style = Tt.type.title)
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("发送添加朋友申请", style = Tt.type.small, color = Tt.colors.textSecondary)
                TtInput(text, { edited = it }, "apply-message", singleLine = false, minLines = 3)
                T("${textLen(text)} / $MAX_APPLY_MESSAGE", Modifier.tag("apply-count").align(Alignment.End), style = Tt.type.caption, color = Tt.colors.textTertiary)
                if (messageTooLong) FieldError("最多 $MAX_APPLY_MESSAGE 个字", "apply-message-error")
                T("设置备注", style = Tt.type.small, color = Tt.colors.textSecondary)
                TtInput(remark, { remark = it }, "apply-remark", placeholder = name)
                if (remarkTooLong) FieldError("最多 $MAX_REMARK 个字", "apply-remark-error")
                PrimaryButton(if (sending) "发送中…" else "发送", Modifier.fillMaxWidth().tag("apply-send"), enabled = !sending && !messageTooLong && !remarkTooLong) { send() }
            }
        }
    }
}

/** 设置备注（PRD-ui F-UI-28）：只有我这一侧的好友可以进入。备注只在我的各端显示。 */
@Composable
fun RemarkPage(userId: String) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val friend = im.friends.firstOrNull { it.userId == userId }
    if (friend == null) {
        if (im.socialLoaded) {
            LaunchedEffect(Unit) { nav.back() }
        }
        return
    }
    val current = friend.remark
    var value by rememberSaveable(userId) { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    val tooLong = textLen(value) > MAX_REMARK

    fun done() {
        if (busy || tooLong) return
        val next = trimText(value)
        if (next == current) {
            nav.back()
            return
        }
        busy = true
        im.launchCmd({ e ->
            busy = false
            app.toast.show(messageFor(errCode(e), "friendRemark"))
            nav.back()
        }) {
            im.client.setFriendRemark(userId, next)
            busy = false
            nav.back()
        }
    }

    Page {
        TopBar(title = "设置备注", onBack = { nav.back() }, right = { TopBarAction("完成", "remark-done", enabled = !tooLong) { done() } })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TtInput(value, { value = it }, "remark-input", placeholder = friend.profile?.nickname.orEmpty())
            if (tooLong) FieldError("最多 $MAX_REMARK 个字", "remark-error")
        }
    }
}
