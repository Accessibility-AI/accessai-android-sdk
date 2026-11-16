package com.accessai.core.engine

import com.accessai.core.Announcement
import java.time.Instant

public sealed interface DialogState {
    public data object Idle : DialogState

    /** Waiting for the AccessAI service to interpret what the user said. */
    public data object Interpreting : DialogState

    /** AccessAI asked a question and is waiting for the answer. */
    public data class AwaitingInput(
        val actionId: String?,
        val paramName: String?,
        val kind: InputKind,
        val question: Announcement,
        /** Display labels when [kind] is [InputKind.DISAMBIGUATION], so touch users can pick. */
        val choices: List<String> = emptyList(),
    ) : DialogState

    /** A sensitive action has been read back and needs an explicit confirmation. */
    public data class AwaitingConfirmation(
        val actionId: String,
        val summary: Announcement,
        val confirmPhrase: String,
        val expiresAt: Instant,
        /** The host's structured read-back for the confirmation card, when the action defines one. */
        val details: ConfirmationDetails? = null,
    ) : DialogState

    /** The OS biometric / screen-lock prompt is showing. No token exists yet. */
    public data class AwaitingAuthentication(val actionId: String) : DialogState

    /** The host handler is running. */
    public data class Executing(val actionId: String) : DialogState

    /** Reading a list one item at a time ("next", "previous", "stop"). */
    public data class ReadingList(val items: List<Announcement>, val index: Int) : DialogState

    /**
     * A request has finished (or could not start) and its result stays on screen until the user asks something
     * else or the host calls [DialogEngine.dismiss]. Behaves like [Idle] for input. The UI picks a screen from
     * [kind]: answer, success, failure, offline, not understood, cancelled or problem.
     */
    public data class Outcome(
        val kind: OutcomeKind,
        /** Short heading, e.g. "Your balance" or "Payment sent". */
        val title: String,
        /** What was said, in full. */
        val announcement: Announcement,
        /** The headline value in large type, e.g. "₦84,250", when there is one. */
        val value: String? = null,
        /** A secondary line, e.g. "Main account, as of 2:14 pm." or a reference number. */
        val detail: String? = null,
        val actionId: String? = null,
        /** For [OutcomeKind.NOT_UNDERSTOOD]: misses in a row, so the UI can offer typing first after three. */
        val misses: Int = 0,
    ) : DialogState
}

public enum class OutcomeKind {
    /** A read-only or navigation action answered. Nothing was changed. */
    ANSWER,
    /** A confirmed action succeeded. Only ever set from a host's ActionResult.Success. */
    SUCCESS,
    /** The host reported failure, or a confirmation could not be completed. */
    FAILURE,
    /** AccessAI could not be reached. Nothing was sent or changed. */
    OFFLINE,
    /** The request wasn't understood, or isn't something this app supports. */
    NOT_UNDERSTOOD,
    /** The user cancelled. Nothing was sent. */
    CANCELLED,
    /** The service was busy or failed. Nothing was sent or changed. */
    PROBLEM,
}

/**
 * What a confirmation card shows, built by the host from the exact resolved parameters that will be confirmed,
 * e.g. verb "Send", amount "₦5,000", recipient "David Adeyemi", recipientDetail "Account ending 2391",
 * note "From Main account · Fee ₦0".
 */
public data class ConfirmationDetails(
    val verb: String,
    val amount: String? = null,
    val recipient: String? = null,
    val recipientDetail: String? = null,
    val note: String? = null,
    /** Heading for the confirmation screen, e.g. "Confirm payment". */
    val title: String? = null,
)

public enum class InputKind {
    /** A required parameter is missing. */
    PARAMETER,
    /** More than one match, e.g. two beneficiaries called David. */
    DISAMBIGUATION,
    /** The request was unclear or a value was rejected. */
    CLARIFY,
}

public enum class InputSource { VOICE, TEXT }

public enum class Speaker { USER, ACCESSAI }

public data class TranscriptEntry(val speaker: Speaker, val text: String)

/** Where announcements go: text-to-speech, a screen reader, or both. */
public interface SpeechOutput {
    public fun speak(announcement: Announcement)
    public fun stop()
}

public data class EngineConfig(
    val appId: String,
    val locale: String = "en-NG",
    /** Interpretations below this confidence are treated as unclear and never acted on. */
    val minConfidence: Double = 0.6,
    /** How long a host handler may run before AccessAI reports that it does not know the result. */
    val actionTimeoutMillis: Long = 20_000,
    /** Announce the title of a newly published screen (suppressed while an action is running). */
    val announceScreenChanges: Boolean = true,
    /** Transcript is in memory only, capped, and cleared on reset. It is never written to disk or sent anywhere. */
    val transcriptLimit: Int = 50,
    /** Failed or cancelled device-auth attempts allowed before the pending action is cancelled. */
    val maxAuthAttempts: Int = 3,
)
