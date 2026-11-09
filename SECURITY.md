# AccessAI SDK security rules

These are enforced in code, not just documented. Each has tests in
[`accessai-core/src/test/kotlin/com/accessai/core/SecurityTest.kt`](accessai-core/src/test/kotlin/com/accessai/core/SecurityTest.kt).

| Rule | Where it is enforced |
|---|---|
| Money-moving actions must be `SENSITIVE`. Any action with an `amount(...)` parameter that isn't is rejected. | `ActionSpecBuilder.build()` |
| A `SENSITIVE` action can't be registered without a `DeviceAuthenticator`. On Android one is always present (`BiometricDeviceAuthenticator`) and can't be turned off, only replaced. | `ActionRegistry.register()`, `AccessAIController` |
| After the user says "confirm …" or taps Confirm, the OS prompt (fingerprint/face, falling back to PIN/pattern/password) must succeed **before** a token is requested. Cancel or failure → nothing is requested, nothing runs. 3 failures cancel the request. | `DialogEngine.completeConfirmation()` |
| The auth result is a signature from a Keystore key the OS unlocks only after that check, over `accessai-auth-v1\|session\|pending_id\|action_id\|params_sha256`. The device's public key is registered when the session starts. A proof can't be reused for another request or other amounts. | `BiometricDeviceAuthenticator`, `AuthChallenge`, contract §Device authentication |
| PINs, passwords, OTPs, CVVs, BVN/NIN with digits are **blocked on the device**: never sent, never put in the transcript. | `SensitiveData.inspectUtterance()` at the start of `DialogEngine.submit()` |
| Account, card, phone and ID numbers (10+ digits, numerals or spoken digit words) are replaced with `[number removed]` before anything else sees the text. | same |
| Screen descriptors are redacted before sending: no element values ever, no `sensitive` or credential elements, numbers and money removed from titles/labels. A `sensitive` screen (login, PIN, card) sends only its title. The full descriptor is used only on the phone, to describe the screen aloud. | `SensitiveData.screenForTransport()` |
| AccessAI never reads or fills login fields: actions can't have credential-like parameters (`pin`, `password`, `otp`, `cvv`, `bvn`…), and credential screen elements can't carry a value. The SDK only navigates (e.g. an `open_login` action) and announces. | `ActionSpecBuilder.build()`, `ScreenElement` |
| AccessAI's own spoken responses (which can contain balances and names) are never sent back to the service. | `DialogEngine.dialogContext()` |
| Transcripts are memory-only, capped at 50 lines, redacted, and wiped when accessibility mode is turned off. No audio is recorded. The SDK writes nothing to logs. Speech recognition prefers on-device models. | `DialogEngine`, `AccessAIController.disable()`, `AndroidSpeechInput` |

## Limits to know about

- **Speech recognition happens before the SDK sees the words.** Android's recognizer (usually Google's) may process audio in the cloud when no offline model is installed. The SDK asks for offline recognition first; when the recognizer has no language pack for the locale (common for en-NG), it retries online, then with plain English. It filters the text, but it can't stop a user saying a PIN to the recognizer. The consent screen and the blocked-PIN message both tell users never to say one.
- **The key unlock lasts 10 seconds.** The OS lets the key sign for 10 seconds after a successful check rather than once per check, so it works on Android 8–10 with a PIN fallback. Only the SDK uses this key. A per-use key bound to a `CryptoObject` is a possible upgrade for Android 11+.
- **The server must enforce its side too.** It must require and verify the auth proof for sensitive actions, reject screen descriptors containing account-number patterns, and keep no transcripts. See `docs/API_CONTRACT.md`.
- **iOS** is not covered. This is an Android SDK.
- **Legal:** processing Nigerian users' data falls under the Nigeria Data Protection Act 2023 (consent, data minimisation, cross-border transfer to LLM providers). Get a legal review before launch.

## Reporting a vulnerability

Open a private security advisory on this repository rather than a public issue.
