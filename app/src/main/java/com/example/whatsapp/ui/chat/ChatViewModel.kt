package com.example.whatsapp.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.example.whatsapp.data.ChatRepository
import com.example.whatsapp.data.media.MediaTooLargeException
import com.example.whatsapp.domain.model.ConnectionState
import com.example.whatsapp.domain.model.Message
import com.example.whatsapp.ui.common.dayKey
import com.example.whatsapp.ui.common.formatDayHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface ChatItem {
    val key: String

    data class MessageItem(val message: Message) : ChatItem {
        override val key get() = message.id
    }

    data class DayHeader(val label: String, val dayKey: Int) : ChatItem {
        override val key get() = "day-$dayKey"
    }
}

data class ChatHeaderState(
    val title: String = "",
    val subtitle: String? = null,
    val processingMediaCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationId: String,
    private val repository: ChatRepository,
    connectionState: Flow<ConnectionState>,
    /** Media prep must not be cancelled if the user leaves the screen right after picking. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val processingCount = MutableStateFlow(0)
    private val isVisible = MutableStateFlow(false)

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors: Flow<String> = _errors.receiveAsFlow()

    val header: StateFlow<ChatHeaderState> = combine(
        repository.conversationTitle(conversationId),
        repository.isTyping(conversationId),
        connectionState,
        processingCount,
    ) { title, typing, connection, processing ->
        val subtitle = when {
            connection != ConnectionState.Connected -> "waiting for connection…"
            typing -> "typing…"
            else -> "online"
        }
        ChatHeaderState(title, subtitle, processing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatHeaderState())

    /**
     * Newest first (the list uses reverseLayout), with day headers inserted between days.
     * cachedIn() keeps loaded pages across configuration changes.
     */
    val messages: Flow<PagingData<ChatItem>> = repository.messages(conversationId)
        .map { page ->
            page.map { ChatItem.MessageItem(it) }
                .insertSeparators { newer: ChatItem.MessageItem?, older: ChatItem.MessageItem? ->
                    when {
                        newer == null -> null
                        older == null || dayKey(older.message.createdAt) != dayKey(newer.message.createdAt) ->
                            ChatItem.DayHeader(formatDayHeader(newer.message.createdAt), dayKey(newer.message.createdAt))
                        else -> null
                    }
                }
        }
        .cachedIn(viewModelScope)

    init {
        // Read receipts only while the chat is actually on screen (not merely in the back stack).
        viewModelScope.launch {
            isVisible
                .flatMapLatest { visible -> if (visible) repository.unreadCount(conversationId) else emptyFlow() }
                .filter { it > 0 }
                .collect { repository.markConversationRead(conversationId) }
        }
    }

    fun setVisible(visible: Boolean) {
        isVisible.value = visible
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.sendText(conversationId, trimmed) }
    }

    fun sendMedia(uri: Uri) {
        processingCount.update { it + 1 }
        appScope.launch {
            try {
                repository.sendMedia(conversationId, uri, caption = null)
            } catch (e: MediaTooLargeException) {
                _errors.trySend("Video too large (max ${e.limitBytes / (1024 * 1024)} MB)")
            } catch (e: IOException) {
                _errors.trySend("Couldn't attach media: ${e.message}")
            } finally {
                processingCount.update { it - 1 }
            }
        }
    }

    fun retry(messageId: String) {
        viewModelScope.launch { repository.retry(messageId) }
    }

    fun download(messageId: String) = repository.download(messageId)

    fun transferProgress(messageId: String): Flow<Float?> = repository.transferProgress(messageId)
}
