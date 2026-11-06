package com.tianting.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.tianting.app.core.AppModel
import com.tianting.app.core.ImSession
import com.tianting.app.core.Navigator
import com.tianting.app.core.decodeParam
import com.tianting.app.core.fatalText
import com.tianting.app.core.isGroupConv
import com.tianting.app.ui.pages.AddFriendPage
import com.tianting.app.ui.pages.AppearancePage
import com.tianting.app.ui.pages.ApplyPage
import com.tianting.app.ui.pages.BlocksPage
import com.tianting.app.ui.pages.ChatInfoPage
import com.tianting.app.ui.pages.ChatPage
import com.tianting.app.ui.pages.ChatsPage
import com.tianting.app.ui.pages.ContactsPage
import com.tianting.app.ui.pages.GroupInvitePage
import com.tianting.app.ui.pages.GroupMembersPage
import com.tianting.app.ui.pages.GroupNewPage
import com.tianting.app.ui.pages.GroupNoticePage
import com.tianting.app.ui.pages.GroupsPage
import com.tianting.app.ui.pages.LoginPage
import com.tianting.app.ui.pages.MePage
import com.tianting.app.ui.pages.NoticeBannerHost
import com.tianting.app.ui.pages.NoticesPage
import com.tianting.app.ui.pages.PrivacyNoticePage
import com.tianting.app.ui.pages.PrivacyPage
import com.tianting.app.ui.pages.RecoveryPage
import com.tianting.app.ui.pages.RemarkPage
import com.tianting.app.ui.pages.RequestsPage
import com.tianting.app.ui.pages.SettingsPage
import com.tianting.app.ui.pages.UserPage
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TiantingTheme
import com.tianting.app.ui.theme.resolveDark

val LocalApp = compositionLocalOf<AppModel> { error("no AppModel") }
val LocalIm = compositionLocalOf<ImSession> { error("not logged in") }
val LocalNav = compositionLocalOf<Navigator> { error("no Navigator") }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(model: AppModel) {
    TiantingTheme(resolveDark(model.themePref)) {
        CompositionLocalProvider(LocalApp provides model, LocalNav provides model.nav) {
            // 根背景用 surface：状态栏、导航条区域和顶栏、标签栏连成一片。
            Box(Modifier.fillMaxSize().background(if (model.im == null && !model.authTopBar && model.recovery == null) Tt.colors.bg else Tt.colors.surface).semantics { testTagsAsResourceId = true }) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val im = model.im
                    val recovery = model.recovery
                    if (recovery != null) {
                        RecoveryPage(recovery) // 确认前进不了其他页面（AC-ACC-01-12）
                    } else if (im == null) {
                        LoginPage()
                    } else {
                        CompositionLocalProvider(LocalIm provides im) {
                            Pages(model.nav)
                            NoticeBannerHost(im, model.nav)
                        }
                    }
                    if (im != null) KickedDialog(im, model)
                    SessionEndedDialog(model)
                    if (recovery == null && im != null) PrivacyUpdateDialog(model)
                    ToastHost(model.toast)
                }
            }
        }
    }
}

/** 路由表（PRD-ui 3.2 节）。只组合栈顶页面；SaveableStateHolder 保留被盖住页面的滚动位置。 */
@Composable
private fun Pages(nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    val entry = nav.current
    BackHandler(enabled = nav.stack.size > 1 || entry.path != "/chats") { nav.back() }
    holder.SaveableStateProvider(entry.id) { Route(entry) }
}

