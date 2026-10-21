package com.example.whatsapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = WaGreenLight,
    secondary = WaGreen,
    tertiary = WaTeal,
    primaryContainer = WaGreenDark,
)

private val LightColorScheme = lightColorScheme(
    primary = WaGreen,
    secondary = WaGreenDark,
    tertiary = WaTeal,
    primaryContainer = WaGreenDark,
    onPrimaryContainer = Color.White,
)

/** Chat-specific colors that Material's color scheme has no slot for. */
@Immutable
data class ChatColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val background: Color,
    val readTick: Color,
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(BubbleOutgoingLight, BubbleIncomingLight, ChatBackgroundLight, ReadTickBlue)
}

@Composable
fun WhatsAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Brand colors instead of dynamic color: a messaging app's identity relies on them.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val chatColors = if (darkTheme) {
        ChatColors(BubbleOutgoingDark, BubbleIncomingDark, ChatBackgroundDark, ReadTickBlue)
    } else {
        ChatColors(BubbleOutgoingLight, BubbleIncomingLight, ChatBackgroundLight, ReadTickBlue)
    }

    CompositionLocalProvider(LocalChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
