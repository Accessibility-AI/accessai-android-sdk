package com.accessai.android

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/** What the microphone is doing, for the UI. */
public sealed interface ListeningState {
    public data object Idle : ListeningState
    public data class Listening(val partial: String = "") : ListeningState
    public data object Unavailable : ListeningState
}

/** Thin wrapper around the platform [SpeechRecognizer]. Must be used on the main thread. */
internal class AndroidSpeechInput(context: Context, private val locale: Locale) {

    interface Listener {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(message: String)
        /** Heard nothing usable (no match, silence, or an empty result). */
        fun onNoMatch()
        fun onEnd()
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Bumped by every start and cancel, so a delayed retry never revives a session the user has left. */
    private var sessions = 0
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Attempts in order: the configured locale on-device, the configured locale online, then plain English
     * online. Recognizers often have no pack for locales like en-NG and fail with "language not supported"
     * before hearing anything; any other error is reported as is. Retries reuse the same recognizer:
     * destroying it and creating another straight away loses the new session on a cold speech service
     * (ERROR_SERVER_DISCONNECTED). So each recognizer is released as soon as its session ends, and a session the
     * service drops before the microphone opens is retried once, quietly, with a fresh recognizer.
     */
    fun start(listener: Listener) {
        start(listener, reconnect = true)
    }

    private fun start(listener: Listener, reconnect: Boolean) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        val generation = ++sessions
        var attempt = 0
        var ready = false
        // A cancelled recognizer can still deliver a late callback (typically ERROR_CLIENT from cancel()).
        // Reporting it would announce a stale error over the next session, which the microphone then hears.
        val current = { recognizer === r }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!current()) return
                ready = true
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (current()) partialResults.bestResult()?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                if (!current()) return
                val text = results.bestResult()
                if (text.isNullOrBlank()) listener.onNoMatch() else listener.onResult(text)
                listener.onEnd()
                release(r)
            }
            override fun onError(error: Int) {
                if (!current()) return
                if (error in DROPPED && !ready && reconnect) {
                    // The service dropped the session before listening. Nothing was heard, so start again once.
                    Log.d(TAG, "Speech session dropped before listening (error $error); retrying once")
                    recognizer = null
                    r.destroy()
                    mainHandler.postDelayed({ if (sessions == generation && recognizer == null) start(listener, reconnect = false) }, RECONNECT_DELAY_MS)
                    return
                }
                if (error in LANGUAGE_ERRORS && attempt < LAST_ATTEMPT) {
                    attempt++
                    // Posted so the recognizer has finished reporting this error before it listens again.
                    mainHandler.post { if (current()) r.startListening(intentFor(attempt)) }
                    return
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onNoMatch()
                else listener.onError(messageFor(error))
                listener.onEnd()
                release(r)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intentFor(attempt))
    }

    private fun intentFor(attempt: Int): Intent {
        val (language, preferOffline) = when (attempt) {
            0 -> locale.toLanguageTag() to true
            1 -> locale.toLanguageTag() to false
            else -> FALLBACK_LANGUAGE to false
        }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep voice on the phone where an offline model is installed.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
    }

    /** Stops listening and processes what was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Frees [r] once its session has ended, so the next start never destroys and creates in the same instant. */
    private fun release(r: SpeechRecognizer) {
        mainHandler.post {
            if (recognizer === r) {
                recognizer = null
                r.destroy()
            }
        }
    }

    /** Stops listening and discards what was heard. */
    fun cancel() {
        sessions++
        val r = recognizer ?: return
        // Cleared first so any callback r still delivers is recognised as stale.
        recognizer = null
        r.cancel()
        r.destroy()
    }

    private fun Bundle?.bestResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "I didn't catch that. Please try again, or type your request."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Voice input needs an internet connection right now. You can type your request instead."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is off, so I can't listen. You can type your request, or allow the microphone in settings."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Voice input is busy. Please try again in a moment."
        in LANGUAGE_ERRORS ->
            "Voice input isn't available for your language on this phone. You can type your request instead."
        else -> "Voice input isn't working right now. You can type your request instead."
    }

    private companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31+); older
        // recognizers never send them.
        val LANGUAGE_ERRORS = setOf(12, 13)
        const val FALLBACK_LANGUAGE = "en-US"
        const val LAST_ATTEMPT = 2
        const val TAG = "AccessAI"
        const val RECONNECT_DELAY_MS = 300L
        // ERROR_CLIENT, and ERROR_SERVER_DISCONNECTED (API 31+): the session was lost, not the user's speech.
        val DROPPED = setOf(SpeechRecognizer.ERROR_CLIENT, 11)
    }
}
