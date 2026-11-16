package com.accessai.compose

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessai.android.AccessAI
import com.accessai.android.AccessAIController
import com.accessai.android.ListeningState
import com.accessai.core.engine.DialogState
import com.accessai.core.engine.InputKind
import com.accessai.core.engine.OutcomeKind
import com.accessai.core.engine.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/**
 * AccessAI voice mode (UI spec 1b): a panel over the host app that shows one state at a time — listening,
 * thinking, answer, clarification, confirmation, success, failure, microphone off, offline, not understood and
 * stopped — each with its own heading, at most three actions and Stop.
 *
 * Place it on top of the host screen, and hide the host content from accessibility services while it shows
 * (`Modifier.clearAndSetSemantics {}`), so TalkBack stays inside the panel. Uses the enclosing [AccessAITheme], or
 * the default one.
 *
 * @param onExit called after "Close" on the stopped screen; AccessAI mode is already off by then.
 * @param onUseWithoutVoice "Use the app without voice" on the offline screen. Defaults to turning AccessAI off.
 */
@Composable
public fun AccessAIModeScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    controller: AccessAIController = AccessAI.controller,
    onUseWithoutVoice: (() -> Unit)? = null,
) {
    EnsureAccessAITheme {
        ModeScreen(onExit, modifier, controller, onUseWithoutVoice ?: { controller.disable(); onExit() })
    }
}

