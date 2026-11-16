package com.accessai.compose

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * The five colour roles a host chooses (UI spec 1f). Everything else (on-colours, tints, outlines, warning and
 * every high-contrast value) is derived, and in high-contrast mode any role that misses 7:1 is overridden.
 */
@Immutable
public data class AccessAIColors(
    val primary: Color = Color(0xFF2B4C7E),
    val surface: Color = Color.White,
    val onSurface: Color = Color(0xFF14171A),
    val positive: Color = Color(0xFF146B34),
    val critical: Color = Color(0xFFB3261E),
)

/** Heading face (headings, amounts) and body face. Sizes come from the spec's type scale and follow font scale. */
@Immutable
public data class AccessAIType(
    val heading: FontFamily = FontFamily.Default,
    val body: FontFamily = FontFamily.Default,
) {
    public companion object {
        public val Default: AccessAIType = AccessAIType()
    }
}

/** Spacing tokens, dp (UI spec 1i). */
public object AccessAISpace {
    public val panel: Dp = 16.dp
    public val block: Dp = 16.dp
    public val button: Dp = 12.dp
    public val icon: Dp = 10.dp
}

/** Size tokens, dp (UI spec 1i). */
public object AccessAISize {
    public val minTarget: Dp = 56.dp
    public val button: Dp = 64.dp
    public val confirm: Dp = 72.dp
    public val mic: Dp = 128.dp
    public val micLarge: Dp = 160.dp
}

/** Every role, resolved for the current mode. Read it with [AccessAITheme.current]. */
@Immutable
public class ResolvedAccessAITheme internal constructor(
    public val highContrast: Boolean,
    public val reducedMotion: Boolean,
    /** Font scale of 150% or more: icons, mic and dots grow (UI spec 1d). */
    public val largeText: Boolean,
    public val primary: Color,
    public val onPrimary: Color,
    public val surface: Color,
    public val onSurface: Color,
    public val mutedText: Color,
    public val container: Color,
    public val outline: Color,
    public val positive: Color,
    public val critical: Color,
    public val warning: Color,
    public val info: Color,
    /** Border width: 2dp, 3dp in high contrast. */
    public val stroke: Dp,
    public val panelRadius: Dp,
    public val cardRadius: Dp,
    public val buttonRadius: Dp,
    public val display: TextStyle,
    /** Recipient name on the confirmation card: 32sp, capped at 52sp. */
    public val recipient: TextStyle,
    public val headline: TextStyle,
    public val title: TextStyle,
    public val body: TextStyle,
    public val label: TextStyle,
    public val caption: TextStyle,
) {
    internal val iconSize: Dp get() = if (largeText) 42.dp else 28.dp
    internal val inlineIconSize: Dp get() = if (largeText) 36.dp else 24.dp
}

internal val LocalAccessAI = staticCompositionLocalOf<ResolvedAccessAITheme> {
    error("AccessAI components must be inside AccessAITheme { }")
}

/**
 * Themes AccessAI's screens. Pass your brand in [colors], [type] and [cornerRadius]; high contrast and reduced
 * motion follow the system unless you override them.
 *
 * ```
 * AccessAITheme(
 *     colors = AccessAIColors(primary = Color(0xFF1B6B2A), onSurface = Color(0xFF0B1F14)),
 *     type = AccessAIType(heading = FontFamily(Font(R.font.matter)), body = FontFamily(Font(R.font.dm_sans))),
 *     cornerRadius = 28.dp,
 * ) { AccessAIModeScreen(onExit = {}) }
 * ```
 */
