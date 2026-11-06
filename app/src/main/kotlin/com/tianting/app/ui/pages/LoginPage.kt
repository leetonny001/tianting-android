package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianting.app.core.AccountPage
import com.tianting.app.core.AuthKind
import com.tianting.app.core.ConfigState
import com.tianting.app.core.NETWORK_ERROR_TEXT
import com.tianting.app.core.RECOVERY_LOST_WARNING
import com.tianting.app.core.USERNAME_TAKEN
import com.tianting.app.core.accountErrorText
import com.tianting.app.core.deletionPendingText
import com.tianting.app.core.long
import com.tianting.app.core.LoginResult
import com.tianting.app.core.MAX_NICKNAME
import com.tianting.app.core.USER_ID_RE
import com.tianting.app.core.defaultMessage
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.LinkButton
import com.tianting.app.ui.TtDialog
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.Page
import com.tianting.app.ui.TopBar
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.T
import com.tianting.app.ui.TtInput
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.sdk.AccountConfig
import com.tianting.sdk.AccountError
import com.tianting.sdk.countCodePoints
import kotlinx.coroutines.launch

@androidx.compose.runtime.Composable
fun Logo() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Tt.colors.brandFill), contentAlignment = Alignment.Center) {
            T("听", style = Tt.type.large, color = Tt.colors.onBrand)
        }
        T("天听", style = Tt.type.large)
    }
}

private enum class AuthScreen { LOGIN, REGISTER, RECOVER, PRIVACY }

/**
 * 未登录时的页面（PRD-chat F-ACC-07 规则 5、第 13.3 节）：先拿到 GET /v1/config 才知道显示什么，失败时只有重试，不猜测模式。
 * 密码和恢复码只用 remember：rememberSaveable 会写进系统保存的状态。
 */
@Composable
fun LoginPage() {
    val app = LocalApp.current
    LaunchedEffect(Unit) { app.refreshConfig() }
    when (val c = app.config) {
        ConfigState.Loading -> Column(Modifier.fillMaxSize().background(Tt.colors.bg).tag("config-loading")) {}
        ConfigState.Failed -> ConfigFailed { app.retryConfig() }
        is ConfigState.Loaded -> AuthPages(c.config)
    }
}

@Composable
private fun ConfigFailed(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Tt.colors.bg).padding(24.dp).tag("config-failed"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Logo()
        T(NETWORK_ERROR_TEXT, Modifier.tag("config-error"), color = Tt.colors.textSecondary, align = TextAlign.Center)
        PrimaryButton("重试", Modifier.fillMaxWidth().tag("config-retry"), onClick = onRetry)
    }
}

@Composable
private fun AuthPages(config: AccountConfig) {
    val app = LocalApp.current
    var screen by rememberSaveable { mutableStateOf(AuthScreen.LOGIN) }
    var useAccount by rememberSaveable { mutableStateOf(false) }
    var privacyFrom by rememberSaveable { mutableStateOf(AuthScreen.LOGIN) }
    val demoEnabled = app.deps.demoBuild && config.demoLogin // 构建开关是上限（F-ACC-07 规则 4）
    val canRegister = config.registerMode != "closed"
    val hasPrivacy = !config.privacyNotice?.url.isNullOrEmpty() // 没有地址就没有地方可开，隐藏入口（F-ACC-10 规则 1，同 H5）
    val openPrivacy = { from: AuthScreen ->
        privacyFrom = from
        screen = AuthScreen.PRIVACY
    }
    BackHandler(enabled = screen != AuthScreen.LOGIN) {
        screen = if (screen == AuthScreen.PRIVACY) privacyFrom else AuthScreen.LOGIN
    }
    SideEffect { app.authTopBar = screen != AuthScreen.LOGIN } // 有顶栏的页面，状态栏和顶栏连成一片
    when (screen) {
        AuthScreen.LOGIN -> if (demoEnabled && !useAccount) {
            DemoLoginForm(onUseAccount = { useAccount = true }, privacy = hasPrivacy) { openPrivacy(AuthScreen.LOGIN) }
        } else {
            AccountLoginForm(
                canRegister = canRegister,
                onRegister = { screen = AuthScreen.REGISTER },
                onForgot = { screen = AuthScreen.RECOVER },
                onUseDemo = if (demoEnabled) ({ useAccount = false }) else null,
                privacy = hasPrivacy,
                onPrivacy = { openPrivacy(AuthScreen.LOGIN) },
            )
        }
        AuthScreen.REGISTER -> RegisterForm(config, onBack = { screen = AuthScreen.LOGIN }) { openPrivacy(AuthScreen.REGISTER) }
        AuthScreen.RECOVER -> RecoverForm(onBack = { screen = AuthScreen.LOGIN })
        AuthScreen.PRIVACY -> PrivacyNoticePage(config.privacyNotice?.url) { screen = privacyFrom }
    }
}