@Composable
private fun ModeScreen(onExit: () -> Unit, modifier: Modifier, controller: AccessAIController, onUseWithoutVoice: () -> Unit) {
    val state by controller.engine.state.collectAsStateWithLifecycle()
    val transcript by controller.engine.transcript.collectAsStateWithLifecycle()
    val listening by controller.listening.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var stopped by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var micDenied by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) controller.startListening()
    }
    // The phone's own voice window, for phones whose speech service can't be used from an app.
    val systemVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onSystemVoiceResult(result.resultCode, result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }
    LaunchedEffect(controller) {
        controller.systemVoiceRequests.collect {
            val prompt = when (val s = controller.engine.state.value) {
                is DialogState.AwaitingConfirmation -> "Say \u201Cconfirm\u201D or \u201Ccancel\u201D"
                is DialogState.AwaitingInput -> s.question.display
                else -> "What would you like to do?"
            }
            runCatching { systemVoice.launch(controller.systemVoiceIntent(prompt)) }
        }
    }
    val diagnostics by controller.speechDiagnostics.collectAsStateWithLifecycle()

    // Back from Settings with the microphone allowed: leave the "Microphone is off" screen.
    LifecycleResumeEffect(Unit) {
        if (controller.hasMicrophonePermission) micDenied = false
        onPauseOrDispose { }
    }

    val listen: () -> Unit = {
        typing = false
        paused = false
        when {
            !controller.isVoiceAvailable -> typing = true
            controller.hasMicrophonePermission -> controller.startListening()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val stop: () -> Unit = {
        stopped = true
        typing = false
        controller.stop()
    }
    val close: () -> Unit = {
        controller.disable(announce = false)
        onExit()
    }

    // Listen once on open, after the greeting, unless TalkBack is reading the screen (it would hear itself).
    LaunchedEffect(Unit) {
        if (controller.isScreenReaderActive || !controller.isVoiceAvailable) return@LaunchedEffect
        withTimeoutOrNull(4_000) { controller.speaking.first { it } }
        controller.speaking.first { !it }
        if (controller.engine.state.value is DialogState.Idle && controller.listening.value == ListeningState.Idle && !stopped && !typing) listen()
    }

    // Screens that ask for an answer (confirm, which one, a missing detail) listen for it once AccessAI has finished
    // speaking, so the microphone never hears AccessAI. Up to three tries while the same question is on screen.
    val awaitingAnswer: DialogState? = state.takeIf { it is DialogState.AwaitingConfirmation || it is DialogState.AwaitingInput }
    LaunchedEffect(awaitingAnswer, stopped, typing) {
        if (awaitingAnswer == null || stopped || typing) return@LaunchedEffect
        if (controller.isScreenReaderActive || !controller.isVoiceAvailable || !controller.hasMicrophonePermission) return@LaunchedEffect
        try {
            if (controller.usesSystemVoiceInput) {
                // One voice window per question; it stays open until the user speaks or closes it.
                withTimeoutOrNull(3_000) { controller.speaking.first { it } }
                controller.speaking.first { !it }
                if (controller.engine.state.value == awaitingAnswer) controller.startListening()
                return@LaunchedEffect
            }
            repeat(3) {
                withTimeoutOrNull(3_000) { controller.speaking.first { it } }
                controller.speaking.first { !it }
                delay(250)
                if (controller.engine.state.value != awaitingAnswer || controller.listening.value != ListeningState.Idle) return@LaunchedEffect
                controller.startListening()
                controller.listening.first { it !is ListeningState.Listening }
                if (controller.engine.state.value != awaitingAnswer) return@LaunchedEffect
            }
        } finally {
            // Left the question (a button was tapped, or Stop): release the microphone.
            if (controller.engine.state.value != awaitingAnswer && controller.listening.value is ListeningState.Listening) controller.cancelListening()
        }
    }

    BackHandler { if (stopped) close() else stop() }

    val lastAsked = transcript.lastOrNull { it.speaker == Speaker.USER }?.text
    val resting = state is DialogState.Idle || state is DialogState.Outcome
    val partial = (listening as? ListeningState.Listening)?.partial

    PanelScaffold(
        modifier = modifier,
        onStop = if (stopped) null else stop,
        devMode = controller.isDevelopmentBackend,
        diagnostics = diagnostics.takeIf { controller.config.showSpeechDiagnostics },
    ) {
        val s = state
        when {
            stopped -> ExitScreen(onTurnOnAgain = { stopped = false; controller.resume() }, onClose = close)
            // These keep their content on screen while listening for the answer.
            s is DialogState.AwaitingConfirmation -> ConfirmScreen(s, controller, partial)
            s is DialogState.AwaitingInput && s.kind == InputKind.DISAMBIGUATION && !typing -> ClarifyScreen(s, controller, partial)
            partial != null -> ListeningScreen(
                partial = partial,
                question = (s as? DialogState.AwaitingInput)?.question?.display,
                onPause = { controller.cancelListening(); paused = true },
                onHelp = { controller.cancelListening(); controller.help() },
                onType = { controller.cancelListening(); typing = true },
            )
            s is DialogState.Interpreting || s is DialogState.Executing -> ThinkingScreen(
                heading = "Working on it",
                asked = lastAsked,
                note = if (s is DialogState.Executing) "Please wait for the result." else "Nothing has been sent.",
                onCancel = if (s is DialogState.Interpreting) controller::cancel else null,
            )
            s is DialogState.AwaitingAuthentication -> ThinkingScreen(
                heading = "Confirm it's you",
                asked = null,
                note = "Use your fingerprint, face or screen lock. Nothing is sent until your phone checks it's you.",
                onCancel = null,
            )
            typing && (resting || s is DialogState.AwaitingInput) -> TypingScreen(
                heading = (s as? DialogState.AwaitingInput)?.question?.display ?: "Type your request",
                onSend = { controller.submitText(it); typing = false },
                onUseVoice = listen,
            )
            s is DialogState.AwaitingInput -> QuestionScreen(
                question = s.question.display,
                voiceOff = micDenied || !controller.isVoiceAvailable,
                onListen = listen,
                onType = { typing = true },
                onCancel = controller::cancel,
            )
            s is DialogState.ReadingList -> ReadingScreen(s, controller)
            micDenied && resting -> PermissionScreen(
                onSend = { controller.submitText(it) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            s is DialogState.Outcome -> OutcomeScreen(
                outcome = s,
                controller = controller,
                onListen = listen,
                onType = { typing = true },
                onUseWithoutVoice = onUseWithoutVoice,
            )
            else -> ReadyScreen(
                paused = paused,
                voiceAvailable = controller.isVoiceAvailable,
                onListen = listen,
                onHelp = controller::help,
                onType = { typing = true },
            )
        }
    }
}

// ---- Screens --------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ReadyScreen(paused: Boolean, voiceAvailable: Boolean, onListen: () -> Unit, onHelp: () -> Unit, onType: () -> Unit) {
    Screen(
        key = "ready",
        cue = null,
        heading = if (paused) "Paused" else "What would you like to do?",
        actions = listOf(
            Action("Help", AccessAIIcons.Help, ActionVariant.SECONDARY, compact = true, onClick = onHelp),
            Action("Type instead", AccessAIIcons.Keyboard, ActionVariant.SECONDARY, compact = true, onClick = onType),
        ),
    ) {
        Mic(
            state = if (!voiceAvailable) MicState.DISABLED else if (paused) MicState.PAUSED else MicState.IDLE,
            label = if (paused) "Paused" else "Tap to speak",
            caption = if (voiceAvailable) "Tap the microphone and say what you need." else "Voice input isn't available on this phone.",
            onClick = onListen,
        )
        Hint("Say “help” to hear what I can do.")
    }
}

@Composable
private fun ColumnScope.ListeningScreen(partial: String, question: String?, onPause: () -> Unit, onHelp: () -> Unit, onType: () -> Unit) {
    Screen(
        key = "listening",
        cue = null, // The recogniser plays its own tone when the microphone opens.
        heading = "Listening",
        sub = question,
        actions = listOf(
            Action("Help", AccessAIIcons.Help, ActionVariant.SECONDARY, compact = true, onClick = onHelp),
            Action("Type instead", AccessAIIcons.Keyboard, ActionVariant.SECONDARY, compact = true, onClick = onType),
        ),
    ) {
        Mic(MicState.LISTENING, label = "Listening", caption = "Tap the microphone to pause.", onClick = onPause)
        // Partial words are shown but silent; AccessAI speaks once it has the final text.
        if (partial.isNotBlank()) TranscriptView("You said", partial, isFinal = false)
        Hint("Say “help” to hear what I can do.")
    }
}

@Composable
private fun ColumnScope.ThinkingScreen(heading: String, asked: String?, note: String, onCancel: (() -> Unit)?) {
    Screen(
        key = "thinking:$heading",
        cue = Cue.TICK,
        heading = heading,
        sub = note,
        subFirst = false,
        actions = listOfNotNull(onCancel?.let { Action("Cancel", AccessAIIcons.Close, ActionVariant.SECONDARY, onClick = it) }),
    ) {
        ThinkingIndicator()
        asked?.let { TranscriptView("You asked", it) }
    }
}

@Composable
private fun ColumnScope.QuestionScreen(question: String, voiceOff: Boolean, onListen: () -> Unit, onType: () -> Unit, onCancel: () -> Unit) {
    Screen(
        key = "question:$question",
        cue = Cue.QUESTION,
        heading = question,
        actions = listOf(
            Action("Type instead", AccessAIIcons.Keyboard, ActionVariant.SECONDARY, onClick = onType),
            Action("Cancel", AccessAIIcons.Close, ActionVariant.TERTIARY, onClick = onCancel),
        ),
    ) {
        Mic(if (voiceOff) MicState.DENIED else MicState.IDLE, label = "Tap to answer", caption = "Or type your answer.", onClick = onListen)
        Hint("Nothing is sent until you confirm.")
    }
}

@Composable
private fun ColumnScope.ClarifyScreen(s: DialogState.AwaitingInput, controller: AccessAIController, listening: String?) {
    val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    val sayable = s.choices.indices.take(words.size).map { "“${words[it]}”" }
    Screen(
        key = "clarify:${s.choices}",
        cue = Cue.QUESTION,
        heading = "Which one?",
        sub = "I found ${s.choices.size} matches. Say ${sayable.joinToString(" or ")}, or tap a name.",
        actions = listOf(
            Action("Repeat options", AccessAIIcons.Repeat, ActionVariant.SECONDARY, onClick = controller::repeat),
            Action("None of these", AccessAIIcons.Close, ActionVariant.TERTIARY, onClick = controller::cancel),
        ),
    ) {
        listening?.let { ListeningStrip(it, "Say ${sayable.joinToString(" or ")}.") }
        Column(verticalArrangement = Arrangement.spacedBy(AccessAISpace.button)) {
            s.choices.forEachIndexed { i, choice ->
                OptionRow(number = i + 1, word = words.getOrElse(i) { "${i + 1}" }, label = choice, onClick = { controller.choose(i) })
            }
        }
        Hint("Nothing is sent until you confirm.", bold = false)
    }
}

@Composable
private fun ColumnScope.ConfirmScreen(s: DialogState.AwaitingConfirmation, controller: AccessAIController, listening: String?) {
    val totalSeconds = 30
    val shown = remember(s) { System.currentTimeMillis() }
    // The 30 seconds start once AccessAI has finished reading the payment back, so the user gets all of them.
    var start by remember(s) { mutableStateOf<Long?>(null) }
    var now by remember(s) { mutableLongStateOf(shown) }
    LaunchedEffect(s) {
        withTimeoutOrNull(3_000) { controller.speaking.first { it } }
        withTimeoutOrNull(25_000) { controller.speaking.first { !it } }
        val begun = System.currentTimeMillis()
        start = begun
        now = begun
        while (true) {
            delay(1_000 - (now - begun) % 1_000)
            now = System.currentTimeMillis()
        }
    }
    // Never past the service's own expiry (60 s from when it was created). A skewed phone clock is ignored.
    val serverLeft = s.expiresAt.toEpochMilli() - shown
    val serverDeadline = if (serverLeft > 5_000) shown + serverLeft - 2_000 else Long.MAX_VALUE
    val deadline = start?.let { minOf(it + totalSeconds * 1_000L, serverDeadline) }
    val secondsLeft = deadline?.let { ceil((it - now) / 1_000.0).toInt().coerceIn(0, totalSeconds) } ?: totalSeconds
    LaunchedEffect(s, secondsLeft == 0) {
        if (secondsLeft == 0) controller.cancel() // Says "Cancelled. Nothing was sent."
    }

    val summary = s.summary.display.replaceFirstChar { it.uppercase() }
    val money = s.details?.amount != null
    Screen(
        key = "confirm:${s.actionId}:${s.summary.display}",
        cue = Cue.CHECK,
        heading = s.details?.title ?: if (money) "Confirm payment" else "Please confirm",
        sub = (if (money) "Money leaves your account when you confirm." else "Nothing happens until you confirm.") +
            " Say “confirm” or “cancel”.",
        actions = listOf(
            Action("Confirm", AccessAIIcons.Check, ActionVariant.PRIMARY, tall = true, description = "Confirm. $summary.", onClick = controller::confirm),
            Action("Cancel", AccessAIIcons.Close, ActionVariant.SECONDARY, tall = true, description = "Cancel. Do not send.", onClick = controller::cancel),
        ),
    ) {
        listening?.let { ListeningStrip(it, "Say \u201Cconfirm\u201D or \u201Ccancel\u201D.") }
        ConfirmationCard(summary = "$summary.", details = s.details, secondsLeft = secondsLeft, totalSeconds = totalSeconds)
    }
}

@Composable
private fun ColumnScope.ReadingScreen(s: DialogState.ReadingList, controller: AccessAIController) {
    val t = AccessAITheme.current
    Screen(
        key = "reading:${s.index}",
        cue = null,
        heading = "${s.index + 1} of ${s.items.size}",
        actions = listOf(
            Action("Previous", null, ActionVariant.SECONDARY, compact = true, enabled = s.index > 0, onClick = controller::previous),
            Action("Next", null, ActionVariant.SECONDARY, compact = true, enabled = s.index < s.items.size - 1, onClick = controller::next),
            Action("Stop reading", AccessAIIcons.Close, ActionVariant.TERTIARY, onClick = controller::cancel),
        ),
    ) {
        Card { Text(s.items[s.index].display, style = t.title, color = t.onSurface) }
    }
}

@Composable
private fun ColumnScope.OutcomeScreen(
    outcome: DialogState.Outcome,
    controller: AccessAIController,
    onListen: () -> Unit,
    onType: () -> Unit,
    onUseWithoutVoice: () -> Unit,
) {
    val t = AccessAITheme.current
    val text = outcome.announcement.display
    val value = outcome.value
    val detail = outcome.detail
    val key = "outcome:${outcome.kind}:${outcome.title}:$text:${outcome.misses}"
    val tryAgain = Action("Try again", AccessAIIcons.Repeat, ActionVariant.PRIMARY, onClick = onListen)
    val done = Action("Done", AccessAIIcons.Check, ActionVariant.SECONDARY, onClick = controller::dismiss)
    when (outcome.kind) {
        OutcomeKind.ANSWER -> Screen(
            key = key, cue = Cue.CHIME, heading = outcome.title,
            actions = listOf(
                Action("Repeat", AccessAIIcons.Repeat, ActionVariant.PRIMARY, onClick = controller::repeat),
                Action("Ask something else", AccessAIIcons.Mic, ActionVariant.SECONDARY, onClick = onListen),
            ),
        ) {
            Card {
                if (value != null) {
                    Text(value, style = t.display, color = t.onSurface, softWrap = false)
                    Text(detail ?: text, style = t.body, color = t.mutedText, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text(text, style = t.title, color = t.onSurface)
                    detail?.let { Text(it, style = t.body, color = t.mutedText, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            StatusBanner(StatusKind.INFO, "Read only", "I only read this. Nothing was changed.")
        }
        OutcomeKind.SUCCESS -> Screen(
            key = key, cue = Cue.SUCCESS, heading = outcome.title,
            actions = listOf(
                Action("Done", AccessAIIcons.Check, ActionVariant.PRIMARY, onClick = controller::dismiss),
                Action("Repeat", AccessAIIcons.Repeat, ActionVariant.SECONDARY, onClick = controller::repeat),
            ),
        ) {
            StatusBanner(StatusKind.SUCCESS, "Success", value ?: text)
            detail?.let { Body(it) }
        }
        OutcomeKind.FAILURE -> Screen(
            key = key, cue = Cue.FAILURE, heading = outcome.title, sub = detail, subFirst = false,
            actions = listOf(tryAgain, done.copy(icon = AccessAIIcons.Close)),
        ) {
            StatusBanner(StatusKind.ERROR, "Failed", text)
        }
        OutcomeKind.OFFLINE -> Screen(
            key = key, cue = Cue.LOW, heading = outcome.title,
            actions = listOf(tryAgain, Action("Use the app without voice", AccessAIIcons.Close, ActionVariant.TERTIARY, onClick = onUseWithoutVoice)),
        ) {
            StatusBanner(StatusKind.WARNING, "Offline", text, icon = AccessAIIcons.WifiOff)
        }
        OutcomeKind.NOT_UNDERSTOOD -> {
            val retry = tryAgain.copy(icon = AccessAIIcons.Mic)
            val type = Action("Type instead", AccessAIIcons.Keyboard, ActionVariant.SECONDARY, onClick = onType)
            // After three misses in a row, typing comes first.
            val first = if (outcome.misses >= 3) listOf(type.copy(variant = ActionVariant.PRIMARY), retry.copy(variant = ActionVariant.SECONDARY)) else listOf(retry, type)
            Screen(
                key = key, cue = Cue.SOFT, heading = outcome.title,
                actions = first + Action("Hear what I can do", AccessAIIcons.Help, ActionVariant.TERTIARY, onClick = controller::help),
            ) {
                StatusBanner(StatusKind.WARNING, "Not understood", text, icon = AccessAIIcons.Help)
            }
        }
        OutcomeKind.CANCELLED -> Screen(
            key = key, cue = null, heading = outcome.title,
            actions = listOf(Action("Ask something else", AccessAIIcons.Mic, ActionVariant.PRIMARY, onClick = onListen), done),
        ) {
            StatusBanner(StatusKind.INFO, "Cancelled", text, icon = AccessAIIcons.Close)
        }
        OutcomeKind.PROBLEM -> Screen(
            key = key, cue = Cue.LOW, heading = outcome.title,
            actions = listOf(tryAgain, done),
        ) {
            StatusBanner(StatusKind.WARNING, "Problem", text)
        }
    }
}

@Composable
private fun ColumnScope.TypingScreen(heading: String, onSend: (String) -> Unit, onUseVoice: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }
    Screen(
        key = "typing:$heading",
        cue = null,
        heading = heading,
        actions = listOf(
            Action("Send", AccessAIIcons.Check, ActionVariant.PRIMARY, enabled = text.isNotBlank(), onClick = send),
            Action("Use voice", AccessAIIcons.Mic, ActionVariant.SECONDARY, onClick = onUseVoice),
        ),
    ) {
        RequestField(text, { text = it }, send)
    }
}

@Composable
private fun ColumnScope.PermissionScreen(onSend: (String) -> Unit, onOpenSettings: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }
    Screen(
        key = "permission",
        cue = Cue.LOW,
        heading = "Microphone is off",
        actions = listOf(
            Action("Send", AccessAIIcons.Check, ActionVariant.PRIMARY, enabled = text.isNotBlank(), onClick = send),
            Action("Open settings", AccessAIIcons.Settings, ActionVariant.SECONDARY, onClick = onOpenSettings),
        ),
    ) {
        StatusBanner(StatusKind.WARNING, "Needs permission", "AccessAI can’t hear you. Allow the microphone, or type instead.", icon = AccessAIIcons.MicOff)
        RequestField(text, { text = it }, send)
    }
}

@Composable
private fun ColumnScope.ExitScreen(onTurnOnAgain: () -> Unit, onClose: () -> Unit) {
    Screen(
        key = "exit",
        cue = Cue.FALLING,
        heading = "AccessAI is off",
        sub = "Your screen reader works as normal. Nothing was saved.",
        actions = listOf(
            Action("Turn on again", AccessAIIcons.Mic, ActionVariant.PRIMARY, onClick = onTurnOnAgain),
            Action("Close", AccessAIIcons.Close, ActionVariant.SECONDARY, onClick = onClose),
        ),
    ) {
        StatusBanner(StatusKind.INFO, "Stopped", "Listening has ended. The microphone is off.", icon = AccessAIIcons.Power)
    }
}

// ---- Panel ----------------------------------------------------------------------------------------

internal data class Action(
    val label: String,
    val icon: ImageVector?,
    val variant: ActionVariant,
    val tall: Boolean = false,
    /** Sits side by side with the next compact action when there's room. */
    val compact: Boolean = false,
    val enabled: Boolean = true,
    val description: String? = null,
    val onClick: () -> Unit,
)

/** Stop for the current panel, so each screen's heading can offer it as a custom action. */
private val LocalStop = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Scrim over the host, then a bottom panel with the header (AccessAI and Stop). Each screen fills the rest
 * through [Screen].
 */
@Composable
internal fun PanelScaffold(
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)?,
    devMode: Boolean = false,
    diagnostics: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AccessAITheme.current
    val panelShape = RoundedCornerShape(topStart = t.panelRadius, topEnd = t.panelRadius)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The scrim takes taps so nothing behind the panel can be pressed by accident.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .clearAndSetSemantics { },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(panelShape)
                .background(t.surface)
                .border(t.stroke, t.outline, panelShape)
                .semantics {
                    paneTitle = "AccessAI"
                    isTraversalGroup = true
                }
                .padding(start = AccessAISpace.panel, end = AccessAISpace.panel, top = AccessAISpace.panel, bottom = 28.dp),
        ) {
            Header(onStop)
            if (devMode) {
                Text(
                    "Development mode: no AccessAI server. Simulated use only.",
                    style = t.caption.copy(fontWeight = FontWeight.Normal),
                    color = t.mutedText,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            diagnostics?.let {
                Text(
                    it,
                    style = t.caption.copy(fontWeight = FontWeight.Normal),
                    color = t.mutedText,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            CompositionLocalProvider(LocalStop provides onStop) { content() }
        }
    }
}

/**
 * One screen of the panel: heading, optional sub-text, scrollable content, then the actions pinned to the bottom
 * so Confirm and Cancel never scroll away at 200% text. Plays [cue] when [key] changes.
 * Focus order: heading first, Stop last (also a custom action on the heading).
 */
@Composable
internal fun ColumnScope.Screen(
    key: String,
    cue: Cue?,
    heading: String,
    sub: String? = null,
    subFirst: Boolean = true,
    actions: List<Action>,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = AccessAITheme.current
    val onStop = LocalStop.current
    PlayCue(key, cue)
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(top = AccessAISpace.block),
        verticalArrangement = Arrangement.spacedBy(AccessAISpace.block),
    ) {
        Text(
            heading,
            style = t.headline,
            color = t.onSurface,
            modifier = Modifier.semantics {
                heading()
                traversalIndex = -1f
                liveRegion = LiveRegionMode.Polite
                if (onStop != null) customActions = listOf(CustomAccessibilityAction("Stop AccessAI") { onStop(); true })
            },
        )
        if (sub != null && subFirst) Body(sub)
        content()
        if (sub != null && !subFirst) Body(sub)
    }
    if (actions.isNotEmpty()) {
        Spacer(Modifier.height(AccessAISpace.block))
        Actions(actions)
    }
}

@Composable
private fun Header(onStop: (() -> Unit)?) {
    val t = AccessAITheme.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = AccessAISize.minTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(t.iconSize).clip(CircleShape).background(t.primary), contentAlignment = Alignment.Center) {
                Icon(AccessAIIcons.Mic, contentDescription = null, tint = t.onPrimary, modifier = Modifier.size(t.iconSize * 0.64f))
            }
            Text("AccessAI", style = t.caption, color = t.onSurface)
        }
        if (onStop != null) {
            Row(
                Modifier
                    .heightIn(min = AccessAISize.minTarget)
                    .widthIn(min = AccessAISize.minTarget)
                    .clip(RoundedCornerShape(t.buttonRadius))
                    .border(t.stroke, t.onSurface, RoundedCornerShape(t.buttonRadius))
                    .clickable(role = Role.Button, onClick = onStop)
                    .clearAndSetSemantics {
                        contentDescription = "Stop AccessAI"
                        role = Role.Button
                        traversalIndex = 1f
                        onClick { onStop(); true }
                    }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AccessAIIcons.Close, contentDescription = null, tint = t.onSurface, modifier = Modifier.size(t.inlineIconSize))
                Text("Stop", style = t.label, color = t.onSurface)
            }
        }
    }
}

@Composable
private fun Actions(actions: List<Action>) {
    val sideBySide = LocalDensity.current.fontScale < 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(AccessAISpace.button)) {
        var i = 0
        while (i < actions.size) {
            val a = actions[i]
            val b = actions.getOrNull(i + 1)
            if (sideBySide && a.compact && b != null && b.compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(AccessAISpace.button)) {
                    ActionFor(a, Modifier.weight(1f))
                    ActionFor(b, Modifier.weight(1f))
                }
                i += 2
            } else {
                ActionFor(a, Modifier.fillMaxWidth())
                i += 1
            }
        }
    }
}

