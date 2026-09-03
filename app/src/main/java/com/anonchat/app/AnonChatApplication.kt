package com.anonchat.app

import android.app.Application

/**
 * Класс приложения. Случайное имя генерируется один раз за жизнь процесса —
 * то есть при каждом новом запуске приложения (свайп из списка задач и
 * повторный запуск, перезагрузка телефона и т.д.) имя будет новым.
 */
class AnonChatApplication : Application() {

    companion object {
        lateinit var instance: AnonChatApplication
            private set
    }

    /** Случайное имя текущей сессии приложения. Генерируется один раз при старте процесса. */
    val sessionName: String by lazy { NameGenerator.generate() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Обращение к sessionName здесь гарантированно "прогревает" lazy-значение
        // сразу при старте приложения.
        sessionName
    }
}
