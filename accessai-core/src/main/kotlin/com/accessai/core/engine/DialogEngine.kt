package com.accessai.core.engine

import com.accessai.core.Announcement
import com.accessai.core.auth.AuthChallenge
import com.accessai.core.auth.AuthOutcome
import com.accessai.core.privacy.SensitiveData
import com.accessai.core.protocol.AuthProof
import com.accessai.core.actions.ActionContext
import com.accessai.core.actions.ActionRegistry
import com.accessai.core.actions.ActionResult
import com.accessai.core.actions.ActionSpec
import com.accessai.core.actions.ParamType
import com.accessai.core.actions.ParamValue
import com.accessai.core.actions.Resolution
import com.accessai.core.actions.ResolvedParams
import com.accessai.core.actions.Validation
import com.accessai.core.net.AccessAIBackend
import com.accessai.core.net.BackendException
import com.accessai.core.nlu.AmountParser
import com.accessai.core.nlu.Control
import com.accessai.core.nlu.LocalGrammar
import com.accessai.core.protocol.CancelConfirmationRequest
import com.accessai.core.protocol.Canonical
import com.accessai.core.protocol.ConfirmRequest
import com.accessai.core.protocol.CreateConfirmationRequest
import com.accessai.core.protocol.CreateSessionRequest
import com.accessai.core.protocol.DialogContext
import com.accessai.core.protocol.InterpretRequest
import com.accessai.core.protocol.InterpretResponse
import com.accessai.core.screen.ScreenDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.UUID

/**
 * Turns what the user says or taps into registered actions.
 *
 * Safety invariants (each covered by tests):
 * - Only actions in the [ActionRegistry] can run; the interpreter only *suggests*.
 * - Recipients and other resolved values are never guessed: ambiguity always becomes a question.
 * - A confirmed action runs only after an exact confirmation phrase or the Confirm button, with a
 *   server-issued token bound to the exact parameters. Any change starts a new confirmation.
 * - Each confirmation runs the handler at most once; timeouts and errors are never retried.
 * - Success is announced only when the host handler returns [ActionResult.Success].
 */
