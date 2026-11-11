// 通用组件（PRD-ui 3.3、3.5、6 节）。testTag 和 tianting-web 的 data-testid 同名（3.5 节规则 4）；
// data-* 属性对应这里的 semantics key，测试用它们区分同名的行。
package com.tianting.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tianting.app.ui.theme.AvatarPalette
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtDimen
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.avatarColorIndex
import com.tianting.sdk.avatarInitial
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

// ---- 测试属性（web 的 data-*） ----

val DataUser = SemanticsPropertyKey<String>("data-user")
val DataConvId = SemanticsPropertyKey<String>("data-conv-id")
val DataStatus = SemanticsPropertyKey<String>("data-status")
val DataClientMsgId = SemanticsPropertyKey<String>("data-client-msg-id")
val DataKind = SemanticsPropertyKey<String>("data-kind")
val DataError = SemanticsPropertyKey<String>("data-error")
val DataEditable = SemanticsPropertyKey<String>("data-editable")
/** 会话项是否置顶（"true" / "false"，同 H5 的 data-pinned，C5）。 */
val DataPinned = SemanticsPropertyKey<String>("data-pinned")
var SemanticsPropertyReceiver.dataUser by DataUser
var SemanticsPropertyReceiver.dataConvId by DataConvId
var SemanticsPropertyReceiver.dataStatus by DataStatus
var SemanticsPropertyReceiver.dataClientMsgId by DataClientMsgId
var SemanticsPropertyReceiver.dataKind by DataKind
var SemanticsPropertyReceiver.dataError by DataError
var SemanticsPropertyReceiver.dataEditable by DataEditable
var SemanticsPropertyReceiver.dataPinned by DataPinned

/** 消息行的标记（C4）：highlight（引用、"有人@我"跳转后高亮）、mention（被 @ 的是我，整体样式提示），多个用空格分开。 */
val DataMark = SemanticsPropertyKey<String>("data-mark")
var SemanticsPropertyReceiver.dataMark by DataMark

fun Modifier.tag(tag: String): Modifier = testTag(tag)

// ---- 文字 ----

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Tt.type.body,
    color: Color = Tt.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    overflow: TextOverflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
) {
    Text(text, modifier, style = style, color = color, maxLines = maxLines, textAlign = align, overflow = overflow)
}

// ---- 页面骨架 ----

@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(Tt.colors.bg), content = content)
}

/** 顶栏（F-UI-06 规则 1，Android 按 3.5 节用 64dp）：字体放大时高度可以增长。 */
@Composable
fun TopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(Tt.colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 48.dp)) {
                if (onBack != null) IconBtn(TtIcons.back, "返回", "topbar-back", onBack)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (titleContent != null) titleContent() else if (title != null) T(title, style = Tt.type.title, maxLines = 1)
            }
            Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) { right?.invoke() }
        }
        HorizontalDivider(color = Tt.colors.divider)
    }
}

@Composable
fun IconBtn(icon: ImageVector, description: String, tag: String, onClick: () -> Unit, tint: Color = Tt.colors.textPrimary) {
    IconButton(onClick, Modifier.tag(tag).size(48.dp)) { Icon(icon, description, tint = tint) }
}

/** 顶栏右侧的文字按钮（"完成""编辑"）。 */
@Composable
fun TopBarAction(text: String, tag: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick, Modifier.tag(tag).heightIn(min = TtDimen.sizeTouchMin.dp), enabled = enabled) {
        T(text, color = if (!enabled) Tt.colors.textTertiary else if (danger) Tt.colors.danger else Tt.colors.brand)
    }
}

/** 连接状态条（F-UI-17）：高 36，把内容下推，不覆盖。显示时机由 SDK 的 banner 决定。 */
@Composable
fun ConnBanner() {
    val im = LocalIm.current
    if (!im.banner) return
    val offline = !im.isOnline
    Row(
        Modifier.tag("conn-banner").fillMaxWidth().heightIn(min = TtDimen.sizeBanner.dp).background(Tt.colors.warningBg).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (offline) {
            Icon(TtIcons.failed, null, Modifier.size(16.dp), tint = Tt.colors.warningText)
        } else {
            Spinner(16.dp, Tt.colors.warningText)
        }
        T(if (offline) "网络不可用" else "连接中…", style = Tt.type.small, color = Tt.colors.warningText)
    }
}

