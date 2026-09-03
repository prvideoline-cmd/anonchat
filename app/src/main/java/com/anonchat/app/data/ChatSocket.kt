package com.anonchat.app.data

import com.anonchat.app.Config
import com.anonchat.app.Session
import com.anonchat.app.model.ChatMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SocketEvent {
    data class NewMessage(val message: ChatMessage) : SocketEvent()
    data class FriendAdded(val chatId: String, val friendId: String, val friendName: String) : SocketEvent()
    data class ConnectionState(val connected: Boolean, val info: String? = null) : SocketEvent()
}

/**
 * Единственное на всё приложение WebSocket-соединение с сервером.
 * Живёт внутри фонового сервиса (ConnectionService), UI-экраны только
 * подписываются на общий поток событий [events] и фильтруют по chatId.
 */
object ChatSocket {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var currentSession: Session? = null

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun connect(session: Session) {
        if (socket != null && currentSession == session) return
        disconnect()
        currentSession = session

        val requestBuilder = Request.Builder()
            .url("${Config.wsUrl}?id=${session.id}&token=${session.token}")
        if (Config.CHAT_SECRET.isNotEmpty()) {
            requestBuilder.header("X-Chat-Secret", Config.CHAT_SECRET)
        }

        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _events.tryEmit(SocketEvent.ConnectionState(true))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "message" -> _events.tryEmit(SocketEvent.NewMessage(obj.toChatMessage()))
                        "friend_added" -> {
                            val friend = obj.optJSONObject("friend")
                            _events.tryEmit(
                                SocketEvent.FriendAdded(
                                    chatId = obj.optString("chatId"),
                                    friendId = friend?.optString("id") ?: "",
                                    friendName = friend?.optString("name") ?: ""
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // игнорируем некорректный пакет
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _events.tryEmit(SocketEvent.ConnectionState(false, t.message))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _events.tryEmit(SocketEvent.ConnectionState(false, "closed"))
            }
        })
    }

    fun sendMessage(chatId: String, name: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val payload = JSONObject().apply {
            put("type", "message")
            put("chatId", chatId)
            put("name", name)
            put("text", trimmed)
        }
        socket?.send(payload.toString())
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        currentSession = null
    }
}

private fun JSONObject.toChatMessage(): ChatMessage = ChatMessage(
    id = optLong("id"),
    chatId = optString("chatId"),
    userId = optString("userId"),
    name = optString("name"),
    text = optString("text"),
    timestamp = optLong("timestamp")
)
