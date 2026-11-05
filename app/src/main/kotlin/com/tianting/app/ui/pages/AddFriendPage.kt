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

/** "复制"按钮（AC-UI-15-1、F-UI-26 规则 1）。 */
@Composable
internal fun CopyButton(text: String, tag: String) {
    val app = LocalApp.current
    LinkButton("复制", Modifier.tag(tag)) { app.copy(text) }
}

private class SearchOutcome(val user: SearchedUser?)

/** 添加朋友（PRD-ui F-UI-26）：只做精确匹配。不存在、不可被搜索、被封禁，界面完全相同。 */
@Composable
fun AddFriendPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    var keyword by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchOutcome?>(null) }
    var searching by remember { mutableStateOf(false) }

    fun submit() {
        val kw = keyword.trim()
        if (kw.isEmpty() || searching) return
        if (!USER_ID_RE.matches(kw)) {
            error = defaultMessage(4004)
            result = null
            return
        }
        searching = true
        error = ""
        im.launchCmd({ e ->
            searching = false
            result = null
            error = messageFor(errCode(e), "userSearch")
        }) {
            val user = im.client.searchUser(kw)
            if (user != null) im.profiles[user.userId] = UserProfile(user.userId, user.nickname, user.avatar, user.official)
            result = SearchOutcome(user)
            searching = false
        }
    }

    Page {
        TopBar(title = "添加朋友", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.background(Tt.colors.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TtInput(
                    keyword,
                    { keyword = it },
                    "search-input",
                    placeholder = "输入对方账号",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
                if (error.isNotEmpty()) FieldError(error, "search-error")
            }
            TtRow {
                T("我的账号：${im.self}", Modifier.weight(1f), style = Tt.type.small, color = Tt.colors.textSecondary)
                CopyButton(im.self, "search-copy")
            }
            if (searching) {
                T("搜索中…", Modifier.tag("search-loading").fillMaxWidth().padding(16.dp), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
            }
            val r = result
            if (!searching && r != null) {
                Column(Modifier.tag("search-result").fillMaxWidth()) {
                    if (r.user != null) ResultCard(r.user) else T("未找到该用户", Modifier.fillMaxWidth().padding(24.dp), color = Tt.colors.textSecondary, align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(user: SearchedUser) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val local = rememberRelation(user.userId)
    val relation = if (local != null && local != Relation.NONE) local else user.relation // 通过验证、申请之后按本地最新状态
    val name = im.nameOf(user.userId).let { if (it == user.userId && user.nickname.isNotEmpty()) user.nickname else it }
    val state = RouteState(source = ApplySource.SEARCH, profile = Profile(user.nickname, user.avatar, user.official))
    TtRow(tag = "search-card", onClick = { nav.go(userPath(user.userId), state) }, minHeight = 72.dp) {
        Avatar(user.userId, name, 48.dp, src = user.avatar)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                T(name, Modifier.weight(1f, fill = false), maxLines = 1)
                if (user.official) OfficialTag()
            }
            T("账号：${user.userId}", style = Tt.type.small, color = Tt.colors.textSecondary)
            when (relation) {
                Relation.SELF -> T("这是你自己", style = Tt.type.small, color = Tt.colors.textSecondary)
                Relation.PENDING_OUT -> T("已发送申请", style = Tt.type.small, color = Tt.colors.textSecondary)
                Relation.BLOCKED -> T("已加入黑名单", style = Tt.type.small, color = Tt.colors.textSecondary)
                else -> Unit
            }
        }
        when (relation) {
            Relation.FRIEND -> PrimaryButton("发消息", Modifier.tag("search-chat")) { nav.go(chatPath(c2cConvId(im.self, user.userId))) }
            Relation.PENDING_IN -> PrimaryButton("通过验证", Modifier.tag("search-accept")) {
                im.launchCmd({ app.toast.show(messageFor(errCode(it), "friendRespond")) }) { im.client.respondFriend(user.userId, FriendAction.ACCEPT) }
            }
            Relation.PENDING_OUT -> SecondaryButton("重新发送", Modifier.tag("search-reapply")) { nav.go(applyPath(user.userId), state) }
            Relation.BLOCKED -> SecondaryButton("查看资料", Modifier.tag("search-profile")) { nav.go(userPath(user.userId), state) }
            Relation.NONE -> PrimaryButton("添加到通讯录", Modifier.tag("search-add")) { nav.go(applyPath(user.userId), state) }
            else -> Unit
        }
    }
}
