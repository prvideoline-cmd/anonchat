package com.anonchat.app

import android.app.Application

class AnonChatApplication : Application() {

    companion object {
        lateinit var instance: AnonChatApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
