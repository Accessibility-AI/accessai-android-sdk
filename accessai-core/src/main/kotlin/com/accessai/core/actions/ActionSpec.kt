package com.accessai.core.actions

import com.accessai.core.Announcement

public enum class Sensitivity(public val wireName: String) {
    /** Reads data, e.g. balance. */
    READ("read"),
    /** Moves the user around the app. */
    NAVIGATE("navigate"),
    /** Changes something that matters, e.g. a transfer. Always requires confirmation. */
    SENSITIVE("sensitive"),
}

/** Parameters after resolution and validation, with their read-back labels. */
public class ResolvedParams internal constructor(
    public val values: Map<String, ParamValue>,
    private val labels: Map<String, Pair<String, String>>,
) {
    public fun text(name: String): String = (values.getValue(name) as ParamValue.Text).value
    public fun number(name: String): Long = (values.getValue(name) as ParamValue.Number).value
    public fun textOrNull(name: String): String? = (values[name] as? ParamValue.Text)?.value
    public fun numberOrNull(name: String): Long? = (values[name] as? ParamValue.Number)?.value

    /** Display label from a resolver, e.g. "David Adeyemi (…2391)". Falls back to the raw value. */
    public fun label(name: String): String = labels[name]?.first ?: values[name]?.let(::raw).orEmpty()

    /** Spoken label from a resolver, e.g. "David Adeyemi, account ending 2 3 9 1". */
    public fun spokenLabel(name: String): String = labels[name]?.second ?: label(name)

    private fun raw(v: ParamValue) = when (v) {
        is ParamValue.Text -> v.value
        is ParamValue.Number -> v.value.toString()
    }

    override fun equals(other: Any?): Boolean = other is ResolvedParams && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "ResolvedParams($values)"
}

/** What the handler receives. [confirmationToken] is non-null for every action that required confirmation. */
public class ActionContext internal constructor(
    public val actionId: String,
    public val params: ResolvedParams,
    /** Signed token to forward to your backend, which must verify it before executing. */
    public val confirmationToken: String?,
    /** Single-use nonce; use it as your backend's idempotency key. */
    public val idempotencyKey: String,
)

public sealed interface ActionResult {
    /** The host did the work. Only this ever produces a success announcement. */
    public data class Success(
        val announcement: Announcement,
        /** Optional items read one at a time ("next" / "stop"), e.g. transactions. */
        val items: List<Announcement> = emptyList(),
        /** Spoken when [items] is empty and the action is a list. */
        val emptyMessage: Announcement? = null,
    ) : ActionResult

    public data class Failure(val announcement: Announcement) : ActionResult
}

public class ActionSpec internal constructor(
    public val id: String,
    /** Short human name, used in help: "check your balance". */
    public val name: String,
    public val description: String,
    public val sensitivity: Sensitivity,
    public val requiresConfirmation: Boolean,
    public val params: List<ParamSpec>,
    public val examples: List<String>,
    internal val validator: suspend (ResolvedParams) -> Validation,
    internal val summary: ((ResolvedParams) -> Announcement)?,
    /** Phrase the user must say to confirm, e.g. "confirm transfer". "confirm" is always accepted. */
    public val confirmPhrase: String,
    internal val handler: suspend (ActionContext) -> ActionResult,
) {
    public fun param(name: String): ParamSpec? = params.firstOrNull { it.name == name }

    /** Sensitive actions need OS biometric or device-credential authentication before a token is requested. */
    public val requiresDeviceAuth: Boolean get() = sensitivity == Sensitivity.SENSITIVE
}

/**
 * Declares an action AccessAI may invoke.
 *
 * ```
 * action("transfer_money") {
 *     name = "send money"
 *     description = "Send money to a saved beneficiary"
 *     sensitivity = Sensitivity.SENSITIVE
 *     text("recipient", "Name of the beneficiary", prompt = "Who would you like to send money to?", resolver = beneficiaries)
 *     amount("amount_minor", "Amount in kobo", prompt = "How much would you like to send?")
 *     validate { p -> if (p.number("amount_minor") > balance()) Validation.Invalid("…") else Validation.Valid }
 *     summary { p -> announce { text("send "); naira(p.number("amount_minor")); text(" to ${p.spokenLabel("recipient")}") } }
 *     confirmPhrase = "confirm transfer"
 *     handler { ctx -> bank.transfer(ctx) }
 * }
 * ```
 */