@Composable
private fun AuthColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Tt.colors.bg).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        content = content,
    )
}

@Composable
private fun Label(text: String) = T(text, style = Tt.type.small, color = Tt.colors.textSecondary)

private val UsernameKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
private val PasswordKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)

/** 隐私说明入口（F-ACC-10 规则 1）。配置为空时不显示。 */
@Composable
private fun PrivacyLink(tag: String, onClick: () -> Unit) {
    LinkButton("隐私说明", Modifier.tag(tag), onClick)
}

/** 演示表单（PRD-ui F-UI-03）。 */
@Composable
private fun DemoLoginForm(onUseAccount: () -> Unit, privacy: Boolean, onPrivacy: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val trimmed = username.trim()
    val nicknameTooLong = countCodePoints(nickname.trim()) > MAX_NICKNAME
    val canSubmit = trimmed.isNotEmpty() && !nicknameTooLong && !busy

    fun submit() {
        if (!canSubmit) return // 防重复提交（AC-UI-03-3）
        if (!USER_ID_RE.matches(trimmed)) {
            error = defaultMessage(4004)
            return
        }
        busy = true
        error = ""
        scope.launch {
            when (val r = app.deps.login.login(trimmed, nickname.trim().ifEmpty { null })) {
                is LoginResult.Ok -> app.onLoggedIn(r.session)
                is LoginResult.Failed -> error = r.message
            }
            busy = false
        }
    }

    AuthColumn {
        Logo()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("用户名")
            TtInput(username, { username = it }, "login-username", keyboardOptions = UsernameKeyboard)
            Label("昵称（选填）")
            TtInput(
                nickname,
                { nickname = it },
                "login-nickname",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            if (nicknameTooLong) FieldError("昵称最多 32 个字", "login-nickname-error")
            if (error.isNotEmpty()) FieldError(error, "login-error")
            PrimaryButton(if (busy) "登录中…" else "登录", Modifier.fillMaxWidth().tag("login-submit"), enabled = canSubmit) { submit() }
            LinkButton("使用账号登录", Modifier.align(Alignment.CenterHorizontally).tag("login-use-account"), onUseAccount)
        }
        T("演示环境：输入任意用户名即可登录", style = Tt.type.small, color = Tt.colors.textTertiary, align = TextAlign.Center)
        if (privacy) PrivacyLink("login-privacy", onPrivacy)
    }
}

/** 账号登录（F-ACC-02）。 */
@Composable
private fun AccountLoginForm(
    canRegister: Boolean,
    onRegister: () -> Unit,
    onForgot: () -> Unit,
    onUseDemo: (() -> Unit)?,
    privacy: Boolean,
    onPrivacy: () -> Unit,
) {
    val app = LocalApp.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<String?>(null) } // 冷静期内登录：问是否撤销（F-ACC-08 规则 2）
    val canSubmit = username.isNotBlank() && password.isNotEmpty() && !busy

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = ""
        app.authenticate(AuthKind.LOGIN, onFailed = { e ->
            busy = false
            val ae = e as? AccountError
            if (ae?.code == 4030 && ae.reason == "accountDeletionPending") {
                deletion = deletionPendingText(ae.details?.long("deleteAt"))
            } else {
                error = accountErrorText(e, AccountPage.LOGIN)
                if (ae?.code == 4018) password = "" // AC-ACC-02-8
            }
        }) { it.login(username.trim(), password) }
    }

    AuthColumn {
        Logo()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("用户名")
            TtInput(username, { username = it }, "account-username", keyboardOptions = UsernameKeyboard)
            Label("密码")
            TtInput(
                password,
                { password = it },
                "account-password",
                password = true,
                keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            if (error.isNotEmpty()) FieldError(error, "account-error")
            PrimaryButton(if (busy) "登录中…" else "登录", Modifier.fillMaxWidth().tag("account-submit"), enabled = canSubmit) { submit() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (canRegister) LinkButton("注册", Modifier.tag("login-to-register"), onRegister) else Box(Modifier)
                LinkButton("忘记密码", Modifier.tag("login-to-recover"), onForgot)
            }
            if (onUseDemo != null) LinkButton("使用演示登录", Modifier.align(Alignment.CenterHorizontally).tag("login-use-demo"), onUseDemo)
        }
        if (privacy) PrivacyLink("login-privacy", onPrivacy)
    }
    deletion?.let { text ->
        TtDialog(
            tag = "deletion-pending",
            onDismiss = { deletion = null },
            confirm = {
                TextButton({
                    deletion = null
                    busy = true
                    app.accountOp({ e ->
                        busy = false
                        error = accountErrorText(e, AccountPage.LOGIN)
                    }) {
                        app.account.cancelDelete(username.trim(), password)
                        busy = false
                        password = "" // 撤销之后重新输入密码登录，不自动登录（同 H5，问题 H10）
                        app.toast.show("已撤销注销，请重新登录")
                    }
                }, Modifier.tag("deletion-cancel-request")) { T("撤销注销", color = Tt.colors.brand) }
            },
            dismiss = { TextButton({ deletion = null }, Modifier.tag("deletion-keep")) { T("取消", color = Tt.colors.textSecondary) } },
        ) { T(text) }
    }
}

