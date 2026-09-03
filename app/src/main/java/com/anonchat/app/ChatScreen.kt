package com.anonchat.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.anonchat.app.data.ApiClient
import com.anonchat.app.data.ChatSocket
import com.anonchat.app.data.SocketEvent
import com.anonchat.app.media.Stickers
import com.anonchat.app.media.VideoCircleRecorder
import com.anonchat.app.media.VoiceRecorder
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.ChatSummary
import com.anonchat.app.model.ReplyPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(session: Session, chatId: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var showStickerPicker by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(chatId) {
        try {
            messages = withContext(Dispatchers.IO) { ApiClient.fetchMessages(session, chatId) }
        } catch (e: Exception) {
            // покажем то, что придёт по сокету дальше
        }
        loading = false
    }

    LaunchedEffect(chatId) {
        ChatSocket.events.collect { event ->
            if (event is SocketEvent.NewMessage && event.message.chatId == chatId) {
                messages = messages + event.message
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendCurrentText() {
        val text = input
        ChatSocket.sendMessage(
            chatId = chatId,
            name = session.name,
            text = text,
            type = "text",
            replyTo = replyTarget?.let { ReplyPreview(id = it.id, name = it.name, text = it.text.ifBlank { mediaLabel(it.type) }) }
        )
        input = ""
        replyTarget = null
    }

    fun sendSticker(emoji: String) {
        ChatSocket.sendMessage(chatId = chatId, name = session.name, text = emoji, type = "sticker")
        showStickerPicker = false
    }

    fun sendMediaFile(file: File, type: String, mimeType: String, durationMs: Long) {
        uploading = true
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) { ApiClient.uploadMedia(session, file, mimeType) }
                ChatSocket.sendMessage(
                    chatId = chatId,
                    name = session.name,
                    text = "",
                    type = type,
                    mediaUrl = url,
                    mediaDurationMs = durationMs,
                    replyTo = replyTarget?.let { ReplyPreview(id = it.id, name = it.name, text = it.text.ifBlank { mediaLabel(it.type) }) }
                )
                replyTarget = null
            } catch (e: Exception) {
                // не удалось отправить медиа — молча игнорируем, можно повторить
            } finally {
                uploading = false
                file.delete()
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { copyUriToCache(context, uri, "photo", ".jpg") }
                if (file != null) sendMediaFile(file, "photo", "image/jpeg", 0L)
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (loading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(messages, key = { _, m -> "${m.chatId}_${m.id}_${m.timestamp}" }) { _, msg ->
                        MessageBubble(
                            msg = msg,
                            isMe = msg.userId == session.id,
                            onReply = { replyTarget = msg },
                            onForward = { forwardTarget = msg }
                        )
                    }
                }
            }

            if (uploading) {
                Surface(color = Color(0x22000000)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Отправка...", fontSize = 12.sp)
                    }
                }
            }

            replyTarget?.let { target ->
                ReplyPreviewBar(target = target, onCancel = { replyTarget = null })
            }

            if (showStickerPicker) {
                StickerPicker(onPick = { sendSticker(it) }, onClose = { showStickerPicker = false })
            }

            MessageInputBar(
                value = input,
                onValueChange = { input = it },
                onSend = { sendCurrentText() },
                onToggleStickers = { showStickerPicker = !showStickerPicker },
                onPickPhoto = {
                    photoPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onVoiceRecorded = { file, duration -> sendMediaFile(file, "voice", "audio/mp4", duration) },
                onVideoCircleRecorded = { file, duration -> sendMediaFile(file, "video_circle", "video/mp4", duration) }
            )
        }
    }

    forwardTarget?.let { msg ->
        ForwardDialog(
            session = session,
            excludeChatId = chatId,
            onDismiss = { forwardTarget = null },
            onForward = { targetChatId ->
                ChatSocket.sendMessage(
                    chatId = targetChatId,
                    name = session.name,
                    text = msg.text,
                    type = msg.type,
                    mediaUrl = msg.mediaUrl,
                    mediaDurationMs = msg.mediaDurationMs,
                    forwardedFromName = msg.forwardedFromName ?: msg.name
                )
                forwardTarget = null
            }
        )
    }
}

private fun mediaLabel(type: String): String = when (type) {
    "photo" -> "📷 Фото"
    "voice" -> "🎤 Голосовое"
    "video_circle" -> "⚫ Видео-кружок"
    "sticker" -> "Стикер"
    else -> ""
}

