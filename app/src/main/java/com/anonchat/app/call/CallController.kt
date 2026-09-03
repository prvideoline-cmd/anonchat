package com.anonchat.app.call

import android.content.Context
import com.anonchat.app.Session
import com.anonchat.app.data.ChatSocket
import com.anonchat.app.data.SocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

data class CallUiState(
    val chatId: String,
    val peerId: String,
    val peerName: String,
    val state: CallState,
    val isIncoming: Boolean,
    val muted: Boolean = false,
    val startedAtMs: Long = 0L
)

/**
 * Единственный на всё приложение контроллер аудиозвонков. Живёт независимо от того,
 * какой экран сейчас открыт — благодаря этому входящий звонок можно принять, даже
 * если пользователь находится в списке чатов, а не в самом чате. UI (CallScreen /
 * баннер входящего звонка) подписывается на [callState].
 */
object CallController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: CallManager? = null
    private var attached = false
    private var mySession: Session? = null

    private var pendingOfferSdp: String? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    private val _callState = MutableStateFlow<CallUiState?>(null)
    val callState = _callState.asStateFlow()

    fun attach(context: Context, session: Session) {
        mySession = session
        if (attached) return
        attached = true
        manager = CallManager(context.applicationContext).apply { initialize() }

        scope.launch {
            ChatSocket.events.collect { event ->
                if (event is SocketEvent.CallSignal) handleSignal(event)
            }
        }
    }

    private fun handleSignal(event: SocketEvent.CallSignal) {
        when (event.kind) {
            "offer" -> {
                if (_callState.value != null) {
                    ChatSocket.sendCallSignal(event.chatId, "busy")
                    return
                }
                pendingOfferSdp = event.sdp
                pendingCandidates.clear()
                remoteDescriptionSet = false
                _callState.value = CallUiState(
                    chatId = event.chatId,
                    peerId = event.fromUserId,
                    peerName = event.fromName,
                    state = CallState.RINGING_INCOMING,
                    isIncoming = true
                )
            }
            "answer" -> {
                val sdp = event.sdp ?: return
                manager?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                remoteDescriptionSet = true
                drainPendingCandidates()
                updateState { it.copy(state = CallState.CONNECTING) }
            }
            "ice" -> {
                val candidate = event.candidate ?: return
                val cand = IceCandidate(event.sdpMid ?: "", event.sdpMLineIndex ?: 0, candidate)
                if (remoteDescriptionSet) manager?.addIceCandidate(cand) else pendingCandidates.add(cand)
            }
            "end", "reject" -> {
                cleanupLocal()
            }
            "busy" -> {
                updateState { it.copy(state = CallState.ENDED) }
                scope.launch {
                    delay(1500)
                    if (_callState.value?.state == CallState.ENDED) _callState.value = null
                }
            }
        }
    }

    private fun drainPendingCandidates() {
        pendingCandidates.forEach { manager?.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun updateState(transform: (CallUiState) -> CallUiState) {
        _callState.value = _callState.value?.let(transform)
    }

    /** Инициировать исходящий звонок в приватном чате. */
    fun startCall(chatId: String, peerId: String, peerName: String) {
        if (_callState.value != null) return
        val mgr = manager ?: return
        remoteDescriptionSet = false
        pendingCandidates.clear()
        _callState.value = CallUiState(chatId, peerId, peerName, CallState.RINGING_OUTGOING, isIncoming = false)

        mgr.createPeerConnection(
            onLocalIceCandidate = { cand ->
                ChatSocket.sendCallSignal(chatId, "ice", candidate = cand.sdp, sdpMid = cand.sdpMid, sdpMLineIndex = cand.sdpMLineIndex)
            },
            onConnected = { updateState { it.copy(state = CallState.ACTIVE, startedAtMs = System.currentTimeMillis()) } },
            onDisconnected = { cleanupLocal() }
        )
        mgr.createOffer { desc ->
            ChatSocket.sendCallSignal(chatId, "offer", sdp = desc.description, sdpType = "offer")
        }
    }

    /** Принять входящий звонок (после того, как пользователь нажал "Ответить"). */
    fun acceptCall() {
        val st = _callState.value ?: return
        val offer = pendingOfferSdp ?: return
        val mgr = manager ?: return

        mgr.createPeerConnection(
            onLocalIceCandidate = { cand ->
                ChatSocket.sendCallSignal(st.chatId, "ice", candidate = cand.sdp, sdpMid = cand.sdpMid, sdpMLineIndex = cand.sdpMLineIndex)
            },
            onConnected = { updateState { it.copy(state = CallState.ACTIVE, startedAtMs = System.currentTimeMillis()) } },
            onDisconnected = { cleanupLocal() }
        )
        mgr.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offer))
        remoteDescriptionSet = true
        drainPendingCandidates()
        mgr.createAnswer { desc ->
            ChatSocket.sendCallSignal(st.chatId, "answer", sdp = desc.description, sdpType = "answer")
        }
        updateState { it.copy(state = CallState.CONNECTING) }
    }

    /** Отклонить входящий звонок, не отвечая. */
    fun rejectCall() {
        val st = _callState.value ?: return
        ChatSocket.sendCallSignal(st.chatId, "reject")
        cleanupLocal()
    }

    /** Завершить активный/исходящий звонок. */
    fun endCall() {
        val st = _callState.value
        if (st != null) ChatSocket.sendCallSignal(st.chatId, "end")
        cleanupLocal()
    }

    fun toggleMute() {
        val st = _callState.value ?: return
        val newMuted = !st.muted
        manager?.setMuted(newMuted)
        updateState { it.copy(muted = newMuted) }
    }

    private fun cleanupLocal() {
        manager?.close()
        pendingOfferSdp = null
        pendingCandidates.clear()
        remoteDescriptionSet = false
        _callState.value = null
    }
}
