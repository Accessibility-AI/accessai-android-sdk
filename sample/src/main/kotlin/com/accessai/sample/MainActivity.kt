package com.accessai.sample

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accessai.android.AccessAI
import com.accessai.compose.AccessAIConsentScreen
import com.accessai.compose.AccessAIModeScreen
import com.accessai.compose.AccessAIScreen
import com.accessai.compose.AccessAITheme
import com.accessai.compose.AccessAIToggleButton
import com.accessai.core.announce
import com.accessai.core.format.Naira
import com.accessai.core.screen.ScreenDescriptor
import com.accessai.core.screen.ScreenElement

// FragmentActivity (not ComponentActivity) so the biometric / screen-lock prompt can be shown.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccessAITheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                    SampleRoot()
                }
            }
        }
    }
}

@Composable
private fun SampleRoot() {
    val controller = AccessAI.controller
    val enabled by controller.enabled.collectAsStateWithLifecycle()
    val consented by controller.consented.collectAsStateWithLifecycle()
    val balance by SampleBank.balance.collectAsStateWithLifecycle()
    var showConsent by remember { mutableStateOf(false) }

    val turnOn = {
        controller.enable(greeting = announce { text("Welcome to the AccessAI sample. Your available balance is "); naira(SampleBank.balance.value); text(".") })
    }

    when {
        showConsent -> AccessAIConsentScreen(
            appName = "the AccessAI sample",
            onContinue = {
                controller.acceptConsent()
                showConsent = false
                turnOn()
            },
            onNotNow = { showConsent = false },
        )
        enabled -> AccessAIModeScreen(onExit = {})
        else -> HomeScreen(balance) { if (consented) turnOn() else showConsent = true }
    }
}

@Composable
private fun HomeScreen(balanceKobo: Long, onTurnOn: () -> Unit) {
    AccessAIScreen(
        ScreenDescriptor(
            screenId = "home",
            title = "Home",
            elements = listOf(
                ScreenElement("balance", "text", "Available balance", value = Naira.display(balanceKobo)),
                ScreenElement("accessai", "button", "Turn on AccessAI accessibility mode"),
            ),
        ),
    )
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("AccessAI Sample", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text("Available balance", style = MaterialTheme.typography.bodyLarge)
        Text(Naira.display(balanceKobo), style = MaterialTheme.typography.displaySmall)
        AccessAIToggleButton(onClick = onTurnOn)
        Text("Simulated funds only. No real money is involved.", style = MaterialTheme.typography.bodyMedium)
    }
}
