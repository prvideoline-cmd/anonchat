package com.anonchat.app.media

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * Запись круглых видео-сообщений ("видео-кружков") через CameraX,
 * по аналогии с Telegram: удерживаем кнопку камеры — идёт запись,
 * отпускаем — запись останавливается и сохраняется в файл.
 */
class VideoCircleRecorder(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var outputFile: File? = null
    private var startTime: Long = 0L
    private var useFrontCamera = true

    /** Инициализирует камеру и превью. Вызывать после того, как PreviewView добавлен в иерархию. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.SD, Quality.LOWEST),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                onReady()
            } catch (e: ExecutionException) {
                onError(e)
            } catch (e: InterruptedException) {
                onError(e)
            } catch (e: Exception) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Начинает запись видео-кружка. */
    @Suppress("MissingPermission")
    fun startRecording(onFinished: (File?, Long) -> Unit) {
        val capture = videoCapture ?: return
        val dir = File(context.cacheDir, "video_circle").apply { mkdirs() }
        val file = File(dir, "circle_${System.currentTimeMillis()}.mp4")
        outputFile = file
        startTime = System.currentTimeMillis()

        val outputOptions = FileOutputOptions.Builder(file).build()
        try {
            activeRecording = capture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val duration = System.currentTimeMillis() - startTime
                        if (event.hasError()) {
                            Log.e("VideoCircleRecorder", "Ошибка записи: ${event.error}")
                            file.delete()
                            onFinished(null, 0L)
                        } else if (duration < 400) {
                            file.delete()
                            onFinished(null, 0L)
                        } else {
                            onFinished(file, duration)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("VideoCircleRecorder", "Не удалось начать запись", e)
            onFinished(null, 0L)
        }
    }

    /** Останавливает текущую запись (событие Finalize придёт в колбэк startRecording). */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    /** Отменяет запись без сохранения. */
    fun cancelRecording() {
        activeRecording?.close()
        activeRecording = null
        outputFile?.delete()
    }

    fun unbind() {
        try {
            activeRecording?.close()
        } catch (e: Exception) {
            // ignore
        }
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
    }
}
