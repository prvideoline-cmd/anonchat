package com.anonchat.app

import android.content.Context
import android.content.SharedPreferences

/** Аккаунт пользователя на этом устройстве: 5-значный ID, выбранное имя и токен авторизации. */
data class Session(
    val id: String,
    val name: String,
    val token: String
)

/**
 * Хранит аккаунт пользователя в SharedPreferences, чтобы при повторном запуске
 * приложения на этом же устройстве имя и ID не менялись (в отличие от старой
 * версии со случайными именами).
 */
object SessionStore {
    private const val PREFS_NAME = "anonchat_session"
    private const val KEY_ID = "user_id"
    private const val KEY_NAME = "user_name"
    private const val KEY_TOKEN = "user_token"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Session? {
        val p = prefs(context)
        val id = p.getString(KEY_ID, null) ?: return null
        val name = p.getString(KEY_NAME, null) ?: return null
        val token = p.getString(KEY_TOKEN, null) ?: return null
        return Session(id, name, token)
    }

    fun save(context: Context, session: Session) {
        prefs(context).edit()
            .putString(KEY_ID, session.id)
            .putString(KEY_NAME, session.name)
            .putString(KEY_TOKEN, session.token)
            .apply()
    }
}
