package com.example.whatsapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.whatsapp.domain.model.Conversation
import com.example.whatsapp.domain.model.Media
import com.example.whatsapp.domain.model.Message
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType
import com.example.whatsapp.domain.model.TransferState
import com.example.whatsapp.domain.model.previewText

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "avatar_color") val avatarColor: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        // Chat screen paging: WHERE conversation_id = ? ORDER BY created_at DESC
        Index(value = ["conversation_id", "created_at"]),
        // Outbox scan and unread counts.
        Index(value = ["is_outgoing", "status"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean,
    val type: MessageType,
    val text: String?,
    /**
     * Ordering key. Outgoing messages keep their local creation time so they never jump around
     * after the server ack; incoming messages use the server timestamp.
     */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "server_seq") val serverSeq: Long?,
    /** [MessageStatus.rank]; stored as an int so SQL can enforce forward-only transitions. */
    val status: Int,
    @ColumnInfo(name = "media_local_path") val mediaLocalPath: String? = null,
    @ColumnInfo(name = "media_remote_url") val mediaRemoteUrl: String? = null,
    @ColumnInfo(name = "media_mime") val mediaMime: String? = null,
    @ColumnInfo(name = "media_width") val mediaWidth: Int = 0,
    @ColumnInfo(name = "media_height") val mediaHeight: Int = 0,
    @ColumnInfo(name = "media_duration_ms") val mediaDurationMs: Long? = null,
    @ColumnInfo(name = "media_size_bytes") val mediaSizeBytes: Long = 0,
    @ColumnInfo(name = "media_micro_thumb", typeAffinity = ColumnInfo.BLOB) val mediaMicroThumb: ByteArray? = null,
    @ColumnInfo(name = "transfer_state") val transferState: TransferState = TransferState.NONE,
)

/** Single-row table holding the sync cursor: the highest server sequence number applied locally. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
)

/** Projection for the conversation list (one row per chat with its latest message + unread count). */
data class ConversationRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "avatar_color") val avatarColor: Long,
    @ColumnInfo(name = "last_type") val lastType: MessageType?,
    @ColumnInfo(name = "last_text") val lastText: String?,
    @ColumnInfo(name = "last_at") val lastAt: Long?,
    @ColumnInfo(name = "last_outgoing") val lastOutgoing: Boolean?,
    @ColumnInfo(name = "last_status") val lastStatus: Int?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    isOutgoing = isOutgoing,
    type = type,
    text = text,
    createdAt = createdAt,
    serverSeq = serverSeq,
    status = MessageStatus.fromRank(status),
    media = if (type == MessageType.TEXT) null else Media(
        localPath = mediaLocalPath,
        remoteUrl = mediaRemoteUrl,
        mimeType = mediaMime.orEmpty(),
        width = mediaWidth,
        height = mediaHeight,
        durationMs = mediaDurationMs,
        sizeBytes = mediaSizeBytes,
        microThumbnail = mediaMicroThumb,
        transferState = transferState,
    ),
)

fun ConversationRow.toDomain(): Conversation {
    val preview = lastType?.let { type ->
        Message(
            id = "", conversationId = id, senderId = "", isOutgoing = lastOutgoing == true,
            type = type, text = lastText, createdAt = lastAt ?: 0, serverSeq = null,
            status = MessageStatus.PENDING, media = null,
        ).previewText()
    }
    return Conversation(
        id = id,
        title = title,
        avatarColor = avatarColor,
        lastMessagePreview = preview,
        lastMessageAt = lastAt,
        lastMessageIsOutgoing = lastOutgoing == true,
        lastMessageStatus = lastStatus?.let(MessageStatus::fromRank),
        unreadCount = unreadCount,
    )
}
