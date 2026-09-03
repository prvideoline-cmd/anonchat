package com.anonchat.app.model

/** Модель одного сообщения в чате (общем или приватном). */
data class ChatMessage(
    val id: Long = 0L,
    val chatId: String = "",
    val userId: String = "",
    val name: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)
