package com.accessai.core

import com.accessai.core.actions.ActionRegistry
import com.accessai.core.actions.ActionResult
import com.accessai.core.actions.Sensitivity
import com.accessai.core.actions.action
import com.accessai.core.auth.AuthOutcome
import com.accessai.core.engine.DialogState
import com.accessai.core.net.LocalRulesBackend
import com.accessai.core.privacy.SensitiveData
import com.accessai.core.screen.ScreenDescriptor
import com.accessai.core.screen.ScreenElement
import com.accessai.core.testing.Harness
import com.accessai.core.testing.RecordingBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One or more tests per rule in the security review. */
class SecurityTest {

    private fun recordingHarness(): Pair<Harness, () -> RecordingBackend> {
        var rec: RecordingBackend? = null
        val h = Harness(backendOverride = { clock -> RecordingBackend(LocalRulesBackend(clock)).also { rec = it } })
        return h to { rec!! }
    }

    // ---- Rule: SENSITIVE actions can't exist without device auth; money actions must be SENSITIVE ----

    @Test
    fun `registering a sensitive action without an authenticator is refused`() {
        val spec = action("transfer_money") {
            sensitivity = Sensitivity.SENSITIVE
            amount("amount_minor", "Amount")
            summary { Announcement.of("send money") }
            handler { ActionResult.Success(Announcement.of("ok")) }
        }
        assertFailsWith<IllegalArgumentException> { ActionRegistry(deviceAuthenticator = null).register(spec) }
    }

    @Test
    fun `an action that takes an amount must be sensitive`() {
        assertFailsWith<IllegalArgumentException> {
            action("pay_bill") {
                sensitivity = Sensitivity.READ
                amount("amount_minor", "Amount")
                handler { ActionResult.Success(Announcement.of("ok")) }
            }
        }
    }

    @Test
    fun `actions can never collect credentials, but may open the login screen`() {
        for (name in listOf("pin", "card_pin", "password", "otp_code", "cvv", "bvn")) {
            assertFailsWith<IllegalArgumentException>(name) {
                action("verify_user") {
                    text(name, "x")
                    handler { ActionResult.Success(Announcement.of("ok")) }
                }
            }
        }
        action("open_login") {
            sensitivity = Sensitivity.NAVIGATE
            handler { ActionResult.Success(Announcement.of("Sign in screen. Enter your details yourself.")) }
        }
    }

    @Test
    fun `credential screen fields can't carry a value`() {
        assertFailsWith<IllegalArgumentException> { ScreenElement("pin", "pin", "PIN", value = "1234") }
        ScreenElement("pin", "pin", "PIN") // label only is fine
    }

    // ---- Rule: OS device auth before any token; proof is bound to the exact request ----

    @Test
    fun `transfer runs only after device auth, with a proof bound to the pending confirmation`() = runTest {
        val (h, rec) = recordingHarness()
        h.engine.submit("Send 5000 to Amaka")
        assertTrue(h.auth.challenges.isEmpty(), "no auth prompt before the user confirms")
        h.engine.submit("confirm transfer")

        val challenge = h.auth.challenges.single()
        assertEquals("transfer_money", challenge.actionId)
        assertContains(challenge.payload, challenge.pendingId)
        assertContains(challenge.payload, challenge.paramsSha256)
        assertNotNull(rec().confirms.single().auth, "token request carries the signed proof")
        assertEquals(h.auth.kid, rec().sessions.single().deviceKey?.kid, "device key registered with the session")
        assertEquals(1, h.bank.transfers.size)
    }

    @Test
    fun `cancelled auth sends nothing and can be retried`() = runTest {
        val (h, rec) = recordingHarness()
        h.auth.script += AuthOutcome.Cancelled
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        assertTrue(h.bank.transfers.isEmpty())
        assertTrue(rec().confirms.isEmpty(), "no token was even requested")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertContains(h.speech.last, "Nothing was sent")

        h.engine.submit("confirm transfer")
        assertEquals(1, h.bank.transfers.size)
    }

