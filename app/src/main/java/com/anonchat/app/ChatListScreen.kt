package com.anonchat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonchat.app.data.ApiClient
import com.anonchat.app.data.ChatSocket
import com.anonchat.app.data.FriendAddResult
import com.anonchat.app.data.SocketEvent
import com.anonchat.app.model.ChatSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    session: Session,
    pendingOpenChatId: String?,
    onConsumedPending: () -> Unit,
    onOpenChat: (chatId: String, title: String, friendId: String?) -> Unit
) {
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        try {
            chats = withContext(Dispatchers.IO) { ApiClient.fetchChats(session) }
            error = null
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(chats, pendingOpenChatId) {
        if (pendingOpenChatId != null) {
            val match = chats.find { it.chatId == pendingOpenChatId }
            if (match != null) {
                onOpenChat(match.chatId, match.title, match.friend?.id)
                onConsumedPending()
            }
        }
    }

    LaunchedEffect(Unit) {
        ChatSocket.events.collect { event ->
            when (event) {
                is SocketEvent.NewMessage -> {
                    val msg = event.message
                    chats = chats.map {
                        if (it.chatId == msg.chatId) it.copy(lastMessage = msg.text, lastTimestamp = msg.timestamp)
                        else it
                    }
                }
                is SocketEvent.FriendAdded -> reload()
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("АнонЧат", fontWeight = FontWeight.Bold)
                        Text("Ваш ID: ${session.id} · ${session.name}", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = Color(0xFF6C5CE7)) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить друга", tint = Color.White)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Text(
                    "Не удалось загрузить чаты: $error",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.chatId }) { chat ->
                        ChatListItem(chat = chat, onClick = { onOpenChat(chat.chatId, chat.title, chat.friend?.id) })
                        Divider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFriendDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { friendId, onResult ->
                scope.launch {
                    when (val result = withContext(Dispatchers.IO) { ApiClient.addFriend(session, friendId) }) {
                        is FriendAddResult.Success -> {
                            reload()
                            onResult(null)
                            showAddDialog = false
                        }
                        is FriendAddResult.Error -> onResult(friendlyError(result.reason))
                    }
                }
            }
        )
    }
}

private fun friendlyError(reason: String): String = when (reason) {
    "user_not_found" -> "Пользователь с таким ID не найден"
    "cannot_add_self" -> "Нельзя добавить самого себя"
    "invalid_auth" -> "Ошибка авторизации, перезайдите в приложение"
    else -> "Ошибка: $reason"
}

@Composable
private fun ChatListItem(chat: ChatSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (chat.isPinned) Color(0xFF4834D4) else Color(0xFF6C5CE7)),
            contentAlignment = Alignment.Center
        ) {
            val avatarUrl = chat.friend?.avatarUrl
            if (avatarUrl != null) {
                coil.compose.AsyncImage(
                    model = Config.mediaUrl(avatarUrl),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(chat.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(chat.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Text(
                chat.lastMessage ?: "Пока нет сообщений",
                fontSize = 13.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddFriendDialog(onDismiss: () -> Unit, onAdd: (String, (String?) -> Unit) -> Unit) {
    var id by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить друга") },
        text = {
            Column {
                Text("Введите 5-значный ID друга", fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = id,
                    onValueChange = { if (it.length <= 5 && it.all { c -> c.isDigit() }) { id = it; error = null } },
                    singleLine = true,
                    label = { Text("ID") }
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (id.length != 5) {
                        error = "ID должен состоять из 5 цифр"
                        return@TextButton
                    }
                    loading = true
                    onAdd(id) { err ->
                        loading = false
                        if (err != null) error = err
                    }
                },
                enabled = !loading
            ) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
