package com.accessai.android

import android.content.Context
import com.accessai.core.actions.ActionSpec
import com.accessai.core.auth.DeviceAuthenticator
import com.accessai.core.net.AccessAIBackend
import com.accessai.core.net.HttpAccessAIBackend
import com.accessai.core.net.LocalRulesBackend
import com.accessai.core.screen.ScreenDescriptor
import java.util.Locale

public data class AccessAIConfig(
    /** Public identifier of your app, registered with the AccessAI service. Not a secret. */
    val appId: String,
    /**
     * Base URL of the AccessAI service, e.g. "https://api.accessai.dev". When null, AccessAI uses
     * [LocalRulesBackend]: keyword matching and unsigned confirmations, for development only.
     */
    val serverUrl: String?,
    val locale: Locale = Locale("en", "NG"),
    /** Interpretations below this confidence become a clarifying question. */
    val minConfidence: Double = 0.6,
    /** How long an action handler may run before AccessAI tells the user it doesn't know the result. */
    val actionTimeoutMillis: Long = 20_000,
    /** Send announcements to TalkBack instead of text-to-speech while TalkBack is on. */
    val deferToScreenReader: Boolean = true,
    /** Announce each newly published screen's title. */
    val announceScreenChanges: Boolean = true,
    /** Text-to-speech rate; 1.0 is normal. */
    val speechRate: Float = 0.95f,
    /**
     * Device authentication for SENSITIVE actions. Null (the default) uses [BiometricDeviceAuthenticator]:
     * fingerprint or face with a PIN / pattern / password fallback. Replace it only with an equivalent OS-backed check.
     */
    val deviceAuthenticator: DeviceAuthenticator? = null,
)

/**
 * Entry point.
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         AccessAI.init(this, AccessAIConfig(appId = "accesspay-demo", serverUrl = BuildConfig.ACCESSAI_URL))
 *         AccessAI.registerAction(balanceAction)
 *     }
 * }
 * ```
 */
public object AccessAI {
    @Volatile
    private var instance: AccessAIController? = null

    /** Initialise once, typically in Application.onCreate. Returns the existing controller if already initialised. */
    @JvmStatic
    public fun init(context: Context, config: AccessAIConfig, backend: AccessAIBackend? = null): AccessAIController =
        instance ?: synchronized(this) {
            instance ?: AccessAIController(
                context.applicationContext,
                config,
                backend ?: config.serverUrl?.let { HttpAccessAIBackend(it, config.appId) } ?: LocalRulesBackend(),
            ).also { instance = it }
        }

    @JvmStatic
    public val controller: AccessAIController
        get() = checkNotNull(instance) { "Call AccessAI.init(context, config) first" }

    @JvmStatic
    public val isInitialized: Boolean get() = instance != null

    /** Adds an action to the allowlist. AccessAI can never invoke anything that is not registered. */
    @JvmStatic
    public fun registerAction(spec: ActionSpec): Unit = controller.registry.register(spec)

    /** Tells AccessAI which supported screen is visible. Prefer the AccessAIScreen composable. */
    @JvmStatic
    public fun publishScreen(descriptor: ScreenDescriptor?): Unit = controller.publishScreen(descriptor)
}
