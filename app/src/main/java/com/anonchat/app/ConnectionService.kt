package com.anonchat.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anonchat.app.call.CallController
import com.anonchat.app.data.ChatSocket
import com.anonchat.app.data.SocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Фоновый сервис держит постоянное WebSocket-соединение с сервером, пока
 * приложение "активно" (запущено пользователем хотя бы раз в этой сессии
 * устройства), и показывает уведомления о новых сообщениях, даже когда
 * экран приложения не открыт. Работает без Google-сервисов — постоянное
 * соединение вместо push, поэтому показывает несворачиваемое уведомление
 * "АнонЧат подключен", пока сервис жив.
 */
class ConnectionService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    companion object {
        private const val NOTIF_ID_SERVICE = 1

        /** UI выставляет id открытого сейчас чата, чтобы не слать по нему уведомление. */
        @Volatile
        var openChatId: String? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())

        val session = SessionStore.load(this)
        if (session == null) {
            stopSelf()
            return
        }

        ChatSocket.connect(session)
        CallController.attach(this, session)

        scope.launch {
            ChatSocket.events.collect { event ->
                when (event) {
                    is SocketEvent.NewMessage -> {
                        val msg = event.message
                        val isMine = msg.userId == session.id
                        if (!isMine && msg.chatId != openChatId) {
                            NotificationHelper.showMessageNotification(this@ConnectionService, msg)
                        }
                    }
                    is SocketEvent.FriendAdded -> {
                        NotificationHelper.showFriendAddedNotification(this@ConnectionService, event.friendName)
                    }
                    is SocketEvent.CallSignal -> {
                        if (event.kind == "offer") {
                            NotificationHelper.showIncomingCallNotification(this@ConnectionService, event.fromName)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_SERVICE)
            .setContentTitle("TELL ME подключен")
            .setContentText("Вы получаете сообщения в реальном времени")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        ChatSocket.disconnect()
    }
}
