package com.accessai.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accessai.core.engine.ConfirmationDetails

// The five components from UI spec 1g. They read AccessAITheme.current, so place them inside AccessAITheme { }.

// ---- ActionButton -------------------------------------------------------------------------------

public enum class ActionVariant { PRIMARY, SECONDARY, TERTIARY }

/**
 * Every tappable action, mirroring a spoken command. At least 64dp high (72dp with [tall], for Confirm and
 * Cancel; 56dp for tertiary). Focus draws a 4dp outline 3dp outside; disabled is dashed and says "unavailable".
 */
@Composable
public fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: ActionVariant = ActionVariant.PRIMARY,
    tall: Boolean = false,
    enabled: Boolean = true,
    /** What TalkBack reads, when it should say more than [label], e.g. "Confirm. Send 5,000 naira to David Adeyemi." */
    contentDescription: String? = null,
) {
    val t = AccessAITheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(t.buttonRadius)
    val background: Color
    val content: Color
    val border: Color?
    when {
        !enabled -> { background = t.container; content = t.mutedText; border = null }
        variant == ActionVariant.PRIMARY -> {
            background = if (pressed) mix(t.primary, if (t.highContrast) Color.White else Color.Black, 0.25f) else t.primary
            content = t.onPrimary
            border = t.primary
        }
        variant == ActionVariant.SECONDARY -> { background = if (pressed) t.container else Color.Transparent; content = t.onSurface; border = t.onSurface }
        else -> { background = if (pressed) t.container else Color.Transparent; content = t.onSurface; border = null }
    }
    val minHeight = when {
        tall -> AccessAISize.confirm
        variant == ActionVariant.TERTIARY -> AccessAISize.minTarget
        else -> AccessAISize.button
    }
    val description = (contentDescription ?: label) + if (enabled) "" else ", unavailable"
    Row(
        modifier = modifier
            .focusRing(focused, t.buttonRadius, t.onSurface)
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(background)
            .then(
                when {
                    !enabled -> Modifier.dashedBorder(t.stroke, t.outline, t.buttonRadius)
                    border != null -> Modifier.border(t.stroke, border, shape)
                    else -> Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = description
                role = Role.Button
                if (enabled) onClick { onClick(); true } else disabled()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(AccessAISpace.icon, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(t.inlineIconSize)) }
        Text(
            label,
            style = t.label,
            color = content,
            textAlign = TextAlign.Center,
            textDecoration = if (variant == ActionVariant.TERTIARY) TextDecoration.Underline else null,
        )
    }
}

// ---- MicButton ----------------------------------------------------------------------------------

public enum class MicState { IDLE, LISTENING, PAUSED, DENIED, DISABLED }

/**
 * Starts, pauses and shows listening. 128dp (160dp at large text sizes), with a slow opacity ring while
 * listening: 25% to 75% over 3.2 s, no scale or movement, and static under reduced motion.
 */
@Composable
public fun MicButton(state: MicState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = AccessAITheme.current
    val size = if (t.largeText) AccessAISize.micLarge else AccessAISize.mic
    val iconSize = if (t.largeText) 72.dp else 56.dp
    val ring = 3.dp
    val ringGap = 10.dp
    val ringAlpha = if (state != MicState.LISTENING) {
        0f
    } else if (t.reducedMotion) {
        1f
    } else {
        val pulse = rememberInfiniteTransition(label = "mic")
        val a by pulse.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "ring",
        )
        a
    }
    val stateText = when (state) {
        MicState.IDLE -> "Not listening. Double-tap to speak."
        MicState.LISTENING -> "Listening. Double-tap to pause."
        MicState.PAUSED -> "Paused. Double-tap to listen."
        MicState.DENIED -> "Off. Microphone permission is needed."
        MicState.DISABLED -> "Unavailable."
    }
    Box(
        modifier = modifier
            .size(size + (ring + ringGap) * 2)
            .clearAndSetSemantics {
                contentDescription = "Microphone"
                stateDescription = stateText
                role = Role.Button
                if (state == MicState.DISABLED) disabled() else onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().alpha(ringAlpha).border(ring, t.primary, CircleShape))
        val fill = when (state) {
            MicState.IDLE, MicState.LISTENING -> t.primary
            MicState.DISABLED -> t.container
            else -> Color.Transparent
        }
        val tint = when (state) {
            MicState.IDLE, MicState.LISTENING -> t.onPrimary
            MicState.PAUSED -> t.onSurface
            else -> t.mutedText
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(fill)
                .then(
                    when (state) {
                        MicState.PAUSED -> Modifier.border(ring, t.onSurface, CircleShape)
                        MicState.DENIED -> Modifier.dashedBorder(ring, t.mutedText, size / 2)
                        else -> Modifier
                    },
                )
                .clickable(enabled = state != MicState.DISABLED, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state == MicState.DENIED) AccessAIIcons.MicOff else AccessAIIcons.Mic,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

// ---- TranscriptView -----------------------------------------------------------------------------

/**
 * What the user said or asked. Partial results are silent to TalkBack; the final text is a polite live region.
 */
@Composable
public fun TranscriptView(label: String, text: String, modifier: Modifier = Modifier, isFinal: Boolean = true) {
    val t = AccessAITheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(t.cardRadius))
            .background(t.container)
            .border(t.stroke, t.outline, RoundedCornerShape(t.cardRadius))
            .clearAndSetSemantics {
                contentDescription = "$label: $text"
                if (isFinal) liveRegion = LiveRegionMode.Polite
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = t.caption.copy(letterSpacing = 0.84.sp), color = t.mutedText)
        Text(text, style = t.title.copy(fontWeight = FontWeight.Normal, lineHeight = t.title.fontSize * 1.3f), color = t.onSurface)
    }
}

// ---- StatusBanner -------------------------------------------------------------------------------

public enum class StatusKind { INFO, SUCCESS, ERROR, WARNING }

/**
 * A result or system status: icon shape, a word and text, always together, so status never relies on colour.
 * The text stays in onSurface; only the icon, label and border use the status colour.
 */
@Composable
public fun StatusBanner(kind: StatusKind, label: String, text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val t = AccessAITheme.current
    val color = when (kind) {
        StatusKind.INFO -> t.info
        StatusKind.SUCCESS -> t.positive
        StatusKind.ERROR -> t.critical
        StatusKind.WARNING -> t.warning
    }
    val glyph = icon ?: when (kind) {
        StatusKind.INFO -> AccessAIIcons.Info
        StatusKind.SUCCESS -> AccessAIIcons.CheckCircle
        StatusKind.ERROR -> AccessAIIcons.ErrorOctagon
        StatusKind.WARNING -> AccessAIIcons.Warning
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.cardRadius))
            .background(t.container)
            .border(t.stroke, color, RoundedCornerShape(t.cardRadius))
            .clearAndSetSemantics { contentDescription = "$label. $text" }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(glyph, contentDescription = null, tint = color, modifier = Modifier.size(t.iconSize))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = t.label, color = color)
            Text(text, style = t.body.copy(lineHeight = t.body.fontSize * 1.35f), color = t.onSurface)
        }
    }
}

