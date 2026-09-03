package com.anonchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* результат нам не важен */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val openChatId = intent?.getStringExtra("openChatId")
        setContent {
            AnonChatTheme {
                AppRoot(initialOpenChatId = openChatId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val openChatId = intent.getStringExtra("openChatId")
        setContent {
            AnonChatTheme {
                AppRoot(initialOpenChatId = openChatId)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun AnonChatTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF6C5CE7),
        secondary = Color(0xFF4834D4),
        background = Color(0xFFF5F3FF)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private sealed class Screen {
    object NameEntry : Screen()
    object ChatList : Screen()
    data class Chat(val chatId: String, val title: String) : Screen()
}

@Composable
private fun AppRoot(initialOpenChatId: String?) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(SessionStore.load(context)) }
    var screen by remember { mutableStateOf<Screen>(if (session == null) Screen.NameEntry else Screen.ChatList) }
    var pendingChatId by remember { mutableStateOf(initialOpenChatId) }

    LaunchedEffect(session) {
        val current = session
        if (current != null) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }

    when (val current = screen) {
        is Screen.NameEntry -> NameEntryScreen(onRegistered = { newSession ->
            SessionStore.save(context, newSession)
            session = newSession
            screen = Screen.ChatList
        })

        is Screen.ChatList -> {
            val activeSession = session
            if (activeSession != null) {
                ChatListScreen(
                    session = activeSession,
                    pendingOpenChatId = pendingChatId,
                    onConsumedPending = { pendingChatId = null },
                    onOpenChat = { chatId, title ->
                        ConnectionService.openChatId = chatId
                        screen = Screen.Chat(chatId, title)
                    }
                )
            }
        }

        is Screen.Chat -> {
            val activeSession = session
            if (activeSession != null) {
                ChatScreen(
                    session = activeSession,
                    chatId = current.chatId,
                    title = current.title,
                    onBack = {
                        ConnectionService.openChatId = null
                        screen = Screen.ChatList
                    }
                )
            }
        }
    }
}
