package com.accessai.core.privacy

import com.accessai.core.screen.ScreenDescriptor
import com.accessai.core.screen.ScreenElement

/**
 * On-device filtering of what leaves the phone. Runs before anything is sent to AccessAI, kept in the
 * transcript, or shown on screen.
 */
public object SensitiveData {

    public sealed interface Check {
        /** Safe to send. [text] may have long numbers replaced. */
        public data class Allowed(val text: String, val redacted: Boolean) : Check

        /** Looks like a PIN, password, OTP or card code. Never sent, never stored. */
        public data object Blocked : Check
    }

    public const val NUMBER_PLACEHOLDER: String = "[number removed]"
    public const val AMOUNT_PLACEHOLDER: String = "[amount]"

    /** Said when an utterance is blocked. */
    public const val BLOCKED_MESSAGE: String =
        "For your safety, I didn't send that. Never say your PIN, password or one-time code out loud. " +
            "When a PIN is needed, your phone or bank app will ask for it on its own screen."

    private val SECRET_WORDS = Regex(
        """\b(pin|pins|pin code|password|passwords|passcode|pass code|otp|one[ -]?time (?:password|code|pin)|cvv|cvc|security code|card number|bvn|nin|token code|secret)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DIGIT_WORDS = setOf("zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

    // 10+ digits, optionally separated by spaces or dashes: NUBAN account numbers (10), BVN/NIN (11),
    // phone numbers (11), card numbers (13-19).
    private val LONG_NUMBER = Regex("""(?<![\d.])\d(?:[\s-]?\d){9,}(?!\d|\.\d)""")

    private val MONEY = Regex(
        """(?:₦|\bNGN\s?|\bN(?=\d))\s?\d[\d,]*(?:\.\d+)?|\b\d[\d,]*(?:\.\d+)?\s?(?:naira|kobo)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Checks one user utterance before it is used for anything. */
    public fun inspectUtterance(text: String): Check {
        if (SECRET_WORDS.containsMatchIn(text) && digitCount(text) >= 3) return Check.Blocked
        val redacted = redactLongNumbers(text)
        return Check.Allowed(redacted, redacted != text)
    }

    /** Replaces account, card, phone and ID numbers (10+ digits, as numerals or spoken digit words). */
    public fun redactLongNumbers(text: String): String {
        var out = LONG_NUMBER.replace(text, NUMBER_PLACEHOLDER)
        // "zero one two three four five six seven eight nine" → one placeholder
        val tokens = out.split(' ')
        val result = mutableListOf<String>()
        var run = mutableListOf<String>()
        fun flush() {
            if (run.size >= 10) result += NUMBER_PLACEHOLDER else result += run
            run = mutableListOf()
        }
        for (t in tokens) {
            if (t.lowercase().trim(',', '.') in DIGIT_WORDS) run += t else { flush(); result += t }
        }
        flush()
        out = result.joinToString(" ")
        return out
    }

    /** Free text that may end up on the network: long numbers and money amounts removed. */
    public fun redactText(text: String): String = MONEY.replace(redactLongNumbers(text), AMOUNT_PLACEHOLDER)

    /**
     * The version of a screen that is sent to AccessAI: no element values at all (that's where balances,
     * names and account numbers live), no credential fields, and labels/titles with numbers and amounts removed.
     * A [ScreenDescriptor.sensitive] screen (login, PIN, card details) is reduced to its id and title.
     */
    public fun screenForTransport(screen: ScreenDescriptor?): ScreenDescriptor? {
        screen ?: return null
        if (screen.sensitive) return ScreenDescriptor(screen.screenId, redactText(screen.title), sensitive = true)
        return ScreenDescriptor(
            screenId = screen.screenId,
            title = redactText(screen.title),
            summary = screen.summary?.let(::redactText),
            elements = screen.elements
                .filterNot { it.isCredential || it.sensitive }
                .map { ScreenElement(it.id, it.role, redactText(it.label), required = it.required) },
        )
    }

    private fun digitCount(text: String): Int {
        val numerals = text.count { it.isDigit() }
        val words = text.lowercase().split(Regex("[^a-z]+")).count { it in DIGIT_WORDS }
        return numerals + words
    }
}
