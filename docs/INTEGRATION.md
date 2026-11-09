# Integrating AccessAI into an Android app

About 15 minutes for a Compose app. The `sample/` module in this repo is a complete working example.

## 1. Add the dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.Accessibility-AI.accessai-android-sdk:accessai-compose:0.2.1")
}
```

`accessai-compose` brings in `accessai-android` and `accessai-core`. Requires minSdk 26.

**Your activity must extend `FragmentActivity`** (or `AppCompatActivity`), not `ComponentActivity`. Transfers need the
phone's fingerprint/face/screen-lock prompt, which Android only shows from a `FragmentActivity`. `setContent { }`
works the same.

Read [SECURITY.md](../SECURITY.md): those rules are enforced by the SDK and will throw at registration if broken.

To work on the SDK and your app at the same time, clone both side by side and add to your app's
`settings.gradle.kts`:

```kotlin
includeBuild("../accessai-android-sdk")   // Gradle substitutes the JitPack dependency with local sources
```

## 2. Initialise and register actions

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AccessAI.init(this, AccessAIConfig(appId = "accesspay-demo", serverUrl = BuildConfig.ACCESSAI_URL))

        AccessAI.registerAction(action("get_balance") {
            name = "check your balance"
            examples("what is my balance")
            handler { ActionResult.Success(announce { text("Your balance is "); naira(bank.balanceKobo()); text(".") }) }
        })

        AccessAI.registerAction(action("transfer_money") {
            name = "send money"
            sensitivity = Sensitivity.SENSITIVE          // forces read-back + explicit confirmation
            confirmPhrase = "confirm transfer"
            text("recipient", "Beneficiary name as said", prompt = "Who would you like to send money to?") { raw ->
                bank.findBeneficiary((raw as ParamValue.Text).value)   // Resolved / Ambiguous / NotFound
            }
            amount("amount_minor", "Amount in kobo", prompt = "How much would you like to send?")
            validate { p -> if (p.number("amount_minor") > bank.balanceKobo()) Validation.Invalid("That's more than your balance.") else Validation.Valid }
            summary { p -> announce { text("send "); naira(p.number("amount_minor")); text(" to ${p.spokenLabel("recipient")}") } }
            handler { ctx ->
                // Forward the token and idempotency key; your backend verifies them (see API_CONTRACT.md).
                when (val r = bank.transfer(ctx.params.text("recipient"), ctx.params.number("amount_minor"), ctx.confirmationToken!!, ctx.idempotencyKey)) {
                    is Ok -> ActionResult.Success(announce { text("Sent. Your balance is "); naira(r.balance); text(".") })
                    is Err -> ActionResult.Failure(Announcement.of("The transfer failed: ${r.reason}. No money was sent."))
                }
            }
        })
    }
}
```

`serverUrl = null` runs without a server (keyword matching, unsigned tokens). Development only.

## 3. Add the entry point and mode screen

```kotlin
val controller = AccessAI.controller
val enabled by controller.enabled.collectAsStateWithLifecycle()
val consented by controller.consented.collectAsStateWithLifecycle()

if (enabled) {
    AccessAITheme { AccessAIModeScreen(onExit = { }) }
} else {
    AccessAIToggleButton(onClick = { if (consented) controller.enable() else showConsent = true })
}
// First run: AccessAIConsentScreen(onContinue = { controller.acceptConsent(); controller.enable() }, onNotNow = { … })
```

## 4. Describe your screens

```kotlin
@Composable
fun TransferScreen() {
    AccessAIScreen(ScreenDescriptor("transfer", "Transfer money", elements = listOf(
        ScreenElement("recipient", "input", "Recipient", required = true),
        ScreenElement("amount", "input", "Amount in naira", required = true),
    )))
    …
}
```

Keep your own screens accessible too: real buttons with text labels, `Modifier.semantics { heading() }` on titles,
touch targets of at least 48dp. AccessAI complements TalkBack; it does not replace it.

Element `value`s (e.g. the balance) are read aloud on the phone but never sent to AccessAI. Mark anything else
private with `sensitive = true`. For login, PIN and card screens, set `sensitive = true` on the whole descriptor and
use credential roles, which can't hold a value:

```kotlin
AccessAIScreen(ScreenDescriptor("login", "Sign in", sensitive = true, elements = listOf(
    ScreenElement("phone", "input", "Phone number"),
    ScreenElement("password", "password", "Password"),
)))
```

AccessAI can open your login screen (a `Sensitivity.NAVIGATE` action such as `open_login`) and announce it. It never
reads or fills login fields, and actions with parameters like `pin`, `password`, `otp` or `cvv` are rejected.

## What AccessAI guarantees, and what stays your job

AccessAI guarantees that only registered actions run, that ambiguous values are asked about rather than guessed,
that sensitive actions are read back, need an exact confirmation **and pass the phone's biometric / screen-lock
check** before any token exists, that each confirmation runs your handler at most once, that PINs and long numbers
never leave the phone, and that success is announced only when your handler returns `ActionResult.Success`.

Your app (and backend) remain responsible for login, authorisation, verifying the confirmation token,
idempotency, and all business rules.
