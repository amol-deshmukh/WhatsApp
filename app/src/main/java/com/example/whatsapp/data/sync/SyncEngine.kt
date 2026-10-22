package com.example.whatsapp.data.sync

import androidx.room.withTransaction
import com.example.whatsapp.data.local.AppDatabase
import com.example.whatsapp.data.local.MessageEntity
import com.example.whatsapp.data.local.SyncStateEntity
import com.example.whatsapp.data.media.TransferScheduler
import com.example.whatsapp.data.network.NetworkStatus
import com.example.whatsapp.data.remote.ChatTransport
import com.example.whatsapp.data.remote.ClientFrame
import com.example.whatsapp.data.remote.MediaPayload
import com.example.whatsapp.data.remote.ServerFrame
import com.example.whatsapp.domain.model.ConnectionState
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType
import com.example.whatsapp.domain.model.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import androidx.tracing.Trace
import com.example.whatsapp.security.WireValidation
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * The only component that turns network events into database writes, and database state (the
 * outbox) into network sends. The UI never talks to the network: it reads Room and writes intents
 * (insert a PENDING message) to Room.
 *
 * Delivery semantics: the client resends any un-acked message on every reconnect (at-least-once),
 * the server dedupes by client message id, and the client dedupes incoming messages by id
 * (INSERT OR IGNORE). Together that gives effectively-once delivery.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val transport: ChatTransport,
    private val connection: ConnectionManager,
    private val transfers: TransferScheduler,
    private val networkStatus: StateFlow<NetworkStatus>,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "SyncEngine"
    }

    private val messageDao = db.messageDao()
    private val syncStateDao = db.syncStateDao()

    private val _typing = MutableStateFlow<Set<String>>(emptySet())
    /** Conversation ids where the other party is currently typing. In-memory only. */
    val typing: StateFlow<Set<String>> = _typing.asStateFlow()

    fun start() {
        scope.launch {
            transport.frames.collect { frame ->
                // One malformed or hostile frame must not kill the sync loop for the session.
                try {
                    traceAsync("SyncEngine.applyFrame") { apply(frame) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Dropping frame ${frame::class.simpleName}: ${e.javaClass.simpleName}")
                }
            }
        }
        scope.launch { drainOutboxWhileConnected() }
    }

    suspend fun sendReadReceipt(conversationId: String, messageIds: List<String>) {
        if (messageIds.isEmpty() || connection.state.value != ConnectionState.Connected) return
        // Best effort. Production: persist receipts in an outbox table like messages.
        runCatching { transport.send(ClientFrame.ReadReceipt(conversationId, messageIds)) }
    }

    private suspend fun drainOutboxWhileConnected() {
        connection.state.collectLatest { state ->
            if (state != ConnectionState.Connected) return@collectLatest
            // Fresh connection: anything we sent before without an ack is resent. Safe because sends are idempotent.
            val inFlight = HashSet<String>()
            messageDao.observeOutbox().collect { pending ->
                for (message in pending) {
                    if (!inFlight.add(message.id)) continue
                    try {
                        transport.send(message.toSendFrame())
                    } catch (e: IOException) {
                        inFlight.remove(message.id)
                        return@collect // socket died; ConnectionManager will reconnect and we restart
                    }
                }
            }
        }
    }

    /**
     * Times one frame end-to-end (validation + DB transaction). A suspending, cookie-based async
     * section, because the DB write may resume on another thread.
     */
    private inline fun traceAsync(name: String, block: () -> Unit) {
        val cookie = nextTraceCookie++
        Trace.beginAsyncSection(name, cookie)
        try {
            block()
        } finally {
            Trace.endAsyncSection(name, cookie)
        }
    }

    private var nextTraceCookie = 0

    private suspend fun apply(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Ack -> messageDao.markAcked(frame.clientMessageId, frame.seq)

            is ServerFrame.NewMessage -> {
                WireValidation.rejectReason(frame)?.let { reason ->
                    // Still advance the cursor: otherwise the server would replay the poison event forever.
                    db.withTransaction { advanceCursor(frame.seq) }
                    Log.w(TAG, "Rejected message: $reason") // no ids/content in logs
                    return
                }
                val inserted = db.withTransaction {
                    val rowId = messageDao.insert(frame.toEntity())
                    advanceCursor(frame.seq)
                    rowId != -1L
                }
                if (inserted && frame.media != null && shouldAutoDownload(frame.type)) {
                    transfers.enqueueDownload(frame.messageId)
                }
                _typing.update { it - frame.conversationId }
            }

            is ServerFrame.StatusChanged -> if (WireValidation.isMessageId(frame.messageId)) db.withTransaction {
                messageDao.advanceStatus(frame.messageId, frame.status.rank)
                advanceCursor(frame.seq)
            } else {
                db.withTransaction { advanceCursor(frame.seq) }
            }

            is ServerFrame.TypingChanged -> _typing.update {
                if (frame.isTyping) it + frame.conversationId else it - frame.conversationId
            }

            ServerFrame.SyncComplete -> Unit
        }
    }

    /** Cursor moves in the same transaction as the write it covers, so a crash can't skip an event. */
    private suspend fun advanceCursor(seq: Long) {
        val current = syncStateDao.lastSeq() ?: 0
        if (seq > current) syncStateDao.upsert(SyncStateEntity(lastSeq = seq))
    }

    /** WhatsApp's default: photos auto-download on any network; videos only on unmetered (Wi-Fi). */
    private fun shouldAutoDownload(type: MessageType): Boolean = when (type) {
        MessageType.IMAGE -> true
        MessageType.VIDEO -> networkStatus.value.isUnmetered
        MessageType.TEXT -> false
    }

    private fun MessageEntity.toSendFrame() = ClientFrame.Send(
        clientMessageId = id,
        conversationId = conversationId,
        type = type,
        text = text,
        media = mediaRemoteUrl?.let { url ->
            MediaPayload(
                remoteUrl = url,
                mimeType = mediaMime.orEmpty(),
                width = mediaWidth,
                height = mediaHeight,
                durationMs = mediaDurationMs,
                sizeBytes = mediaSizeBytes,
                microThumbnail = mediaMicroThumb,
                sha256 = mediaSha256,
            )
        },
    )

    private fun ServerFrame.NewMessage.toEntity() = MessageEntity(
        id = messageId,
        conversationId = conversationId,
        senderId = senderId,
        isOutgoing = false,
        type = type,
        text = text,
        createdAt = serverTimestamp,
        serverSeq = seq,
        status = MessageStatus.DELIVERED.rank,
        mediaRemoteUrl = media?.remoteUrl,
        mediaMime = media?.mimeType,
        mediaWidth = media?.width ?: 0,
        mediaHeight = media?.height ?: 0,
        mediaDurationMs = media?.durationMs,
        mediaSizeBytes = media?.sizeBytes ?: 0,
        mediaMicroThumb = media?.microThumbnail,
        mediaSha256 = media?.sha256,
        transferState = if (media != null) TransferState.DOWNLOAD_AVAILABLE else TransferState.NONE,
    )
}
