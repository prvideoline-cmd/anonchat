package com.anonchat.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anonchat.app.model.ChatMessage
import kotlin.random.Random

object NotificationHelper {
    const val CHANNEL_ID_SERVICE = "anonchat_service"
    const val CHANNEL_ID_MESSAGES = "anonchat_messages"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    "Соединение с чатом",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_MESSAGES,
                    "Новые сообщения",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    fun showMessageNotification(context: Context, msg: ChatMessage) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openChatId", msg.chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            msg.chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setContentTitle(msg.name)
            .setContentText(msg.text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifySafely(context, notification)
    }

    fun showIncomingCallNotification(context: Context, callerName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            "call".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setContentTitle("Входящий звонок")
            .setContentText("$callerName вам звонит — откройте приложение, чтобы ответить")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        notifySafely(context, notification)
    }

    fun showFriendAddedNotification(context: Context, friendName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setContentTitle("Новый друг")
            .setContentText("$friendName добавил(а) вас в друзья")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notifySafely(context, notification)
    }

    private fun notifySafely(context: Context, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(Random.nextInt(1000, 999999), notification)
        } catch (e: SecurityException) {
            // пользователь не дал разрешение на уведомления — молча пропускаем
        }
    }
}
