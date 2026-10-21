package com.example.whatsapp.data.remote.fake

import com.example.whatsapp.data.remote.ChatTransport
import com.example.whatsapp.data.remote.ClientFrame
import com.example.whatsapp.data.remote.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Simulates a WebSocket to [FakeChatServer]: connection latency, per-frame latency, and abrupt
 * drops when the network goes away. Swap for an OkHttp WebSocket implementation in production.
 */
class FakeChatTransport(
    private val server: FakeChatServer,
    private val isNetworkOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val latencyMs: Long = 150,
) : ChatTransport {

    private val inbound = Channel<ServerFrame>(Channel.UNLIMITED)
    override val frames: Flow<ServerFrame> = inbound.receiveAsFlow()

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private var sessionId = 0L

    override suspend fun connect() {
        delay(latencyMs * 2) // TCP + TLS + upgrade handshake
        if (!isNetworkOnline()) throw IOException("Network unreachable")
        sessionId = server.attach { frame -> inbound.send(frame) }
        _isOpen.value = true
    }

    override suspend fun send(frame: ClientFrame) {
        if (!_isOpen.value || !isNetworkOnline()) {
            close()
            throw IOException("Socket closed")
        }
        delay(latencyMs)
        server.onFrame(frame)
    }

    override fun close() {
        if (!_isOpen.value) return
        _isOpen.value = false
        val id = sessionId
        scope.launch { server.detach(id) }
    }
}
