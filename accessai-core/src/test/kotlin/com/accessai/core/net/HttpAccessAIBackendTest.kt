package com.accessai.core.net

import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.InterpretRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpAccessAIBackendTest {
    private val server = MockWebServer()
    private lateinit var backend: HttpAccessAIBackend

    @BeforeTest
    fun setUp() {
        server.start()
        backend = HttpAccessAIBackend(server.url("/").toString(), appId = "accesspay-demo")
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `interpret posts the contract and parses the response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"request_id":"r1","outcome":"action","action_id":"transfer_money",
                   "params":{"recipient":"David","amount_minor":500000},"missing_params":[],"confidence":0.93,"extra":"ignored"}""",
            ),
        )
        val response = backend.interpret(InterpretRequest(sessionId = "s1", text = "Send 5k to David", locale = "en-NG", actions = emptyList()))
        assertEquals("transfer_money", response.actionId)
        assertEquals("500000", response.params.getValue("amount_minor").toString())

        val recorded = server.takeRequest()
        assertEquals("/v1/interpret", recorded.path)
        assertEquals("accesspay-demo", recorded.getHeader("X-AccessAI-App"))
        val body = recorded.body.readUtf8()
        assertContains(body, "\"session_id\":\"s1\"")
        assertContains(body, "\"dialog\":{")
    }

    @Test
    fun `contract errors keep their code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"not_a_confirmation","message":"no"}}"""))
        val e = assertFailsWith<BackendException> { backend.confirm("pc_1", ConfirmRequest("s1", "voice", "yes")) }
        assertEquals(BackendException.NOT_A_CONFIRMATION, e.code)
    }

    @Test
    fun `rate limiting keeps Retry-After`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
                .setBody("""{"error":{"code":"rate_limited","message":"slow down"}}"""),
        )
        val e = assertFailsWith<BackendException> { backend.interpret(InterpretRequest("s1", "hi", "en-NG", actions = emptyList())) }
        assertEquals(BackendException.RATE_LIMITED, e.code)
        assertEquals(7L, e.retryAfterSeconds)
    }

    @Test
    fun `server errors without a body are reported as server errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val e = assertFailsWith<BackendException> { backend.interpret(InterpretRequest("s1", "hi", "en-NG", actions = emptyList())) }
        assertEquals(BackendException.SERVER, e.code)
    }
}
