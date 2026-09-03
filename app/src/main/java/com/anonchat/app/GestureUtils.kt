package com.anonchat.app

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException

/**
 * Свайп сообщения слева направо (ответ на сообщение, как в Telegram).
 * [onDrag] получает дельту смещения по X, [onDragEnd] вызывается по окончании жеста.
 */
fun Modifier.pointerInputSwipeToReply(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
): Modifier = composed {
    this.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                if (dragAmount > 0) onDrag(dragAmount)
            }
        )
    }
}

/**
 * Различает короткий тап (переключение микрофон/камера) и долгое удержание
 * (запись голосового сообщения или видео-кружка).
 */
fun Modifier.pointerInputHold(
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
): Modifier = composed {
    this.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                val startTime = System.currentTimeMillis()
                var started = false
                try {
                    onHoldStart()
                    started = true
                    tryAwaitRelease()
                } catch (c: CancellationException) {
                    // жест отменён системой — всё равно завершаем через onHoldEnd ниже
                } finally {
                    val duration = System.currentTimeMillis() - startTime
                    if (started) onHoldEnd()
                    if (duration < 250) onTap()
                }
            }
        )
    }
}