@Composable
public fun AccessAITheme(
    colors: AccessAIColors = AccessAIColors(),
    type: AccessAIType = AccessAIType.Default,
    cornerRadius: Dp = 28.dp,
    highContrast: Boolean = isSystemHighContrast(),
    reducedMotion: Boolean = !animationsEnabled(),
    content: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val resolved = remember(colors, type, cornerRadius, highContrast, reducedMotion, fontScale) {
        resolve(colors, type, cornerRadius, highContrast, reducedMotion, fontScale)
    }
    val scheme = if (highContrast) {
        darkColorScheme(
            primary = resolved.primary, onPrimary = resolved.onPrimary, background = resolved.surface,
            onBackground = resolved.onSurface, surface = resolved.surface, onSurface = resolved.onSurface,
            surfaceVariant = resolved.container, onSurfaceVariant = resolved.mutedText, outline = resolved.outline,
            error = resolved.critical, onError = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = resolved.primary, onPrimary = resolved.onPrimary, background = resolved.surface,
            onBackground = resolved.onSurface, surface = resolved.surface, onSurface = resolved.onSurface,
            surfaceVariant = resolved.container, onSurfaceVariant = resolved.mutedText, outline = resolved.outline,
            error = resolved.critical, onError = Color.White,
        )
    }
    val typography = Typography(
        displayLarge = resolved.display,
        headlineMedium = resolved.headline,
        titleLarge = resolved.title,
        bodyLarge = resolved.body,
        bodyMedium = resolved.body,
        labelLarge = resolved.label,
        labelSmall = resolved.caption,
    )
    CompositionLocalProvider(LocalAccessAI provides resolved) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

public object AccessAITheme {
    /** The resolved roles and type for the enclosing [AccessAITheme]. */
    public val current: ResolvedAccessAITheme
        @Composable get() = LocalAccessAI.current
}

/** Dark mode or Android's "High contrast text": AccessAI then uses its black, 7:1 palette. */
@Composable
public fun isSystemHighContrast(): Boolean {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val highContrastText = remember {
        runCatching { Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false)
    }
    return dark || highContrastText
}

/** False when Android's "Remove animations" is on. */
@Composable
public fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

// ---- Resolution ---------------------------------------------------------------------------------

private fun resolve(
    c: AccessAIColors,
    type: AccessAIType,
    cornerRadius: Dp,
    hc: Boolean,
    reducedMotion: Boolean,
    fontScale: Float,
): ResolvedAccessAITheme {
    val surface = if (hc) Color.Black else c.surface
    val onSurface = if (hc) Color.White else ensureContrast(c.onSurface, surface, 7.0)
    val primary = if (hc) ensureContrast(c.primary, Color.Black, 7.0) else ensureContrast(c.primary, betterOn(c.primary), 4.5)
    val pill = cornerRadius >= 20.dp
    return ResolvedAccessAITheme(
        highContrast = hc,
        reducedMotion = reducedMotion,
        largeText = fontScale >= 1.5f,
        primary = primary,
        onPrimary = betterOn(primary),
        surface = surface,
        onSurface = onSurface,
        mutedText = if (hc) Color(0xFFE8E8E8) else ensureContrast(mix(onSurface, surface, 0.2f), surface, 7.0),
        container = if (hc) Color(0xFF121212) else mix(surface, primary, 0.075f),
        outline = if (hc) Color.White else ensureContrast(mix(onSurface, surface, 0.4f), surface, 3.0),
        positive = if (hc) ensureContrast(lighterOr(c.positive, Color(0xFF7CF08C)), Color.Black, 7.0) else ensureContrast(c.positive, surface, 4.5),
        critical = if (hc) ensureContrast(lighterOr(c.critical, Color(0xFFFF9A91)), Color.Black, 7.0) else ensureContrast(c.critical, surface, 4.5),
        warning = if (hc) Color(0xFFFFC46B) else Color(0xFF8A4B00),
        info = primary,
        stroke = if (hc) 3.dp else 2.dp,
        panelRadius = cornerRadius,
        cardRadius = cornerRadius * 0.57f,
        buttonRadius = if (pill) 999.dp else cornerRadius * 0.67f,
        display = TextStyle(fontFamily = type.heading, fontWeight = FontWeight.Bold, fontSize = capped(56f, 76f, fontScale), lineHeight = capped(64f, 84f, fontScale)),
        recipient = TextStyle(fontFamily = type.body, fontWeight = FontWeight.Bold, fontSize = capped(32f, 52f, fontScale), lineHeight = capped(37f, 60f, fontScale)),
        headline = TextStyle(fontFamily = type.heading, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        title = TextStyle(fontFamily = type.body, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        body = TextStyle(fontFamily = type.body, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
        label = TextStyle(fontFamily = type.body, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
        caption = TextStyle(fontFamily = type.body, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    )
}

/** [base] sp at normal size, never more than [cap] sp on screen whatever the font scale. */
private fun capped(base: Float, cap: Float, fontScale: Float): TextUnit = (min(base * fontScale, cap) / fontScale).sp

internal fun contrast(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return max(la, lb) / min(la, lb)
}

/** Black or white, whichever reads better on [background]. */
internal fun betterOn(background: Color): Color =
    if (contrast(Color.Black, background) >= contrast(Color.White, background)) Color.Black else Color.White

/** Keeps [color]'s hue and moves its lightness away from [background] until the contrast is at least [ratio]. */
internal fun ensureContrast(color: Color, background: Color, ratio: Double): Color {
    if (contrast(color, background) >= ratio) return color
    val (h, s, l0) = toHsl(color)
    val lighten = background.luminance() < 0.5f
    var l = l0
    repeat(100) {
        l = if (lighten) min(1f, l + 0.01f) else max(0f, l - 0.01f)
        val candidate = fromHsl(h, s, l)
        if (contrast(candidate, background) >= ratio) return candidate
    }
    return if (lighten) Color.White else Color.Black
}

/** The host's status colour when it is already the lighter one, otherwise the spec's high-contrast value. */
private fun lighterOr(host: Color, fallback: Color): Color = if (host.luminance() >= fallback.luminance()) host else fallback

internal fun mix(a: Color, b: Color, amountOfB: Float): Color = Color(
    red = a.red + (b.red - a.red) * amountOfB,
    green = a.green + (b.green - a.green) * amountOfB,
    blue = a.blue + (b.blue - a.blue) * amountOfB,
)

private fun toHsl(c: Color): Triple<Float, Float, Float> {
    val r = c.red
    val g = c.green
    val b = c.blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    return Triple(h, s, l)
}

private fun fromHsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m)
}
