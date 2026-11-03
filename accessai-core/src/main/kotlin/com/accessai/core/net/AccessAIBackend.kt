package com.accessai.core.net

import com.accessai.core.protocol.CancelConfirmationRequest
import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.ConfirmResponse
import com.accessai.core.protocol.CreateConfirmationRequest
import com.accessai.core.protocol.CreateConfirmationResponse
import com.accessai.core.protocol.CreateSessionRequest
import com.accessai.core.protocol.CreateSessionResponse
import com.accessai.core.protocol.InterpretRequest
import com.accessai.core.protocol.InterpretResponse

/** The AccessAI service. [HttpAccessAIBackend] talks to the real one; [LocalRulesBackend] is for offline development. */
public interface AccessAIBackend {
    public suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse
    public suspend fun interpret(request: InterpretRequest): InterpretResponse
    public suspend fun createConfirmation(request: CreateConfirmationRequest): CreateConfirmationResponse
    public suspend fun confirm(pendingId: String, request: ConfirmRequest): ConfirmResponse
    public suspend fun cancelConfirmation(pendingId: String, request: CancelConfirmationRequest)
}

public class BackendException(
    /** Error code from the contract, or "network" / "timeout" / "server" / "bad_response". */
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public val isConnectivity: Boolean get() = code == NETWORK || code == TIMEOUT

    public companion object {
        public const val NETWORK: String = "network"
        public const val TIMEOUT: String = "timeout"
        public const val SERVER: String = "server"
        public const val BAD_RESPONSE: String = "bad_response"
        public const val SESSION_EXPIRED: String = "session_expired"
        public const val NOT_A_CONFIRMATION: String = "not_a_confirmation"
        public const val CONFIRMATION_EXPIRED: String = "confirmation_expired"
        public const val CONFIRMATION_USED: String = "confirmation_used"
        /** Missing, invalid, or signed by a key other than the session's device key. */
        public const val AUTH_INVALID: String = "auth_invalid"
    }
}
