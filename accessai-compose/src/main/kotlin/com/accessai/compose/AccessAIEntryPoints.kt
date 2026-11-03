package com.accessai.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** The prominent entry point (PRD §6.1). Large, labelled, and the first thing TalkBack users can find. */
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
 * First-run explanation (PRD §6.1, §10.2): what AccessAI does, what is sent where, and how to leave.
 * [serverName] describes where requests go, e.g. "the AccessAI service".
 */
@Composable
public fun AccessAIConsentScreen(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    appName: String = "this app",
    serverName: String = "the AccessAI service",
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "About AccessAI",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Paragraph("AccessAI lets you use $appName by speaking or typing. You can check your balance, send money, and hear your transactions read aloud.")
            Paragraph("When you speak, your phone's speech service turns your voice into text. AccessAI does not record or keep your voice.")
            Paragraph("The text of your request is sent to $serverName to work out what you want. Your balance and account details stay in $appName and are not sent.")
            Paragraph("AccessAI will always read back a payment and wait for you to say \"confirm\" or tap Confirm. Then your phone's own security check (fingerprint, face, or screen lock) must succeed before anything is sent. You can say \"cancel\" at any time.")
            Paragraph("Never say your PIN, password or one-time code out loud. AccessAI ignores them and never asks for them; your phone or $appName will ask on its own screen.")
            Paragraph("Microphone access is optional. If you say no, you can type instead. You can turn AccessAI off at any time with the \"Turn off AccessAI\" button.")
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("Continue", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("Not now", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
}
