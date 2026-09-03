package com.anonchat.app

import android.app.Application
import com.anonchat.app.media.SoundPlayer

class AnonChatApplication : Application() {

    companion object {
        lateinit var instance: AnonChatApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SoundPlayer.init(this)
    }
}
