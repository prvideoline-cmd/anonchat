package com.anonchat.app.model

/** Превью сообщения, на которое отвечают (для функции "ответить"). */
data class ReplyPreview(
    val id: Long = 0L,
    val name: String = "",
    val text: String = ""
)

/** Модель одного сообщения в чате (общем или приватном). */
data class ChatMessage(
    val id: Long = 0L,
    val chatId: String = "",
    val userId: String = "",
    val name: String = "",
    val text: String = "",
    val timestamp: Long = 0L,
    // "text" | "sticker" | "photo" | "voice" | "video_circle"
    val type: String = "text",
    val mediaUrl: String? = null,
    val mediaDurationMs: Long = 0L,
    val replyTo: ReplyPreview? = null,
    val forwardedFromName: String? = null,
    // Аватар отправителя на момент отправки (может быть null, если аватара нет).
    val avatarUrl: String? = null
)
