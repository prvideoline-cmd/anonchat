package com.anonchat.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anonchat.app.data.ChatEvent
import com.anonchat.app.data.ChatRepository
import com.anonchat.app.model.ChatMessage
import kotlinx.coroutines.launch

class ChatViewModel(
    val myName: String,
    private val repository: ChatRepository = ChatRepository()
) : ViewModel() {

    val messages = mutableStateListOf<ChatMessage>()
    val isConnected = mutableStateOf(false)
    val statusInfo = mutableStateOf<String?>(null)

    private val seenIds = HashSet<Long>()

    init {
        viewModelScope.launch {
            repository.connect().collect { event ->
                when (event) {
                    is ChatEvent.History -> {
                        for (msg in event.messages) {
                            if (seenIds.add(msg.id)) messages.add(msg)
                        }
                    }
                    is ChatEvent.NewMessage -> {
                        if (seenIds.add(event.message.id)) {
                            messages.add(event.message)
                        }
                    }
                    is ChatEvent.Status -> {
                        isConnected.value = event.connected
                        statusInfo.value = event.info
                    }
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        repository.sendMessage(myName, text)
    }
}
