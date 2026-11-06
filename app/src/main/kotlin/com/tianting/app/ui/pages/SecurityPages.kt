// 账号安全和改昵称（PRD-chat 第 13.3 节，FE-616）。
package com.tianting.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianting.app.core.AccountPage
import com.tianting.app.core.MAX_NICKNAME
import com.tianting.app.core.RECOVERY_LOST_WARNING
import com.tianting.app.core.accountErrorText
import com.tianting.app.core.accountEventText
import com.tianting.app.core.formatDateTime
import com.tianting.app.core.platformText
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.LocalNav
import com.tianting.app.ui.Page
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.RowsSkeleton
import com.tianting.app.ui.T
import com.tianting.app.ui.TopBar
import com.tianting.app.ui.TtDialog
import com.tianting.app.ui.TtInput
import com.tianting.app.ui.TtRow
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.AccountEvents
import com.tianting.sdk.countCodePoints
import kotlinx.coroutines.CancellationException

private val PasswordKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)

/** 账号安全（第 13.3 节）。只有账号会话能进（路由那里判断）。 */
@Composable
fun SecurityPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    var events by remember { mutableStateOf<AccountEvents?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        try {
            events = app.account.listEvents()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    Page {
        TopBar(title = "账号安全", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TtRow(tag = "security-password", onClick = { nav.go("/me/settings/security/password") }) { T("修改密码") }
            TtRow(tag = "security-recovery", onClick = { nav.go("/me/settings/security/recovery") }) { T("重新生成恢复码") }
            TtRow(tag = "security-others", onClick = { nav.go("/me/settings/security/others") }) {
                Column(Modifier.weight(1f)) {
                    T("退出其他设备")
                    events?.otherSessions?.let { o ->
                        val last = o.lastActiveAt?.let { "，最近活跃 ${formatDateTime(it)}" }.orEmpty()
                        T("其他设备 ${o.count} 台$last", Modifier.tag("security-other-count"), style = Tt.type.small, color = Tt.colors.textSecondary)
                    }
                }
            }
            T("最近登录记录", Modifier.padding(16.dp, 12.dp), style = Tt.type.small, color = Tt.colors.textSecondary)
            val list = events
            when {
                failed -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("加载失败", Modifier.tag("security-events-failed"), color = Tt.colors.textSecondary)
                    TextButton({
                        failed = false
                        attempt++
                    }, Modifier.tag("security-events-reload")) { T("重试", color = Tt.colors.brand) }
                }
                list == null -> RowsSkeleton("security-events-loading")
                list.events.isEmpty() -> T("暂无记录", Modifier.tag("security-events-empty").padding(16.dp), color = Tt.colors.textSecondary)
                else -> for (e in list.events) {
                    TtRow(tag = "security-event") {
                        T(accountEventText(e.kind), Modifier.weight(1f))
                        T(
                            listOf(platformText(e.platform), formatDateTime(e.at)).filter { it.isNotEmpty() }.joinToString(" "),
                            style = Tt.type.small,
                            color = Tt.colors.textSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TtRow(tag = "security-delete", onClick = { deleting = true }) {
                T("注销账号", Modifier.fillMaxWidth(), color = Tt.colors.danger, align = TextAlign.Center)
            }
        }
    }
    if (deleting) DeleteDialog { deleting = false }
}

/** 注销确认框（F-ACC-08 规则 1，AC-ACC-08-6）：错误原地提示。成功后 SDK 结束会话，弹窗走 Fatal。 */
@Composable
private fun DeleteDialog(onClose: () -> Unit) {
    val app = LocalApp.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    TtDialog(
        tag = "delete-dialog",
        onDismiss = onClose,
        title = "注销账号",
        confirm = {
            TextButton({
                busy = true
                error = ""
                app.accountOp({ e ->
                    busy = false
                    password = ""
                    error = accountErrorText(e, AccountPage.PASSWORD_CONFIRM)
                }) {
                    app.account.requestDelete(password)
                    onClose()
                }
            }, Modifier.tag("delete-submit"), enabled = password.isNotEmpty() && !busy) { T(if (busy) "提交中…" else "注销", color = if (password.isNotEmpty() && !busy) Tt.colors.danger else Tt.colors.textTertiary) }
        },
        dismiss = { TextButton(onClose, Modifier.tag("delete-cancel")) { T("取消", color = Tt.colors.textSecondary) } },
    ) {
        T("7 天后不可恢复，用户名不能再使用，已发的消息会保留", Modifier.tag("delete-text"))
        T("当前密码", style = Tt.type.small, color = Tt.colors.textSecondary)
        TtInput(password, { password = it }, "delete-password", password = true, keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done))
        if (error.isNotEmpty()) FieldError(error, "delete-error")
    }
}

/** 修改密码（F-ACC-04 规则 2、3）。成功后 AppModel 进保存页；失败原地提示。 */
@Composable
fun ChangePasswordPage() {
    val nav = LocalNav.current
    val app = LocalApp.current
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        TopBar(title = "修改密码", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("当前密码", style = Tt.type.small, color = Tt.colors.textSecondary)
            TtInput(oldPassword, { oldPassword = it }, "password-old", password = true, keyboardOptions = PasswordKeyboard)
            T("新密码", style = Tt.type.small, color = Tt.colors.textSecondary)
            TtInput(newPassword, { newPassword = it }, "password-new", password = true, keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done))
            T("修改后其他设备会下线，并生成新的恢复码", style = Tt.type.small, color = Tt.colors.textSecondary)
            if (error.isNotEmpty()) FieldError(error, "password-error")
            PrimaryButton(if (busy) "提交中…" else "确认修改", Modifier.fillMaxWidth().tag("password-submit"), enabled = oldPassword.isNotEmpty() && newPassword.isNotEmpty() && !busy) {
                busy = true
                error = ""
                app.changePassword(oldPassword, newPassword, onDone = { nav.back() }, onFailed = { e ->
                    busy = false
                    error = accountErrorText(e, AccountPage.PASSWORD_CONFIRM)
                })
            }
        }
    }
}

/** 重新生成恢复码、退出其他设备：都只要当前密码。 */
@Composable
private fun PasswordConfirmPage(title: String, hint: String, button: String, tagPrefix: String, onSubmit: (password: String, onDone: () -> Unit, onFailed: (Throwable) -> Unit) -> Unit) {
    val nav = LocalNav.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        TopBar(title = title, onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(hint, style = Tt.type.small, color = Tt.colors.textSecondary)
            T("当前密码", style = Tt.type.small, color = Tt.colors.textSecondary)
            TtInput(password, { password = it }, "$tagPrefix-password", password = true, keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done))
            if (error.isNotEmpty()) FieldError(error, "$tagPrefix-error")
            PrimaryButton(if (busy) "提交中…" else button, Modifier.fillMaxWidth().tag("$tagPrefix-submit"), enabled = password.isNotEmpty() && !busy) {
                busy = true
                error = ""
                onSubmit(password, { nav.back() }) { e ->
                    busy = false
                    password = ""
                    error = accountErrorText(e, AccountPage.PASSWORD_CONFIRM)
                }
            }
        }
    }
}