public class DialogEngine(
    private val registry: ActionRegistry,
    private val backend: AccessAIBackend,
    private val speech: SpeechOutput,
    private val config: EngineConfig,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<DialogState>(DialogState.Idle)
    public val state: StateFlow<DialogState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    public val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _lastAnnouncement = MutableStateFlow<Announcement?>(null)
    public val lastAnnouncement: StateFlow<Announcement?> = _lastAnnouncement.asStateFlow()

    private val _screen = MutableStateFlow<ScreenDescriptor?>(null)
    public val screen: StateFlow<ScreenDescriptor?> = _screen.asStateFlow()

    private var sessionId: String? = null
    private var draft: Draft? = null
    private var pending: PendingConfirmation? = null

    // ---- Public API -------------------------------------------------------------------------

    /** Handles one user utterance (spoken or typed). Calls are processed one at a time. */
    public suspend fun submit(text: String, source: InputSource = InputSource.VOICE): Unit = mutex.withLock {
        if (text.isBlank()) {
            say(Announcement.of("I didn't catch that. Please try again, or type your request."))
            return@withLock
        }
        // Before anything else: PINs and passwords are dropped, long numbers redacted. The raw text goes nowhere.
        val raw = when (val check = SensitiveData.inspectUtterance(text.trim())) {
            SensitiveData.Check.Blocked -> {
                addTranscript(Speaker.USER, "[not sent: it may have contained a PIN or password]")
                say(Announcement.of(SensitiveData.BLOCKED_MESSAGE))
                return@withLock
            }
            is SensitiveData.Check.Allowed -> check.text
        }
        addTranscript(Speaker.USER, raw)
        val norm = LocalGrammar.normalize(raw)

        when (val s = _state.value) {
            is DialogState.Executing -> {
                say(Announcement.of("Please wait, I'm still working on your last request."))
                return@withLock
            }
            is DialogState.AwaitingConfirmation -> {
                handleConfirmationReply(norm, raw)
                return@withLock
            }
            else -> Unit
        }

        LocalGrammar.control(norm)?.let { handleControl(it); return@withLock }
        if (tryLocalAnswer(norm, raw)) return@withLock
        interpret(raw, source)
    }

    /** The on-screen Confirm button. Equivalent to saying the confirmation phrase. */
    public suspend fun confirmByTouch(): Unit = mutex.withLock {
        if (_state.value is DialogState.AwaitingConfirmation) completeConfirmation(method = "touch", utterance = null)
    }

    /** The on-screen Cancel button, or the "cancel" command. */
    public suspend fun cancel(): Unit = mutex.withLock { handleControl(Control.CANCEL) }

    public suspend fun next(): Unit = mutex.withLock { handleControl(Control.NEXT) }

    public suspend fun previous(): Unit = mutex.withLock { handleControl(Control.PREVIOUS) }

    public suspend fun help(): Unit = mutex.withLock { handleControl(Control.HELP) }

    public suspend fun describeScreen(): Unit = mutex.withLock { handleControl(Control.DESCRIBE_SCREEN) }

    /** Picks one of the [DialogState.AwaitingInput.choices] by touch. */
    public suspend fun choose(index: Int): Unit = mutex.withLock {
        val d = draft ?: return@withLock
        val candidate = d.candidates.getOrNull(index) ?: return@withLock
        resolveCandidate(d, candidate)
        continueDraft(d)
    }

    public fun repeat() {
        _lastAnnouncement.value?.let { speech.speak(it) }
    }

    public fun stopSpeaking() {
        speech.stop()
    }

    /** Says something on the host's behalf, e.g. a greeting with the balance. */
    public fun say(announcement: Announcement) {
        if (announcement.spoken.isBlank()) return
        _lastAnnouncement.value = announcement
        addTranscript(Speaker.ACCESSAI, announcement.display)
        speech.speak(announcement)
    }

    /** Hosts call this whenever a supported screen becomes visible. */
    public fun setScreen(descriptor: ScreenDescriptor?, announce: Boolean = true) {
        val previous = _screen.value
        _screen.value = descriptor
        val busy = _state.value is DialogState.Executing || _state.value is DialogState.Interpreting
        if (announce && config.announceScreenChanges && descriptor != null && descriptor.screenId != previous?.screenId && !busy) {
            say(Announcement.of("${descriptor.title} screen."))
        }
    }

    /** Clears everything, e.g. when accessibility mode is turned off: pending confirmation, transcript, last response. */
    public suspend fun reset(): Unit = mutex.withLock {
        pending?.let { cancelRemote(it) }
        pending = null
        draft = null
        speech.stop()
        _transcript.value = emptyList()
        _lastAnnouncement.value = null
        _state.value = DialogState.Idle
    }

    // ---- Interpretation ---------------------------------------------------------------------

    private suspend fun interpret(raw: String, source: InputSource) {
        val previousState = _state.value
        _state.value = DialogState.Interpreting
        val response = try {
            callInterpret(raw)
        } catch (e: BackendException) {
            _state.value = previousState
            say(connectivityMessage(e, "understand that"))
            return
        }
        handleInterpretation(response, source)
    }

    private suspend fun callInterpret(raw: String): InterpretResponse {
        suspend fun once() = backend.interpret(
            InterpretRequest(
                sessionId = ensureSession(),
                text = raw,
                locale = config.locale,
                screen = SensitiveData.screenForTransport(_screen.value),
                actions = registry.manifest(),
                dialog = dialogContext(),
            ),
        )
        return try {
            once()
        } catch (e: BackendException) {
            if (e.code != BackendException.SESSION_EXPIRED) throw e
            sessionId = null
            once()
        }
    }

    private suspend fun handleInterpretation(response: InterpretResponse, source: InputSource) {
        when (response.outcome) {
            "control" -> {
                val control = Control.fromWire(response.control)
                if (control != null) handleControl(control) else clarify(null)
            }
            "action" -> {
                if (response.confidence < config.minConfidence) return clarify(null)
                val spec = response.actionId?.let { registry[it] }
                if (spec == null) {
                    // The interpreter suggested something the host never registered. Never run it.
                    return unsupported(null)
                }
                val d = draft?.takeIf { it.spec.id == spec.id } ?: Draft(spec).also { draft = it }
                for ((name, json) in response.params) {
                    val param = spec.param(name) ?: continue
                    val value = ParamValue.fromJson(json, param.type) ?: continue
                    d.set(name, value)
                }
                continueDraft(d)
            }
            "clarify" -> clarify(response.message)
            else -> unsupported(response.message)
        }
    }

    /** Answers that need no network: an amount for an amount question, or picking a candidate. */
    private suspend fun tryLocalAnswer(norm: String, raw: String): Boolean {
        val s = _state.value as? DialogState.AwaitingInput ?: return false
        val d = draft ?: return false
        when (s.kind) {
            InputKind.DISAMBIGUATION -> {
                val index = LocalGrammar.pickCandidate(norm, d.candidates.map { it.label }) ?: return false
                resolveCandidate(d, d.candidates[index])
                continueDraft(d)
                return true
            }
            InputKind.PARAMETER -> {
                val param = s.paramName?.let { d.spec.param(it) } ?: return false
                if (param.type != ParamType.AmountMinor || norm.split(' ').size > 4) return false
                val kobo = AmountParser.parseKobo(raw) ?: return false
                d.set(param.name, ParamValue.Number(kobo))
                continueDraft(d)
                return true
            }
            InputKind.CLARIFY -> return false
        }
    }

    // ---- Drafts: collecting, resolving and validating parameters ----------------------------

    private suspend fun continueDraft(d: Draft) {
        val spec = d.spec
        spec.params.firstOrNull { it.required && it.name !in d.raw }?.let { missing ->
            ask(d, missing.name, InputKind.PARAMETER, Announcement.of(missing.prompt))
            return
        }
        for (param in spec.params) {
            val resolver = param.resolver ?: continue
            val value = d.raw[param.name] ?: continue
            if (param.name in d.resolved) continue
            val resolution = try {
                resolver.resolve(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                draft = null
                _state.value = DialogState.Idle
                say(Announcement.of("Sorry, I couldn't look that up right now. Nothing was done."))
                return
            }
            when (resolution) {
                is Resolution.Resolved -> d.resolved[param.name] = Resolved(resolution.value, resolution.label, resolution.spokenLabel)
                is Resolution.Ambiguous -> {
                    if (resolution.candidates.isEmpty()) {
                        d.remove(param.name)
                        ask(d, param.name, InputKind.PARAMETER, Announcement.of("I couldn't find a match. ${param.prompt}"))
                        return
                    }
                    d.candidates = resolution.candidates
                    d.disambiguating = param.name
                    val names = resolution.candidates.map { it.spokenLabel }
                    val question = Announcement(
                        spoken = "I found ${resolution.candidates.size} matches: ${joinOr(names)}. Which one?",
                        display = "I found ${resolution.candidates.size} matches. Which one?",
                    )
                    _state.value = DialogState.AwaitingInput(spec.id, param.name, InputKind.DISAMBIGUATION, question, resolution.candidates.map { it.label })
                    say(question)
                    return
                }
                is Resolution.NotFound -> {
                    d.remove(param.name)
                    ask(d, param.name, InputKind.PARAMETER, Announcement.of("${resolution.message} ${param.prompt}"))
                    return
                }
            }
        }

        val params = d.toResolvedParams()
        val validation = try {
            spec.validator(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Validation.Invalid("Sorry, I couldn't check those details right now. Nothing was done.")
        }
        if (validation is Validation.Invalid) {
            // Keep the draft so the user can correct one detail ("make it two thousand").
            _state.value = DialogState.AwaitingInput(spec.id, null, InputKind.CLARIFY, Announcement.of(validation.message))
            say(Announcement.of(validation.message))
            return
        }

        draft = null
        if (spec.requiresConfirmation) beginConfirmation(d, params)
        else execute(spec, params, token = null, idempotencyKey = UUID.randomUUID().toString())
    }

    private fun resolveCandidate(d: Draft, candidate: Resolution.Candidate) {
        val name = d.disambiguating ?: return
        d.resolved[name] = Resolved(candidate.value, candidate.label, candidate.spokenLabel)
        d.candidates = emptyList()
        d.disambiguating = null
    }

    private fun ask(d: Draft, paramName: String, kind: InputKind, question: Announcement) {
        draft = d
        _state.value = DialogState.AwaitingInput(d.spec.id, paramName, kind, question)
        say(question)
    }

    private fun clarify(message: String?) {
        val text = message?.takeIf { it.isNotBlank() } ?: "Sorry, I'm not sure what you meant. You can say help to hear what I can do."
        val d = draft
        _state.value = if (d != null) DialogState.AwaitingInput(d.spec.id, null, InputKind.CLARIFY, Announcement.of(text)) else DialogState.Idle
        say(Announcement.of(text))
    }

    private fun unsupported(message: String?) {
        draft = null
        _state.value = DialogState.Idle
        val text = message?.takeIf { it.isNotBlank() } ?: "Sorry, I can't do that yet."
        say(Announcement.of("$text ${helpText()}"))
    }

    // ---- Confirmation -----------------------------------------------------------------------

    private suspend fun beginConfirmation(d: Draft, params: ResolvedParams) {
        val spec = d.spec
        val localHash = Canonical.sha256(params.values)
        val ticket = try {
            backend.createConfirmation(CreateConfirmationRequest(ensureSession(), spec.id, Canonical.jsonObject(params.values)))
        } catch (e: BackendException) {
            _state.value = DialogState.Idle
            say(connectivityMessage(e, "prepare that for confirmation").plus(Announcement.of("Nothing was sent.")))
            return
        }
        if (!ticket.paramsSha256.equals(localHash, ignoreCase = true)) {
            cancelRemote(PendingConfirmation(spec, d, params, ticket.pendingId, Instant.EPOCH, localHash))
            _state.value = DialogState.Idle
            say(Announcement.of("Something didn't match while preparing your request, so I stopped. Nothing was sent."))
            return
        }
        val expiresAt = runCatching { Instant.parse(ticket.expiresAt) }.getOrElse { clock().plusSeconds(60) }
        pending = PendingConfirmation(spec, d, params, ticket.pendingId, expiresAt, localHash)

        val summary = spec.summary!!(params)
        val prompt = Announcement(
            spoken = "You are about to ${summary.spoken}. Say \"${spec.confirmPhrase}\" to continue, or \"cancel\" to stop.",
            display = "You are about to ${summary.display}. Say \"${spec.confirmPhrase}\" or tap Confirm to continue. Say \"cancel\" or tap Cancel to stop.",
        )
        _state.value = DialogState.AwaitingConfirmation(spec.id, summary, spec.confirmPhrase, expiresAt)
        say(prompt)
    }

    private suspend fun handleConfirmationReply(norm: String, raw: String) {
        val p = pending ?: run { _state.value = DialogState.Idle; return }
        if (LocalGrammar.isConfirm(norm, p.spec.confirmPhrase)) {
            completeConfirmation(method = "voice", utterance = raw)
            return
        }
        when (LocalGrammar.control(norm)) {
            Control.CANCEL -> return handleControl(Control.CANCEL)
            Control.REPEAT -> return repeat()
            Control.STOP -> return stopSpeaking()
            Control.HELP, Control.DESCRIBE_SCREEN -> return reprompt(p, null)
            else -> Unit
        }
        if (LocalGrammar.isBareAffirmative(norm)) {
            reprompt(p, "To keep you safe, I need you to say \"${p.spec.confirmPhrase}\", or tap Confirm.")
            return
        }

        // Anything else might be a correction ("make it two thousand"). Ask the interpreter.
        val response = try {
            callInterpret(raw)
        } catch (e: BackendException) {
            reprompt(p, "I couldn't understand that because of a connection problem.")
            return
        }
        when {
            response.outcome == "control" && Control.fromWire(response.control) == Control.CANCEL -> handleControl(Control.CANCEL)
            response.outcome == "action" && response.actionId == p.spec.id && response.confidence >= config.minConfidence -> {
                val updates = response.params.mapNotNull { (name, json) ->
                    p.spec.param(name)?.let { ParamValue.fromJson(json, it.type) }?.let { name to it }
                }.filter { (name, value) -> p.draft.raw[name] != value }
                if (updates.isEmpty()) {
                    reprompt(p, null)
                    return
                }
                // Changing any detail invalidates the old confirmation; a new read-back follows.
                pending = null
                cancelRemote(p)
                val d = p.draft
                updates.forEach { (name, value) -> d.set(name, value) }
                draft = d
                continueDraft(d)
            }
            response.outcome == "action" && response.actionId != null && registry[response.actionId] != null -> {
                pending = null
                cancelRemote(p)
                say(Announcement.of("I've cancelled the ${p.spec.name} request. Nothing was sent."))
                handleInterpretation(response, InputSource.VOICE)
            }
            else -> reprompt(p, "I didn't hear a confirmation.")
        }
    }

    private suspend fun completeConfirmation(method: String, utterance: String?) {
        val p = pending ?: return
        // Single use: the pending confirmation is consumed before anything is awaited.
        pending = null
        if (clock() >= p.expiresAt) {
            _state.value = DialogState.Idle
            say(Announcement.of("That confirmation has expired, so nothing was sent. Please make the request again."))
            return
        }
        val session = try {
            ensureSession()
        } catch (e: BackendException) {
            _state.value = DialogState.Idle
            say(connectivityMessage(e, "confirm your request").plus(Announcement.of("Nothing was sent.")))
            return
        }

        // Device authentication: the OS checks it's the account holder before any token can exist.
        var proof: AuthProof? = null
        if (p.spec.requiresDeviceAuth) {
            val authenticator = checkNotNull(registry.deviceAuthenticator) { "SENSITIVE action registered without authenticator" }
            _state.value = DialogState.AwaitingAuthentication(p.spec.id)
            say(Announcement.of("To confirm it's you, use your fingerprint, face, or screen lock now."))
            val challenge = AuthChallenge(session, p.pendingId, p.spec.id, p.paramsSha256, p.spec.summary!!(p.params))
            val outcome = try {
                authenticator.authenticate(challenge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AuthOutcome.Failed("Your phone couldn't check it's you.")
            }
            when (outcome) {
                is AuthOutcome.Authenticated -> proof = outcome.proof
                is AuthOutcome.Unavailable -> {
                    cancelRemote(p)
                    _state.value = DialogState.Idle
                    say(Announcement.of("${outcome.message} Nothing was sent."))
                    return
                }
                AuthOutcome.Cancelled, is AuthOutcome.Failed -> {
                    p.authAttempts++
                    val why = (outcome as? AuthOutcome.Failed)?.message ?: "You closed the check."
                    if (p.authAttempts >= config.maxAuthAttempts) {
                        cancelRemote(p)
                        _state.value = DialogState.Idle
                        say(Announcement.of("$why I've cancelled the request. Nothing was sent."))
                    } else {
                        // Nothing was signed, so the same pending confirmation can be retried.
                        pending = p
                        _state.value = DialogState.AwaitingConfirmation(p.spec.id, p.spec.summary!!(p.params), p.spec.confirmPhrase, p.expiresAt)
                        say(Announcement.of("$why Nothing was sent. Say \"${p.spec.confirmPhrase}\" to try again, or \"cancel\" to stop."))
                    }
                    return
                }
            }
        }

        _state.value = DialogState.Executing(p.spec.id)
        val confirmed = try {
            backend.confirm(p.pendingId, ConfirmRequest(session, method, utterance, proof))
        } catch (e: BackendException) {
            when (e.code) {
                BackendException.AUTH_INVALID -> {
                    // E.g. new fingerprint enrolled: the device key changed. Re-register it on the next session.
                    sessionId = null
                    _state.value = DialogState.Idle
                    say(Announcement.of("I couldn't verify it was you, so nothing was sent. Please make the request again."))
                }
                BackendException.NOT_A_CONFIRMATION -> {
                    pending = p
                    _state.value = DialogState.AwaitingConfirmation(p.spec.id, p.spec.summary!!(p.params), p.spec.confirmPhrase, p.expiresAt)
                    reprompt(p, "That wasn't recognised as a confirmation.")
                }
                BackendException.CONFIRMATION_EXPIRED, BackendException.CONFIRMATION_USED -> {
                    _state.value = DialogState.Idle
                    say(Announcement.of("That confirmation is no longer valid, so nothing was sent. Please make the request again."))
                }
                else -> {
                    _state.value = DialogState.Idle
                    say(connectivityMessage(e, "confirm your request").plus(Announcement.of("Nothing was sent.")))
                }
            }
            return
        }
        execute(p.spec, p.params, token = confirmed.token, idempotencyKey = confirmed.nonce)
    }

    private fun reprompt(p: PendingConfirmation, preface: String?) {
        val summary = p.spec.summary!!(p.params)
        val text = "You are about to ${summary.spoken}. Say \"${p.spec.confirmPhrase}\" to continue, or \"cancel\" to stop."
        say(Announcement(listOfNotNull(preface, text).joinToString(" ")))
    }

    private suspend fun cancelRemote(p: PendingConfirmation) {
        val session = sessionId ?: return
        try {
            backend.cancelConfirmation(p.pendingId, CancelConfirmationRequest(session))
        } catch (_: BackendException) {
            // The server-side confirmation expires on its own; the local one is already gone.
        }
    }

    // ---- Execution ----------------------------------------------------------------------------

    private suspend fun execute(spec: ActionSpec, params: ResolvedParams, token: String?, idempotencyKey: String) {
        check(!spec.requiresConfirmation || token != null) { "Confirmed action ${spec.id} without a token" }
        _state.value = DialogState.Executing(spec.id)
        val context = ActionContext(spec.id, params, token, idempotencyKey)
        val result = try {
            withTimeout(config.actionTimeoutMillis) { spec.handler(context) }
        } catch (e: TimeoutCancellationException) {
            ActionResult.Failure(
                Announcement.of(
                    if (spec.requiresConfirmation) "I didn't get a result in time, so I don't know if it went through. I did not try again. Please check your transactions before trying again."
                    else "That is taking too long, so I stopped waiting. Please try again.",
                ),
            )
        } catch (e: CancellationException) {
            _state.value = DialogState.Idle
            throw e
        } catch (e: Exception) {
            ActionResult.Failure(Announcement.of("Sorry, that could not be completed. Nothing was changed by AccessAI."))
        }

        when (result) {
            is ActionResult.Success -> {
                if (result.items.isEmpty()) {
                    _state.value = DialogState.Idle
                    say(result.announcement + (result.emptyMessage ?: Announcement("")))
                } else {
                    _state.value = DialogState.ReadingList(result.items, 0)
                    say(result.announcement + itemPrompt(result.items, 0))
                }
            }
            is ActionResult.Failure -> {
                _state.value = DialogState.Idle
                say(result.announcement)
            }
        }
    }

    private fun itemPrompt(items: List<Announcement>, index: Int): Announcement {
        val position = Announcement.of("${index + 1} of ${items.size}.")
        val hint = if (index < items.size - 1) Announcement.of("Say next for the next one, or stop.") else Announcement.of("That was the last one.")
        return position + items[index] + hint
    }

    // ---- Controls -------------------------------------------------------------------------

    private suspend fun handleControl(control: Control) {
        val s = _state.value
        when (control) {
            Control.CANCEL -> {
                val p = pending
                when {
                    p != null -> {
                        pending = null
                        cancelRemote(p)
                        _state.value = DialogState.Idle
                        say(Announcement.of("Cancelled. Nothing was sent."))
                    }
                    s is DialogState.Executing -> say(Announcement.of("It's too late to cancel; I'm waiting for the result."))
                    draft != null -> {
                        draft = null
                        _state.value = DialogState.Idle
                        say(Announcement.of("Okay, cancelled."))
                    }
                    s is DialogState.ReadingList -> {
                        speech.stop()
                        _state.value = DialogState.Idle
                    }
                    else -> say(Announcement.of("There's nothing to cancel."))
                }
            }
            Control.STOP -> {
                speech.stop()
                if (s is DialogState.ReadingList) _state.value = DialogState.Idle
            }
            Control.REPEAT -> repeat()
            Control.NEXT, Control.PREVIOUS -> {
                if (s !is DialogState.ReadingList) {
                    say(Announcement.of("There's nothing to read right now."))
                    return
                }
                val index = s.index + if (control == Control.NEXT) 1 else -1
                when {
                    index >= s.items.size -> {
                        _state.value = DialogState.Idle
                        say(Announcement.of("That was the last one."))
                    }
                    index < 0 -> say(Announcement.of("That was the first one.") + itemPrompt(s.items, 0))
                    else -> {
                        _state.value = s.copy(index = index)
                        say(itemPrompt(s.items, index))
                    }
                }
            }
            Control.HELP -> say(Announcement.of(helpText()))
            Control.DESCRIBE_SCREEN -> say(
                Announcement.of(_screen.value?.describe() ?: "I don't have a description of this screen.") + Announcement.of(helpText()),
            )
        }
    }

    private fun helpText(): String {
        val names = registry.all().map { it.name }
        val actions = if (names.isEmpty()) "" else "You can ask me to ${joinOr(names)}. "
        return "${actions}You can also say repeat, stop, cancel, or where am I."
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private suspend fun ensureSession(): String {
        sessionId?.let { return it }
        val deviceKey = registry.deviceAuthenticator?.let { auth ->
            try {
                auth.deviceKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        return backend.createSession(CreateSessionRequest(config.appId, config.locale, deviceKey)).sessionId.also { sessionId = it }
    }

    private fun dialogContext(): DialogContext {
        val p = pending
        val d = p?.draft ?: draft
        val awaiting = when (val s = _state.value) {
            is DialogState.AwaitingConfirmation -> "confirmation"
            is DialogState.AwaitingInput -> if (s.kind == InputKind.DISAMBIGUATION) "disambiguation" else "parameter"
            else -> if (p != null) "confirmation" else "none"
        }
        return DialogContext(
            pendingActionId = d?.spec?.id,
            // Only what the user said, never resolved identifiers.
            filledParams = d?.raw?.mapValues { it.value.toJson() } ?: emptyMap(),
            missingParams = d?.spec?.params?.filter { it.required && it.name !in d.raw }?.map { it.name } ?: emptyList(),
            awaiting = if (d == null) "none" else awaiting,
            // Never sent: AccessAI's last message may contain a balance or a beneficiary's name.
            lastSystemMessage = null,
        )
    }

    private fun connectivityMessage(e: BackendException, doing: String): Announcement = Announcement.of(
        if (e.isConnectivity) "I couldn't connect to AccessAI to $doing. Please check your internet connection. You can still type, or say repeat or cancel."
        else "Sorry, something went wrong while trying to $doing.",
    )

    private fun addTranscript(speaker: Speaker, text: String) {
        _transcript.update { (it + TranscriptEntry(speaker, text)).takeLast(config.transcriptLimit) }
    }

    private fun joinOr(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
    }

    private class Resolved(val value: ParamValue, val label: String, val spokenLabel: String)

    private class Draft(val spec: ActionSpec) {
        /** Values as the user gave them. */
        val raw = linkedMapOf<String, ParamValue>()
        /** Values replaced by a resolver (e.g. "David" → beneficiary id). */
        val resolved = mutableMapOf<String, Resolved>()
        var candidates: List<Resolution.Candidate> = emptyList()
        var disambiguating: String? = null

        fun set(name: String, value: ParamValue) {
            if (raw[name] != value) resolved.remove(name)
            raw[name] = value
            if (disambiguating == name) {
                candidates = emptyList()
                disambiguating = null
            }
        }

        fun remove(name: String) {
            raw.remove(name)
            resolved.remove(name)
        }

        fun toResolvedParams(): ResolvedParams {
            val values = spec.params.mapNotNull { p ->
                val v = resolved[p.name]?.value ?: raw[p.name] ?: return@mapNotNull null
                p.name to v
            }.toMap()
            val labels = resolved.mapValues { it.value.label to it.value.spokenLabel }
            return ResolvedParams(values, labels)
        }
    }

    private class PendingConfirmation(
        val spec: ActionSpec,
        val draft: Draft,
        val params: ResolvedParams,
        val pendingId: String,
        val expiresAt: Instant,
        val paramsSha256: String,
    ) {
        var authAttempts = 0
    }
}
