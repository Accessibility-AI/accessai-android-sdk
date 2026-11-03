package com.accessai.core.auth

import com.accessai.core.Announcement
import com.accessai.core.protocol.AuthProof
import com.accessai.core.protocol.DeviceKeyInfo

/**
 * Proves the account holder is present before a sensitive action, using the phone's own lock
 * (fingerprint, face, PIN, pattern or password). AccessAI never sees the biometric or the PIN; it only
 * receives a signature from a device key that the OS unlocks after a successful check.
 *
 * On Android the SDK provides one (BiometricPrompt + Android Keystore); hosts can supply their own.
 */
public interface DeviceAuthenticator {
    /** The public half of the device key, registered with each AccessAI session. Null if the phone has no screen lock. */
    public suspend fun deviceKey(): DeviceKeyInfo?

    /** Shows the OS authentication prompt and, on success, signs [challenge.payload] with the device key. */
    public suspend fun authenticate(challenge: AuthChallenge): AuthOutcome
}

/** What gets signed. Binding it to the pending confirmation and parameter hash means a proof can't be reused. */
public class AuthChallenge(
    public val sessionId: String,
    public val pendingId: String,
    public val actionId: String,
    public val paramsSha256: String,
    /** What the user is approving, for the prompt's subtitle, e.g. "Send ₦5,000 to David Adeyemi (…2391)". */
    public val summary: Announcement,
) {
    /** UTF-8 bytes of this string are signed. The AI service rebuilds it to verify. */
    public val payload: String get() = "accessai-auth-v1|$sessionId|$pendingId|$actionId|$paramsSha256"
}

public sealed interface AuthOutcome {
    public data class Authenticated(val proof: AuthProof) : AuthOutcome

    /** The user dismissed the prompt. */
    public data object Cancelled : AuthOutcome

    /** The check ran and failed, e.g. too many attempts. [message] is spoken. */
    public data class Failed(val message: String) : AuthOutcome

    /** The phone can't do it, e.g. no screen lock set up. [message] is spoken. */
    public data class Unavailable(val message: String) : AuthOutcome
}
