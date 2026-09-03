package com.anonchat.app.data

import com.anonchat.app.Config
import com.anonchat.app.Session
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.ChatSummary
import com.anonchat.app.model.FriendInfo
import com.anonchat.app.model.ReplyPreview
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class FriendAddResult {
    data class Success(val chatId: String, val friend: FriendInfo) : FriendAddResult()
    data class Error(val reason: String) : FriendAddResult()
}

/** REST-клиент к серверу на вашем VPS: регистрация, список чатов, история, друзья. */
object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun applySecret(builder: Request.Builder) {
        if (Config.CHAT_SECRET.isNotEmpty()) builder.header("X-Chat-Secret", Config.CHAT_SECRET)
    }

    @Throws(IOException::class)
    fun register(name: String): Session {
        val body = JSONObject().apply { put("name", name) }.toString().toRequestBody(JSON)
        val builder = Request.Builder().url("${Config.restBaseUrl}/api/register").post(body)
        applySecret(builder)
        client.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $bodyStr")
            val obj = JSONObject(bodyStr)
            return Session(obj.getString("id"), obj.getString("name"), obj.getString("token"))
        }
    }

    @Throws(IOException::class)
    fun fetchChats(session: Session): List<ChatSummary> {
        val builder = Request.Builder()
            .url("${Config.restBaseUrl}/api/chats?id=${session.id}&token=${session.token}")
        applySecret(builder)
        client.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $bodyStr")
            val obj = JSONObject(bodyStr)
            val result = ArrayList<ChatSummary>()

            val general = obj.getJSONObject("general")
            result.add(
                ChatSummary(
                    chatId = general.getString("chatId"),
                    title = general.optString("name", "Общий чат"),
                    isPinned = true,
                    lastMessage = general.optStringOrNull("lastMessage"),
                    lastTimestamp = general.optLong("lastTimestamp")
                )
            )

            val friendsArr = obj.optJSONArray("friends") ?: JSONArray()
            for (i in 0 until friendsArr.length()) {
                val item = friendsArr.getJSONObject(i)
                val friendObj = item.getJSONObject("friend")
                result.add(
                    ChatSummary(
                        chatId = item.getString("chatId"),
                        title = friendObj.optString("name", "Друг"),
                        isPinned = false,
                        lastMessage = item.optStringOrNull("lastMessage"),
                        lastTimestamp = item.optLong("lastTimestamp"),
                        friend = FriendInfo(friendObj.optString("id"), friendObj.optString("name"))
                    )
                )
            }
            return result
        }
    }

    @Throws(IOException::class)
    fun fetchMessages(session: Session, chatId: String): List<ChatMessage> {
        val builder = Request.Builder()
            .url("${Config.restBaseUrl}/api/messages?chatId=$chatId&id=${session.id}&token=${session.token}")
        applySecret(builder)
        client.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: "[]"
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $bodyStr")
            val arr = JSONArray(bodyStr)
            val result = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val replyObj = o.optJSONObject("replyTo")
                result.add(
                    ChatMessage(
                        id = o.optLong("id"),
                        chatId = chatId,
                        userId = o.optString("userId"),
                        name = o.optString("name"),
                        text = o.optString("text"),
                        timestamp = o.optLong("timestamp"),
                        type = o.optString("type", "text"),
                        mediaUrl = o.optStringOrNull("mediaUrl"),
                        mediaDurationMs = o.optLong("mediaDurationMs"),
                        replyTo = replyObj?.let {
                            ReplyPreview(id = it.optLong("id"), name = it.optString("name"), text = it.optString("text"))
                        },
                        forwardedFromName = o.optStringOrNull("forwardedFromName")
                    )
                )
            }
            return result
        }
    }

    /** Загружает медиафайл (фото/голосовое/видео-кружок) на сервер и возвращает относительный URL вида /media/... */
    @Throws(IOException::class)
    fun uploadMedia(session: Session, file: File, mimeType: String): String {
        val mediaType = mimeType.toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .build()
        val builder = Request.Builder()
            .url("${Config.restBaseUrl}/api/upload?id=${session.id}&token=${session.token}")
            .post(body)
        applySecret(builder)
        client.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $bodyStr")
            val obj = JSONObject(bodyStr)
            return obj.getString("url")
        }
    }

    fun addFriend(session: Session, friendId: String): FriendAddResult {
        return try {
            val body = JSONObject().apply {
                put("id", session.id)
                put("token", session.token)
                put("friendId", friendId)
            }.toString().toRequestBody(JSON)
            val builder = Request.Builder().url("${Config.restBaseUrl}/api/friends/add").post(body)
            applySecret(builder)
            client.newCall(builder.build()).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                if (!resp.isSuccessful) {
                    return FriendAddResult.Error(obj.optString("error", "unknown_error"))
                }
                val friendObj = obj.getJSONObject("friend")
                FriendAddResult.Success(
                    chatId = obj.getString("chatId"),
                    friend = FriendInfo(friendObj.getString("id"), friendObj.getString("name"))
                )
            }
        } catch (e: IOException) {
            FriendAddResult.Error(e.message ?: "network_error")
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
