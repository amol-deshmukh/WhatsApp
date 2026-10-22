package com.example.whatsapp.ui.chat

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.whatsapp.domain.model.Media
import com.example.whatsapp.domain.model.Message
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType
import com.example.whatsapp.domain.model.TransferState
import com.example.whatsapp.ui.common.StatusTicks
import com.example.whatsapp.ui.common.formatBytes
import com.example.whatsapp.ui.common.formatDuration
import com.example.whatsapp.ui.common.formatTime
import com.example.whatsapp.ui.theme.LocalChatColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

@Composable
fun MessageBubble(
    message: Message,
    progressOf: (String) -> Flow<Float?>,
    observeMessage: (String) -> Flow<Message?>,
    onRetry: (String) -> Unit,
    onDownload: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
) {
    // Room's PagingSource can miss an invalidation when a write lands during its initial load
    // (seen on-device: a finished download kept showing a spinner until the chat was reopened).
    // While a transfer is in flight, also observe this one row directly — a single-row Flow
    // registers before it queries, so it can't miss the final write. Cost: one observer per
    // *visible* unsettled media bubble (lazy list), none for settled messages.
    val inFlight = message.media?.transferState.let { it != null && it != TransferState.NONE }
    val liveFlow = remember(message.id, inFlight) { if (inFlight) observeMessage(message.id) else flowOf(null) }
    val live by liveFlow.collectAsStateWithLifecycle(initialValue = null)
    MessageBubbleContent(live ?: message, progressOf, onRetry, onDownload, onOpenMedia)
}

@Composable
private fun MessageBubbleContent(
    message: Message,
    progressOf: (String) -> Flow<Float?>,
    onRetry: (String) -> Unit,
    onDownload: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
) {
    val chatColors = LocalChatColors.current
    val context = LocalContext.current
    val outgoing = message.isOutgoing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_row")
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (outgoing) chatColors.outgoingBubble else chatColors.incomingBubble,
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (outgoing) 12.dp else 2.dp,
                bottomEnd = if (outgoing) 2.dp else 12.dp,
            ),
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(if (message.status == MessageStatus.FAILED) Modifier.clickable { onRetry(message.id) } else Modifier),
        ) {
            Column(Modifier.padding(4.dp)) {
                message.media?.let { media ->
                    MediaContent(message, media, progressOf, onRetry, onDownload, onOpenMedia)
                }
                message.text?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (message.status == MessageStatus.FAILED) {
                        Text(
                            "Tap to retry  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        formatTime(context, message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (outgoing) {
                        Spacer(Modifier.width(4.dp))
                        StatusTicks(message.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaContent(
    message: Message,
    media: Media,
    progressOf: (String) -> Flow<Float?>,
    onRetry: (String) -> Unit,
    onDownload: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
) {
    val localFile = media.localPath?.let(::File)?.takeIf { it.exists() }
    val transferring = media.transferState == TransferState.UPLOADING || media.transferState == TransferState.DOWNLOADING
    // Only subscribe to progress while a transfer is running.
    val progressFlow = remember(message.id, transferring) { if (transferring) progressOf(message.id) else flowOf(null) }
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue = null)

    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(media.aspectRatio.coerceIn(0.6f, 1.8f))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable(enabled = localFile != null) { onOpenMedia(message.id) },
        contentAlignment = Alignment.Center,
    ) {
        if (localFile != null) {
            AsyncImage(model = localFile, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (media.microThumbnail != null) {
            // Blurred micro thumbnail: the receiver sees something immediately, before any download.
            AsyncImage(
                model = media.microThumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(12.dp) else Modifier),
            )
        }

        when (media.transferState) {
            TransferState.UPLOAD_PENDING, TransferState.UPLOADING, TransferState.DOWNLOADING ->
                TransferIndicator(progress)
            TransferState.DOWNLOAD_AVAILABLE -> OverlayButton(onClick = { onDownload(message.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Download", tint = Color.White)
                    Text(formatBytes(media.sizeBytes), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            TransferState.FAILED -> OverlayButton(onClick = { onRetry(message.id) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White, modifier = Modifier.padding(10.dp))
            }
            TransferState.NONE -> if (message.type == MessageType.VIDEO && localFile != null) {
                Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }

        if (message.type == MessageType.VIDEO && media.durationMs != null) {
            Text(
                formatDuration(media.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun TransferIndicator(progress: Float?) {
    Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
        if (progress != null) {
            CircularProgressIndicator(progress = { progress }, color = Color.White, modifier = Modifier.size(40.dp))
        } else {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun OverlayButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
