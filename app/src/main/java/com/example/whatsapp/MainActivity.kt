package com.example.whatsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import com.example.whatsapp.security.applyScreenSecurity
import com.example.whatsapp.ui.AppNavHost
import com.example.whatsapp.ui.common.appContainer
import com.example.whatsapp.ui.theme.WhatsAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = appContainer.securitySettings
        applyScreenSecurity(settings.screenSecurity.value) // before first frame, so nothing leaks
        lifecycleScope.launch { settings.screenSecurity.collect(::applyScreenSecurity) }
        setContent {
            WhatsAppTheme {
                // Exposes Compose testTags as view resource ids so UiAutomator (macrobenchmarks,
                // screenshot script) can find "conversation_row", "messages_list", etc.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    AppNavHost()
                }
            }
        }
    }
}
