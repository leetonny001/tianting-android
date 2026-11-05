// 生成文件，不要手改：./gradlew :app:generateTokens（来源 design/tokens.json）。
package com.tianting.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
internal data class TtColors(
    val brand: Color,
    val brandFill: Color,
    val brandFillPressed: Color,
    val onBrand: Color,
    val bg: Color,
    val surface: Color,
    val surfaceSelected: Color,
    val chatBg: Color,
    val bubbleSelfBg: Color,
    val bubbleSelfText: Color,
    val bubbleOtherBg: Color,
    val bubbleOtherText: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val borderInput: Color,
    val danger: Color,
    val badgeBg: Color,
    val onBadge: Color,
    val warningBg: Color,
    val warningText: Color,
    val scrim: Color,
)

internal val LightTokens = TtColors(
    brand = Color(0xFF4C5FD5),
    brandFill = Color(0xFF4C5FD5),
    brandFillPressed = Color(0xFF3D4DB8),
    onBrand = Color(0xFFFFFFFF),
    bg = Color(0xFFF2F3F7),
    surface = Color(0xFFFFFFFF),
    surfaceSelected = Color(0xFFE8EBFA),
    chatBg = Color(0xFFEEF0FA),
    bubbleSelfBg = Color(0xFF4C5FD5),
    bubbleSelfText = Color(0xFFFFFFFF),
    bubbleOtherBg = Color(0xFFFFFFFF),
    bubbleOtherText = Color(0xFF1A1D24),
    textPrimary = Color(0xFF1A1D24),
    textSecondary = Color(0xFF5C6370),
    textTertiary = Color(0xFF636977),
    divider = Color(0xFFE4E6EB),
    borderInput = Color(0xFF7E8496),
    danger = Color(0xFFC8281C),
    badgeBg = Color(0xFFD92D20),
    onBadge = Color(0xFFFFFFFF),
    warningBg = Color(0xFFFFF4E5),
    warningText = Color(0xFF8A4B00),
    scrim = Color(0x661A1D24),
)

internal val DarkTokens = TtColors(
    brand = Color(0xFF8C9BFF),
    brandFill = Color(0xFF4C5FD5),
    brandFillPressed = Color(0xFF3D4DB8),
    onBrand = Color(0xFFFFFFFF),
    bg = Color(0xFF111318),
    surface = Color(0xFF1B1E25),
    surfaceSelected = Color(0xFF2A2F45),
    chatBg = Color(0xFF0D0F13),
    bubbleSelfBg = Color(0xFF4352C4),
    bubbleSelfText = Color(0xFFFFFFFF),
    bubbleOtherBg = Color(0xFF252932),
    bubbleOtherText = Color(0xFFE8EAF0),
    textPrimary = Color(0xFFE8EAF0),
    textSecondary = Color(0xFFA3A9B7),
    textTertiary = Color(0xFF9298A8),
    divider = Color(0xFF2C313B),
    borderInput = Color(0xFF737A8A),
    danger = Color(0xFFF2685D),
    badgeBg = Color(0xFFD92D20),
    onBadge = Color(0xFFFFFFFF),
    warningBg = Color(0xFF3A2A12),
    warningText = Color(0xFFFFC870),
    scrim = Color(0x99000000),
)

internal val AvatarPalette = listOf(Color(0xFF4C5FD5), Color(0xFF7C3AED), Color(0xFFBE185D), Color(0xFFB91C1C), Color(0xFFC2410C), Color(0xFFA16207), Color(0xFF0F766E), Color(0xFF0369A1))

/** PRD-ui 6.3 节的值：字号是 sp，其余是 dp（3.5 节 1:1 换算）。 */
internal object TtDimen {
    const val fontCaption = 12
    const val fontSmall = 14
    const val fontBody = 16
    const val fontTitle = 17
    const val fontLarge = 22
    const val space1 = 4
    const val space2 = 8
    const val space3 = 12
    const val space4 = 16
    const val space5 = 24
    const val space6 = 32
    const val radiusSm = 4
    const val radiusMd = 8
    const val radiusLg = 12
    const val radiusGroup = 10
    const val sizeTopbar = 48
    const val sizeTabbar = 56
    const val sizeConvItem = 72
    const val sizeBanner = 36
    const val sizeContainer = 480
    const val sizeRail = 64
    const val sizeListColumn = 280
    const val sizePanel = 360
    const val sizeDialogWide = 400
    const val sizeComposerWide = 120
    const val sizeTouchMin = 44
}
