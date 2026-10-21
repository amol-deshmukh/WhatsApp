package com.example.whatsapp.data.sync

import com.example.whatsapp.data.network.NetworkStatus
import com.example.whatsapp.data.remote.ChatTransport
import com.example.whatsapp.data.remote.ClientFrame
import com.example.whatsapp.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns the socket lifecycle. The connection is reference counted: it is open only while something
 * holds it (the app is in the foreground, or a background worker is flushing the outbox). When
 * nobody holds it, the socket is closed and FCM push is responsible for waking the app — keeping
 * a socket open in the background drains battery and is killed by Doze anyway.
 */
class ConnectionManager(
    private val transport: ChatTransport,
    private val networkStatus: StateFlow<NetworkStatus>,
    private val lastSeq: suspend () -> Long,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
) {
    private val holders = MutableStateFlow(0)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            combine(
                holders.map { it > 0 },
                networkStatus.map { it.isOnline },
            ) { wanted, online -> wanted to online }
                .distinctUntilChanged()
                .collectLatest { (wanted, online) ->
                    when {
                        !wanted -> {
                            transport.close()
                            _state.value = ConnectionState.Idle
                        }
                        !online -> {
                            transport.close()
                            _state.value = ConnectionState.NoNetwork
                        }
                        else -> try {
                            connectLoop()
                        } finally {
                            transport.close()
                        }
                    }
                }
        }
    }

    /** Keeps the connection open until the returned handle is invoked. */
    fun acquire(): () -> Unit {
        holders.update { it + 1 }
        var released = false
        return {
            if (!released) {
                released = true
                holders.update { it - 1 }
            }
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = ConnectionState.Connecting
            try {
                transport.connect()
                transport.send(ClientFrame.Hello(lastSeq()))
                attempt = 0
                _state.value = ConnectionState.Connected
                transport.isOpen.first { open -> !open } // suspend until the socket drops
            } catch (e: IOException) {
                // Fall through to backoff.
            }
            val wait = backoff.delayFor(attempt++)
            _state.value = ConnectionState.WaitingToRetry(wait)
            delay(wait)
        }
    }
}