@Composable
private fun ActionFor(a: Action, modifier: Modifier) {
    ActionButton(
        label = a.label,
        onClick = a.onClick,
        modifier = modifier,
        icon = a.icon,
        variant = a.variant,
        tall = a.tall,
        enabled = a.enabled,
        contentDescription = a.description,
    )
}

// ---- Small pieces -----------------------------------------------------------------------------------

@Composable
private fun Mic(state: MicState, label: String, caption: String, onClick: () -> Unit) {
    val t = AccessAITheme.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MicButton(state, onClick)
        Text(label, style = t.title, color = t.onSurface, textAlign = TextAlign.Center)
        Text(caption, style = t.caption.copy(fontWeight = FontWeight.Normal), color = t.mutedText, textAlign = TextAlign.Center)
    }
}

/**
 * Shown inside a screen that is listening for its answer, instead of switching to the Listening screen.
 * The microphone tone and the words carry the state; partial words stay silent to TalkBack.
 */
@Composable
private fun ListeningStrip(partial: String, prompt: String) {
    val t = AccessAITheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.cardRadius))
            .border(t.stroke, t.primary, RoundedCornerShape(t.cardRadius))
            .clearAndSetSemantics { contentDescription = "Listening. $prompt" }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(t.iconSize).clip(CircleShape).background(t.primary), contentAlignment = Alignment.Center) {
            Icon(AccessAIIcons.Mic, contentDescription = null, tint = t.onPrimary, modifier = Modifier.size(t.iconSize * 0.64f))
        }
        Column(Modifier.weight(1f)) {
            Text("Listening", style = t.label, color = t.onSurface)
            Text(partial.ifBlank { prompt }, style = t.body, color = t.mutedText)
        }
    }
}

