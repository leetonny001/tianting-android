package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianting.app.BuildConfig
import com.tianting.app.core.LoginResult
import com.tianting.app.core.MAX_NICKNAME
import com.tianting.app.core.USER_ID_RE
import com.tianting.app.core.defaultMessage
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.T
import com.tianting.app.ui.TtInput
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
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

/** 登录页（PRD-ui F-UI-03）：一期只有演示登录。 */
@Composable
fun LoginPage() {
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

    Column(
        Modifier.fillMaxSize().background(Tt.colors.bg).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Logo()
        if (!BuildConfig.DEMO_LOGIN) {
            T("请从业务方入口进入天听", color = Tt.colors.textSecondary, align = TextAlign.Center, modifier = Modifier.tag("login-closed"))
            return@Column
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("用户名", style = Tt.type.small, color = Tt.colors.textSecondary)
            TtInput(
                username,
                { username = it },
                "login-username",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            )
            T("昵称（选填）", style = Tt.type.small, color = Tt.colors.textSecondary)
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
        }
        T("演示环境：输入任意用户名即可登录", style = Tt.type.small, color = Tt.colors.textTertiary, align = TextAlign.Center)
    }
}
