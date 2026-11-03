package com.accessai.core.screen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A structured description of a supported screen (PRD §8.4). Hosts publish one per screen so
 * AccessAI can describe it and the interpreter knows where the user is.
 *
 * The full descriptor stays on the phone and is used to describe the screen aloud. What is sent to the
 * AccessAI service is a redacted copy with no values (see SensitiveData.screenForTransport).
 */
@Serializable
public data class ScreenDescriptor(
    @SerialName("screen_id") val screenId: String,
    val title: String,
    val summary: String? = null,
    val elements: List<ScreenElement> = emptyList(),
    /** Login, PIN, card-details and similar screens: only the id and title ever leave the phone. */
    val sensitive: Boolean = false,
) {
    /** Plain-language description for "where am I" / "describe screen". Spoken on the device only. */
    public fun describe(): String = buildString {
        append(title).append(" screen.")
        summary?.let { append(' ').append(it.trimEnd('.')).append('.') }
        val visible = elements.filterNot { it.isCredential }
        if (visible.isNotEmpty()) {
            append(" It has ")
            append(visible.joinToString("; ") { it.describe() })
            append('.')
        }
        if (sensitive) append(" Enter your details here yourself; AccessAI doesn't read or fill this screen.")
    }
}

@Serializable
public data class ScreenElement(
    val id: String,
    /** e.g. "button", "input", "text", "list", "heading". Credential fields: "password", "pin", "otp". */
    val role: String,
    val label: String,
    val required: Boolean = false,
    /** Read aloud on the device; never sent to the AccessAI service. */
    val value: String? = null,
    /** Excluded from what is sent to the AccessAI service, even its label. */
    val sensitive: Boolean = false,
) {
    init {
        require(!isCredential || value == null) {
            "Screen element '$id' is a $role field. AccessAI never reads credential values, so don't pass one"
        }
    }

    internal val isCredential: Boolean get() = role.lowercase() in CREDENTIAL_ROLES

    internal fun describe(): String = buildString {
        append(label)
        if (role != "text") append(", ").append(role)
        if (required) append(", required")
        value?.let { append(", ").append(it) }
    }

    public companion object {
        public val CREDENTIAL_ROLES: Set<String> = setOf("password", "pin", "otp", "passcode", "cvv")
    }
}
