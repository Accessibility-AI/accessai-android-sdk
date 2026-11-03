# AccessAI service API — contract v1.1

This is the contract between the SDK (`accessai-core`, package `com.accessai.core.protocol`) and the AI service
(`accessai-server`). Change both together and bump the version.

**v1.1 (SDK 0.2.0):** device authentication for sensitive actions (`device_key` on sessions, `auth` on confirm,
`auth_invalid` error, `amr` token claim) and data-handling rules (see [Privacy](#privacy-and-retention)). All new
request fields are additive.

- Base URL: configured by the host app (`AccessAIConfig.serverUrl`). HTTPS only in production.
- All bodies are JSON, `snake_case`. Unknown fields must be ignored by both sides.
- Every request carries `X-AccessAI-App: <app_id>` (public identifier, not a secret) and `X-Request-Id: <uuid>`.
- The SDK never retries a request automatically.

## Errors

Any non-2xx response should have this body:

```json
{ "error": { "code": "not_a_confirmation", "message": "Human-readable detail for logs" } }
```

| code | HTTP | When |
|---|---|---|
| `bad_request` | 400 | Schema validation failed |
| `session_expired` | 401 | Unknown or expired `session_id` (the SDK creates a new session and retries `/interpret` once) |
| `not_found` | 404 | Unknown `pending_id`, or it belongs to another session |
| `not_a_confirmation` | 422 | `/confirm` with `method: "voice"` whose utterance is not an exact confirmation |
| `confirmation_expired` | 410 | `/confirm` after `expires_at` |
| `confirmation_used` | 409 | `/confirm` on a pending confirmation that was already confirmed or cancelled |
| `auth_invalid` | 401 | `/confirm` for a sensitive action without a valid device-auth proof (see below) |
| `rate_limited` | 429 | |
| `server` | 5xx | |

## POST /v1/sessions

```json
// request
{
  "app_id": "accesspay-demo",
  "locale": "en-NG",
  "device_key": {                                 // present when the phone has a screen lock
    "kid": "3f9a0c1d22e4b6a7",                    // first 16 hex of SHA-256(public_key DER)
    "alg": "ES256",
    "public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE…",   // base64 X.509 SubjectPublicKeyInfo DER
    "attestation_chain": ["MIIC…", "MIIB…"]       // optional Android key attestation, leaf first
  }
}
// response 200
{ "session_id": "s_8f2c…", "expires_at": "2026-10-29T13:00:00Z" }
```

Store `device_key` with the session. Every sensitive confirmation in this session must be signed by it. Optionally
verify `attestation_chain` to require hardware-backed keys.

## POST /v1/interpret

Turns one user utterance into a structured suggestion. **Never executes anything.**

```json
// request
{
  "session_id": "s_8f2c…",
  "text": "Send five thousand naira to David",
  "locale": "en-NG",
  "screen": {                                    // optional; current screen descriptor
    "screen_id": "home", "title": "Home", "summary": null,
    "elements": [ { "id": "balance", "role": "text", "label": "Available balance", "required": false, "value": "₦84,250" } ]
  },
  "actions": [                                   // the host's allowlist; the ONLY actions that may be returned
    {
      "id": "transfer_money",
      "name": "send money",
      "description": "Send money to a saved beneficiary",
      "sensitivity": "sensitive",                // "read" | "navigate" | "sensitive"
      "requires_confirmation": true,
      "params": [
        { "name": "recipient",    "type": "string",       "description": "Beneficiary name as the user said it", "required": true },
        { "name": "amount_minor", "type": "amount_minor", "description": "Amount in kobo", "required": true }
      ],
      "examples": ["send 5000 naira to David"]
    }
  ],
  "dialog": {                                    // what AccessAI is in the middle of, if anything
    "pending_action_id": null,
    "filled_params": {},                         // values as the user said them (never resolved ids)
    "missing_params": [],
    "awaiting": "none",                          // "none" | "parameter" | "disambiguation" | "confirmation"
    "last_system_message": "Welcome to AccessPay…"
  }
}
```

```json
// response 200
{
  "request_id": "r_…",
  "outcome": "action",                           // "action" | "clarify" | "unsupported" | "control"
  "action_id": "transfer_money",                 // for "action": MUST be one of request.actions[].id
  "params": { "recipient": "David", "amount_minor": 500000 },
  "missing_params": [],
  "control": null,                               // for "control": cancel | stop | repeat | next | previous | help | describe_screen
  "message": null,                               // for "clarify"/"unsupported": short spoken text, no amounts
  "confidence": 0.93                             // 0..1; the SDK treats < 0.6 (configurable) as unclear
}
```

Rules for the service:

1. `action_id` must come from `request.actions`. The SDK drops anything else, but don't send it.
2. Only include params you actually heard. **Never guess** a recipient or an amount: leave it out and the SDK asks.
3. `amount_minor` is a JSON **integer** in kobo (₦5,000 → `500000`). Strings or decimals are rejected by the SDK.
   Parse amounts deterministically ("5k", "₦5,000", "five thousand") rather than trusting free model output.
4. `string` params are the user's words ("David"), not ids. The host resolves them on the device.
5. When `dialog.pending_action_id` is set, treat the utterance as a continuation of that action, e.g.
   "make it 2000" while awaiting confirmation → `action` with the same id and only `amount_minor`.
6. Do not return "confirm" as a control. Confirmation never goes through the model.
7. Send no balances or beneficiary lists to the model; they are not in the request.

## POST /v1/confirmations

Called by the SDK after it has read the details back to the user, before the user confirms.

```json
// request
{ "session_id": "s_…", "action_id": "transfer_money", "params": { "amount_minor": 500000, "recipient": "ben_001" } }
// response 200
{ "pending_id": "pc_…", "params_sha256": "b729a280…cd47", "expires_at": "2026-10-29T12:01:00Z" }
```

`params` here are the **resolved** values (beneficiary id, not name). `params_sha256` is the lowercase hex SHA-256 of the
canonical JSON of `params`:

```python
json.dumps(params, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
```

Reference vector: `{"amount_minor":500000,"recipient":"ben_001"}` →
`b729a280eabf6c63b6a6b889bfa1b26d4dad23286f2e3a36b98028e35879cd47`.
The SDK refuses to continue if the server's hash differs from its own. Suggested TTL: 60 seconds.

## POST /v1/confirmations/{pending_id}/confirm

```json
// request
{
  "session_id": "s_…",
  "method": "voice",                              // or "touch" (no utterance)
  "utterance": "confirm transfer",
  "auth": {                                       // required for sensitive actions
    "method": "biometric",                        // or "device_credential" (PIN / pattern / password)
    "kid": "3f9a0c1d22e4b6a7",
    "alg": "ES256",
    "signature": "MEUCIQ…"                        // base64 DER ECDSA signature
  }
}
// response 200
{ "status": "confirmed", "token": "<compact JWS>", "nonce": "8b0e…", "expires_at": "2026-10-29T12:01:00Z" }
```

### Device authentication (sensitive actions)

The SDK only calls `/confirm` after the phone's own lock screen (fingerprint or face, falling back to PIN, pattern
or password) succeeded. It proves this with a signature from a Keystore key the OS unlocks only after that check.
Before minting a token for a sensitive action, the service must:

1. require `auth`, with `auth.kid` equal to the session's `device_key.kid`;
2. rebuild the signed payload exactly (UTF-8):
   `accessai-auth-v1|{session_id}|{pending_id}|{action_id}|{params_sha256}`;
3. verify `auth.signature` with the session's public key (ECDSA P-256 / SHA-256, DER signature);
4. otherwise return `auth_invalid` and **mint nothing**.

Because the payload includes the pending id and the parameter hash, a proof can't be replayed for another request
or different amounts. Put `auth.method` in the token as `amr` so the host backend has an audit trail. The service
never receives a PIN, password or biometric; reject any request that seems to contain one.

- For `voice`, re-check the utterance with a **strict, deterministic** grammar: accept only phrases like
  `confirm`, `confirm transfer`, `i confirm`, `yes confirm`. Reject anything containing a negation. No model.
- Single use: a second call returns `confirmation_used`.
- `token` is a compact JWS signed with **EdDSA (Ed25519)**. Claims:

| claim | meaning |
|---|---|
| `iss` | `"accessai"` |
| `sub` | session id |
| `aid` | action id |
| `ph` | `params_sha256` |
| `jti` | nonce (also returned as `nonce`) |
| `amr` | `["biometric"]` or `["device_credential"]`: how the user authenticated (v1.1) |
| `iat`, `exp` | issued-at, expiry (≤ 60 s) |

## POST /v1/confirmations/{pending_id}/cancel

`{ "session_id": "s_…" }` → `204`. Idempotent.

## GET /.well-known/jwks.json

Public key(s) for verifying confirmation tokens. The host backend (e.g. the AccessPay mock bank) must, before
executing a sensitive action:

1. verify the JWS signature against this JWKS, and `exp`;
2. check `aid` equals the action it is about to run;
3. recompute the canonical SHA-256 of the exact parameters it received and compare to `ph`;
4. reject a `jti` it has already seen (use it as the idempotency key).

Tokens from the SDK's offline `LocalRulesBackend` start with `dev-unsigned.` and must always be rejected in production.

## Privacy and retention

What the SDK guarantees before anything is sent (see `SECURITY.md` in the SDK):

- utterances that look like a PIN, password, OTP or card code are blocked on the device and never sent;
- 10+ digit numbers (account, card, phone, BVN) are replaced with `[number removed]`;
- `screen` has no element `value`s, no sensitive or credential elements, and numbers/amounts in titles and labels
  are replaced with `[number removed]` / `[amount]`. A `sensitive: true` screen (login, PIN) carries only its title;
- `dialog.last_system_message` is always null (it could contain a balance).

What the service must do:

- **Reject** (`bad_request`) any `screen` with an element `value`, or with text matching an account-number pattern
  (10+ digits). This is defence in depth for a misconfigured client.
- Never send balances, beneficiary lists or screen values to an LLM.
- **No retention:** don't store utterances or transcripts beyond the request. Logs mask amounts, names and
  utterances. Keep only what confirmations need (pending id, session, action, params hash, status, timestamps).
- Tell the host which LLM provider and region processes the text, so it can be disclosed to users
  (Nigeria Data Protection Act 2023: cross-border transfer).
