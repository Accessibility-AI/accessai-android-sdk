package com.accessai.core.testing

import com.accessai.core.net.AccessAIBackend
import com.accessai.core.net.BackendException
import com.accessai.core.protocol.CancelConfirmationRequest
import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.ConfirmResponse
import com.accessai.core.protocol.CreateConfirmationRequest
import com.accessai.core.protocol.CreateConfirmationResponse
import com.accessai.core.protocol.CreateSessionRequest
import com.accessai.core.protocol.CreateSessionResponse
import com.accessai.core.protocol.InterpretRequest
import com.accessai.core.protocol.InterpretResponse

/** A backend that returns whatever the test says, to simulate a misbehaving or unavailable service. */
class ScriptedBackend(
    private val interpretResponse: InterpretResponse? = null,
    private val interpretError: Boolean = false,
    private val confirmationResponse: CreateConfirmationResponse? = null,
    /** Error code thrown by every /interpret call instead of [interpretError]'s network error. */
    private val interpretErrorCode: String? = null,
    /** Error codes thrown by the next /confirmations calls, in order. */
    val createConfirmationErrors: ArrayDeque<String> = ArrayDeque(),
    /** Error codes thrown by the next /confirm calls, in order. */
    val confirmErrors: ArrayDeque<String> = ArrayDeque(),
) : AccessAIBackend {
    val interpretRequests = mutableListOf<InterpretRequest>()
    val createConfirmationRequests = mutableListOf<CreateConfirmationRequest>()
    val confirmRequests = mutableListOf<ConfirmRequest>()
    private var sessions = 0

    override suspend fun createSession(request: CreateSessionRequest) = CreateSessionResponse("s${++sessions}")

    override suspend fun interpret(request: InterpretRequest): InterpretResponse {
        interpretRequests += request
        interpretErrorCode?.let { throw BackendException(it, it) }
        if (interpretError) throw BackendException(BackendException.NETWORK, "offline")
        return interpretResponse ?: InterpretResponse(outcome = "unsupported")
    }

    override suspend fun createConfirmation(request: CreateConfirmationRequest): CreateConfirmationResponse {
        createConfirmationRequests += request
        createConfirmationErrors.removeFirstOrNull()?.let { throw BackendException(it, it) }
        return confirmationResponse ?: CreateConfirmationResponse("pc_1", sha256(request.params.toString()), "2100-01-01T00:00:00Z")
    }

    override suspend fun confirm(pendingId: String, request: ConfirmRequest): ConfirmResponse {
        confirmRequests += request
        confirmErrors.removeFirstOrNull()?.let { throw BackendException(it, it) }
        return ConfirmResponse("confirmed", "token", "nonce", "2100-01-01T00:00:00Z")
    }

    override suspend fun cancelConfirmation(pendingId: String, request: CancelConfirmationRequest) = Unit
}

/** The SDK sends params already in canonical form (sorted keys, no whitespace), so this matches its own hash. */
private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
