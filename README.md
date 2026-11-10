# AccessAI Android SDK

[![CI](https://github.com/Accessibility-AI/accessai-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/Accessibility-AI/accessai-android-sdk/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Accessibility-AI/accessai-android-sdk.svg)](https://jitpack.io/#Accessibility-AI/accessai-android-sdk)

A voice-first, TalkBack-friendly accessibility layer for Android apps. Developers register the actions their app
allows (check balance, send money, read transactions); blind and low-vision users can then do those things by
speaking or typing, with every sensitive action read back and explicitly confirmed.

**The AI only interprets. It never executes.** Your handlers run your business logic, and only after AccessAI's
deterministic checks pass.

Part of AccessAI:

| Repo | What |
|---|---|
| **accessai-android-sdk** (this) | Kotlin SDK: action registry, dialog engine, speech, accessible Compose UI |
| [accessai-server-gemini](https://github.com/Accessibility-AI/accessai-server-gemini) | AI service (Gemini): interpretation, confirmation tokens |
| [accesspay-demo](https://github.com/Accessibility-AI/accesspay-demo) | Demo fintech app + mock bank that integrate this SDK |

## Modules

| Module | Contents | Needs Android? |
|---|---|---|
| `accessai-core` | Action DSL and registry, `DialogEngine` (the conversation and safety logic), confirmation flow, amount parsing, naira speech formatting, HTTP client, offline `LocalRulesBackend` | No (pure Kotlin/JVM) |
| `accessai-android` | `AccessAI` entry point, `AccessAIController`, speech recognition, text-to-speech, TalkBack-aware announcements | Yes |
| `accessai-compose` | `AccessAIModeScreen`, `AccessAIConsentScreen`, `AccessAIToggleButton`, `AccessAIScreen`, high-contrast `AccessAITheme` | Yes |
| `sample` | Minimal host app with simulated funds; CI builds it into an installable APK | Yes |

## Quick start

See [docs/INTEGRATION.md](docs/INTEGRATION.md). The service contract is in [docs/API_CONTRACT.md](docs/API_CONTRACT.md).

```kotlin
implementation("com.github.Accessibility-AI.accessai-android-sdk:accessai-compose:0.2.2")
```

## How a transfer flows

1. User: "Send 5k to David." The SDK sends the text, the current screen and your action manifest to `/v1/interpret`.
2. The service suggests `transfer_money {recipient: "David", amount_minor: 500000}`.
3. Your resolver finds two Davids → "I found 2 matches: David Adeyemi or David Okafor. Which one?"
4. Your validator checks the balance. The SDK reads back: "You are about to send five thousand naira to David Adeyemi,
   account ending 2 3 9 1. Say "confirm transfer" to continue, or "cancel" to stop."
5. Only "confirm transfer" (or the Confirm button) counts. "Yes", "okay", silence and "don't confirm" do not.
6. The phone's own security check runs: fingerprint or face, or the PIN / pattern / password fallback. AccessAI
   never sees any of it, only a signature from a key the OS unlocks after the check succeeds.
7. The service checks that signature, then issues a signed, 60-second, single-use token bound to the exact parameters.
8. Your handler runs once with the token and an idempotency key. Success is announced only if it returns `Success`.

PINs and passwords said aloud are dropped on the phone, and account numbers and screen values are redacted before
anything is sent. See [SECURITY.md](SECURITY.md) for every enforced rule.

## Building

Android compilation, lint and the sample APK run in **GitHub Actions** (see `.github/workflows/ci.yml`): every push and
pull request. Download the APK and AARs from the run's **Artifacts** section.

Locally you need only JDK 17 to build and test the core, which holds all of the conversation and safety logic:

```bash
./gradlew -PcoreOnly=true :accessai-core:test
```

## Status

v0.2.2, hackathon MVP. English only. Simulated funds only. Not a compliance certification.
