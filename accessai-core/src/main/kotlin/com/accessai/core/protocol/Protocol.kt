package com.accessai.core.protocol

import com.accessai.core.screen.ScreenDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire models for the AccessAI service, contract v1. See docs/API_CONTRACT.md.
// Every change here must be mirrored in accessai-server/contracts/v1.

@Serializable
public data class CreateSessionRequest(
    @SerialName("app_id") val appId: String,
    val locale: String,
    /** The device key that must sign every sensitive confirmation in this session. */
    @SerialName("device_key") val deviceKey: DeviceKeyInfo? = null,
)

/** Public half of an OS-protected device key. The private half never leaves the phone's secure hardware. */
@Serializable
public data class DeviceKeyInfo(
    /** Key id: first 16 hex chars of SHA-256 of [publicKey]'s DER bytes. */
    val kid: String,
    /** "ES256": ECDSA P-256 with SHA-256. */
    val alg: String,
    /** Base64 (standard) X.509 SubjectPublicKeyInfo DER. */
    @SerialName("public_key") val publicKey: String,
    /** Optional Android key attestation chain, base64 DER certificates, leaf first. */
    @SerialName("attestation_chain") val attestationChain: List<String>? = null,
)

/** Proof that the OS authenticated the user for one specific pending confirmation. */
@Serializable
public data class AuthProof(
    /** "biometric" or "device_credential" (PIN, pattern or password), as reported by the OS. */
    val method: String,
    val kid: String,
    val alg: String,
    /** Base64 (standard) DER ECDSA signature over the UTF-8 challenge payload. */
    val signature: String,
)

@Serializable
public data class CreateSessionResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
public data class ManifestParam(
    val name: String,
    /** One of: "string", "integer", "amount_minor", "enum". */
    val type: String,
    val description: String,
    val required: Boolean,
    @SerialName("enum_values") val enumValues: List<String>? = null,
)

@Serializable
public data class ManifestAction(
    val id: String,
    val name: String,
    val description: String,
    /** One of: "read", "navigate", "sensitive". */
    val sensitivity: String,
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean,
    val params: List<ManifestParam>,
    val examples: List<String> = emptyList(),
)

@Serializable
public data class DialogContext(
    @SerialName("pending_action_id") val pendingActionId: String? = null,
    @SerialName("filled_params") val filledParams: Map<String, JsonElement> = emptyMap(),
    @SerialName("missing_params") val missingParams: List<String> = emptyList(),
    /** One of: "none", "parameter", "disambiguation", "confirmation". */
    val awaiting: String = "none",
    @SerialName("last_system_message") val lastSystemMessage: String? = null,
)

@Serializable
public data class InterpretRequest(
    @SerialName("session_id") val sessionId: String,
    val text: String,
    val locale: String,
    val screen: ScreenDescriptor? = null,
    val actions: List<ManifestAction>,
    val dialog: DialogContext = DialogContext(),
)

@Serializable
public data class InterpretResponse(
    @SerialName("request_id") val requestId: String? = null,
    /** One of: "action", "clarify", "unsupported", "control". */
    val outcome: String,
    @SerialName("action_id") val actionId: String? = null,
    val params: Map<String, JsonElement> = emptyMap(),
    @SerialName("missing_params") val missingParams: List<String> = emptyList(),
    /** For outcome "control": "cancel", "stop", "repeat", "next", "previous", "help", "describe_screen". */
    val control: String? = null,
    /** Spoken text for "clarify" and "unsupported". Never used for amounts or confirmations. */
    val message: String? = null,
    val confidence: Double = 1.0,
)

@Serializable
public data class CreateConfirmationRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("action_id") val actionId: String,
    val params: JsonObject,
)

@Serializable
public data class CreateConfirmationResponse(
    @SerialName("pending_id") val pendingId: String,
    @SerialName("params_sha256") val paramsSha256: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
public data class ConfirmRequest(
    @SerialName("session_id") val sessionId: String,
    /** "voice" or "touch". */
    val method: String,
    val utterance: String? = null,
    /** Required for sensitive actions: device authentication bound to this pending confirmation. */
    val auth: AuthProof? = null,
)

@Serializable
public data class ConfirmResponse(
    val status: String,
    /** Compact JWS (EdDSA) the host backend verifies before executing. */
    val token: String,
    val nonce: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
public data class CancelConfirmationRequest(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
public data class ErrorResponse(val error: ErrorBody)

@Serializable
public data class ErrorBody(val code: String, val message: String)
