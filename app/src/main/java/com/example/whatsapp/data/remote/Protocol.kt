package com.example.whatsapp.data.remote

import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType

/*
 * Wire protocol between client and chat server.
 *
 * In production these would be Protobuf messages framed over a TLS WebSocket (or MQTT, which is
 * what WhatsApp's FunXMPP / Messenger's MQTT do). They are plain Kotlin classes here because the
 * server is simulated in-process; the shapes are what matter.
 */

class MediaPayload(
    val remoteUrl: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val sizeBytes: Long,
    val microThumbnail: ByteArray?,
)

sealed interface ClientFrame {
    /** First frame on every connection: "send me everything after [lastSeq]". */
    data class Hello(val lastSeq: Long) : ClientFrame

    /** [clientMessageId] is the idempotency key: resending the same id never creates a duplicate. */
    data class Send(
        val clientMessageId: String,
        val conversationId: String,
        val type: MessageType,
        val text: String?,
        val media: MediaPayload?,
    ) : ClientFrame

    data class ReadReceipt(val conversationId: String, val messageIds: List<String>) : ClientFrame
}

sealed interface ServerFrame {
    /**
     * Events stored in the server's per-user log. [seq] is strictly increasing; the client persists
     * the highest applied seq as its sync cursor and sends it in [ClientFrame.Hello].
     */
    sealed interface Sequenced : ServerFrame {
        val seq: Long
    }

    /** Response to [ClientFrame.Send]. Not sequenced: if lost, the client resends and the server re-acks. */
    data class Ack(val clientMessageId: String, val seq: Long, val serverTimestamp: Long) : ServerFrame

    data class NewMessage(
        override val seq: Long,
        val messageId: String,
        val conversationId: String,
        val senderId: String,
        val type: MessageType,
        val text: String?,
        val serverTimestamp: Long,
        val media: MediaPayload?,
    ) : Sequenced

    data class StatusChanged(
        override val seq: Long,
        val messageId: String,
        val status: MessageStatus,
    ) : Sequenced

    /** Ephemeral: never stored or replayed. */
    data class TypingChanged(val conversationId: String, val isTyping: Boolean) : ServerFrame

    /** Marks the end of the backlog replay that follows a Hello. */
    data object SyncComplete : ServerFrame
}
