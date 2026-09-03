package com.anonchat.app.model

/** Модель одного сообщения в чате. */
data class ChatMessage(
    val id: Long = 0L,
    val name: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)
