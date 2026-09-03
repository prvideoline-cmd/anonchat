package com.anonchat.app.model

data class FriendInfo(
    val id: String,
    val name: String,
    val avatarUrl: String? = null
)

/** Строка в списке чатов: либо закреплённый общий чат, либо приватный чат с другом. */
data class ChatSummary(
    val chatId: String,
    val title: String,
    val isPinned: Boolean,
    val lastMessage: String?,
    val lastTimestamp: Long,
    val friend: FriendInfo? = null,
    // Сколько сообщений (по id) прочитал собеседник в этом приватном чате — для галочек "прочитано".
    val peerReadUpTo: Long = 0L
)
