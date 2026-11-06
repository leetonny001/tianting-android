// 保存恢复码、隐私说明（PRD-chat F-ACC-05、F-ACC-10，FE-615）。
package com.tianting.app.ui.pages

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tianting.app.core.AccountPage
import com.tianting.app.core.PRIVACY_LOAD_FAILED
import com.tianting.app.core.RECOVERY_LOST_WARNING
import com.tianting.app.core.RecoveryPrompt
import com.tianting.app.core.accountErrorText
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.LinkButton
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.Page
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.SecondaryButton
import com.tianting.app.ui.T
import com.tianting.app.ui.TopBar
import com.tianting.app.ui.TtInput
import com.tianting.app.ui.dataStatus
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt

private val PasswordKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)

/**
 * 保存恢复码（F-ACC-05 规则 1，AC-ACC-01-12）：盖住其他页面，返回键不起作用，只有"我已保存"能离开。
 * 确认之前 token 不写存储（SDK 保证），在这里被杀就回登录页。
 */
@Composable
fun RecoveryPage(prompt: RecoveryPrompt) {
    BackHandler(enabled = true) { } // 只拦截：这一页不能退回去
    when (prompt) {
        is RecoveryPrompt.Show -> RecoveryShow(prompt)
        RecoveryPrompt.NeedPassword -> RecoveryNeedPassword()
    }
}

@Composable
private fun RecoveryShow(prompt: RecoveryPrompt.Show) {
    val app = LocalApp.current
    var busy by remember(prompt) { mutableStateOf(false) }
    var error by remember(prompt) { mutableStateOf("") }
    Page {
        TopBar(title = "保存恢复码")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T(RECOVERY_LOST_WARNING, Modifier.tag("recovery-warning"), color = Tt.colors.danger)
            T("忘记密码时要用它找回账号。请把它保存在这台设备以外的地方，不要发给别人。", style = Tt.type.small, color = Tt.colors.textSecondary)
            SelectionContainer {
                T(prompt.code, Modifier.fillMaxWidth().tag("recovery-code"), style = Tt.type.title, align = TextAlign.Center)
            }
            SecondaryButton("复制", Modifier.fillMaxWidth().tag("recovery-copy")) { app.copySecret(prompt.code) }
            if (error.isNotEmpty()) FieldError(error, "recovery-error")
            PrimaryButton(if (busy) "提交中…" else "我已保存", Modifier.fillMaxWidth().tag("recovery-saved"), enabled = !busy) {
                busy = true
                error = ""
                app.ackRecovery(prompt) { e ->
                    busy = false
                    error = accountErrorText(e, AccountPage.PASSWORD_CONFIRM)
                }
            }
        }
    }
}

/** 服务端说最新一代没确认，手里没有码（AC-ACC-05-19）：输入密码重新生成。密码也忘了只能退出，再走"忘记密码"。 */
@Composable
private fun RecoveryNeedPassword() {
    val app = LocalApp.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        TopBar(title = "保存恢复码")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("上一次的恢复码没有确认保存，要重新生成。", Modifier.tag("recovery-need-password"))
            T(RECOVERY_LOST_WARNING, Modifier.tag("recovery-warning"), style = Tt.type.small, color = Tt.colors.danger)
            T("当前密码", style = Tt.type.small, color = Tt.colors.textSecondary)
            TtInput(password, { password = it }, "recovery-password", password = true, keyboardOptions = PasswordKeyboard.copy(imeAction = ImeAction.Done))
            if (error.isNotEmpty()) FieldError(error, "recovery-error")
            PrimaryButton(if (busy) "提交中…" else "重新生成", Modifier.fillMaxWidth().tag("recovery-regenerate"), enabled = password.isNotEmpty() && !busy) {
                busy = true
                error = ""
                app.regenerateRecovery(password, onDone = {}, onFailed = { e ->
                    busy = false
                    password = ""
                    error = accountErrorText(e, AccountPage.PASSWORD_CONFIRM)
                })
            }
            LinkButton("退出登录", Modifier.align(Alignment.CenterHorizontally).tag("recovery-logout")) { app.logout() }
        }
    }
}

/**
 * 隐私说明（F-ACC-10）：部署配置的地址，用系统 WebView 打开，不开 JavaScript。加载失败提示，不阻止登录。
 * 没有地址时入口隐藏（"打包页面"还没有文字，第 20.3 节）；万一走到这里也只提示打不开。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PrivacyNoticePage(url: String?, onBack: () -> Unit) {
    var failed by remember(url) { mutableStateOf(url.isNullOrEmpty()) }
    var loaded by remember(url) { mutableStateOf(false) }
    Page {
        TopBar(title = "隐私说明", onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth().tag("privacy-page").semantics { dataStatus = if (failed) "failed" else if (loaded) "loaded" else "loading" }) {
            if (failed) {
                T(PRIVACY_LOAD_FAILED, Modifier.fillMaxWidth().padding(24.dp).tag("privacy-failed"), color = Tt.colors.textSecondary, align = TextAlign.Center)
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                                    loaded = false
                                }

                                override fun onPageFinished(view: WebView?, u: String?) {
                                    loaded = true
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) failed = true
                                }

                                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                    if (request?.isForMainFrame == true) failed = true
                                }
                            }
                            loadUrl(url.orEmpty())
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
