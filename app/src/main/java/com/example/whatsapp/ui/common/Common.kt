package com.example.whatsapp.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whatsapp.ChatApplication
import com.example.whatsapp.di.AppContainer
import com.example.whatsapp.domain.model.ConnectionState
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.ui.theme.LocalChatColors
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Context.appContainer: AppContainer get() = (applicationContext as ChatApplication).container

@Composable
fun Avatar(title: String, color: Long, size: Dp = 48.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Color(color), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

@Composable
fun StatusTicks(status: MessageStatus, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        MessageStatus.FAILED -> Icon(
            Icons.Default.Warning,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(14.dp),
        )
        MessageStatus.PENDING -> Text("🕓", fontSize = 10.sp, modifier = modifier)
        MessageStatus.SENT -> Text("✓", fontSize = 12.sp, color = muted, modifier = modifier)
        MessageStatus.DELIVERED -> Text("✓✓", fontSize = 12.sp, color = muted, modifier = modifier)
        MessageStatus.READ -> Text("✓✓", fontSize = 12.sp, color = LocalChatColors.current.readTick, modifier = modifier)
    }
}

fun ConnectionState.label(): String? = when (this) {
    ConnectionState.Connected, ConnectionState.Idle -> null
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.NoNetwork -> "Waiting for network…"
    is ConnectionState.WaitingToRetry -> "Reconnecting in ${(retryInMs + 999) / 1000}s…"
}

fun formatTime(context: Context, timestamp: Long): String =
    DateFormat.getTimeFormat(context).format(Date(timestamp))

/** Chat list style: time for today, "Yesterday", otherwise a short date. */
fun formatListTimestamp(context: Context, timestamp: Long, now: Long = System.currentTimeMillis()): String =
    when (daysBetween(timestamp, now)) {
        0 -> formatTime(context, timestamp)
        1 -> "Yesterday"
        else -> DateFormat.getDateFormat(context).format(Date(timestamp))
    }

fun formatDayHeader(timestamp: Long, now: Long = System.currentTimeMillis()): String =
    when (daysBetween(timestamp, now)) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }

fun dayKey(timestamp: Long): Int {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
}

private fun daysBetween(timestamp: Long, now: Long): Int {
    fun startOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((startOfDay(now) - startOfDay(timestamp)) / 86_400_000L).toInt()
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
