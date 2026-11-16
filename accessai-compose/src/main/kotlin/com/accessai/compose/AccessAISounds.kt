package com.accessai.compose

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

/** A sound (and sometimes a haptic) per screen, from the TalkBack annotation sheet in UI spec 1h. */
internal enum class Cue {
    /** Thinking: one quiet tick, no loop. */
    TICK,
    /** Answer: a short neutral chime. */
    CHIME,
    /** Clarification: a two-note question tone. */
    QUESTION,
    /** Confirmation: a distinct "check" tone. */
    CHECK,
    /** Success: two rising notes, then one light haptic tick. */
    SUCCESS,
    /** Failure: one low falling tone and a double haptic. Never shares a sound with success. */
    FAILURE,
    /** Offline: one low tone. */
    LOW,
    /** Not understood: a soft neutral tone. */
    SOFT,
    /** Stop / exit: a falling tone. */
    FALLING,
}

/** Plays [cue] each time [key] changes to a new screen. Null plays nothing. */
@Composable
internal fun PlayCue(key: Any, cue: Cue?) {
    val view = LocalView.current
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50) }.getOrNull() }
    DisposableEffect(tones) { onDispose { tones?.release() } }
    LaunchedEffect(key) {
        val generator = tones ?: return@LaunchedEffect
        when (cue) {
            null -> Unit
            Cue.TICK -> generator.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            Cue.CHIME -> generator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Cue.QUESTION -> generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            Cue.CHECK -> generator.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 250)
            Cue.SUCCESS -> {
                generator.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                delay(320)
                view.haptic(success = true)
            }
            Cue.FAILURE -> {
                generator.startTone(ToneGenerator.TONE_PROP_NACK, 400)
                view.haptic(success = false)
                delay(160)
                view.haptic(success = false)
            }
            Cue.LOW -> generator.startTone(ToneGenerator.TONE_CDMA_LOW_SS, 300)
            Cue.SOFT -> generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
            Cue.FALLING -> generator.startTone(ToneGenerator.TONE_CDMA_HIGH_SS_2, 300)
        }
    }
}

private fun View.haptic(success: Boolean) {
    val constant = when {
        Build.VERSION.SDK_INT >= 30 && success -> HapticFeedbackConstants.CONFIRM
        Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.REJECT
        success -> HapticFeedbackConstants.KEYBOARD_TAP
        else -> HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}
