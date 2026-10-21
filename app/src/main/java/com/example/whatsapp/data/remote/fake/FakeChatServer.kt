package com.example.whatsapp.data.remote.fake

import com.example.whatsapp.data.remote.ClientFrame
import com.example.whatsapp.data.remote.MediaPayload
import com.example.whatsapp.data.remote.ServerFrame
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

/**
 * In-process stand-in for the chat backend so the app runs without infrastructure.
 *
 * It implements the server-side behaviours the client design depends on:
 *  - idempotent sends keyed by client message id,
 *  - a per-user ordered event log with sequence numbers and replay-from-cursor on reconnect,
 *  - delivery/read receipts and typing indicators from the simulated contacts,
 *  - events produced while the client is offline are buffered and replayed later.
 */
class FakeChatServer(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val replyDelayMs: Long = 2_500,
) {
    private val mutex = Mutex()

    // Seeded from wall-clock so the persisted client cursor stays valid across process restarts
    // (this fake server's state lives in memory and is lost when the process dies).
    private var nextSeq = clock() * 1_000
    private val log = ArrayDeque<ServerFrame.Sequenced>()
    private val acceptedSeqByClientId = HashMap<String, Long>()
    private var session: (suspend (ServerFrame) -> Unit)? = null
    private var sessionId = 0L

    /** Returns a session id so a late [detach] from an old connection cannot kill a newer one. */
    suspend fun attach(sink: suspend (ServerFrame) -> Unit): Long = mutex.withLock {
        session = sink
        ++sessionId
    }

    suspend fun detach(id: Long) = mutex.withLock {
        if (id == sessionId) session = null
    }

    suspend fun onFrame(frame: ClientFrame) {
        when (frame) {
            is ClientFrame.Hello -> replay(frame.lastSeq)
            is ClientFrame.Send -> accept(frame)
            is ClientFrame.ReadReceipt -> Unit // Would be fanned out to the sender's devices.
        }
    }

    private suspend fun replay(afterSeq: Long) = mutex.withLock {
        val sink = session ?: return@withLock
        // A real server would answer "cursor too old, do a full resync" if afterSeq < oldest retained seq.
        log.filter { it.seq > afterSeq }.forEach { sink(it) }
        sink(ServerFrame.SyncComplete)
    }

    private suspend fun accept(frame: ClientFrame.Send) {
        val (seq, isDuplicate) = mutex.withLock {
            val existing = acceptedSeqByClientId[frame.clientMessageId]
            if (existing != null) {
                existing to true
            } else {
                val seq = nextSeq++
                acceptedSeqByClientId[frame.clientMessageId] = seq
                seq to false
            }
        }
        pushEphemeral(ServerFrame.Ack(frame.clientMessageId, seq, clock()))
        if (isDuplicate) return

        scope.launch { simulateRecipient(frame) }
    }

    private suspend fun simulateRecipient(frame: ClientFrame.Send) {
        delay(300)
        appendToLog { seq -> ServerFrame.StatusChanged(seq, frame.clientMessageId, MessageStatus.DELIVERED) }
        delay(700)
        appendToLog { seq -> ServerFrame.StatusChanged(seq, frame.clientMessageId, MessageStatus.READ) }
        pushEphemeral(ServerFrame.TypingChanged(frame.conversationId, true))
        delay(replyDelayMs)
        pushEphemeral(ServerFrame.TypingChanged(frame.conversationId, false))
        appendToLog { seq -> reply(seq, frame) }
    }

    private fun reply(seq: Long, frame: ClientFrame.Send): ServerFrame.NewMessage {
        val echoMedia: MediaPayload? = frame.media
        val text = when (frame.type) {
            MessageType.TEXT -> CANNED_REPLIES[random.nextInt(CANNED_REPLIES.size)]
            MessageType.IMAGE -> "Nice photo! Sending it back 🔁"
            MessageType.VIDEO -> "Cool video! Here it is again 🔁"
        }
        return ServerFrame.NewMessage(
            seq = seq,
            messageId = UUID.randomUUID().toString(),
            conversationId = frame.conversationId,
            senderId = frame.conversationId,
            type = frame.type,
            text = text,
            serverTimestamp = clock(),
            media = echoMedia,
        )
    }

    private suspend fun appendToLog(build: (Long) -> ServerFrame.Sequenced) = mutex.withLock {
        val event = build(nextSeq++)
        log.addLast(event)
        if (log.size > MAX_LOG_SIZE) log.removeFirst()
        session?.invoke(event)
    }

    private suspend fun pushEphemeral(frame: ServerFrame) = mutex.withLock { session?.invoke(frame) }

    private companion object {
        const val MAX_LOG_SIZE = 5_000
        val CANNED_REPLIES = listOf(
            "Sounds good 👍",
            "Haha, true 😂",
            "Let me check and get back to you.",
            "On my way!",
            "Can we talk later? In a meeting.",
            "Absolutely, let's do it.",
            "Did you see the design doc?",
        )
    }
}
