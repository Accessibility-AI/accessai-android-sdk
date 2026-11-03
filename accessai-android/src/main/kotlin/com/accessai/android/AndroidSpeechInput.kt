package com.accessai.android

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
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
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(listener: Listener) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
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
                listener.onError(messageFor(error))
                listener.onEnd()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep voice on the phone where an offline model is installed.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
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
        else -> "Voice input isn't working right now. You can type your request instead."
    }
}
