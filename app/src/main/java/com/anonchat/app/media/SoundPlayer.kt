package com.anonchat.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.SoundPool
import com.anonchat.app.R

/**
 * Короткие звуки в приложении: отправка/получение сообщения и рингтон входящего звонка.
 * Короткие звуки идут через SoundPool (без задержки), рингтон — через MediaPlayer в цикле,
 * используя системный рингтон устройства (чтобы не навязывать свой звук поверх настроек пользователя).
 */
object SoundPlayer {

    private var soundPool: SoundPool? = null
    private var sentSoundId: Int = 0
    private var receivedSoundId: Int = 0
    private var loaded = false

    private var ringtonePlayer: MediaPlayer? = null

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        soundPool = pool
        try {
            sentSoundId = pool.load(context, R.raw.sent, 1)
            receivedSoundId = pool.load(context, R.raw.received, 1)
        } catch (e: Exception) {
            // если звуки не загрузились — молча работаем без них
        }
    }

    fun playSent() {
        if (sentSoundId != 0) soundPool?.play(sentSoundId, 0.6f, 0.6f, 0, 0, 1f)
    }

    fun playReceived() {
        if (receivedSoundId != 0) soundPool?.play(receivedSoundId, 0.7f, 0.7f, 0, 0, 1f)
    }

    /** Запускает системный рингтон в цикле (входящий звонок). */
    fun startRinging(context: Context) {
        stopRinging()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getValidRingtoneUri(context)
                ?: return
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepareAsync()
                setOnPreparedListener { it.start() }
            }
            ringtonePlayer = player
        } catch (e: Exception) {
            // не удалось получить системный рингтон — звонок всё равно продолжит работать без звука
        }
    }

    fun stopRinging() {
        try {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
        } catch (e: Exception) {
            // ignore
        }
        ringtonePlayer = null
    }
}
