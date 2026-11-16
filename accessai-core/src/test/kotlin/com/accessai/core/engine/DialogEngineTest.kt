package com.accessai.core.engine

import com.accessai.core.net.BackendException
import com.accessai.core.protocol.CreateConfirmationResponse
import com.accessai.core.protocol.InterpretResponse
import com.accessai.core.testing.Harness
import com.accessai.core.testing.ScriptedBackend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DialogEngineTest {

    // ---- Core use cases (PRD §4) ------------------------------------------------------------

    @Test
    fun `balance is read aloud in words`() = runTest {
        val h = Harness()
        h.engine.submit("What is my balance?")
        assertContains(h.speech.last, "eighty-four thousand two hundred and fifty naira")
        assertEquals(OutcomeKind.ANSWER, assertIs<DialogState.Outcome>(h.engine.state.value).kind)
    }

    @Test
    fun `navigation request runs the navigate action`() = runTest {
        val h = Harness()
        h.engine.submit("I want to send money")
        assertContains(h.speech.last, "Opening transfers")
    }

    @Test
    fun `transfer is read back, confirmed exactly once, and the result announced`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to David Adeyemi")
        val state = assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertEquals("transfer_money", state.actionId)
        assertContains(h.speech.last, "five thousand naira to David Adeyemi, account ending 2 3 9 1")
        assertContains(h.speech.last, "confirm transfer")
        assertTrue(h.bank.transfers.isEmpty(), "nothing runs before confirmation")

        h.engine.submit("Confirm transfer")
        assertEquals(1, h.bank.transfers.size)
        val ctx = h.bank.transfers.single()
        assertEquals("ben_001", ctx.params.text("recipient"))
        assertEquals(500_000L, ctx.params.number("amount_minor"))
        assertNotNull(ctx.confirmationToken)
        assertContains(h.speech.last, "successful")
        assertContains(h.speech.last, "seventy-nine thousand two hundred and fifty naira")
    }

    @Test
    fun `transactions are read one at a time with next and stop`() = runTest {
        val h = Harness()
        h.engine.submit("Read my recent transactions")
        assertContains(h.speech.last, "1 of 3")
        assertContains(h.speech.last, "Airtime purchase")
        h.engine.submit("next")
        assertContains(h.speech.last, "2 of 3")
        h.engine.submit("stop")
        assertEquals(DialogState.Idle, h.engine.state.value)
    }

    @Test
    fun `empty transaction history is announced`() = runTest {
        val h = Harness()
        h.bank.transactions = emptyList()
        h.engine.submit("transaction history")
        assertContains(h.speech.last, "no transactions")
    }

    // ---- Never guess (FR-04, FR-08, §10.4) ---------------------------------------------------

    @Test
    fun `ambiguous recipient asks which one and never guesses`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5k to David")
        val state = assertIs<DialogState.AwaitingInput>(h.engine.state.value)
        assertEquals(InputKind.DISAMBIGUATION, state.kind)
        assertContains(h.speech.last, "David Adeyemi or David Okafor")
        assertTrue(h.bank.transfers.isEmpty())

        h.engine.submit("Okafor")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertContains(h.speech.last, "David Okafor")
        h.engine.submit("confirm transfer")
        assertEquals("ben_002", h.bank.transfers.single().params.text("recipient"))
    }

    @Test
    fun `disambiguation by touch`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5k to David")
        h.engine.choose(0)
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertContains(h.speech.last, "David Adeyemi")
    }

    @Test
    fun `missing amount triggers a question and the answer is understood`() = runTest {
        val h = Harness()
        h.engine.submit("Send money to Amaka")
        val state = assertIs<DialogState.AwaitingInput>(h.engine.state.value)
        assertEquals("amount_minor", state.paramName)
        assertContains(h.speech.last, "How much would you like to send?")
        h.engine.submit("two thousand naira")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertContains(h.speech.last, "two thousand naira to Amaka Obi")
    }

    @Test
    fun `unknown recipient is reported, not guessed`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Tunde")
        assertContains(h.speech.last, "couldn't find Tunde")
        assertIs<DialogState.AwaitingInput>(h.engine.state.value)
    }

    @Test
    fun `amount above balance is rejected before confirmation`() = runTest {
        val h = Harness()
        h.engine.submit("Send 100000 naira to Amaka")
        assertContains(h.speech.last, "more than your available balance")
        assertFalse(h.engine.state.value is DialogState.AwaitingConfirmation)
        assertTrue(h.bank.transfers.isEmpty())
    }

    // ---- Confirmation safety (FR-08, FR-11, §13.3) -------------------------------------------

    @Test
    fun `bare yes or okay never confirms`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        for (reply in listOf("yes", "okay", "go ahead", "send it", "yeah")) {
            h.engine.submit(reply)
            assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        }
        assertContains(h.speech.last, "To keep you safe")
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `negated or unrelated replies never confirm`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        for (reply in listOf("don't confirm", "I do not confirm transfer", "hmm", "confirm transfer to David")) {
            h.engine.submit(reply)
        }
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `cancel prevents execution and a later confirm does nothing`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("cancel")
        assertContains(h.speech.last, "Nothing was sent")
        assertEquals(OutcomeKind.CANCELLED, assertIs<DialogState.Outcome>(h.engine.state.value).kind)
        h.engine.submit("confirm transfer")
        h.engine.confirmByTouch()
        assertTrue(h.bank.transfers.isEmpty())
    }

    @Test
    fun `double confirmation executes once`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        h.engine.submit("confirm transfer")
        h.engine.confirmByTouch()
        assertEquals(1, h.bank.transfers.size)
    }

    @Test
    fun `touch confirm works`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        h.engine.confirmByTouch()
        assertEquals(1, h.bank.transfers.size)
    }

    @Test
    fun `changing the amount invalidates the old confirmation`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("make it 2000")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertContains(h.speech.last, "two thousand naira to Amaka Obi")
        h.engine.submit("confirm transfer")
        assertEquals(200_000L, h.bank.transfers.single().params.number("amount_minor"))
    }

    @Test
    fun `expired confirmation does not execute`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        h.now = h.now.plusSeconds(120)
        h.engine.submit("confirm transfer")
        assertContains(h.speech.last, "expired")
        assertTrue(h.bank.transfers.isEmpty())
    }

    // ---- Honest results (FR-10) ----------------------------------------------------------------

    @Test
    fun `failed handler is announced as a failure`() = runTest {
        val h = Harness()
        h.bank.failNextTransfer = true
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        assertContains(h.speech.last, "failed")
        assertFalse(h.speech.all().contains("successful"))
    }

    @Test
    fun `timeout is not retried and does not claim success`() = runTest {
        val h = Harness()
        h.bank.transferDelayMillis = 5_000
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        assertContains(h.speech.last, "did not try again")
        assertFalse(h.speech.all().contains("successful"))
        assertEquals(OutcomeKind.FAILURE, assertIs<DialogState.Outcome>(h.engine.state.value).kind)
    }

    @Test
    fun `network failure is explained`() = runTest {
        val h = Harness(backendOverride = { ScriptedBackend(interpretError = true) })
        h.engine.submit("What is my balance?")
        assertContains(h.speech.last, "check your internet connection")
    }

    // ---- Contract v1.1 service errors ------------------------------------------------------------

    @Test
    fun `rate limited service asks the user to try again shortly`() = runTest {
        val h = Harness(backendOverride = { ScriptedBackend(interpretErrorCode = BackendException.RATE_LIMITED) })
        h.engine.submit("What is my balance?")
        assertContains(h.speech.last, "busy right now")
        assertEquals(OutcomeKind.PROBLEM, assertIs<DialogState.Outcome>(h.engine.state.value).kind)
    }

    @Test
    fun `expired session while preparing a confirmation starts a new session once`() = runTest {
        var backend: ScriptedBackend? = null
        val h = Harness(backendOverride = {
            ScriptedBackend(interpretResponse = transferTo("Amaka"), createConfirmationErrors = ArrayDeque(listOf(BackendException.SESSION_EXPIRED)))
                .also { backend = it }
        })
        h.engine.submit("send 5000 to Amaka")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        assertEquals(listOf("s1", "s2"), backend!!.createConfirmationRequests.map { it.sessionId })
    }

    @Test
    fun `expired session at confirm sends nothing and never retries`() = runTest {
        var backend: ScriptedBackend? = null
        val h = Harness(backendOverride = {
            ScriptedBackend(interpretResponse = transferTo("Amaka"), confirmErrors = ArrayDeque(listOf(BackendException.SESSION_EXPIRED)))
                .also { backend = it }
        })
        h.engine.submit("send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        assertContains(h.speech.last, "nothing was sent")
        assertEquals(1, backend!!.confirmRequests.size)
        assertTrue(h.bank.transfers.isEmpty())
        assertEquals(OutcomeKind.FAILURE, assertIs<DialogState.Outcome>(h.engine.state.value).kind)
    }

    private fun transferTo(name: String) = InterpretResponse(
        outcome = "action", actionId = "transfer_money", confidence = 0.95,
        params = mapOf("recipient" to JsonPrimitive(name), "amount_minor" to JsonPrimitive(500_000)),
    )

    // ---- The interpreter cannot bypass the allowlist (FR-05, FR-11) ---------------------------

    @Test
    fun `unregistered action from the interpreter is never run`() = runTest {
        val h = Harness(backendOverride = {
            ScriptedBackend(interpretResponse = InterpretResponse(outcome = "action", actionId = "delete_account", confidence = 0.99))
        })
        h.engine.submit("delete my account")
        assertContains(h.speech.last, "can't do that")
        assertEquals(OutcomeKind.NOT_UNDERSTOOD, assertIs<DialogState.Outcome>(h.engine.state.value).kind)
    }

    @Test
    fun `low-confidence interpretation asks instead of acting`() = runTest {
        val h = Harness(backendOverride = {
            ScriptedBackend(interpretResponse = InterpretResponse(outcome = "action", actionId = "get_balance", confidence = 0.2))
        })
        h.engine.submit("mumble")
        assertContains(h.speech.last, "not sure")
    }

    @Test
    fun `malformed amount from the interpreter is not used`() = runTest {
        val h = Harness(backendOverride = {
            ScriptedBackend(
                interpretResponse = InterpretResponse(
                    outcome = "action", actionId = "transfer_money",
                    params = mapOf("recipient" to JsonPrimitive("Amaka"), "amount_minor" to JsonPrimitive("5000")),
                ),
            )
        })
        h.engine.submit("send five thousand to Amaka")
        val state = assertIs<DialogState.AwaitingInput>(h.engine.state.value)
        assertEquals("amount_minor", state.paramName)
    }

    @Test
    fun `server confirmation for different parameters is refused`() = runTest {
        val h = Harness(backendOverride = { clock ->
            ScriptedBackend(
                interpretResponse = InterpretResponse(
                    outcome = "action", actionId = "transfer_money",
                    params = mapOf("recipient" to JsonPrimitive("Amaka"), "amount_minor" to JsonPrimitive(500_000)),
                ),
                confirmationResponse = CreateConfirmationResponse("pc_1", "0".repeat(64), clock().plusSeconds(60).toString()),
            )
        })
        h.engine.submit("send 5000 to Amaka")
        assertContains(h.speech.last, "Nothing was sent")
        h.engine.submit("confirm transfer")
        assertTrue(h.bank.transfers.isEmpty())
    }

    // ---- Controls ----------------------------------------------------------------------------

    @Test
    fun `repeat says the last thing again`() = runTest {
        val h = Harness()
        h.engine.submit("What is my balance?")
        val before = h.speech.spoken.size
        h.engine.submit("say that again")
        assertEquals(before + 1, h.speech.spoken.size)
        assertEquals(h.speech.spoken[before - 1], h.speech.last)
    }

    @Test
    fun `help lists registered actions`() = runTest {
        val h = Harness()
        h.engine.submit("help")
        assertContains(h.speech.last, "check your balance")
        assertContains(h.speech.last, "send money")
    }

    @Test
    fun `describe screen uses the published descriptor`() = runTest {
        val h = Harness()
        h.engine.setScreen(
            com.accessai.core.screen.ScreenDescriptor(
                "transfer", "Transfer money",
                elements = listOf(com.accessai.core.screen.ScreenElement("amount", "input", "Amount in naira", required = true)),
            ),
        )
        h.engine.submit("where am I")
        assertContains(h.speech.last, "Transfer money screen")
        assertContains(h.speech.last, "Amount in naira, input, required")
    }
}