@Composable
fun Spinner(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    Icon(TtIcons.loading, null, modifier.size(size).rotate(angle), tint = color)
}

// ---- 头像（F-UI-21） ----

/** 只有 https 地址才请求（AC-UI-21-4、AC-UI-21-5）。 */
fun avatarImageUrl(src: String?): String? = src?.takeIf { it.startsWith("https://") }

/** Coil 用的 OkHttp：万一有非 https 的请求（重定向到 http 也算）直接失败。 */
fun httpsOnlyClient(): OkHttpClient = OkHttpClient.Builder().addNetworkInterceptor(HttpsOnly).followSslRedirects(false).build()

object HttpsOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        if (!chain.request().url.isHttps) throw IOException("avatar must be https")
        return chain.proceed(chain.request())
    }
}

@Composable
fun Avatar(id: String, name: String, size: Dp, group: Boolean = false, src: String? = null, local: java.io.File? = null) {
    val shape = if (group) RoundedCornerShape(TtDimen.radiusGroup.dp) else CircleShape
    var failed by remember(src, local) { mutableStateOf(false) }
    // local：这次登录里自己刚换的头像（本机文件，同 H5 的 H13）；其他只接受 https 地址（AC-UI-21-5）。
    val url: Any? = (local ?: avatarImageUrl(src)).takeIf { !failed }
    val density = LocalDensity.current
    // 占位字不随系统字体放大：头像大小是固定的。
    val initialSize = with(density) { (size * 0.42f).toSp() }
    val lineHeight = with(density) { (size * 0.6f).toSp() }
    Box(
        Modifier.tag("avatar").size(size).clip(shape).background(AvatarPalette[avatarColorIndex(id)]),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(url).crossfade(false).build(),
                contentDescription = null,
                modifier = Modifier.tag("avatar-img").fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        } else {
            Text(avatarInitial(name), color = Tt.colors.onBrand, style = TextStyle(fontSize = initialSize, lineHeight = lineHeight))
        }
    }
}

@Composable
fun OfficialTag() {
    Box(
        Modifier.tag("official").clip(RoundedCornerShape(TtDimen.radiusSm.dp)).background(Tt.colors.surfaceSelected).padding(horizontal = 4.dp),
    ) {
        T("官方", style = Tt.type.caption, color = Tt.colors.brand)
    }
}

fun badgeText(n: Long): String = if (n > 99) "99+" else n.toString()

@Composable
fun Badge(text: String, tag: String, modifier: Modifier = Modifier) {
    Box(
        modifier.tag(tag).heightIn(min = 18.dp).widthIn(min = 18.dp).clip(RoundedCornerShape(9.dp)).background(Tt.colors.badgeBg).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, style = Tt.type.caption, color = Tt.colors.onBadge, maxLines = 1)
    }
}

// ---- 列表行 ----

@Composable
fun TtRow(
    modifier: Modifier = Modifier,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 52.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val m = modifier.fillMaxWidth().background(Tt.colors.surface)
    Column((if (tag != null) m.tag(tag) else m).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        HorizontalDivider(color = Tt.colors.divider)
    }
}

@Composable
fun RowsSkeleton(tag: String) {
    Column(Modifier.tag(tag)) {
        repeat(6) {
            TtRow(minHeight = 56.dp) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Tt.colors.divider))
                Box(Modifier.weight(1f).heightIn(min = 14.dp).clip(RoundedCornerShape(4.dp)).background(Tt.colors.divider))
            }
        }
    }
}

@Composable
fun EmptyState(text: String, tag: String, modifier: Modifier = Modifier, art: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.tag(tag).fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (art) Box(Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(TtDimen.radiusLg.dp)).background(Tt.colors.surfaceSelected))
        T(text, color = Tt.colors.textSecondary, align = TextAlign.Center)
        action?.invoke()
    }
}

// ---- 按钮 ----

