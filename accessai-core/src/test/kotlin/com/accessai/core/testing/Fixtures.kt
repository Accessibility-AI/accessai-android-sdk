package com.accessai.core.testing

import com.accessai.core.Announcement
import com.accessai.core.actions.ActionRegistry
import com.accessai.core.actions.ActionResult
import com.accessai.core.actions.ActionContext
import com.accessai.core.actions.ParamValue
import com.accessai.core.actions.Resolution
import com.accessai.core.actions.Sensitivity
import com.accessai.core.actions.Validation
import com.accessai.core.actions.action
import com.accessai.core.announce
import com.accessai.core.engine.DialogEngine
import com.accessai.core.engine.EngineConfig
import com.accessai.core.engine.SpeechOutput
import com.accessai.core.net.AccessAIBackend
import com.accessai.core.net.LocalRulesBackend
import com.accessai.core.auth.AuthChallenge
import com.accessai.core.auth.AuthOutcome
import com.accessai.core.auth.DeviceAuthenticator
import com.accessai.core.protocol.AuthProof
import com.accessai.core.protocol.DeviceKeyInfo
import kotlinx.coroutines.delay
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class RecordingSpeech : SpeechOutput {
    val spoken = mutableListOf<String>()
    var stops = 0
    override fun speak(announcement: Announcement) { spoken += announcement.spoken }
    override fun stop() { stops++ }
    val last: String get() = spoken.last()
    fun all(): String = spoken.joinToString(" | ")
}

data class Beneficiary(val id: String, val name: String, val last4: String)

/** A tiny fake bank mirroring the AccessPay demo. */
class FakeBank {
    var balanceKobo = 8_425_000L
    val beneficiaries = listOf(
        Beneficiary("ben_001", "David Adeyemi", "2391"),
        Beneficiary("ben_002", "David Okafor", "7710"),
        Beneficiary("ben_003", "Amaka Obi", "5521"),
    )
    val transfers = mutableListOf<ActionContext>()
    var failNextTransfer = false
    var transferDelayMillis = 0L
    var transactions = listOf("Airtime purchase, 1,000 naira", "Salary, 250,000 naira", "Transfer to Amaka Obi, 3,000 naira")

    fun resolve(query: String): Resolution {
        val q = query.lowercase()
        val matches = beneficiaries.filter { b -> q.split(' ').all { it in b.name.lowercase().split(' ') } }
        return when (matches.size) {
            0 -> Resolution.NotFound("I couldn't find $query in your saved beneficiaries.")
            1 -> matches[0].let { Resolution.Resolved(ParamValue.Text(it.id), "${it.name} (…${it.last4})", "${it.name}, account ending ${it.last4.toList().joinToString(" ")}") }
            else -> Resolution.Ambiguous(matches.map { Resolution.Candidate(ParamValue.Text(it.id), "${it.name} (…${it.last4})", it.name) })
        }
    }
}

/** Stands in for BiometricPrompt + Keystore: a real P-256 key, real signatures, scripted outcomes. */
class FakeAuthenticator : DeviceAuthenticator {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    val kid = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).joinToString("") { "%02x".format(it) }.take(16)

    /** Outcomes to return, in order; when empty, authentication succeeds. */
    val script = ArrayDeque<AuthOutcome>()
    val challenges = mutableListOf<AuthChallenge>()
    var signWithWrongKey = false

    override suspend fun deviceKey() = DeviceKeyInfo(kid, "ES256", publicKey)

    override suspend fun authenticate(challenge: AuthChallenge): AuthOutcome {
        challenges += challenge
        script.removeFirstOrNull()?.let { return it }
        val signingKey = if (signWithWrongKey) {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
        } else keyPair.private
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey)
            update(challenge.payload.toByteArray())
            sign()
        }
        return AuthOutcome.Authenticated(AuthProof("biometric", kid, "ES256", Base64.getEncoder().encodeToString(signature)))
    }
}

fun registryFor(bank: FakeBank, auth: DeviceAuthenticator? = FakeAuthenticator()): ActionRegistry = ActionRegistry(auth).apply {
    register(action("get_balance") {
        name = "check your balance"
        description = "Read the user's available balance"
        examples("what is my balance", "how much do I have")
        handler { ActionResult.Success(announce { text("Your available balance is "); naira(bank.balanceKobo); text(".") }) }
    })
    register(action("navigate_to_transfer") {
        name = "open transfers"
        sensitivity = Sensitivity.NAVIGATE
        examples("I want to send money", "open transfer screen")
        handler { ActionResult.Success(Announcement.of("Opening transfers.")) }
    })
    register(action("get_transaction_history") {
        name = "read your recent transactions"
        examples("read my recent transactions", "transaction history")
        handler {
            ActionResult.Success(
                Announcement.of("You have ${bank.transactions.size} recent transactions."),
                items = bank.transactions.map { Announcement.of(it) },
                emptyMessage = Announcement.of("You have no transactions yet."),
            )
        }
    })
    register(action("transfer_money") {
        name = "send money"
        description = "Send money to a saved beneficiary"
        sensitivity = Sensitivity.SENSITIVE
        confirmPhrase = "confirm transfer"
        text("recipient", "Beneficiary name as the user said it", prompt = "Who would you like to send money to?") { bank.resolve((it as ParamValue.Text).value) }
        amount("amount_minor", "Amount in kobo", prompt = "How much would you like to send?")
        examples("send 5000 naira to David", "transfer money to Amaka")
        validate { p ->
            when {
                p.number("amount_minor") <= 0 -> Validation.Invalid("The amount must be more than zero.")
                p.number("amount_minor") > bank.balanceKobo -> Validation.Invalid("That's more than your available balance. What amount would you like to send?")
                else -> Validation.Valid
            }
        }
        summary { p -> announce { text("send "); naira(p.number("amount_minor")); text(" to ${p.spokenLabel("recipient")}") } }
        handler { ctx ->
            if (bank.transferDelayMillis > 0) delay(bank.transferDelayMillis)
            if (bank.failNextTransfer) {
                bank.failNextTransfer = false
                return@handler ActionResult.Failure(Announcement.of("The transfer failed. No money was sent."))
            }
            bank.transfers += ctx
            bank.balanceKobo -= ctx.params.number("amount_minor")
            ActionResult.Success(announce { text("Your simulated transfer was successful. Your remaining balance is "); naira(bank.balanceKobo); text(".") })
        }
    })
}

class Harness(
    val bank: FakeBank = FakeBank(),
    var now: Instant = Instant.parse("2026-10-29T12:00:00Z"),
    backendOverride: ((() -> Instant) -> AccessAIBackend)? = null,
) {
    val speech = RecordingSpeech()
    val auth = FakeAuthenticator()
    val clock: () -> Instant = { now }
    val backend: AccessAIBackend = backendOverride?.invoke(clock) ?: LocalRulesBackend(clock)
    val engine = DialogEngine(registryFor(bank, auth), backend, speech, EngineConfig(appId = "test", actionTimeoutMillis = 1_000), clock)
}