// ---- ConfirmationCard ---------------------------------------------------------------------------

/**
 * Amount, recipient, account and fee in large type, read by TalkBack as one node ([summary]), then the countdown.
 * Without [details] the summary itself is shown large.
 */
@Composable
public fun ConfirmationCard(
    summary: String,
    details: ConfirmationDetails?,
    secondsLeft: Int,
    modifier: Modifier = Modifier,
    totalSeconds: Int = 30,
) {
    val t = AccessAITheme.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AccessAISpace.block)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.cardRadius))
                .background(t.container)
                .border(t.stroke + 1.dp, t.onSurface, RoundedCornerShape(t.cardRadius))
                .clearAndSetSemantics { contentDescription = summary }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (details == null) {
                Text(summary, style = t.title, color = t.onSurface)
            } else {
                Caption(details.verb)
                details.amount?.let { Text(it, style = t.display, color = t.onSurface, softWrap = false) }
                details.recipient?.let {
                    Caption("To", Modifier.padding(top = 10.dp))
                    Text(it, style = t.recipient, color = t.onSurface)
                }
                details.recipientDetail?.let { Text(it, style = t.title.copy(fontWeight = FontWeight.Normal), color = t.onSurface) }
                details.note?.let {
                    Box(Modifier.padding(top = 10.dp, bottom = 6.dp).fillMaxWidth().height(t.stroke).alpha(0.5f).background(t.outline))
                    Text(it, style = t.body, color = t.mutedText)
                }
            }
        }
        ConfirmationCountdown(secondsLeft, totalSeconds)
    }
}

/**
 * "Cancels in 18 seconds" and a bar that steps once a second and never blinks. TalkBack hears it only at 20, 10
 * and 5 seconds, as polite updates, never every second.
 */
@Composable
public fun ConfirmationCountdown(secondsLeft: Int, totalSeconds: Int = 30, modifier: Modifier = Modifier) {
    val t = AccessAITheme.current
    val left = secondsLeft.coerceIn(0, totalSeconds)
    val milestone = listOf(5, 10, 20).firstOrNull { left <= it } ?: totalSeconds
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Cancels in $milestone seconds. If you do nothing, nothing is sent."
                progressBarRangeInfo = ProgressBarRangeInfo(milestone.toFloat(), 0f..totalSeconds.toFloat())
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AccessAISpace.icon), verticalAlignment = Alignment.CenterVertically) {
            Icon(AccessAIIcons.Clock, contentDescription = null, tint = t.onSurface, modifier = Modifier.size(t.inlineIconSize))
            Text(
                "Cancels in $left ${if (left == 1) "second" else "seconds"}",
                style = t.body.copy(fontWeight = if (left <= 5) FontWeight.ExtraBold else FontWeight.Bold),
                color = t.onSurface,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .border(t.stroke, t.onSurface, RoundedCornerShape(10.dp))
                .padding(t.stroke + 2.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(left / totalSeconds.toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(t.onSurface),
            )
        }
        Text("If you do nothing, nothing is sent.", style = t.caption.copy(fontWeight = FontWeight.Normal), color = t.mutedText)
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    val t = AccessAITheme.current
    Text(text.uppercase(), style = t.caption.copy(letterSpacing = 1.1.sp), color = t.mutedText, modifier = modifier)
}

// ---- Drawing helpers ------------------------------------------------------------------------------

/** A 4dp outline 3dp outside the element while it has keyboard or switch focus (UI spec 1g). */
internal fun Modifier.focusRing(focused: Boolean, radius: Dp, color: Color): Modifier = drawBehind {
    if (!focused) return@drawBehind
    val width = 4.dp.toPx()
    val out = 3.dp.toPx() + width / 2
    val r = (radius.toPx() + out).coerceAtMost((size.height / 2) + out)
    drawRoundRect(
        color = color,
        topLeft = Offset(-out, -out),
        size = Size(size.width + out * 2, size.height + out * 2),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width),
    )
}

internal fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val w = width.toPx()
    val r = radius.toPx().coerceAtMost(size.minDimension / 2)
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 2))),
    )
}
