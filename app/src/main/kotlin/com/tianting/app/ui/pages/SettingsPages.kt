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

/** 黑名单（PRD-ui F-UI-29）：按 createdAt 倒序（SDK 排好）；拉黑入口只在资料卡。 */
@Composable
fun BlocksPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    var busy by remember { mutableStateOf<String?>(null) }
    Page {
        TopBar(title = "黑名单", onBack = { nav.back() })
        if (im.blocks.isEmpty()) {
            EmptyState("黑名单为空", "blocks-empty")
        } else {
            LazyColumn(Modifier.weight(1f).tag("blocks")) {
                items(im.blocks, key = { it.userId }) { b ->
                    TtRow(Modifier.semantics { dataUser = b.userId }, tag = "block-row", onClick = { nav.go(userPath(b.userId)) }) {
                        Avatar(b.userId, b.displayName, 40.dp, src = b.profile?.avatar)
                        T(b.displayName, Modifier.weight(1f), maxLines = 1)
                        SecondaryButton("移出", Modifier.tag("block-remove"), enabled = busy != b.userId) {
                            busy = b.userId
                            im.launchCmd({ e ->
                                busy = null
                                app.toast.show(messageFor(errCode(e), "unblock"))
                            }) {
                                im.client.unblock(b.userId)
                                busy = null
                                app.toast.show("已移出黑名单")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 隐私设置（PRD-ui F-UI-30）：请求期间开关禁用，失败回到原值；其他端修改后实时更新。 */
@Composable
fun PrivacyPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    var pending by remember { mutableStateOf<Boolean?>(null) }
    Page {
        TopBar(title = "隐私", onBack = { nav.back() })
        TtRow {
            T("允许通过账号搜索到我", Modifier.weight(1f))
            Switch(
                checked = pending ?: im.settings.searchable,
                enabled = pending == null,
                onCheckedChange = { next ->
                    pending = next
                    im.launchCmd({ e ->
                        pending = null
                        app.toast.show(messageFor(errCode(e), "setPrivacy"))
                    }) {
                        im.client.setSearchable(next)
                        pending = null
                    }
                },
                modifier = Modifier.tag("privacy-switch"),
                colors = SwitchDefaults.colors(checkedTrackColor = Tt.colors.brandFill, checkedThumbColor = Tt.colors.onBrand, uncheckedBorderColor = Tt.colors.borderInput, uncheckedThumbColor = Tt.colors.borderInput, uncheckedTrackColor = Tt.colors.surface),
            )
        }
        T(
            "关闭后，其他人无法通过账号搜索到你；已经是朋友、同在一个群聊或和你聊过天的人仍可以看到你的资料",
            Modifier.padding(16.dp),
            style = Tt.type.small,
            color = Tt.colors.textSecondary,
        )
    }
}

/** 群聊列表（PRD-ui F-UI-32）：每次进入重新加载；不受会话列表 500 个上限影响。 */
@Composable
fun GroupsPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    var groups by remember { mutableStateOf<List<MyGroup>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val connected = im.state == ConnState.CONNECTED
    LaunchedEffect(connected, attempt) {
        if (!connected) return@LaunchedEffect
        try {
            groups = im.client.loadMyGroups()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val sorted = remember(groups) {
        val cmp = compareNames(im.collator)
        groups.orEmpty().sortedWith { a, b -> cmp.compare(a.name, b.name).takeIf { it != 0 } ?: a.groupId.compareTo(b.groupId) }
    }
    Page {
        TopBar(title = "群聊", onBack = { nav.back() })
        val list = groups
        when {
            failed -> T(
                "加载失败，点击重试",
                Modifier.tag("groups-retry").fillMaxWidth().clickable {
                    failed = false
                    groups = null
                    attempt++
                }.padding(24.dp),
                color = Tt.colors.brand,
                align = TextAlign.Center,
            )
            list == null -> RowsSkeleton("groups-skeleton")
            list.isEmpty() -> EmptyState("还没有加入群聊", "groups-empty")
            else -> LazyColumn(Modifier.weight(1f).tag("groups")) {
                item("count") { T("${list.size} 个群聊", Modifier.tag("groups-count").padding(16.dp, 8.dp), style = Tt.type.caption, color = Tt.colors.textSecondary) }
                items(sorted, key = { it.groupId }) { g ->
                    TtRow(Modifier.semantics { dataUser = g.groupId }, tag = "group-row", onClick = { nav.go(chatPath(groupConvId(g.groupId))) }) {
                        Avatar(g.groupId, g.name, 40.dp, group = true)
                        T(g.name, Modifier.weight(1f).tag("group-name"), maxLines = 1)
                        T("${g.memberCount} 人", Modifier.tag("group-count"), style = Tt.type.small, color = Tt.colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** 我（PRD-ui F-UI-15）：一期不提供资料编辑。显示名来自 users 缓存里的自己。 */
@Composable
fun MePage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val profile = rememberProfile(im.self)
    val name = profile?.nickname?.ifEmpty { null } ?: im.self
    Page {
        TopBar(title = "我")
        ConnBanner()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TtRow(minHeight = 96.dp) {
                Avatar(im.self, name, 64.dp, src = profile?.avatar)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(name, Modifier.weight(1f, fill = false).tag("me-name"), style = Tt.type.large)
                        if (im.official) OfficialTag()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("账号：${im.self}", Modifier.weight(1f, fill = false).tag("me-id"), style = Tt.type.small, color = Tt.colors.textSecondary)
                        CopyButton(im.self, "me-copy")
                    }
                }
            }
            TtRow(tag = "me-settings", onClick = { nav.go("/me/settings") }) { T("设置") }
        }
        TabBar("/me")
    }
}

/** 设置（PRD-ui F-UI-16）。 */
@Composable
fun SettingsPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    var confirm by remember { mutableStateOf(false) }
    Page {
        TopBar(title = "设置", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TtRow(tag = "settings-privacy", onClick = { nav.go("/me/settings/privacy") }) { T("隐私") }
            TtRow(tag = "settings-blocks", onClick = { nav.go("/me/settings/blocks") }) {
                T("黑名单", Modifier.weight(1f))
                if (im.blocks.isNotEmpty()) T("${im.blocks.size}", Modifier.tag("settings-blocks-count"), color = Tt.colors.textSecondary)
            }
            TtRow(tag = "settings-appearance", onClick = { nav.go("/me/settings/appearance") }) { T("外观") }
            Column(Modifier.tag("about").padding(top = 12.dp)) {
                TtRow { T("关于天听") }
                TtRow { T("版本 ${com.tianting.app.BuildConfig.VERSION_NAME}（构建 ${com.tianting.app.BuildConfig.BUILD_ID}）", Modifier.tag("app-version"), style = Tt.type.small, color = Tt.colors.textSecondary) }
                TtRow { T("图标：Lucide（ISC 许可）", style = Tt.type.small, color = Tt.colors.textSecondary) }
            }
            Spacer(Modifier.height(12.dp))
            TtRow(tag = "logout", onClick = { confirm = true }) {
                T("退出登录", Modifier.fillMaxWidth(), color = Tt.colors.danger, align = TextAlign.Center)
            }
        }
    }
    if (confirm) {
        val pending = im.pendingCount
        Confirm(
            if (pending > 0) "有 $pending 条消息未发送，退出后将丢失" else "确定退出登录？",
            "退出",
            "logout-dialog",
            danger = true,
            onCancel = { confirm = false },
            onOk = { app.logout() },
        )
    }
}

/** 外观（F-UI-16、F-UI-22）：跟随系统（默认）/ 浅色 / 深色，存在 ui.theme。 */
@Composable
fun AppearancePage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    Page {
        TopBar(title = "外观", onBack = { nav.back() })
        for ((pref, label) in listOf(ThemePref.SYSTEM to "跟随系统", ThemePref.LIGHT to "浅色", ThemePref.DARK to "深色")) {
            TtRow(tag = "appearance-${pref.wire}", onClick = { app.setTheme(pref) }) {
                T(label, Modifier.weight(1f))
                RadioButton(
                    selected = app.themePref == pref,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Tt.colors.brand, unselectedColor = Tt.colors.borderInput),
                )
            }
        }
    }
}
