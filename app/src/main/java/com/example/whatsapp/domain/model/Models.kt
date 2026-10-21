package com.example.whatsapp.domain.model

/**
 * Delivery status of a message. The [rank] is persisted and only ever moves forward
 * (PENDING -> SENT -> DELIVERED -> READ), so out-of-order or duplicated server events
 * can never move a message "backwards". FAILED is terminal until the user retries.
 */
enum class MessageStatus(val rank: Int) {
    FAILED(-1),
    PENDING(0),
    SENT(1),
    DELIVERED(2),
    READ(3);

    fun canTransitionTo(next: MessageStatus): Boolean = when {
        this == FAILED -> next == PENDING // manual retry
        next == FAILED -> this == PENDING // only unsent messages can fail
        else -> next.rank > rank
    }

    companion object {
        fun fromRank(rank: Int): MessageStatus = entries.first { it.rank == rank }
    }
}

enum class MessageType { TEXT, IMAGE, VIDEO }

/** Local lifecycle of a media attachment's bytes, independent of the message's delivery status. */
enum class TransferState {
    NONE,           // text message, or media fully available locally
    UPLOAD_PENDING, // waiting for upload worker / network
    UPLOADING,
    DOWNLOAD_AVAILABLE, // incoming media not yet fetched (user or auto-download policy decides)
    DOWNLOADING,
    FAILED,
}

data class Media(
    val localPath: String?,
    val remoteUrl: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val sizeBytes: Long,
    /** Tiny (~64px) JPEG sent inline with the message so the bubble renders before download. */
    val microThumbnail: ByteArray?,
    val transferState: TransferState,
) {
    val aspectRatio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f

    // ByteArray uses identity equality; compare content so Compose/Paging diffing stays correct.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Media) return false
        return localPath == other.localPath && remoteUrl == other.remoteUrl &&
            mimeType == other.mimeType && width == other.width && height == other.height &&
            durationMs == other.durationMs && sizeBytes == other.sizeBytes &&
            transferState == other.transferState &&
            microThumbnail.contentEquals(other.microThumbnail)
    }

    override fun hashCode(): Int {
        var result = localPath.hashCode()
        result = 31 * result + remoteUrl.hashCode()
        result = 31 * result + transferState.hashCode()
        result = 31 * result + (microThumbnail?.contentHashCode() ?: 0)
        return result
    }
}

data class Message(
    /** Client-generated UUID. Doubles as the idempotency key for retries. */
    val id: String,
    val conversationId: String,
    val senderId: String,
    val isOutgoing: Boolean,
    val type: MessageType,
    val text: String?,
    val createdAt: Long,
    val serverSeq: Long?,
    val status: MessageStatus,
    val media: Media?,
)

data class Conversation(
    val id: String,
    val title: String,
    val avatarColor: Long,
    val lastMessagePreview: String?,
    val lastMessageAt: Long?,
    val lastMessageIsOutgoing: Boolean,
    val lastMessageStatus: MessageStatus?,
    val unreadCount: Int,
)

sealed interface ConnectionState {
    data object Connected : ConnectionState
    data object Connecting : ConnectionState
    data class WaitingToRetry(val retryInMs: Long) : ConnectionState
    data object NoNetwork : ConnectionState
    /** App is backgrounded: socket intentionally closed, push takes over. */
    data object Idle : ConnectionState
}

fun Message.previewText(): String = when (type) {
    MessageType.TEXT -> text.orEmpty()
    MessageType.IMAGE -> "📷 " + (text?.takeIf { it.isNotBlank() } ?: "Photo")
    MessageType.VIDEO -> "🎥 " + (text?.takeIf { it.isNotBlank() } ?: "Video")
}
