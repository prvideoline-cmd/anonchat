package com.anonchat.app.call

import android.content.Context
import com.anonchat.app.data.ChatSocket
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** Состояние текущего звонка, наблюдаемое экраном. */
enum class CallState { IDLE, RINGING_OUTGOING, RINGING_INCOMING, CONNECTING, ACTIVE, ENDED }

/**
 * Обёртка над WebRTC для голосовых (аудио-only) звонков в приватных чатах.
 * Сигналинг (обмен offer/answer/ICE-кандидатами) идёт через уже открытый
 * WebSocket к нашему серверу ([ChatSocket.sendCallSignal]) — сам голос
 * передаётся напрямую между устройствами (P2P), сервер его не видит.
 *
 * Используется публичный STUN-сервер Google для обхода NAT. Если звонок не
 * устанавливается за конусным/строгим NAT — потребуется свой TURN-сервер
 * (например, coturn) на VPS; это можно добавить отдельным шагом позже.
 */
class CallManager(private val appContext: Context) {

    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    fun initialize() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Создаёт новое P2P-соединение для звонка. [onLocalIceCandidate] вызывается для каждого
     * локального ICE-кандидата, который нужно отправить собеседнику; [onRemoteAudio]
     * вызывается, когда приходит удалённый аудиопоток (можно не использовать явно —
     * WebRTC сам воспроизводит его через аудио-подсистему устройства).
     */
    fun createPeerConnection(
        onLocalIceCandidate: (IceCandidate) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        val f = factory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onLocalIceCandidate(candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onConnected()
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> onDisconnected()
                    else -> {}
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        localAudioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("audio0", localAudioSource)
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))
    }

    fun createOffer(onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                onCreated(desc)
            }
        }, constraints)
    }

    fun createAnswer(onCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                onCreated(desc)
            }
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun close() {
        try {
            peerConnection?.close()
        } catch (e: Exception) {
            // ignore
        }
        peerConnection = null
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
    }

    fun release() {
        close()
        factory?.dispose()
        factory = null
        eglBase.release()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