    @Test
    fun `repeated auth failures cancel the request`() = runTest {
        val h = Harness()
        repeat(3) { h.auth.script += AuthOutcome.Failed("That didn't match.") }
        h.engine.submit("Send 5000 to Amaka")
        repeat(3) { h.engine.submit("confirm transfer") }
        assertEquals(DialogState.Idle, h.engine.state.value)
        assertContains(h.speech.last, "cancelled")
        h.engine.submit("confirm transfer")
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `no screen lock means no transfer`() = runTest {
        val h = Harness()
        h.auth.script += AuthOutcome.Unavailable("To send money, set up a screen lock on this phone first.")
        h.engine.submit("Send 5000 to Amaka")
        h.engine.confirmByTouch()
        assertContains(h.speech.last, "set up a screen lock")
        assertTrue(h.bank.transfers.isEmpty())
        assertEquals(DialogState.Idle, h.engine.state.value)
    }

    @Test
    fun `a proof signed by another key is rejected by the service`() = runTest {
        val h = Harness()
        h.auth.signWithWrongKey = true
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        assertContains(h.speech.last, "couldn't verify it was you")
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `reading actions don't prompt for auth`() = runTest {
        val h = Harness()
        h.engine.submit("What is my balance?")
        assertTrue(h.auth.challenges.isEmpty())
    }

    // ---- Rule: PINs and passwords never leave the phone ----

    @Test
    fun `a spoken PIN is never sent or stored`() = runTest {
        val (h, rec) = recordingHarness()
        for (said in listOf("my PIN is 1234", "my pin is 1234 send 5k to Amaka", "my password is one two three four", "OTP 482913")) {
            h.engine.submit(said)
            assertEquals(SensitiveData.BLOCKED_MESSAGE, h.speech.last, said)
        }
        assertTrue(rec().interprets.isEmpty(), "nothing reached the AccessAI service")
        val transcript = h.engine.transcript.value.joinToString { it.text }
        for (secret in listOf("1234", "482913", "one two three four")) assertFalse(transcript.contains(secret), secret)
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `a PIN said instead of confirm does not confirm and is not sent`() = runTest {
        val (h, rec) = recordingHarness()
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("my pin is 5678")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertFalse(rec().everythingSent().contains("5678"))
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `talking about a PIN without digits is fine`() = runTest {
        val (h, rec) = recordingHarness()
        h.engine.submit("how do I change my PIN")
        assertEquals(1, rec().interprets.size)
    }

    @Test
    fun `account, card and phone numbers are redacted before sending`() = runTest {
        val (h, rec) = recordingHarness()
        h.engine.submit("send 5000 to account 0123456789")
        h.engine.submit("my card is 4111 1111 1111 1111")
        val sent = rec().everythingSent()
        assertFalse(sent.contains("0123456789"))
        assertFalse(sent.contains("4111"))
        assertContains(sent, SensitiveData.NUMBER_PLACEHOLDER)
        assertFalse(h.engine.transcript.value.any { it.text.contains("0123456789") })
    }

    // ---- Rule: screen descriptors are redacted before sending ----

    @Test
    fun `screen values, sensitive fields and numbers never leave the phone`() = runTest {
        val (h, rec) = recordingHarness()
        h.engine.setScreen(
            ScreenDescriptor(
                "home", "Home",
                summary = "Account 0123456789, balance ₦84,250",
                elements = listOf(
                    ScreenElement("balance", "text", "Available balance", value = "₦84,250"),
                    ScreenElement("acct", "text", "Account number", value = "0123456789"),
                    ScreenElement("name", "text", "Adaeze Okonkwo", sensitive = true),
                    ScreenElement("send", "button", "Send money"),
                ),
            ),
        )
        h.engine.submit("help me")
        val screen = rec().interprets.single().screen!!
        assertTrue(screen.elements.all { it.value == null })
        assertFalse(screen.elements.any { it.id == "name" })
        val sent = rec().everythingSent()
        for (leak in listOf("84,250", "0123456789", "Adaeze")) assertFalse(sent.contains(leak), leak)

        // The full descriptor is still used on the phone to describe the screen aloud.
        h.engine.submit("where am I")
        assertContains(h.speech.last, "₦84,250")
    }

    @Test
    fun `a login screen sends only its title`() {
        val login = ScreenDescriptor(
            "login", "Sign in",
            elements = listOf(ScreenElement("phone", "input", "Phone number"), ScreenElement("pw", "password", "Password")),
            sensitive = true,
        )
        val sent = SensitiveData.screenForTransport(login)!!
        assertEquals("Sign in", sent.title)
        assertTrue(sent.elements.isEmpty())
        assertContains(login.describe(), "AccessAI doesn't read or fill this screen")
    }

    // ---- Rule: nothing AccessAI says (balances, names) is echoed back; transcript is ephemeral ----

    @Test
    fun `the balance AccessAI spoke is never sent back to the service`() = runTest {
        val (h, rec) = recordingHarness()
        h.engine.submit("What is my balance?")
        h.engine.submit("Send 5000 to Amaka")
        val sent = rec().everythingSent()
        assertFalse(sent.contains("eighty-four"))
        assertFalse(sent.contains("84,250"))
        assertNull(rec().interprets.last().dialog.lastSystemMessage)
    }

    @Test
    fun `reset wipes the transcript and the last response`() = runTest {
        val h = Harness()
        h.engine.submit("What is my balance?")
        h.engine.reset()
        assertTrue(h.engine.transcript.value.isEmpty())
        assertNull(h.engine.lastAnnouncement.value)
    }

    @Test
    fun `redaction unit cases`() {
        assertEquals("send 5000 to David", (SensitiveData.inspectUtterance("send 5000 to David") as SensitiveData.Check.Allowed).text)
        assertEquals("call [number removed]", SensitiveData.redactLongNumbers("call 0803 123 4567"))
        assertEquals("[number removed] please", SensitiveData.redactLongNumbers("zero eight zero three one two three four five six please"))
        assertEquals("balance [amount] and [amount]", SensitiveData.redactText("balance ₦84,250 and 5,000 naira"))
        assertIs<SensitiveData.Check.Blocked>(SensitiveData.inspectUtterance("my bvn is 22334455667"))
    }
}
