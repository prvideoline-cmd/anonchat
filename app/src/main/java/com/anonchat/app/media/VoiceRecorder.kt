package com.anonchat.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Простая обёртка над MediaRecorder для записи голосовых сообщений
 * (удержание кнопки микрофона).
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime: Long = 0L

    /** Начинает запись, возвращает файл, в который пишем аудио, или null при ошибке. */
    fun start(): File? {
        return try {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            outputFile = file
            startTime = System.currentTimeMillis()
            file
        } catch (e: Exception) {
            release()
            null
        }
    }

    /** Останавливает запись. Возвращает файл + длительность в мс, либо null если запись была слишком короткой/неудачной. */
    fun stop(): Pair<File, Long>? {
        val duration = System.currentTimeMillis() - startTime
        val file = outputFile
        return try {
            recorder?.stop()
            release()
            if (file != null && duration >= 400) file to duration else {
                file?.delete()
                null
            }
        } catch (e: Exception) {
            file?.delete()
            release()
            null
        }
    }

    /** Отменяет запись без сохранения (например, если это был просто короткий тап). */
    fun cancel() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // игнорируем — recorder мог не успеть накопить данные
        }
        release()
        outputFile?.delete()
        outputFile = null
    }

    private fun release() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
            // ignore
        }
        recorder = null
    }
}
