package com.accessai.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.accessai.android.AccessAI
import com.accessai.android.AccessAIController
import com.accessai.core.screen.ScreenDescriptor

/**
 * Publishes [descriptor] while this composable is on screen, so AccessAI can describe the screen and
 * the interpreter knows where the user is. Place one inside each supported screen.
 */
@Composable
public fun AccessAIScreen(descriptor: ScreenDescriptor, controller: AccessAIController = AccessAI.controller) {
    DisposableEffect(descriptor) {
        controller.publishScreen(descriptor)
        onDispose { }
    }
}

/** A plain entry button for hosts that don't use [AccessAIEntryPanel]. Large, labelled and easy to find. */
@Composable
public fun AccessAIToggleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Turn on AccessAI accessibility mode",
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The entry point (UI spec "1 · Entry"): a panel pinned to the bottom of the host's home screen with a heading,
 * one line of explanation and a 72dp button. No announcement on load; the host's screen reads as normal.
 *
 * @param hint extra TalkBack text for the button, e.g. "Opens a short privacy notice." before consent.
 */
@Composable
public fun AccessAIEntryPanel(
    onTurnOn: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Use this app by voice",
    text: String = "Ask for your balance, send money and more. You always confirm before money moves.",
    buttonLabel: String = "Turn on AccessAI accessibility mode",
    hint: String? = null,
) {
    EnsureAccessAITheme {
        val t = AccessAITheme.current
        val shape = RoundedCornerShape(topStart = t.panelRadius, topEnd = t.panelRadius)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(t.surface)
                .border(t.stroke, t.outline, shape)
                .padding(start = AccessAISpace.panel, end = AccessAISpace.panel, top = AccessAISpace.panel, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AccessAISpace.block),
        ) {
            Text(title, style = t.headline, color = t.onSurface, modifier = Modifier.semantics { heading() })
            Text(text, style = t.body, color = t.mutedText)
            ActionButton(
                label = buttonLabel,
                onClick = onTurnOn,
                icon = AccessAIIcons.Mic,
                tall = true,
                contentDescription = listOfNotNull(buttonLabel, hint).joinToString(". "),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * First-run explanation (UI spec "1 · First-run consent"): what is sent, what is kept, how money is protected
 * and how to leave, shown as the AccessAI panel over the host. Stop is the same as "Not now".
 *
 * @param serverName where requests go, e.g. "the AccessAI service".
 */
@Composable
public fun AccessAIConsentScreen(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    appName: String = "this app",
    serverName: String = "the AccessAI service",
) {
    EnsureAccessAITheme {
        PanelScaffold(modifier = modifier, onStop = onNotNow) {
            Screen(
                key = "consent",
                cue = null,
                heading = "Before you start",
                actions = listOf(
                    Action("Agree and turn on", AccessAIIcons.Check, ActionVariant.PRIMARY, onClick = onContinue),
                    Action("Not now", AccessAIIcons.Close, ActionVariant.SECONDARY, onClick = onNotNow),
                ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsentItem(
                        AccessAIIcons.Cloud,
                        "What is sent",
                        "Your phone turns your voice into words. Those words, and the labels on this screen, go to " +
                            "$serverName only to work out what you mean. Balances and account details stay in $appName.",
                    )
                    ConsentItem(
                        AccessAIIcons.Trash,
                        "Nothing is kept",
                        "AccessAI never records your voice. Your words are not stored after each request ends.",
                    )
                    ConsentItem(
                        AccessAIIcons.Shield,
                        "You stay in control",
                        "Money moves only after you confirm and your phone checks it's you. Never say a PIN, password or code.",
                    )
                    ConsentItem(
                        AccessAIIcons.Power,
                        "How to leave",
                        "Tap Stop at any time, or press Back. Say “cancel” to stop a request.",
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentItem(icon: ImageVector, title: String, text: String) {
    val t = AccessAITheme.current
    Row(
        Modifier.semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = t.primary, modifier = Modifier.size(t.iconSize))
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body.copy(fontWeight = FontWeight.Bold), color = t.onSurface)
            Text(text, style = t.body.copy(lineHeight = t.body.fontSize * 1.35f), color = t.mutedText)
        }
    }
}