@Composable
fun RegenerateRecoveryPage() {
    val app = LocalApp.current
    PasswordConfirmPage("重新生成恢复码", "生成新的恢复码后，旧恢复码 7 天后失效。$RECOVERY_LOST_WARNING", "重新生成", "regenerate") { p, done, failed ->
        app.regenerateRecovery(p, done, failed)
    }
}

@Composable
fun LogoutOthersPage() {
    val app = LocalApp.current
    PasswordConfirmPage("退出其他设备", "其他设备会下线，这台设备保持登录，密码不变", "退出其他设备", "others") { p, done, failed ->
        app.logoutOthers(p, onDone = {
            app.toast.show("已退出其他设备")
            done()
        }, onFailed = failed)
    }
}

/** 改昵称（F-ACC-06，AC-ACC-06-5）。本端的缓存由 SDK 更新，ImSession 收到 ProfileChanged 再刷新界面。 */
@Composable
fun NicknamePage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val current = im.profiles[im.self]?.nickname.orEmpty()
    var value by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val trimmed = com.tianting.sdk.trimText(value)
    val tooLong = countCodePoints(trimmed) > MAX_NICKNAME
    Page {
        TopBar(title = "修改昵称", onBack = { nav.back() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TtInput(value, { value = it }, "nickname-input", placeholder = im.self)
            if (tooLong) FieldError("昵称最多 $MAX_NICKNAME 个字", "nickname-too-long")
            if (error.isNotEmpty()) FieldError(error, "nickname-error")
            PrimaryButton(if (busy) "提交中…" else "保存", Modifier.fillMaxWidth().tag("nickname-submit"), enabled = trimmed.isNotEmpty() && !tooLong && !busy) {
                busy = true
                error = ""
                app.accountOp({ e ->
                    busy = false
                    error = accountErrorText(e, AccountPage.NICKNAME)
                }) {
                    app.account.updateProfile(nickname = trimmed)
                    nav.back()
                }
            }
        }
    }
}

/** "我"页卡片右侧的编辑箭头（F-UI-15 规则 2）：沿用清单里的 chevron-left 转 180 度，不加新图标。 */
@Composable
fun EditChevron() {
    Icon(TtIcons.back, "编辑", Modifier.size(20.dp).rotate(180f), tint = Tt.colors.textTertiary)
}