@Composable
private fun Route(entry: Navigator.Entry) {
    val seg = entry.path.trim('/').split('/')
    val nav = LocalNav.current
    when {
        entry.path == "/chats" -> ChatsPage()
        entry.path == "/contacts" -> ContactsPage()
        entry.path == "/me" -> MePage()
        entry.path == "/groups/new" -> GroupNewPage()
        entry.path == "/contacts/requests" -> RequestsPage()
        entry.path == "/contacts/groups" -> GroupsPage()
        entry.path == "/contacts/add" -> AddFriendPage()
        entry.path == "/me/notices" -> NoticesPage()
        entry.path == "/me/settings" -> SettingsPage()
        entry.path == "/me/settings/blocks" -> BlocksPage()
        entry.path == "/me/settings/privacy" -> PrivacyPage()
        entry.path == "/me/settings/appearance" -> AppearancePage()
        entry.path == "/me/settings/privacy-notice" -> PrivacyNoticePage(LocalApp.current.cachedConfig?.privacyNotice?.url) { nav.back() }
        seg[0] == "contacts" && seg.getOrNull(1) == "apply" && seg.size == 3 -> ApplyPage(decodeParam(seg[2]), entry.state)
        seg[0] == "user" && seg.size == 2 -> UserPage(decodeParam(seg[1]), entry.state)
        seg[0] == "user" && seg.size == 3 && seg[2] == "remark" -> RemarkPage(decodeParam(seg[1]))
        seg[0] == "chat" && seg.size == 2 -> ChatPage(decodeParam(seg[1]))
        seg[0] == "chat" && seg.size == 3 && seg[2] == "info" -> ChatInfoPage(decodeParam(seg[1]))
        seg[0] == "chat" && seg.size == 3 && isGroupConv(decodeParam(seg[1])) -> {
            val convId = decodeParam(seg[1])
            when (seg[2]) {
                "notice" -> GroupNoticePage(convId)
                "members" -> GroupMembersPage(convId, entry.state.mode)
                "invite" -> GroupInvitePage(convId)
                else -> nav.setRootLater()
            }
        }
        else -> nav.setRootLater()
    }
}

@Composable
private fun Navigator.setRootLater() {
    androidx.compose.runtime.LaunchedEffect(Unit) { setRoot("/chats") }
}

/** 被踢与登录失效弹窗（F-UI-18）：系统返回键、手势、点外部都不关闭（3.6 节 AC-UI-18-1），只有"确定"。 */
@Composable
private fun KickedDialog(im: ImSession, model: AppModel) {
    val fatal = im.fatal ?: return
    TtDialog(
        tag = "kicked-dialog",
        onDismiss = {},
        dismissible = false,
        confirm = { TextButton({ model.logout(keepNoticeSeen = true) }, Modifier.tag("kicked-ok")) { T("确定", color = Tt.colors.brand) } },
    ) { T(fatalText(fatal)) }
}

/** 还没有连接时会话结束（登录页、确认前的保存页）：同样只有"确定"（F-ACC-03 规则 6、6a）。 */
@Composable
private fun SessionEndedDialog(model: AppModel) {
    val text = model.endedText ?: return
    TtDialog(
        tag = "kicked-dialog",
        onDismiss = {},
        dismissible = false,
        confirm = { TextButton({ model.dismissEnded() }, Modifier.tag("kicked-ok")) { T("确定", color = Tt.colors.brand) } },
    ) { T(text) }
}

/** 隐私说明版本更新（F-ACC-10 规则 2，AC-ACC-10-3）：提示一次，不强制。 */
@Composable
private fun PrivacyUpdateDialog(model: AppModel) {
    if (model.privacyUpdate == null) return
    TtDialog(
        tag = "privacy-update",
        onDismiss = { model.dismissPrivacyUpdate() },
        confirm = {
            TextButton({
                model.confirmPrivacyUpdate()
                model.nav.go("/me/settings/privacy-notice")
            }, Modifier.tag("privacy-update-view")) { T("查看", color = Tt.colors.brand) }
        },
        dismiss = { TextButton({ model.confirmPrivacyUpdate() }, Modifier.tag("privacy-update-ok")) { T("知道了", color = Tt.colors.textSecondary) } },
    ) { T("隐私说明已更新") }
}
