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

/** 拉黑确认框的说明（F-UI-14）；举报成功后询问拉黑也用它（frontend-chat 第 79 条）。 */
internal const val BLOCK_TEXT = "加入黑名单后，你将不再收到对方的私聊消息和好友申请；在共同的群聊里仍能看到对方的发言"

private enum class UserConfirm { BLOCK, DELETE }

/**
 * 资料卡（PRD-ui F-UI-14）。资料来源：好友 / 申请 / 黑名单项的 profile → users 缓存 → 路由状态 → 只显示 userId。
 * 不在可见范围内的用户 users 不返回，页面与不存在的用户结构相同，不显示"用户不存在"。
 */
@Composable
fun UserPage(userId: String, state: RouteState) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val relation = rememberRelation(userId)
    val cached = rememberProfile(userId)
    val friend = im.friends.firstOrNull { it.userId == userId }
    val block = im.blocks.firstOrNull { it.userId == userId }
    val request = im.requests.items.firstOrNull { it.userId == userId && it.profile != null }
    val profile = friend?.profile ?: request?.profile ?: block?.profile ?: cached?.let { Profile(it.nickname, it.avatar, it.official) } ?: state.profile
    val nickname = profile?.nickname.orEmpty()
    val name = friend?.remark?.ifEmpty { null } ?: nickname.ifEmpty { userId }
    val official = profile?.official == true
    val isFriend = friend != null
    val open = im.settings.mode == SocialMode.OPEN
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<UserConfirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val canChat = when (relation) {
        Relation.FRIEND -> true
        Relation.BLOCKED -> open || isFriend
        Relation.PENDING_IN, Relation.PENDING_OUT -> open
        Relation.NONE -> open || official
        else -> false
    }

    fun run(cmd: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        im.launchCmd({ e ->
            busy = false
            app.toast.show(messageFor(errCode(e), cmd))
        }) {
            block()
            busy = false
        }
    }

    val applyState = RouteState(
        source = state.source ?: ApplySource.CARD,
        sourceRef = state.sourceRef,
        profile = profile?.let { Profile(nickname, it.avatar, official) },
    )
    val toApply = { nav.go(applyPath(userId), applyState) }
    val toChat = { nav.go(chatPath(c2cConvId(im.self, userId))) }
    Page {
        TopBar(
            onBack = { nav.back() },
            right = {
                if (relation != null && relation != Relation.SELF) {
                    Box {
                        IconBtn(TtIcons.more, "更多", "user-more", { menu = true })
                        DropdownMenu(menu, { menu = false }, Modifier.tag("user-menu"), containerColor = Tt.colors.surface) {
                            if (isFriend) {
                                DropdownMenuItem({ T("设置备注") }, {
                                    menu = false
                                    nav.go(remarkPath(userId))
                                }, Modifier.tag("menu-remark"))
                            }
                            if (relation == Relation.BLOCKED) {
                                DropdownMenuItem({ T("移出黑名单") }, {
                                    menu = false
                                    run("unblock") { im.client.unblock(userId) }
                                }, Modifier.tag("menu-unblock"))
                            } else {
                                DropdownMenuItem({ T("加入黑名单") }, {
                                    menu = false
                                    confirm = UserConfirm.BLOCK
                                }, Modifier.tag("menu-block"))
                            }
                            if (isFriend) {
                                DropdownMenuItem({ T("删除朋友") }, {
                                    menu = false
                                    confirm = UserConfirm.DELETE
                                }, Modifier.tag("menu-delete-friend"))
                            }
                            // 举报用户（F-MOD-03 规则 8）：features.moderation 打开才有，对自己不显示（菜单本来就只对别人有，frontend-chat 第 76、77 条）
                            if (im.moderation) {
                                DropdownMenuItem({ T("举报") }, {
                                    menu = false
                                    reporting = true
                                }, Modifier.tag("menu-report"))
                            }
                        }
                    }
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TtRow(tag = "user-head", minHeight = 96.dp) {
                Avatar(userId, name, 64.dp, src = profile?.avatar)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(name, Modifier.weight(1f, fill = false).tag("user-name"), style = Tt.type.large)
                        if (official) OfficialTag()
                    }
                    if (!friend?.remark.isNullOrEmpty() && nickname.isNotEmpty()) T("昵称：$nickname", Modifier.tag("user-nickname"), style = Tt.type.small, color = Tt.colors.textSecondary)
                    T("账号：$userId", Modifier.tag("user-id"), style = Tt.type.small, color = Tt.colors.textSecondary)
                    if (relation == Relation.BLOCKED) T("已加入黑名单", Modifier.tag("user-blocked"), style = Tt.type.small, color = Tt.colors.textSecondary)
                }
            }
            Column(Modifier.tag("user-actions").padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (relation) {
                    Relation.PENDING_IN -> PrimaryButton("通过验证", Modifier.fillMaxWidth().tag("user-accept"), enabled = !busy) { run("friendRespond") { im.client.respondFriend(userId, FriendAction.ACCEPT) } }
                    Relation.PENDING_OUT -> {
                        T("已发送申请，等待验证", Modifier.fillMaxWidth(), color = Tt.colors.textSecondary, align = TextAlign.Center)
                        SecondaryButton("重新发送", Modifier.fillMaxWidth().tag("user-reapply")) { toApply() }
                    }
                    Relation.NONE -> PrimaryButton("添加到通讯录", Modifier.fillMaxWidth().tag("user-add")) { toApply() }
                    else -> Unit
                }
                if (relation != null && relation != Relation.SELF && canChat) {
                    if (relation == Relation.FRIEND) PrimaryButton("发消息", Modifier.fillMaxWidth().tag("user-chat")) { toChat() } else SecondaryButton("发消息", Modifier.fillMaxWidth().tag("user-chat")) { toChat() }
                }
                if (isFriend && relation == Relation.FRIEND) SecondaryButton("设置备注", Modifier.fillMaxWidth().tag("user-remark")) { nav.go(remarkPath(userId)) }
            }
        }
    }
    // 举报用户成功后询问拉黑；已在黑名单的不问（frontend-chat 第 79 条，ReportSheet 按黑名单再查）。
    if (reporting) ReportSheet(ReportRequest(ReportTarget.User(userId), askBlock = userId)) { reporting = false }
    when (confirm) {
        UserConfirm.BLOCK -> Confirm(BLOCK_TEXT, "确定", "block-dialog", onCancel = { confirm = null }, onOk = {
            confirm = null
            run("block") { im.client.block(userId) }
        })
        UserConfirm.DELETE -> Confirm(
            "确定删除朋友\"$name\"？" + if (im.settings.mode == SocialMode.FRIENDS) "\n删除后你们将无法继续私聊" else "",
            "删除",
            "delete-dialog",
            danger = true,
            onCancel = { confirm = null },
            onOk = {
                confirm = null
                run("friendDelete") { im.client.deleteFriend(userId) }
            },
        )
        null -> Unit
    }
}
