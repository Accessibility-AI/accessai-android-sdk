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
    ) : DialogState

    /** The OS biometric / screen-lock prompt is showing. No token exists yet. */
    public data class AwaitingAuthentication(val actionId: String) : DialogState

    /** The host handler is running. */
    public data class Executing(val actionId: String) : DialogState

    /** Reading a list one item at a time ("next", "previous", "stop"). */
    public data class ReadingList(val items: List<Announcement>, val index: Int) : DialogState
}

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
