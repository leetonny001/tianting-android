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

/** 通讯录（PRD-ui F-UI-13）：新的朋友入口 + 群聊入口 + 好友列表。开放模式下也只显示好友。 */
@Composable
fun ContactsPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val groups = remember(im.friends) { groupByPinyin(im.friends, im.collator, { it.displayName }, { it.userId }) }
    Page {
        TopBar(title = "通讯录", right = { IconBtn(TtIcons.addFriend, "添加朋友", "contacts-add", { nav.go("/contacts/add") }) })
        ConnBanner()
        LazyColumn(Modifier.weight(1f).tag("contacts")) {
            item("requests") {
                TtRow(tag = "entry-requests", onClick = { nav.go("/contacts/requests") }) {
                    EntryIcon(TtIcons.newFriend)
                    T("新的朋友", Modifier.weight(1f))
                    if (im.requests.unseen > 0) Badge(badgeText(im.requests.unseen.toLong()), "requests-badge")
                }
            }
            item("groups") {
                TtRow(tag = "entry-groups", onClick = { nav.go("/contacts/groups") }) {
                    EntryIcon(TtIcons.groups)
                    T("群聊", Modifier.weight(1f))
                }
            }
            if (!im.socialLoaded) {
                item("skeleton") { RowsSkeleton("contacts-skeleton") }
            } else if (im.friends.isEmpty()) {
                item("empty") {
                    EmptyState("还没有朋友", "contacts-empty") {
                        PrimaryButton("添加朋友", Modifier.tag("contacts-empty-add")) { nav.go("/contacts/add") }
                    }
                }
            } else {
                for (g in groups) {
                    item("group-${g.group}") {
                        T(g.group, Modifier.tag("group-${g.group}").fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = Tt.type.caption, color = Tt.colors.textSecondary)
                    }
                    items(g.items, key = { "f-${it.userId}" }) { FriendRow(it) }
                }
                item("count") {
                    T("${im.friends.size} 位朋友", Modifier.tag("contacts-count").fillMaxWidth().padding(16.dp), style = Tt.type.caption, color = Tt.colors.textTertiary, align = TextAlign.Center)
                }
            }
        }
        TabBar("/contacts")
    }
}

@Composable
private fun EntryIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(TtDimen.radiusMd.dp)).background(Tt.colors.brandFill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Tt.colors.onBrand)
    }
}

@Composable
private fun FriendRow(f: Friend) {
    val nav = LocalNav.current
    TtRow(Modifier.semantics { dataUser = f.userId }, tag = "friend-row", onClick = { nav.go(userPath(f.userId)) }) {
        Avatar(f.userId, f.displayName, 40.dp, src = f.profile?.avatar)
        T(f.displayName, Modifier.weight(1f, fill = false).tag("friend-name"), maxLines = 1)
        if (f.profile?.official == true) OfficialTag()
        if (f.blocked) T("已拉黑", style = Tt.type.caption, color = Tt.colors.textTertiary)
    }
}
