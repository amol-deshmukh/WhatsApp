package com.example.whatsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                AppNavHost()
            }
        }
    }
}
