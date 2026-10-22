package com.example.whatsapp.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.example.whatsapp.domain.model.Message
import com.example.whatsapp.ui.common.Avatar
import com.example.whatsapp.ui.common.appContainer
import com.example.whatsapp.ui.theme.LocalChatColors

@Composable
fun ChatRoute(conversationId: String, onBack: () -> Unit, onOpenMedia: (String) -> Unit) {
    val container = LocalContext.current.appContainer
    val viewModel = viewModel(key = conversationId) {
        ChatViewModel(conversationId, container.chatRepository, container.connectionManager.state, container.appScope)
    }
    LifecycleStartEffect(viewModel) {
        viewModel.setVisible(true)
        onStopOrDispose { viewModel.setVisible(false) }
    }

    val header by viewModel.header.collectAsStateWithLifecycle()
    val messages = viewModel.messages.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbarHostState.showSnackbar(it) } }

    // System Photo Picker: no storage permission needed, user grants access per item.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::sendMedia)
    }

    ChatScreen(
        header = header,
        messages = messages,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSend = viewModel::sendText,
        onAttach = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        bubble = { message ->
            MessageBubble(
                message = message,
                progressOf = viewModel::transferProgress,
                observeMessage = viewModel::observeMessage,
                onRetry = viewModel::retry,
                onDownload = viewModel::download,
                onOpenMedia = onOpenMedia,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    header: ChatHeaderState,
    messages: LazyPagingItems<ChatItem>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    bubble: @Composable (Message) -> Unit,
) {
    val listState = rememberLazyListState()
    val newestKey = if (messages.itemCount > 0) messages.peek(0)?.key else null
    // Follow new messages only if the user is already at the bottom; don't yank them while reading history.
    LaunchedEffect(newestKey) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(header.title, 0xFF9E9E9E, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(header.title, style = MaterialTheme.typography.titleMedium)
                            header.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                if (header.processingMediaCount > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                Composer(onSend = onSend, onAttach = onAttach)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = LocalChatColors.current.background,
    ) { padding ->
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("messages_list"),
        ) {
            items(
                count = messages.itemCount,
                key = messages.itemKey { it.key },
                contentType = messages.itemContentType { it::class },
            ) { index ->
                when (val item = messages[index]) {
                    is ChatItem.MessageItem -> bubble(item.message)
                    is ChatItem.DayHeader -> DayHeader(item.label)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit, onAttach: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message") },
            leadingIcon = {
                IconButton(onClick = onAttach) { Icon(Icons.Default.Add, contentDescription = "Attach photo or video") }
            },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).testTag("composer_input"),
        )
        Spacer(Modifier.width(6.dp))
        FilledIconButton(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = text.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier.padding(bottom = 4.dp).testTag("send_button"),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
