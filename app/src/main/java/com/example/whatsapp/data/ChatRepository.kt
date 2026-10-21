package com.example.whatsapp.data

import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.whatsapp.data.local.AppDatabase
import com.example.whatsapp.data.local.ConversationEntity
import com.example.whatsapp.data.local.MessageEntity
import com.example.whatsapp.data.local.toDomain
import com.example.whatsapp.data.media.MediaProcessor
import com.example.whatsapp.data.media.TransferProgressTracker
import com.example.whatsapp.data.media.TransferScheduler
import com.example.whatsapp.data.sync.SyncEngine
import com.example.whatsapp.domain.model.Conversation
import com.example.whatsapp.domain.model.Message
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType
import com.example.whatsapp.domain.model.TransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * UI-facing API. Every write goes to Room first and returns immediately; the SyncEngine and
 * WorkManager move data to the network asynchronously. This is what makes the UI instant and
 * fully functional offline.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val syncEngine: SyncEngine,
    private val mediaProcessor: MediaProcessor,
    private val transfers: TransferScheduler,
    private val progress: TransferProgressTracker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val messageDao = db.messageDao()
    private val conversationDao = db.conversationDao()

    fun conversations(): Flow<List<Conversation>> =
        conversationDao.observeConversations().map { rows -> rows.map { it.toDomain() } }

    fun conversationTitle(id: String): Flow<String> =
        conversationDao.observe(id).map { it?.title.orEmpty() }.distinctUntilChanged()

    fun messages(conversationId: String): Flow<PagingData<Message>> =
        Pager(
            // Page size ~2-3 screens; prefetch keeps scroll-back smooth without loading the full history.
            config = PagingConfig(pageSize = 50, prefetchDistance = 20, enablePlaceholders = false),
            pagingSourceFactory = { messageDao.pagingSource(conversationId) },
        ).flow.map { page -> page.map { it.toDomain() } }

    fun message(id: String): Flow<Message?> = messageDao.observe(id).map { it?.toDomain() }

    fun unreadCount(conversationId: String): Flow<Int> = messageDao.observeUnreadCount(conversationId)

    fun isTyping(conversationId: String): Flow<Boolean> =
        syncEngine.typing.map { conversationId in it }.distinctUntilChanged()

    fun transferProgress(messageId: String): Flow<Float?> = progress.observe(messageId)

    suspend fun sendText(conversationId: String, text: String) {
        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = ME,
                isOutgoing = true,
                type = MessageType.TEXT,
                text = text,
                createdAt = clock(),
                serverSeq = null,
                status = MessageStatus.PENDING.rank,
            )
        )
        // Nothing else to do: the SyncEngine observes the outbox query and sends when connected.
    }

    /** Compresses/copies the picked media, records the message, then hands off to the upload worker. */
    suspend fun sendMedia(conversationId: String, uri: Uri, caption: String?) {
        val id = UUID.randomUUID().toString()
        val prepared = mediaProcessor.prepare(uri, id)
        messageDao.insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                senderId = ME,
                isOutgoing = true,
                type = prepared.type,
                text = caption?.takeIf { it.isNotBlank() },
                createdAt = clock(),
                serverSeq = null,
                status = MessageStatus.PENDING.rank,
                mediaLocalPath = prepared.file.absolutePath,
                mediaMime = prepared.mimeType,
                mediaWidth = prepared.width,
                mediaHeight = prepared.height,
                mediaDurationMs = prepared.durationMs,
                mediaSizeBytes = prepared.file.length(),
                mediaMicroThumb = prepared.microThumbnail,
                mediaSha256 = prepared.sha256,
                transferState = TransferState.UPLOAD_PENDING,
            )
        )
        transfers.enqueueUpload(id)
    }

    suspend fun retry(messageId: String) {
        val message = messageDao.get(messageId) ?: return
        if (!message.isOutgoing) {
            // Incoming media that failed (e.g. integrity check): fetch it again.
            if (message.transferState == TransferState.FAILED) {
                messageDao.setTransferState(messageId, TransferState.DOWNLOAD_AVAILABLE)
                transfers.enqueueDownload(messageId)
            }
            return
        }
        messageDao.resetForRetry(messageId)
        if (message.type != MessageType.TEXT && message.mediaRemoteUrl == null) transfers.enqueueUpload(messageId)
    }

    fun download(messageId: String) = transfers.enqueueDownload(messageId)

    suspend fun markConversationRead(conversationId: String) {
        val ids = messageDao.consumeUnread(conversationId)
        syncEngine.sendReadReceipt(conversationId, ids)
    }

    suspend fun seedIfEmpty() {
        if (conversationDao.count() > 0) return
        val now = clock()
        conversationDao.insertAll(
            listOf(
                ConversationEntity("alice", "Alice", 0xFFE57373, now),
                ConversationEntity("bob", "Bob (Eng Manager)", 0xFF64B5F6, now - 1),
                ConversationEntity("design-review", "Design Review Bot", 0xFF81C784, now - 2),
            )
        )
    }

    companion object {
        const val ME = "me"
    }
}