/** 注册（F-ACC-01）。4095 提示在用户名框下方，其他错误在按钮上方；已填内容都保留（AC-ACC-01-13、01-18）。 */
@Composable
private fun RegisterForm(config: AccountConfig, onBack: () -> Unit, onPrivacy: () -> Unit) {
    val app = LocalApp.current
    var username by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var usernameError by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val needInvite = config.registerMode == "invite"
    val nicknameTooLong = countCodePoints(nickname.trim()) > config.limits.nicknameMaxLen
    val canSubmit = username.isNotBlank() && password.isNotEmpty() && (!needInvite || invite.isNotBlank()) && !nicknameTooLong && !busy

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = ""
        usernameError = ""
        app.authenticate(AuthKind.REGISTER, onFailed = { e ->
            busy = false
            val text = accountErrorText(e, AccountPage.REGISTER)
            if (text == USERNAME_TAKEN) usernameError = text else error = text
        }) { it.register(username.trim(), password, nickname.trim().ifEmpty { null }, invite.trim().ifEmpty { null }) }
    }

    Page {
        TopBar(title = "注册", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("用户名")
            TtInput(username, { username = it }, "register-username", keyboardOptions = UsernameKeyboard)
            if (usernameError.isNotEmpty()) FieldError(usernameError, "register-username-error")
            T("${config.limits.usernameMinLen}～${config.limits.usernameMaxLen} 位，字母开头，可以用小写字母、数字、下划线和连字符。注册后不能修改", style = Tt.type.small, color = Tt.colors.textTertiary)
            Label("密码")
            TtInput(password, { password = it }, "register-password", password = true, keyboardOptions = PasswordKeyboard)
            T("${config.limits.passwordMinLen}～${config.limits.passwordMaxLen} 个字符", style = Tt.type.small, color = Tt.colors.textTertiary)
            Label("昵称（选填）")
            TtInput(nickname, { nickname = it }, "register-nickname", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            if (nicknameTooLong) FieldError("昵称最多 ${config.limits.nicknameMaxLen} 个字", "register-nickname-error")
            if (needInvite) {
                Label("邀请码")
                TtInput(
                    invite,
                    { invite = it },
                    "register-invite",
                    keyboardOptions = UsernameKeyboard.copy(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
            T(RECOVERY_LOST_WARNING, Modifier.tag("register-warning"), style = Tt.type.small, color = Tt.colors.textSecondary)
            if (error.isNotEmpty()) FieldError(error, "register-error")
            PrimaryButton(if (busy) "提交中…" else "注册", Modifier.fillMaxWidth().tag("register-submit"), enabled = canSubmit) { submit() }
            if (!config.privacyNotice?.url.isNullOrEmpty()) {
                T("注册即表示你已阅读隐私说明", Modifier.align(Alignment.CenterHorizontally), style = Tt.type.small, color = Tt.colors.textSecondary)
                Box(Modifier.align(Alignment.CenterHorizontally)) { PrivacyLink("register-privacy", onPrivacy) }
            }
        }
    }
}

/** 忘记密码（F-ACC-05 规则 2）：只有恢复码流程，没有客服入口（规则 6）。 */
@Composable
private fun RecoverForm(onBack: () -> Unit) {
    val app = LocalApp.current
    var username by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val canSubmit = username.isNotBlank() && code.isNotBlank() && password.isNotEmpty() && !busy

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = ""
        app.authenticate(AuthKind.RECOVER, onFailed = { e ->
            busy = false
            error = accountErrorText(e, AccountPage.RECOVER)
        }) { it.recover(username.trim(), code.trim(), password) }
    }

    Page {
        TopBar(title = "找回密码", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("用户名")
            TtInput(username, { username = it }, "recover-username", keyboardOptions = UsernameKeyboard)
            Label("恢复码")
            TtInput(code, { code = it }, "recover-code", keyboardOptions = UsernameKeyboard)
            Label("新密码")
            TtInput(
                password,
                { password = it },
                "recover-password",
                password = true,
                keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            T(RECOVERY_LOST_WARNING, Modifier.tag("recover-warning"), style = Tt.type.small, color = Tt.colors.textSecondary)
            if (error.isNotEmpty()) FieldError(error, "recover-error")
            PrimaryButton(if (busy) "提交中…" else "找回", Modifier.fillMaxWidth().tag("recover-submit"), enabled = canSubmit) { submit() }
        }
    }
}
