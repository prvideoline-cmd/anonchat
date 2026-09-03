package com.anonchat.app.data

import com.anonchat.app.Config
import com.anonchat.app.model.ChatMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Слой доступа к данным: чат-сервер на вашем VPS (см. папку /server в проекте).
 *
 *  - История сообщений подгружается один раз по REST: GET {restBaseUrl}/api/messages
 *  - Новые сообщения приходят и отправляются через WebSocket: {wsUrl}
 *
 * Протокол простой JSON:
 *  клиент -> сервер:  {"type":"message","name":"...","text":"..."}
 *  сервер -> клиент:  {"type":"message","id":1,"name":"...","text":"...","timestamp":169...}
 */
sealed class ChatEvent {
    data class History(val messages: List<ChatMessage>) : ChatEvent()
    data class NewMessage(val message: ChatMessage) : ChatEvent()
    data class Status(val connected: Boolean, val info: String? = null) : ChatEvent()
}

class ChatRepository {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // держим сокет открытым для WS
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /**
     * Открывает соединение: сначала грузит историю по REST, затем держит
     * WebSocket-соединение и эмитит все новые сообщения и статусы связи.
     */
    fun connect(): Flow<ChatEvent> = callbackFlow {
        // 1) История
        try {
            val history = fetchHistory()
            trySend(ChatEvent.History(history))
        } catch (e: Exception) {
            trySend(ChatEvent.Status(connected = false, info = "Не удалось загрузить историю: ${e.message}"))
        }

        // 2) WebSocket для реального времени
        val request = Request.Builder()
            .url(Config.wsUrl)
            .apply {
                if (Config.CHAT_SECRET.isNotEmpty()) {
                    header("X-Chat-Secret", Config.CHAT_SECRET)
                }
            }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(ChatEvent.Status(connected = true))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    if (obj.optString("type") == "message") {
                        trySend(ChatEvent.NewMessage(obj.toChatMessage()))
                    }
                } catch (e: Exception) {
                    // игнорируем некорректный пакет
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(ChatEvent.Status(connected = false, info = t.message ?: "Ошибка соединения"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(ChatEvent.Status(connected = false, info = "Соединение закрыто"))
            }
        }

        socket = client.newWebSocket(request, listener)

        awaitClose {
            socket?.close(1000, "bye")
            socket = null
        }
    }

    fun sendMessage(name: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val payload = JSONObject().apply {
            put("type", "message")
            put("name", name)
            put("text", trimmed)
        }
        socket?.send(payload.toString())
    }

    @Throws(IOException::class)
    private fun fetchHistory(): List<ChatMessage> {
        val requestBuilder = Request.Builder().url("${Config.restBaseUrl}/api/messages")
        if (Config.CHAT_SECRET.isNotEmpty()) {
            requestBuilder.header("X-Chat-Secret", Config.CHAT_SECRET)
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: "[]"
            val array = JSONArray(body)
            val result = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                result.add(array.getJSONObject(i).toChatMessage())
            }
            return result
        }
    }

    private fun JSONObject.toChatMessage(): ChatMessage = ChatMessage(
        id = optLong("id"),
        name = optString("name"),
        text = optString("text"),
        timestamp = optLong("timestamp")
    )
}