public fun action(id: String, block: ActionSpecBuilder.() -> Unit): ActionSpec =
    ActionSpecBuilder(id).apply(block).build()

public class ActionSpecBuilder internal constructor(private val id: String) {
    public var name: String = id.replace('_', ' ')
    public var description: String = ""
    public var sensitivity: Sensitivity = Sensitivity.READ
    /** Forced to true for [Sensitivity.SENSITIVE]; can be opted into for others. */
    public var requiresConfirmation: Boolean = false
    public var confirmPhrase: String = "confirm"
    private val params = mutableListOf<ParamSpec>()
    private val examples = mutableListOf<String>()
    private var validator: suspend (ResolvedParams) -> Validation = { Validation.Valid }
    private var summary: ((ResolvedParams) -> Announcement)? = null
    private var handler: (suspend (ActionContext) -> ActionResult)? = null

    public fun text(name: String, description: String, required: Boolean = true, prompt: String = "What is the $name?", resolver: ParamResolver? = null) {
        params += ParamSpec(name, ParamType.Text, description, required, prompt, resolver)
    }

    public fun integer(name: String, description: String, required: Boolean = true, prompt: String = "What is the $name?") {
        params += ParamSpec(name, ParamType.Integer, description, required, prompt, null)
    }

    public fun amount(name: String, description: String, required: Boolean = true, prompt: String = "How much?") {
        params += ParamSpec(name, ParamType.AmountMinor, description, required, prompt, null)
    }

    public fun oneOf(name: String, description: String, values: List<String>, required: Boolean = true, prompt: String = "Which $name?") {
        params += ParamSpec(name, ParamType.OneOf(values), description, required, prompt, null)
    }

    /** Example phrasings sent to the interpreter to improve recognition. */
    public fun examples(vararg phrases: String) {
        examples += phrases
    }

    public fun validate(block: suspend (ResolvedParams) -> Validation) {
        validator = block
    }

    /** What the user is about to do, read back before confirmation. Required for confirmed actions. */
    public fun summary(block: (ResolvedParams) -> Announcement) {
        summary = block
    }

    public fun handler(block: suspend (ActionContext) -> ActionResult) {
        handler = block
    }

    internal fun build(): ActionSpec {
        require(ID_PATTERN.matches(id)) { "Action id '$id' must be lower_snake_case" }
        require(params.map { it.name }.toSet().size == params.size) { "Action '$id' has duplicate parameter names" }
        // AccessAI never collects credentials. Login and PIN entry always stay on the host's own screens.
        (listOf(id) + params.map { it.name }).firstOrNull { CREDENTIAL_NAME.containsMatchIn(it) }?.let {
            throw IllegalArgumentException("Action '$id': '$it' looks like a credential. AccessAI never collects PINs, passwords, OTPs or card details")
        }
        // Anything that takes an amount moves money, so it must be SENSITIVE (read-back, confirmation, device auth).
        require(params.none { it.type == ParamType.AmountMinor } || sensitivity == Sensitivity.SENSITIVE) {
            "Action '$id' takes an amount, so it must be sensitivity = Sensitivity.SENSITIVE"
        }
        val confirm = requiresConfirmation || sensitivity == Sensitivity.SENSITIVE
        require(!confirm || summary != null) { "Action '$id' requires confirmation, so it must define summary { }" }
        return ActionSpec(
            id = id,
            name = name,
            description = description.ifBlank { name },
            sensitivity = sensitivity,
            requiresConfirmation = confirm,
            params = params.toList(),
            examples = examples.toList(),
            validator = validator,
            summary = summary,
            confirmPhrase = confirmPhrase.trim().lowercase(),
            handler = requireNotNull(handler) { "Action '$id' has no handler { }" },
        )
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9_]{1,63}")
        val CREDENTIAL_NAME = Regex(
            // "open_login" is fine (navigation); collecting a password is not.
            "(^|_)(pin|pins|password|passwd|passcode|otp|cvv|cvc|bvn|nin|card_number|pan|secret|credential|credentials)(_|$)",
        )
    }
}
