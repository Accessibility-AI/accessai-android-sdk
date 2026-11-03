package com.accessai.android

import android.Manifest
import android.app.Application
import android.content.Context
import com.accessai.core.auth.DeviceAuthenticator
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.accessai.core.Announcement
import com.accessai.core.actions.ActionRegistry
import com.accessai.core.engine.DialogEngine
import com.accessai.core.engine.EngineConfig
import com.accessai.core.engine.InputSource
import com.accessai.core.net.AccessAIBackend
import com.accessai.core.net.LocalRulesBackend
import com.accessai.core.screen.ScreenDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connects the dialog engine to the device: microphone, speech, preferences. All methods are safe to
 * call from UI click handlers; work runs on the main thread in the controller's own scope.
 */
public class AccessAIController internal constructor(
    private val context: Context,
    public val config: AccessAIConfig,
    backend: AccessAIBackend,
) {
    /** Every SENSITIVE action goes through this before a token is requested. There is no way to turn it off. */
    public val deviceAuthenticator: DeviceAuthenticator =
        config.deviceAuthenticator ?: BiometricDeviceAuthenticator(context.applicationContext as Application)

    public val registry: ActionRegistry = ActionRegistry(deviceAuthenticator)

    private val speechOut = AndroidSpeechOutput(context, config.locale, config.deferToScreenReader, config.speechRate)
    private val speechIn = AndroidSpeechInput(context, config.locale)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("accessai", Context.MODE_PRIVATE)

    public val engine: DialogEngine = DialogEngine(
        registry = registry,
        backend = backend,
        speech = speechOut,
        config = EngineConfig(
            appId = config.appId,
            locale = config.locale.toLanguageTag(),
            minConfidence = config.minConfidence,
            actionTimeoutMillis = config.actionTimeoutMillis,
            announceScreenChanges = config.announceScreenChanges,
        ),
    )

    /** True when running without a server: unsigned confirmations, development only. */
    public val isDevelopmentBackend: Boolean = backend is LocalRulesBackend

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    /** Whether accessibility mode is on. Remembered across launches. */
    public val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _consented = MutableStateFlow(prefs.getBoolean(KEY_CONSENT, false))
    /** Whether the user has read the first-run explanation. */
    public val consented: StateFlow<Boolean> = _consented.asStateFlow()

    private val _listening = MutableStateFlow<ListeningState>(ListeningState.Idle)
    public val listening: StateFlow<ListeningState> = _listening.asStateFlow()

    public val speaking: StateFlow<Boolean> get() = speechOut.speaking

    public val isVoiceAvailable: Boolean get() = speechIn.isAvailable

    public val hasMicrophonePermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    public val isScreenReaderActive: Boolean get() = speechOut.screenReaderActive

    // ---- Mode -------------------------------------------------------------------------------

    /** Turns accessibility mode on and announces it. [greeting] can add e.g. the balance. */
    public fun enable(greeting: Announcement? = null) {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        _enabled.value = true
        val intro = Announcement.of("AccessAI accessibility mode is on. Tap Speak, or type, and tell me what you'd like to do. Say help to hear your options.")
        engine.say(greeting?.let { it + intro } ?: intro)
    }

    public fun disable() {
        speechIn.cancel()
        _listening.value = ListeningState.Idle
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        _enabled.value = false
        scope.launch {
            engine.reset()
            engine.say(Announcement.of("AccessAI accessibility mode is off."))
        }
    }

    public fun acceptConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
        _consented.value = true
    }

    // ---- Input ------------------------------------------------------------------------------

    public fun startListening() {
        if (!isVoiceAvailable) {
            _listening.value = ListeningState.Unavailable
            engine.say(Announcement.of("Voice input isn't available on this phone. You can type your request instead."))
            return
        }
        if (!hasMicrophonePermission) {
            engine.say(Announcement.of("Microphone permission is off, so I can't listen. You can type your request instead."))
            return
        }
        engine.stopSpeaking()
        _listening.value = ListeningState.Listening()
        speechIn.start(object : AndroidSpeechInput.Listener {
            override fun onPartial(text: String) {
                _listening.value = ListeningState.Listening(text)
            }
            override fun onResult(text: String) {
                scope.launch { engine.submit(text, InputSource.VOICE) }
            }
            override fun onError(message: String) {
                engine.say(Announcement.of(message))
            }
            override fun onEnd() {
                _listening.value = ListeningState.Idle
            }
        })
    }

    /** Stops listening and uses what was heard. */
    public fun stopListening() {
        speechIn.stop()
    }

    /** Stops listening and discards what was heard. */
    public fun cancelListening() {
        speechIn.cancel()
        _listening.value = ListeningState.Idle
    }

    public fun submitText(text: String) {
        engine.stopSpeaking()
        scope.launch { engine.submit(text, InputSource.TEXT) }
    }

    // ---- Buttons ----------------------------------------------------------------------------

    public fun confirm(): Unit = launch { engine.confirmByTouch() }
    public fun cancel(): Unit = launch { engine.cancel() }
    public fun next(): Unit = launch { engine.next() }
    public fun previous(): Unit = launch { engine.previous() }
    public fun help(): Unit = launch { engine.help() }
    public fun describeScreen(): Unit = launch { engine.describeScreen() }
    public fun choose(index: Int): Unit = launch { engine.choose(index) }
    public fun repeat(): Unit = engine.repeat()
    public fun stopSpeaking(): Unit = engine.stopSpeaking()

    public fun publishScreen(descriptor: ScreenDescriptor?) {
        engine.setScreen(descriptor, announce = _enabled.value)
    }

    private fun launch(block: suspend () -> Unit) {
        engine.stopSpeaking()
        scope.launch { block() }
    }

    private companion object {
        const val KEY_ENABLED = "mode_enabled"
        const val KEY_CONSENT = "consent_v1"
    }
}
