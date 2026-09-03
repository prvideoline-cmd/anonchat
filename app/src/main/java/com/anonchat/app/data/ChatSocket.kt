package com.anonchat.app.data

import com.anonchat.app.Config
import com.anonchat.app.Session
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.ReplyPreview
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
    data class ReadReceipt(val chatId: String, val byUserId: String, val upToId: Long) : SocketEvent()
    data class CallSignal(
        val kind: String, // "offer" | "answer" | "ice" | "end" | "reject" | "busy"
        val chatId: String,
        val fromUserId: String,
        val fromName: String,
        val sdp: String? = null,
        val sdpType: String? = null,
        val candidate: String? = null,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null
    ) : SocketEvent()
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
                        "read_receipt" -> {
                            _events.tryEmit(
                                SocketEvent.ReadReceipt(
                                    chatId = obj.optString("chatId"),
                                    byUserId = obj.optString("byUserId"),
                                    upToId = obj.optLong("upToId")
                                )
                            )
                        }
                        "call_signal" -> {
                            val sdpObj = obj.optJSONObject("sdp")
                            val candObj = obj.optJSONObject("candidate")
                            _events.tryEmit(
                                SocketEvent.CallSignal(
                                    kind = obj.optString("kind"),
                                    chatId = obj.optString("chatId"),
                                    fromUserId = obj.optString("fromUserId"),
                                    fromName = obj.optString("fromName"),
                                    sdp = sdpObj?.optString("sdp"),
                                    sdpType = sdpObj?.optString("type"),
                                    candidate = candObj?.optString("candidate"),
                                    sdpMid = candObj?.optString("sdpMid"),
                                    sdpMLineIndex = if (candObj != null && candObj.has("sdpMLineIndex")) candObj.optInt("sdpMLineIndex") else null
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

    /**
     * Отправить сообщение произвольного типа.
     * type: "text" | "sticker" | "photo" | "voice" | "video_circle"
     */
    fun sendMessage(
        chatId: String,
        name: String,
        text: String,
        type: String = "text",
        mediaUrl: String? = null,
        mediaDurationMs: Long = 0L,
        replyTo: ReplyPreview? = null,
        forwardedFromName: String? = null
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && mediaUrl == null) return
        val payload = JSONObject().apply {
            put("type", type)
            put("chatId", chatId)
            put("name", name)
            put("text", trimmed)
            if (mediaUrl != null) put("mediaUrl", mediaUrl)
            if (mediaDurationMs > 0) put("mediaDurationMs", mediaDurationMs)
            if (replyTo != null) {
                put("replyTo", JSONObject().apply {
                    put("id", replyTo.id)
                    put("name", replyTo.name)
                    put("text", replyTo.text)
                })
            }
            if (forwardedFromName != null) put("forwardedFromName", forwardedFromName)
        }
        socket?.send(payload.toString())
    }

    /** Сообщает серверу, что мы прочитали сообщения в приватном чате [chatId] до id [upToId] включительно. */
    fun sendRead(chatId: String, upToId: Long) {
        if (upToId <= 0) return
        val payload = JSONObject().apply {
            put("action", "read")
            put("chatId", chatId)
            put("upToId", upToId)
        }
        socket?.send(payload.toString())
    }

    /** Отправить сигнал звонка (offer/answer/ice/end/reject/busy) собеседнику приватного чата. */
    fun sendCallSignal(
        chatId: String,
        kind: String,
        sdp: String? = null,
        sdpType: String? = null,
        candidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null
    ) {
        val payload = JSONObject().apply {
            put("action", "call")
            put("kind", kind)
            put("chatId", chatId)
            if (sdp != null) {
                put("sdp", JSONObject().apply {
                    put("sdp", sdp)
                    put("type", sdpType ?: "")
                })
            }
            if (candidate != null) {
                put("candidate", JSONObject().apply {
                    put("candidate", candidate)
                    put("sdpMid", sdpMid ?: "")
                    put("sdpMLineIndex", sdpMLineIndex ?: 0)
                })
            }
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
    timestamp = optLong("timestamp"),
    type = optString("msgType", "text"),
    mediaUrl = if (has("mediaUrl") && !isNull("mediaUrl")) optString("mediaUrl") else null,
    mediaDurationMs = optLong("mediaDurationMs"),
    replyTo = optJSONObject("replyTo")?.let {
        ReplyPreview(id = it.optLong("id"), name = it.optString("name"), text = it.optString("text"))
    },
    forwardedFromName = if (has("forwardedFromName") && !isNull("forwardedFromName")) optString("forwardedFromName") else null,
    avatarUrl = if (has("avatarUrl") && !isNull("avatarUrl")) optString("avatarUrl") else null
)
