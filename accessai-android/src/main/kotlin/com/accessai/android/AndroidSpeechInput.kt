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
        /**
         * The phone's speech service can't be used from an app: it fails before hearing anything in every
         * language, or never answers. The caller can switch to the system voice-input window instead.
         */
        fun onUnavailable()
        fun onEnd()
    }

    /** One line per step (attempt, error code, timeout). Codes only, never anything the user said. */
    var onDiagnostic: ((String) -> Unit)? = null

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Bumped by every start and cancel, so a delayed retry never revives a session the user has left. */
    private var sessions = 0
    private val prefs = appContext.getSharedPreferences("accessai", Context.MODE_PRIVATE)
    /**
     * The first attempt that can work on this phone. Attempts that failed before hearing anything (the language
     * isn't supported here) are skipped from then on, so the user isn't kept waiting through them, or invited by
     * a "listening" tone to speak into one that is about to fail.
     */
    private var firstAttempt = prefs.getInt(KEY_FIRST_ATTEMPT, 0).coerceIn(0, LAST_ATTEMPT)
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Attempts in order: the configured locale on-device, the configured locale online, plain English online,
     * then plain English on-device. Recognizers often have no pack for locales like en-NG: some say "language
     * not supported", others report a network or server error before hearing anything, so either moves on to
     * the next attempt as long as the user hasn't started speaking.
     *
     * Some services (notably on Android Go phones) accept a session and then never answer, so a watchdog ends
     * a session that hasn't heard speech within [START_TIMEOUT_MS], or has heard speech but produced no result
     * within [SPEECH_TIMEOUT_MS]. Either means the service is unusable, reported through [Listener.onUnavailable].
     *
     * Retries reuse the same recognizer: destroying it and creating another straight away loses the new session
     * on a cold speech service (ERROR_SERVER_DISCONNECTED). So each recognizer is released as soon as its session
     * ends, and a session the service drops before the microphone opens is retried once, after a short pause.
     */
    fun start(listener: Listener) {
        begin(listener, reconnect = true)
    }

    private fun begin(listener: Listener, reconnect: Boolean) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        val generation = ++sessions
        var attempt = firstAttempt
        var ready = false
        var heard = false
        var audio = false
        // Incremented whenever a watchdog is armed or the session moves on, so only the latest one can fire.
        var watch = 0
        // A cancelled recognizer can still deliver a late callback (typically ERROR_CLIENT from cancel()).
        // Reporting it would announce a stale error over the next session, which the microphone then hears.
        val current = { recognizer === r }

        fun diag(event: String) {
            val line = "Voice try ${attempt + 1}/${LAST_ATTEMPT + 1} (${describe(attempt)}): $event"
            Log.i(TAG, line)
            onDiagnostic?.invoke(line)
        }

        fun giveUp(reason: String) {
            watch++
            diag("gave up: $reason")
            recognizer = null
            r.cancel()
            r.destroy()
            listener.onUnavailable()
            listener.onEnd()
        }

        fun armStart() {
            val token = ++watch
            mainHandler.postDelayed({
                if (!current() || watch != token || heard) return@postDelayed
                giveUp(if (!ready) "the speech service never started" else if (audio) "no speech detected" else "no sound from the microphone")
            }, START_TIMEOUT_MS)
        }

        fun armSpeech() {
            val token = ++watch
            mainHandler.postDelayed({
                if (!current() || watch != token) return@postDelayed
                diag("no result yet, stopping")
                r.stopListening()
                val last = ++watch
                mainHandler.postDelayed({ if (current() && watch == last) giveUp("no result after speech") }, RESULT_GRACE_MS)
            }, SPEECH_TIMEOUT_MS)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!current()) return
                ready = true
                diag("listening")
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (current()) audio = true
            }
            override fun onBeginningOfSpeech() {
                if (!current()) return
                heard = true
                diag("speech started")
                armSpeech()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (current()) partialResults.bestResult()?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                if (!current()) return
                watch++
                val text = results.bestResult()
                diag(if (text.isNullOrBlank()) "empty result" else "result")
                if (text.isNullOrBlank()) listener.onNoMatch() else listener.onResult(text)
                listener.onEnd()
                release(r)
            }
            override fun onError(error: Int) {
                if (!current()) return
                watch++
                diag("error $error${if (heard) " after speech" else ""}${if (audio) "" else ", no microphone sound"}")
                if (error in DROPPED && !ready && reconnect) {
                    // The service dropped the session before listening. Nothing was heard, so start again once.
                    recognizer = null
                    r.destroy()
                    mainHandler.postDelayed({ if (sessions == generation && recognizer == null) begin(listener, reconnect = false) }, RECONNECT_DELAY_MS)
                    return
                }
                // Busy before hearing anything can be momentary, so it moves on but isn't remembered.
                val busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !heard
                val serviceFailed = error in LANGUAGE_ERRORS || (error in SERVICE_ERRORS && !heard) || busy
                if (serviceFailed && attempt < LAST_ATTEMPT) {
                    attempt++
                    if (attempt > firstAttempt && !busy) {
                        firstAttempt = attempt
                        prefs.edit().putInt(KEY_FIRST_ATTEMPT, attempt).apply()
                    }
                    ready = false
                    audio = false
                    // Posted so the recognizer has finished reporting this error before it listens again.
                    mainHandler.post {
                        if (current()) {
                            diag("starting")
                            r.startListening(intentFor(attempt))
                            armStart()
                        }
                    }
                    return
                }
                when {
                    serviceFailed -> listener.onUnavailable()
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onNoMatch()
                    else -> listener.onError(messageFor(error))
                }
                listener.onEnd()
                release(r)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        diag("starting")
        r.startListening(intentFor(attempt))
        armStart()
    }

    private fun intentFor(attempt: Int): Intent {
        val (language, preferOffline) = attemptSettings(attempt)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep voice on the phone where an offline model is installed.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
    }

    private fun attemptSettings(attempt: Int): Pair<String, Boolean> = when (attempt) {
        0 -> locale.toLanguageTag() to true
        1 -> locale.toLanguageTag() to false
        2 -> FALLBACK_LANGUAGE to false
        else -> FALLBACK_LANGUAGE to true
    }

    private fun describe(attempt: Int): String = attemptSettings(attempt).let { (language, offline) ->
        "$language ${if (offline) "on device" else "online"}"
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
            "Voice input isn't working on this phone right now. You can type your request instead."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is off, so I can't listen. You can type your request, or allow the microphone in settings."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Voice input is busy. Please try again in a moment."
        SpeechRecognizer.ERROR_AUDIO ->
            "I couldn't use the microphone. Another app may be using it. You can type your request instead."
        else -> "Voice input isn't working right now. You can type your request instead."
    }

    private companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31+); older
        // recognizers never send them.
        val LANGUAGE_ERRORS = setOf(12, 13)
        const val FALLBACK_LANGUAGE = "en-US"
        const val LAST_ATTEMPT = 3
        const val KEY_FIRST_ATTEMPT = "speech_first_attempt"
        // Reported by some recognizers for a language they can't handle, before hearing anything.
        val SERVICE_ERRORS = setOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER)
        const val TAG = "AccessAI"
        const val RECONNECT_DELAY_MS = 300L
        // ERROR_CLIENT, and ERROR_SERVER_DISCONNECTED (API 31+): the session was lost, not the user's speech.
        val DROPPED = setOf(SpeechRecognizer.ERROR_CLIENT, 11)
        /** A working service reports silence itself (ERROR_SPEECH_TIMEOUT) within about 5 s. */
        const val START_TIMEOUT_MS = 10_000L
        const val SPEECH_TIMEOUT_MS = 15_000L
        const val RESULT_GRACE_MS = 5_000L
    }
}