private fun copyUriToCache(context: android.content.Context, uri: Uri, prefix: String, ext: String): File? {
    return try {
        val dir = File(context.cacheDir, prefix).apply { mkdirs() }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        if (file.exists() && file.length() > 0) file else null
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatMessage, isMe: Boolean, onReply: (ChatMessage) -> Unit, onForward: (ChatMessage) -> Unit) {
    val bubbleColor = if (isMe) Color(0xFF6C5CE7) else Color.White
    val textColor = if (isMe) Color.White else Color.Black
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    val swipeThresholdPx = 160f

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        // Иконка ответа, проявляется при свайпе вправо
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = (offsetX.value / swipeThresholdPx).coerceIn(0f, 1f)),
            modifier = Modifier.align(Alignment.CenterStart)
        )

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInputSwipeToReply(
                    onDrag = { delta ->
                        scope.launch {
                            val next = (offsetX.value + delta).coerceIn(0f, swipeThresholdPx)
                            offsetX.snapTo(next)
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (offsetX.value >= swipeThresholdPx * 0.9f) onReply(msg)
                            offsetX.animateTo(0f, tween(200))
                        }
                    }
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                )
        ) {
            if (!isMe) {
                Text(
                    text = msg.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                )
            }
            Box {
                Surface(color = if (msg.type == "sticker") Color.Transparent else bubbleColor, shape = RoundedCornerShape(16.dp), shadowElevation = if (msg.type == "sticker") 0.dp else 1.dp) {
                    Column(modifier = Modifier.padding(if (msg.type == "sticker") 4.dp else 14.dp, if (msg.type == "sticker") 4.dp else 8.dp)) {
                        msg.forwardedFromName?.let {
                            Text("Переслано от $it", fontSize = 11.sp, fontStyle = FontStyle.Italic, color = textColor.copy(alpha = 0.7f))
                        }
                        msg.replyTo?.let { reply ->
                            Surface(
                                color = textColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    Text(reply.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
                                    Text(reply.text, fontSize = 11.sp, color = textColor, maxLines = 1)
                                }
                            }
                        }

                        MessageContent(msg = msg, textColor = textColor)

                        Text(
                            text = formatTime(msg.timestamp),
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Ответить") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                        onClick = { showMenu = false; onReply(msg) }
                    )
                    DropdownMenuItem(
                        text = { Text("Переслать") },
                        leadingIcon = { Icon(Icons.Filled.Forward, contentDescription = null) },
                        onClick = { showMenu = false; onForward(msg) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageContent(msg: ChatMessage, textColor: Color) {
    when (msg.type) {
        "sticker" -> Text(text = msg.text, fontSize = 42.sp)
        "photo" -> {
            if (msg.mediaUrl != null) {
                AsyncImage(
                    model = Config.mediaUrl(msg.mediaUrl),
                    contentDescription = "Фото",
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
        "voice" -> {
            if (msg.mediaUrl != null) {
                VoicePlayer(url = Config.mediaUrl(msg.mediaUrl), durationMs = msg.mediaDurationMs, textColor = textColor)
            }
        }
        "video_circle" -> {
            if (msg.mediaUrl != null) {
                VideoCirclePlayer(url = Config.mediaUrl(msg.mediaUrl))
            }
        }
        else -> Text(text = msg.text, color = textColor, fontSize = 15.sp)
    }
}

@Composable
private fun VoicePlayer(url: String, durationMs: Long, textColor: Color) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(url) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = {
            if (isPlaying) {
                player?.pause()
                isPlaying = false
            } else {
                if (player == null) {
                    player = MediaPlayer().apply {
                        setDataSource(url)
                        setOnCompletionListener { isPlaying = false }
                        prepareAsync()
                        setOnPreparedListener { start(); isPlaying = true }
                    }
                } else {
                    player?.start()
                    isPlaying = true
                }
            }
        }) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Слушать голосовое",
                tint = textColor
            )
        }
        Text("${(durationMs / 1000).coerceAtLeast(1)} сек", color = textColor, fontSize = 13.sp)
    }
}

@Composable
private fun VideoCirclePlayer(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(Uri.parse(url))
                    setOnPreparedListener { it.isLooping = true; start(); isPlaying = true }
                }
            }
        )
        if (!isPlaying) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Воспроизвести", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ReplyPreviewBar(target: ChatMessage, onCancel: () -> Unit) {
    Surface(color = Color(0xFFEDEBFF)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(target.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(target.text.ifBlank { mediaLabel(target.type) }, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Отменить ответ")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerPicker(onPick: (String) -> Unit, onClose: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 4.dp) {
        Column(modifier = Modifier.fillMaxWidth().height(180.dp).padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Стикеры", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(6)) {
                items(Stickers.all) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(onClick = { onPick(emoji) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleStickers: () -> Unit,
    onPickPhoto: () -> Unit,
    onVoiceRecorded: (File, Long) -> Unit,
    onVideoCircleRecorded: (File, Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var iconMode by remember { mutableStateOf("mic") } // "mic" | "camera"
    var isRecordingVoice by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }

    val voiceRecorder = remember { VoiceRecorder(context) }
    val videoRecorder = remember { VideoCircleRecorder(context) }

    DisposableEffect(Unit) {
        onDispose {
            voiceRecorder.cancel()
            videoRecorder.unbind()
        }
    }

    var audioPermGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraPermGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audioPermGranted = it
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraPermGranted = it
    }

    Column {
        AnimatedVisibility(visible = isRecordingVideo) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(200.dp).clip(CircleShape)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).also { pv ->
                                videoRecorder.bind(lifecycleOwner, pv, onReady = {
                                    // Могли уже отпустить кнопку, пока камера привязывалась —
                                    // тогда запись не начинаем.
                                    if (isRecordingVideo) {
                                        videoRecorder.startRecording { file, duration ->
                                            isRecordingVideo = false
                                            if (file != null) onVideoCircleRecorded(file, duration)
                                        }
                                    }
                                })
                            }
                        },
                        onRelease = { videoRecorder.unbind() }
                    )
                }
            }
        }

        Surface(color = Color.White, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleStickers) {
                    Icon(Icons.Filled.SentimentSatisfied, contentDescription = "Стикеры", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onPickPhoto) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Фото", tint = MaterialTheme.colorScheme.primary)
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Написать сообщение...") },
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))

                if (value.isNotBlank()) {
                    FilledIconButton(
                        onClick = onSend,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF6C5CE7))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = Color.White)
                    }
                } else {
                    val holdModifier = Modifier.pointerInputHold(
                        onTap = {
                            if (!isRecordingVoice && !isRecordingVideo) {
                                iconMode = if (iconMode == "mic") "camera" else "mic"
                            }
                        },
                        onHoldStart = {
                            if (iconMode == "mic") {
                                if (!audioPermGranted) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@pointerInputHold
                                }
                                voiceRecorder.start()
                                isRecordingVoice = true
                            } else {
                                if (!cameraPermGranted || !audioPermGranted) {
                                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                                    if (!audioPermGranted) audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@pointerInputHold
                                }
                                isRecordingVideo = true
                            }
                        },
                        onHoldEnd = {
                            if (isRecordingVoice) {
                                isRecordingVoice = false
                                val result = voiceRecorder.stop()
                                if (result != null) onVoiceRecorded(result.first, result.second)
                            }
                            if (isRecordingVideo) {
                                videoRecorder.stopRecording()
                            }
                        }
                    )

                    FilledIconButton(
                        onClick = {},
                        modifier = holdModifier,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isRecordingVoice || isRecordingVideo) Color.Red else Color(0xFF6C5CE7)
                        )
                    ) {
                        Icon(
                            if (iconMode == "mic") Icons.Filled.Mic else Icons.Filled.Camera,
                            contentDescription = if (iconMode == "mic") "Голосовое сообщение" else "Видео-кружок",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForwardDialog(session: Session, excludeChatId: String, onDismiss: () -> Unit, onForward: (String) -> Unit) {
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            chats = withContext(Dispatchers.IO) { ApiClient.fetchChats(session) }.filter { it.chatId != excludeChatId }
        } catch (e: Exception) {
            // покажем пустой список
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переслать сообщение") },
        text = {
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (chats.isEmpty()) {
                Text("Нет других чатов")
            } else {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(chats) { chat ->
                        Text(
                            chat.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onForward(chat.chatId) })
                                .padding(vertical = 12.dp),
                            fontSize = 15.sp
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Отмена") }
        }
    )
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
