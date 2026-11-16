package com.accessai.core.net

import com.accessai.core.protocol.CancelConfirmationRequest
import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.ConfirmResponse
import com.accessai.core.protocol.CreateConfirmationRequest
import com.accessai.core.protocol.CreateConfirmationResponse
import com.accessai.core.protocol.CreateSessionRequest
import com.accessai.core.protocol.CreateSessionResponse
import com.accessai.core.protocol.ErrorResponse
import com.accessai.core.protocol.InterpretRequest
import com.accessai.core.protocol.InterpretResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTPS client for the AccessAI service (contract v1.1). Holds no secrets: [appId] is a public identifier.
 * Requests are never retried automatically.
 */
public class HttpAccessAIBackend(
    baseUrl: String,
    private val appId: String,
    client: OkHttpClient? = null,
) : AccessAIBackend {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse =
        post("v1/sessions", request, CreateSessionRequest.serializer(), CreateSessionResponse.serializer())

    override suspend fun interpret(request: InterpretRequest): InterpretResponse =
        post("v1/interpret", request, InterpretRequest.serializer(), InterpretResponse.serializer())

    override suspend fun createConfirmation(request: CreateConfirmationRequest): CreateConfirmationResponse =
        post("v1/confirmations", request, CreateConfirmationRequest.serializer(), CreateConfirmationResponse.serializer())

    override suspend fun confirm(pendingId: String, request: ConfirmRequest): ConfirmResponse =
        post("v1/confirmations/$pendingId/confirm", request, ConfirmRequest.serializer(), ConfirmResponse.serializer())

    override suspend fun cancelConfirmation(pendingId: String, request: CancelConfirmationRequest) {
        post<CancelConfirmationRequest, Unit>("v1/confirmations/$pendingId/cancel", request, CancelConfirmationRequest.serializer(), null)
    }

    private suspend fun <Req, Res> post(path: String, body: Req, reqSer: KSerializer<Req>, resSer: KSerializer<Res>?): Res {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .header("X-AccessAI-App", appId)
            .header("X-Request-Id", UUID.randomUUID().toString())
            .post(JSON.encodeToString(reqSer, body).toRequestBody(MEDIA_TYPE))
            .build()
        val response = http.newCall(request).await()
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw errorFrom(r.code, text, r.header("Retry-After")?.trim()?.toLongOrNull())
            @Suppress("UNCHECKED_CAST")
            if (resSer == null) return Unit as Res
            return try {
                JSON.decodeFromString(resSer, text)
            } catch (e: SerializationException) {
                throw BackendException(BackendException.BAD_RESPONSE, "Unexpected response from AccessAI", e)
            } catch (e: IllegalArgumentException) {
                throw BackendException(BackendException.BAD_RESPONSE, "Unexpected response from AccessAI", e)
            }
        }
    }

    private fun errorFrom(status: Int, body: String, retryAfter: Long?): BackendException {
        val parsed = runCatching { JSON.decodeFromString(ErrorResponse.serializer(), body).error }.getOrNull()
        return when {
            parsed != null -> BackendException(parsed.code, parsed.message, retryAfterSeconds = retryAfter)
            status == 429 -> BackendException(BackendException.RATE_LIMITED, "AccessAI is rate limited", retryAfterSeconds = retryAfter)
            status >= 500 -> BackendException(BackendException.SERVER, "AccessAI service error ($status)")
            else -> BackendException(BackendException.BAD_RESPONSE, "AccessAI request failed ($status)")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
            override fun onFailure(call: Call, e: IOException) {
                val code = if (e is InterruptedIOException) BackendException.TIMEOUT else BackendException.NETWORK
                cont.resumeWithException(BackendException(code, "Could not reach AccessAI", e))
            }
        })
    }

    private companion object {
        val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
