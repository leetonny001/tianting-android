// TiantingTheme（PRD-ui 6 节）：颜色、字号、间距都来自 Tokens.gen.kt。页面只用 Tt.colors / Tt.type，不写颜色字面量（AC-UI-22-3）。
package com.tianting.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

internal val LocalTtColors = staticCompositionLocalOf { LightTokens }

/** 字号（6.3 节）：行高 1.4～1.5。字体用系统字体（3.5 节），跟随系统字体缩放。 */
internal object TtType {
    val caption = TextStyle(fontSize = TtDimen.fontCaption.sp, lineHeight = 17.sp)
    val small = TextStyle(fontSize = TtDimen.fontSmall.sp, lineHeight = 20.sp)
    val body = TextStyle(fontSize = TtDimen.fontBody.sp, lineHeight = 22.sp)
    val title = TextStyle(fontSize = TtDimen.fontTitle.sp, lineHeight = 24.sp)
    val large = TextStyle(fontSize = TtDimen.fontLarge.sp, lineHeight = 30.sp)
}

internal object Tt {
    val colors: TtColors @Composable get() = LocalTtColors.current
    val type get() = TtType
}

/** 偏好（ui.theme）：system / light / dark（F-UI-22 规则 3）。 */
enum class ThemePref(val wire: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.wire == v } ?: SYSTEM
    }
}

@Composable
fun resolveDark(pref: ThemePref): Boolean = when (pref) {
    ThemePref.DARK -> true
    ThemePref.LIGHT -> false
    ThemePref.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun TiantingTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.brandFill, onPrimary = c.onBrand, background = c.bg, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary, outline = c.borderInput, outlineVariant = c.divider, error = c.danger,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface,
            surfaceContainerLowest = c.surface, secondaryContainer = c.surfaceSelected, onSecondaryContainer = c.textPrimary, surfaceTint = Color.Transparent,
        )
    } else {
        lightColorScheme(
            primary = c.brandFill, onPrimary = c.onBrand, background = c.bg, onBackground = c.textPrimary, surface = c.surface, onSurface = c.textPrimary,
            surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary, outline = c.borderInput, outlineVariant = c.divider, error = c.danger,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface,
            surfaceContainerLowest = c.surface, secondaryContainer = c.surfaceSelected, onSecondaryContainer = c.textPrimary, surfaceTint = Color.Transparent,
        )
    }
    CompositionLocalProvider(LocalTtColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 浅色模式对方气泡加描边（6.5 节）：用 surface 的亮度判断当前主题，不额外传参。 */
internal fun TtColors.isDark(): Boolean = this === DarkTokens