/** Three dots that fade in turn (3 s cycle, 0.5 s apart); static under reduced motion. "Thinking…" carries the state. */
@Composable
private fun ThinkingIndicator() {
    val t = AccessAITheme.current
    val dot = if (t.largeText) 24.dp else 16.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.cardRadius))
            .background(t.container)
            .border(t.stroke, t.outline, RoundedCornerShape(t.cardRadius))
            .clearAndSetSemantics { contentDescription = "Thinking. Please wait." }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val transition = if (t.reducedMotion) null else rememberInfiniteTransition(label = "dots")
            repeat(3) { i ->
                val alpha = transition?.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 500)),
                    label = "dot$i",
                )?.value ?: 1f
                Box(Modifier.size(dot).alpha(alpha).clip(CircleShape).background(t.onSurface))
            }
        }
        Text("Thinking…", style = t.label, color = t.onSurface)
    }
}

@Composable
private fun OptionRow(number: Int, word: String, label: String, onClick: () -> Unit) {
    val t = AccessAITheme.current
    val shape = RoundedCornerShape(t.cardRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AccessAISize.confirm)
            .clip(shape)
            .border(t.stroke, t.onSurface, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "${word.replaceFirstChar { it.uppercase() }}. $label. Double-tap to choose."
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(t.iconSize).clip(CircleShape).background(t.onSurface), contentAlignment = Alignment.Center) {
            Text("$number", style = t.label, color = t.surface)
        }
        Text(label, style = t.title, color = t.onSurface)
    }
}

@Composable
private fun RequestField(text: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    val t = AccessAITheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Type your request", style = t.caption, color = t.onSurface)
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            placeholder = { Text("For example: What’s my balance?", style = t.body, color = t.mutedText) },
            textStyle = t.body.copy(color = t.onSurface),
            singleLine = true,
            shape = RoundedCornerShape(t.cardRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = t.onSurface,
                unfocusedBorderColor = t.onSurface,
                cursorColor = t.onSurface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = AccessAISize.button).semantics { contentDescription = "Type your request" },
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val t = AccessAITheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.cardRadius))
            .background(t.container)
            .border(t.stroke, t.outline, RoundedCornerShape(t.cardRadius))
            .semantics(mergeDescendants = true) { }
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun Body(text: String) {
    val t = AccessAITheme.current
    Text(text, style = t.body, color = t.mutedText)
}

@Composable
private fun Hint(text: String, bold: Boolean = true) {
    val t = AccessAITheme.current
    Text(
        text,
        style = t.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = if (bold) t.onSurface else t.mutedText,
        textAlign = if (bold) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}
