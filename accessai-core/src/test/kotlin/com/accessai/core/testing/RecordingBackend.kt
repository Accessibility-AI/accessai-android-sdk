package com.accessai.core.testing

import com.accessai.core.net.AccessAIBackend
import com.accessai.core.protocol.CancelConfirmationRequest
import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.CreateConfirmationRequest
import com.accessai.core.protocol.CreateSessionRequest
import com.accessai.core.protocol.InterpretRequest
import kotlinx.serialization.json.Json

/** Wraps a backend and records every request exactly as it would go over the wire. */
class RecordingBackend(private val delegate: AccessAIBackend) : AccessAIBackend {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    val wire = mutableListOf<String>()
    val sessions = mutableListOf<CreateSessionRequest>()
    val interprets = mutableListOf<InterpretRequest>()
    val confirms = mutableListOf<ConfirmRequest>()

    /** Everything that left the phone, as one string. */
    fun everythingSent(): String = wire.joinToString("\n")

    override suspend fun createSession(request: CreateSessionRequest) =
        delegate.createSession(request.also { sessions += it; wire += json.encodeToString(CreateSessionRequest.serializer(), it) })

    override suspend fun interpret(request: InterpretRequest) =
        delegate.interpret(request.also { interprets += it; wire += json.encodeToString(InterpretRequest.serializer(), it) })

    override suspend fun createConfirmation(request: CreateConfirmationRequest) =
        delegate.createConfirmation(request.also { wire += json.encodeToString(CreateConfirmationRequest.serializer(), it) })

    override suspend fun confirm(pendingId: String, request: ConfirmRequest) =
        delegate.confirm(pendingId, request.also { confirms += it; wire += json.encodeToString(ConfirmRequest.serializer(), it) })

    override suspend fun cancelConfirmation(pendingId: String, request: CancelConfirmationRequest) =
        delegate.cancelConfirmation(pendingId, request.also { wire += json.encodeToString(CancelConfirmationRequest.serializer(), it) })
}
