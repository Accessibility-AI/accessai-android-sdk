package com.accessai.core.engine

import com.accessai.core.protocol.InterpretResponse
import com.accessai.core.testing.Harness
import com.accessai.core.testing.ScriptedBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Each finished request leaves a typed result on screen, so the UI can show the matching spec screen. */
class OutcomeTest {

    private fun DialogEngine.outcome() = assertIs<DialogState.Outcome>(state.value)

    @Test
    fun `a read action is an answer, titled from the action name`() = runTest {
        val h = Harness()
        h.engine.submit("What is my balance?")
        val o = h.engine.outcome()
        assertEquals(OutcomeKind.ANSWER, o.kind)
        assertEquals("Check your balance", o.title)
        assertEquals("get_balance", o.actionId)
        assertEquals(h.speech.lastAnnouncement, o.announcement)
    }

    @Test
    fun `a confirmed action that succeeds is a success`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to David Adeyemi")
        h.engine.submit("confirm transfer")
        val o = h.engine.outcome()
        assertEquals(OutcomeKind.SUCCESS, o.kind)
        assertEquals("transfer_money", o.actionId)
    }

    @Test
    fun `host failure keeps the host's title and detail`() = runTest {
        val h = Harness()
        h.bank.failNextTransfer = true
        h.engine.submit("Send 5000 to Amaka")
        h.engine.submit("confirm transfer")
        val o = h.engine.outcome()
        assertEquals(OutcomeKind.FAILURE, o.kind)
        assertEquals("Payment not sent", o.title)
        assertEquals("Your balance is still ₦84,250.", o.detail)
    }

    @Test
    fun `no connection is offline`() = runTest {
        val h = Harness(backendOverride = { ScriptedBackend(interpretError = true) })
        h.engine.submit("What is my balance?")
        val o = h.engine.outcome()
        assertEquals(OutcomeKind.OFFLINE, o.kind)
        assertEquals("No connection", o.title)
    }

    @Test
    fun `misses are counted in a row and reset by anything else`() = runTest {
        val h = Harness(backendOverride = {
            ScriptedBackend(interpretResponse = InterpretResponse(outcome = "clarify", message = "Sorry, I'm not sure what you meant."))
        })
        h.engine.speechNotRecognised()
        h.engine.submit("mumble")
        h.engine.speechNotRecognised()
        assertEquals(OutcomeKind.NOT_UNDERSTOOD, h.engine.outcome().kind)
        assertEquals(3, h.engine.outcome().misses)
        assertContains(h.speech.last, "I didn't catch that. Try: “what is my balance”")
    }

    @Test
    fun `an answer ends a run of misses`() = runTest {
        val h = Harness()
        h.engine.speechNotRecognised()
        h.engine.speechNotRecognised()
        assertEquals(2, h.engine.outcome().misses)
        h.engine.submit("What is my balance?")
        assertEquals(OutcomeKind.ANSWER, h.engine.outcome().kind)
        h.engine.speechNotRecognised()
        assertEquals(1, h.engine.outcome().misses)
    }

    @Test
    fun `not caught while waiting for an answer keeps waiting`() = runTest {
        val h = Harness()
        h.engine.submit("Send money to Amaka")
        assertIs<DialogState.AwaitingInput>(h.engine.state.value)
        h.engine.speechNotRecognised()
        assertIs<DialogState.AwaitingInput>(h.engine.state.value)
        assertContains(h.speech.last, "didn't catch that")
    }

    @Test
    fun `not caught during a confirmation keeps it and asks for confirm or cancel`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        val before = assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        h.engine.speechNotRecognised()
        assertEquals(before, h.engine.state.value)
        assertContains(h.speech.last, "confirm” or “cancel")
        h.engine.submit("confirm")
        assertEquals(OutcomeKind.SUCCESS, h.engine.outcome().kind)
    }

    @Test
    fun `cancelling a confirmation is shown as cancelled`() = runTest {
        val h = Harness()
        h.engine.submit("Send 5000 to Amaka")
        h.engine.cancel()
        val o = h.engine.outcome()
        assertEquals(OutcomeKind.CANCELLED, o.kind)
        assertContains(o.announcement.spoken, "Nothing was sent")
    }

    @Test
    fun `dismiss clears a result, and a new request replaces it`() = runTest {
        val h = Harness()
        h.engine.submit("What is my balance?")
        h.engine.dismiss()
        assertEquals(DialogState.Idle, h.engine.state.value)

        h.engine.submit("What is my balance?")
        h.engine.submit("Send 5000 to Amaka")
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
        h.engine.dismiss()
        assertIs<DialogState.AwaitingConfirmation>(h.engine.state.value)
    }
}
