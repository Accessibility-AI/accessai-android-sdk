package com.accessai.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Outline icons from the UI spec: 24-unit grid, round 2.2 stroke. Tinted by the Icon that draws them. */
public object AccessAIIcons {
    public val Mic: ImageVector by lazy { icon("Mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3") }
    public val MicOff: ImageVector by lazy { icon("MicOff", "M3 3l18 18M9 9v3a3 3 0 0 0 5 2M15 10V6a3 3 0 0 0-5.7-1.3M5 11a7 7 0 0 0 11 5.7M12 18v3") }
    public val Close: ImageVector by lazy { icon("Close", "M6 6l12 12M18 6L6 18", 2.4f) }
    public val Check: ImageVector by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5") }
    public val CheckCircle: ImageVector by lazy { icon("CheckCircle", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8 12.5l3 3 5-6") }
    public val ErrorOctagon: ImageVector by lazy { icon("ErrorOctagon", "M8 3h8l5 5v8l-5 5H8l-5-5V8zM9 9l6 6M15 9l-6 6") }
    public val Info: ImageVector by lazy { icon("Info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01") }
    public val Warning: ImageVector by lazy { icon("Warning", "M12 3l10 18H2zM12 10v5M12 18h.01") }
    public val WifiOff: ImageVector by lazy {
        icon("WifiOff", "M3 3l18 18M2 9a15 15 0 0 1 5-3M22 9a15 15 0 0 0-8-3.8M5.5 12.5a10 10 0 0 1 3-2M18.5 12.5a10 10 0 0 0-3-2M9 16a5 5 0 0 1 6 0M12 20h.01")
    }
    public val Repeat: ImageVector by lazy { icon("Repeat", "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3") }
    public val Keyboard: ImageVector by lazy { icon("Keyboard", "M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10") }
    public val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2L14.3 3h-4.6l-.3 2.7a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2l.3 2.7h4.6l.3-2.7a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z",
        )
    }
    public val Help: ImageVector by lazy { icon("Help", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5M12 17h.01") }
    public val Cloud: ImageVector by lazy { icon("Cloud", "M7 18a4 4 0 0 1-.5-8A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z") }
    public val Trash: ImageVector by lazy { icon("Trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13") }
    public val Power: ImageVector by lazy { icon("Power", "M12 3v9M6.3 6.8a8 8 0 1 0 11.4 0") }
    public val Clock: ImageVector by lazy { icon("Clock", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2") }

    private fun icon(name: String, path: String, stroke: Float = 2.2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
