package com.example.whatsapp.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whatsapp.data.ChatRepository
import com.example.whatsapp.data.network.NetworkMonitor
import com.example.whatsapp.domain.model.ConnectionState
import com.example.whatsapp.domain.model.Conversation
import com.example.whatsapp.security.SecuritySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatListUiState(
    val conversations: List<Conversation> = emptyList(),
    val connection: ConnectionState = ConnectionState.Idle,
    val simulateOffline: Boolean = false,
    val screenSecurity: Boolean = false,
    val isLoading: Boolean = true,
)

class ChatListViewModel(
    repository: ChatRepository,
    connectionState: Flow<ConnectionState>,
    private val networkMonitor: NetworkMonitor,
    private val securitySettings: SecuritySettings,
) : ViewModel() {

    val uiState: StateFlow<ChatListUiState> = combine(
        repository.conversations(),
        connectionState,
        networkMonitor.simulateOffline,
        securitySettings.screenSecurity,
    ) { conversations, connection, simulateOffline, screenSecurity ->
        ChatListUiState(conversations, connection, simulateOffline, screenSecurity, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    fun setSimulateOffline(offline: Boolean) = networkMonitor.setSimulateOffline(offline)

    fun setScreenSecurity(enabled: Boolean) = securitySettings.setScreenSecurity(enabled)
}
