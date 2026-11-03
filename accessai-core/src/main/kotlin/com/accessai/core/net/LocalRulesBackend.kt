package com.accessai.core.net

import com.accessai.core.nlu.AmountParser
import com.accessai.core.nlu.LocalGrammar
import com.accessai.core.protocol.CancelConfirmationRequest
import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.ConfirmResponse
import com.accessai.core.protocol.CreateConfirmationRequest
import com.accessai.core.protocol.CreateConfirmationResponse
import com.accessai.core.protocol.CreateSessionRequest
import com.accessai.core.protocol.CreateSessionResponse
import com.accessai.core.protocol.InterpretRequest
import com.accessai.core.protocol.InterpretResponse
import com.accessai.core.protocol.ManifestAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.accessai.core.protocol.DeviceKeyInfo
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An on-device stand-in for the AccessAI service: keyword matching instead of an AI model, and
 * UNSIGNED confirmation tokens. For development, demos without a server and tests only. A real
 * host backend must reject its tokens (they start with "dev-unsigned.").
 */
public class LocalRulesBackend(
    private val clock: () -> Instant = Instant::now,
    private val confirmationTtl: Duration = Duration.ofSeconds(60),
) : AccessAIBackend {

    private class Pending(val sessionId: String, val actionId: String, val paramsSha256: String, val expiresAt: Instant, var used: Boolean = false)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val deviceKeys = ConcurrentHashMap<String, DeviceKeyInfo>()

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        val id = "local-" + UUID.randomUUID()
        request.deviceKey?.let { deviceKeys[id] = it }
        return CreateSessionResponse(sessionId = id)
    }

    override suspend fun interpret(request: InterpretRequest): InterpretResponse {
        val norm = LocalGrammar.normalize(request.text)
        LocalGrammar.control(norm)?.let { return InterpretResponse(outcome = "control", control = it.wireName) }

        val dialog = request.dialog
        val continuing = dialog.pendingActionId?.let { id -> request.actions.firstOrNull { it.id == id } }
        if (continuing != null && dialog.awaiting != "none") {
            val params = extractParams(continuing, request.text, norm).toMutableMap()
            if (params.isEmpty() && dialog.awaiting == "parameter") {
                // A bare answer to a question, e.g. "David" after "Who would you like to send money to?"
                val missingText = continuing.params.firstOrNull { it.name in dialog.missingParams && it.type == "string" }
                if (missingText != null) params[missingText.name] = JsonPrimitive(cleanName(norm))
            }
            if (params.isNotEmpty()) return actionResponse(continuing, params, dialog.filledParams.keys)
        }

        val best = request.actions
            .map { it to score(it, norm) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?: return InterpretResponse(outcome = "unsupported", confidence = 0.9)
        return actionResponse(best, extractParams(best, request.text, norm), emptySet())
    }

    override suspend fun createConfirmation(request: CreateConfirmationRequest): CreateConfirmationResponse {
        val hash = sha256(request.params.toString())
        val id = "pc_" + UUID.randomUUID().toString().replace("-", "")
        val expires = clock() + confirmationTtl
        pending[id] = Pending(request.sessionId, request.actionId, hash, expires)
        return CreateConfirmationResponse(pendingId = id, paramsSha256 = hash, expiresAt = expires.toString())
    }

    override suspend fun confirm(pendingId: String, request: ConfirmRequest): ConfirmResponse {
        val p = pending[pendingId] ?: throw BackendException("not_found", "Unknown confirmation")
        if (p.sessionId != request.sessionId) throw BackendException("not_found", "Unknown confirmation")
        if (p.used) throw BackendException(BackendException.CONFIRMATION_USED, "Confirmation already used")
        if (clock() > p.expiresAt) throw BackendException(BackendException.CONFIRMATION_EXPIRED, "Confirmation expired")
        if (request.method == "voice" && !isServerConfirm(request.utterance.orEmpty())) {
            throw BackendException(BackendException.NOT_A_CONFIRMATION, "Not a confirmation")
        }
        // Same check the real service must make: a session with a device key needs a valid signature from it.
        deviceKeys[p.sessionId]?.let { key ->
            val auth = request.auth ?: throw BackendException(BackendException.AUTH_INVALID, "Device authentication required")
            val payload = "accessai-auth-v1|${p.sessionId}|$pendingId|${p.actionId}|${p.paramsSha256}"
            if (auth.kid != key.kid || !verifyEs256(key.publicKey, payload, auth.signature)) {
                throw BackendException(BackendException.AUTH_INVALID, "Device authentication failed")
            }
        }
        p.used = true
        val nonce = UUID.randomUUID().toString()
        val claims = buildJsonObject {
            put("sub", p.sessionId); put("aid", p.actionId); put("ph", p.paramsSha256)
            put("jti", nonce); put("exp", p.expiresAt.epochSecond)
        }
        val token = "dev-unsigned." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray())
        return ConfirmResponse(status = "confirmed", token = token, nonce = nonce, expiresAt = p.expiresAt.toString())
    }

    override suspend fun cancelConfirmation(pendingId: String, request: CancelConfirmationRequest) {
        pending.remove(pendingId)
    }

    private fun actionResponse(action: ManifestAction, params: Map<String, JsonElement>, alreadyFilled: Set<String>): InterpretResponse {
        val missing = action.params.filter { it.required && it.name !in params && it.name !in alreadyFilled }.map { it.name }
        return InterpretResponse(outcome = "action", actionId = action.id, params = params, missingParams = missing, confidence = 0.9)
    }

    private fun score(action: ManifestAction, norm: String): Int {
        val words = norm.split(' ').filter { it !in STOPWORDS }.toSet()
        if (words.isEmpty()) return 0
        val phrases = listOf(action.name, action.id.replace('_', ' ')) + action.examples
        val best = phrases.maxOf { phrase -> LocalGrammar.normalize(phrase).split(' ').filter { it !in STOPWORDS }.toSet().intersect(words).size }
        // Prefer the action whose parameters the utterance actually fills ("send money to Amaka").
        return if (best == 0) 0 else best * 2 + 3 * extractParams(action, norm, norm).size
    }

    private fun extractParams(action: ManifestAction, raw: String, norm: String): Map<String, JsonElement> = buildMap {
        for (p in action.params) {
            when (p.type) {
                "amount_minor" -> AmountParser.parseKobo(raw)?.let { put(p.name, JsonPrimitive(it)) }
                "string" -> RECIPIENT.find(norm)?.groupValues?.get(1)?.let(::cleanName)?.takeIf { it.isNotBlank() }
                    ?.let { put(p.name, JsonPrimitive(it)) }
                "enum" -> p.enumValues?.firstOrNull { norm.contains(it.lowercase()) }?.let { put(p.name, JsonPrimitive(it)) }
            }
        }
    }

    private fun cleanName(text: String): String =
        text.split(' ')
            .filterNot { it in NAME_NOISE || AmountParser.parseKobo(it) != null }
            .joinToString(" ")
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .trim()

    private fun isServerConfirm(utterance: String): Boolean {
        val n = LocalGrammar.normalize(utterance)
        return Regex("^(yes |i )?confirm( [a-z]+){0,2}$").matches(n) && NEGATIONS.none { n.contains(it) }
    }

    private fun verifyEs256(publicKeyB64: String, payload: String, signatureB64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signatureB64))
        }
    }.getOrDefault(false)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val RECIPIENT = Regex("""\b(?:to|give|for|pay)\s+([a-z][a-z' ]*)""")
        val STOPWORDS = setOf("i", "my", "me", "the", "a", "to", "is", "what", "want", "please", "can", "you", "of", "for", "and", "naira", "it")
        val NAME_NOISE = setOf("the", "naira", "now", "please", "sum", "of", "account", "my", "to", "k", "thousand", "million", "send", "money", "transfer", "i", "want")
        val NEGATIONS = listOf("not", "don't", "dont", "never", "cancel")
    }
}
