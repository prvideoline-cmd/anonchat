package com.anonchat.app.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Полноэкранное окно звонка (входящий/исходящий/активный) — рисуется поверх остального UI. */
@Composable
fun CallOverlay(state: CallUiState) {
    var elapsedSec by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.state, state.startedAtMs) {
        if (state.state == CallState.ACTIVE) {
            while (true) {
                elapsedSec = (System.currentTimeMillis() - state.startedAtMs) / 1000
                delay(1000)
            }
        } else {
            elapsedSec = 0L
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1730)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Color(0xFF5B5FEF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(state.peerName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (state.state) {
                    CallState.RINGING_OUTGOING -> "Вызов..."
                    CallState.RINGING_INCOMING -> "Входящий звонок"
                    CallState.CONNECTING -> "Соединение..."
                    CallState.ACTIVE -> formatDuration(elapsedSec)
                    CallState.ENDED -> "Абонент занят"
                    CallState.IDLE -> ""
                },
                color = Color(0xCCFFFFFF),
                fontSize = 15.sp
            )

            Spacer(modifier = Modifier.height(56.dp))

            when (state.state) {
                CallState.RINGING_INCOMING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        CallButton(icon = Icons.Filled.CallEnd, background = Color(0xFFE74C3C), onClick = { CallController.rejectCall() })
                        CallButton(icon = Icons.Filled.Call, background = Color(0xFF2ECC71), onClick = { CallController.acceptCall() })
                    }
                }
                CallState.ENDED -> {}
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        CallButton(
                            icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            background = Color(0x33FFFFFF),
                            onClick = { CallController.toggleMute() }
                        )
                        CallButton(icon = Icons.Filled.CallEnd, background = Color(0xFFE74C3C), onClick = { CallController.endCall() })
                    }
                }
            }
        }
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = background)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