private val ButtonShape = RoundedCornerShape(TtDimen.radiusMd.dp)
private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** 主要按钮：文字可以换行、不被截断（3.5 节规则 3）。 */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick,
        modifier.heightIn(min = TtDimen.sizeTouchMin.dp),
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tt.colors.brandFill,
            contentColor = Tt.colors.onBrand,
            disabledContainerColor = Tt.colors.divider,
            disabledContentColor = Tt.colors.textSecondary,
        ),
    ) { Text(text, style = Tt.type.body, textAlign = TextAlign.Center) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick,
        modifier.heightIn(min = TtDimen.sizeTouchMin.dp),
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, Tt.colors.borderInput),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Tt.colors.textPrimary, disabledContentColor = Tt.colors.textTertiary),
    ) { Text(text, style = Tt.type.body, textAlign = TextAlign.Center) }
}

@Composable
fun LinkButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = TtDimen.sizeTouchMin.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
        T(text, color = Tt.colors.brand)
    }
}

// ---- 输入框 ----

@Composable
fun TtInput(
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    password: Boolean = false,
) {
    BasicTextField(
        value,
        onChange,
        modifier.tag(tag).fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = Tt.type.body.copy(color = Tt.colors.textPrimary),
        cursorBrush = SolidColor(Tt.colors.brand),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp)
                    .border(1.dp, Tt.colors.borderInput, RoundedCornerShape(TtDimen.radiusMd.dp))
                    .background(Tt.colors.surface, RoundedCornerShape(TtDimen.radiusMd.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, color = Tt.colors.textTertiary)
                inner()
            }
        },
    )
}

@Composable
fun FieldError(text: String, tag: String? = null) {
    T(text, modifier = if (tag != null) Modifier.tag(tag) else Modifier, style = Tt.type.small, color = Tt.colors.danger)
}

// ---- 弹窗（3.5 节：确认框用系统样式） ----

@Composable
fun TtDialog(
    tag: String,
    onDismiss: () -> Unit,
    title: String? = null,
    dismissible: Boolean = true,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        modifier = Modifier.tag(tag),
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
        title = title?.let { { T(it, style = Tt.type.title) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = confirm,
        dismissButton = dismiss,
        containerColor = Tt.colors.surface,
        titleContentColor = Tt.colors.textPrimary,
        textContentColor = Tt.colors.textPrimary,
        shape = RoundedCornerShape(TtDimen.radiusLg.dp),
    )
}

@Composable
fun Confirm(text: String, ok: String, tag: String, danger: Boolean = false, onCancel: () -> Unit, onOk: () -> Unit) {
    TtDialog(
        tag,
        onCancel,
        confirm = { TextButton(onOk, Modifier.tag("$tag-ok")) { T(ok, color = if (danger) Tt.colors.danger else Tt.colors.brand) } },
        dismiss = { TextButton(onCancel, Modifier.tag("$tag-cancel")) { T("取消", color = Tt.colors.textSecondary) } },
    ) { T(text) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtSheet(tag: String, onDismiss: () -> Unit, canDismiss: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    // canDismiss 为 false 时（例如提交中）下滑、点遮罩、返回键都不关（举报单，FE-694）。
    val dismissible = androidx.compose.runtime.rememberUpdatedState(canDismiss)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden || dismissible.value })
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = Tt.colors.surface, contentColor = Tt.colors.textPrimary) {
        Column(
            Modifier.tag(tag).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// ---- 轻提示 ----

/** F-UI-19 规则 1：容器底部居中（在输入区上方），2 秒后消失，新的替换旧的。 */
@Composable
fun BoxScope.ToastHost(state: com.tianting.app.core.ToastState) {
    val text = state.text
    LaunchedEffect(state.serial) {
        if (state.text != null) {
            delay(2000)
            state.text = null
        }
    }
    if (text != null) {
        Box(
            Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 96.dp).tag("toast")
                .clip(RoundedCornerShape(TtDimen.radiusMd.dp)).background(Tt.colors.textPrimary).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            T(text, style = Tt.type.small, color = Tt.colors.surface, align = TextAlign.Center)
        }
    }
}
