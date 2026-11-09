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
        fun onEnd()
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Attempts in order: the configured locale on-device, the configured locale online, then plain English
     * online. Recognizers often have no pack for locales like en-NG and fail with "language not supported"
     * before hearing anything; any other error is reported as is. Retries reuse the same recognizer:
     * destroying it and creating another straight away loses the new session on a cold speech service.
     */
    fun start(listener: Listener) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        var attempt = 0
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestResult()?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                val text = results.bestResult()
                if (text.isNullOrBlank()) listener.onError("I didn't catch that. Please try again, or type your request.")
                else listener.onResult(text)
                listener.onEnd()
            }
            override fun onError(error: Int) {
                if (error in LANGUAGE_ERRORS && attempt < LAST_ATTEMPT) {
                    attempt++
                    // Posted so the recognizer has finished reporting this error before it listens again.
                    mainHandler.post { if (recognizer === r) r.startListening(intentFor(attempt)) }
                    return
                }
                listener.onError(messageFor(error))
                listener.onEnd()
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

    /** Stops listening and discards what was heard. */
    fun cancel() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
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
    }
}
