package com.accessai.compose

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessai.android.AccessAI
import com.accessai.android.AccessAIController
import com.accessai.android.ListeningState
import com.accessai.core.Announcement
import com.accessai.core.engine.DialogState
import com.accessai.core.engine.InputKind
import com.accessai.core.engine.Speaker

/**
 * The AccessAI accessibility-mode screen (PRD §6.2): status, a large Speak button, a text fallback,
 * the latest response in large type, Confirm/Cancel for payments, and Repeat / Stop / Where am I / Help / Exit.
 *
 * Every control is a real button with a text label, so TalkBack, Switch Access and magnification all work.
 * Announcements are spoken by the controller (TTS, or TalkBack when it is on), so nothing here uses a
 * live region for the same text — that would make TalkBack read it twice.
 */
@Composable
public fun AccessAIModeScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    controller: AccessAIController = AccessAI.controller,
    title: String = "AccessAI is on",
) {
    val state by controller.engine.state.collectAsStateWithLifecycle()
    val last by controller.engine.lastAnnouncement.collectAsStateWithLifecycle()
    val transcript by controller.engine.transcript.collectAsStateWithLifecycle()
    val listening by controller.listening.collectAsStateWithLifecycle()
    val speaking by controller.speaking.collectAsStateWithLifecycle()

    var micDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) controller.startListening()
        else controller.engine.say(Announcement.of("Microphone permission was not given. You can type your request instead."))
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (controller.isDevelopmentBackend) {
                Text(
                    "Development mode: no AccessAI server is configured. Simulated use only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            StatusLine(state, listening)

            // The latest response, large, for low-vision users.
            last?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it.display,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }

            StateControls(state, controller)

            SpeakButton(
                listening = listening,
                onStart = {
                    if (controller.hasMicrophonePermission) controller.startListening()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = controller::stopListening,
            )
            if (micDenied || !controller.isVoiceAvailable) {
                Text(
                    "Voice input is off. You can type your request below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            TypeRequest(onSend = controller::submitText)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("Repeat", Modifier.weight(1f), onClick = controller::repeat)
                SecondaryButton("Stop speaking", Modifier.weight(1f), enabled = speaking, onClick = controller::stopSpeaking)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("Where am I?", Modifier.weight(1f), onClick = controller::describeScreen)
                SecondaryButton("Help", Modifier.weight(1f), onClick = controller::help)
            }

            Conversation(transcript.takeLast(8).map { (if (it.speaker == Speaker.USER) "You: " else "AccessAI: ") + it.text })

            OutlinedButton(
                onClick = {
                    controller.disable()
                    onExit()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text("Turn off AccessAI accessibility mode", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatusLine(state: DialogState, listening: ListeningState) {
    val text = when {
        listening is ListeningState.Listening -> if (listening.partial.isBlank()) "Listening…" else "Listening: ${listening.partial}"
        state is DialogState.Interpreting -> "Working…"
        state is DialogState.Executing -> "Working…"
        state is DialogState.AwaitingConfirmation -> "Waiting for your confirmation"
        state is DialogState.AwaitingAuthentication -> "Confirm it's you on your phone's security check"
        state is DialogState.AwaitingInput -> "Waiting for your answer"
        state is DialogState.ReadingList -> "Reading ${state.index + 1} of ${state.items.size}"
        else -> "Ready"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.secondary,
        // Short status changes ("Listening…", "Working…") are announced; the content itself is spoken elsewhere.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun StateControls(state: DialogState, controller: AccessAIController) {
    when (state) {
        is DialogState.AwaitingConfirmation -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = controller::confirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .semantics { contentDescription = "Confirm: ${state.summary.display}" },
            ) { Text("Confirm", style = MaterialTheme.typography.labelLarge) }
            Button(
                onClick = controller::cancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
        }
        is DialogState.AwaitingInput -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.kind == InputKind.DISAMBIGUATION) {
                state.choices.forEachIndexed { i, choice ->
                    OutlinedButton(
                        onClick = { controller.choose(i) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                    ) { Text(choice, style = MaterialTheme.typography.labelLarge) }
                }
            }
            SecondaryButton("Cancel", Modifier.fillMaxWidth(), onClick = controller::cancel)
        }
        is DialogState.ReadingList -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Previous", Modifier.weight(1f), enabled = state.index > 0, onClick = controller::previous)
            SecondaryButton("Next", Modifier.weight(1f), enabled = state.index < state.items.size - 1, onClick = controller::next)
            SecondaryButton("Stop", Modifier.weight(1f), onClick = controller::cancel)
        }
        else -> Unit
    }
}

@Composable
private fun SpeakButton(listening: ListeningState, onStart: () -> Unit, onStop: () -> Unit) {
    val isListening = listening is ListeningState.Listening
    Button(
        onClick = if (isListening) onStop else onStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .semantics { stateDescription = if (isListening) "Listening" else "Not listening" },
    ) {
        Text(if (isListening) "Stop listening" else "Speak", style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun TypeRequest(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Type your request") },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton("Send", Modifier.fillMaxWidth(), enabled = text.isNotBlank(), onClick = send)
    }
}

@Composable
private fun Conversation(lines: List<String>) {
    if (lines.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "Hide conversation" else "Show conversation", style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
