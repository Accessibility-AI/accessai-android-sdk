package com.accessai.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.accessai.core.auth.AuthChallenge
import com.accessai.core.auth.AuthOutcome
import com.accessai.core.auth.DeviceAuthenticator
import com.accessai.core.protocol.AuthProof
import com.accessai.core.protocol.DeviceKeyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume

/**
 * Device authentication with the phone's own lock screen: fingerprint or face, falling back to PIN,
 * pattern or password. AccessAI never sees any of these; it gets only a signature.
 *
 * The signing key is EC P-256 in the Android Keystore (hardware-backed where available). The OS only
 * lets it sign for a few seconds after a successful screen-lock or strong-biometric check, and a new
 * fingerprint enrolment destroys it. The prompt runs over whichever FragmentActivity is in front.
 */
public class BiometricDeviceAuthenticator(private val app: Application) : DeviceAuthenticator {

    private var resumed: WeakReference<FragmentActivity>? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is FragmentActivity) resumed = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private val authenticators: Int =
        // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is only supported from Android 11.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override suspend fun deviceKey(): DeviceKeyInfo? = withContext(Dispatchers.Default) {
        if (BiometricManager.from(app).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) return@withContext null
        runCatching { ensureKey(); export() }.getOrNull()
    }

    override suspend fun authenticate(challenge: AuthChallenge): AuthOutcome = withContext(Dispatchers.Main) {
        when (BiometricManager.from(app).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                return@withContext AuthOutcome.Unavailable("To send money, set up a screen lock (PIN, pattern or password) on this phone first.")
            else -> return@withContext AuthOutcome.Unavailable("This phone can't confirm it's you right now.")
        }
        val activity = resumed?.get()
            ?: return@withContext AuthOutcome.Unavailable("I can't show the security check from this screen.")
        val key = try {
            withContext(Dispatchers.Default) { ensureKey() }
        } catch (e: Exception) {
            return@withContext AuthOutcome.Unavailable("This phone can't confirm it's you right now.")
        }

        val method = prompt(activity, challenge) ?: return@withContext AuthOutcome.Cancelled
        if (method is PromptResult.Error) return@withContext AuthOutcome.Failed(method.message)

        try {
            val signature = withContext(Dispatchers.Default) {
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(key)
                    update(challenge.payload.toByteArray(Charsets.UTF_8))
                    sign()
                }
            }
            val info = export()
            AuthOutcome.Authenticated(
                AuthProof(
                    method = (method as PromptResult.Ok).method,
                    kid = info.kid,
                    alg = info.alg,
                    signature = Base64.encodeToString(signature, Base64.NO_WRAP),
                ),
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            AuthOutcome.Failed("Your phone's security settings changed.")
        } catch (e: UserNotAuthenticatedException) {
            AuthOutcome.Failed("Please use your screen lock PIN, pattern or password.")
        } catch (e: Exception) {
            AuthOutcome.Failed("Your phone couldn't check it's you.")
        }
    }

    private sealed interface PromptResult {
        data class Ok(val method: String) : PromptResult
        data class Error(val message: String) : PromptResult
    }

    /** Null when the user dismissed the prompt. */
    private suspend fun prompt(activity: FragmentActivity, challenge: AuthChallenge): PromptResult? =
        suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val method = if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC) "biometric" else "device_credential"
                    if (cont.isActive) cont.resume(PromptResult.Ok(method))
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    if (cont.isActive) cont.resume(if (cancelled) null else PromptResult.Error("$errString."))
                }
                // A single unrecognised finger: the system prompt stays open and lets the user try again.
                override fun onAuthenticationFailed() = Unit
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm it's you")
                    .setSubtitle(challenge.summary.display.replaceFirstChar { it.uppercase() })
                    .setAllowedAuthenticators(authenticators)
                    .setConfirmationRequired(true)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    // ---- Keystore ------------------------------------------------------------------------

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey(): PrivateKey {
        (keyStore().getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setAttestationChallenge(ATTESTATION_CHALLENGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        try {
            generator.initialize(builder.build())
            generator.generateKeyPair()
        } catch (e: Exception) {
            // Some devices can't attest; the key is still OS-protected without it.
            builder.setAttestationChallenge(null)
            generator.initialize(builder.build())
            generator.generateKeyPair()
        }
        return keyStore().getKey(ALIAS, null) as PrivateKey
    }

    private fun export(): DeviceKeyInfo {
        val ks = keyStore()
        val der = ks.getCertificate(ALIAS).publicKey.encoded
        val chain = ks.getCertificateChain(ALIAS)?.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        val kid = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }.take(16)
        return DeviceKeyInfo(kid = kid, alg = "ES256", publicKey = Base64.encodeToString(der, Base64.NO_WRAP), attestationChain = chain)
    }

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "accessai_device_key_v1"
        const val AUTH_VALIDITY_SECONDS = 10
        val ATTESTATION_CHALLENGE = "accessai-device-key-v1".toByteArray()
    }
}
