package com.anonchat.app.data

import android.content.Context

/**
 * "Удалить у меня" — сообщение остаётся на сервере и у собеседника, но скрывается
 * локально на этом устройстве. Храним id скрытых сообщений в SharedPreferences,
 * чтобы они не появлялись обратно после перезапуска приложения.
 */
object LocalDeletedStore {
    private const val PREFS_NAME = "anonchat_deleted"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(chatId: String) = "hidden_$chatId"

    fun hide(context: Context, chatId: String, messageId: Long) {
        val p = prefs(context)
        val current = p.getStringSet(key(chatId), emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(messageId.toString())
        p.edit().putStringSet(key(chatId), current).apply()
    }

    fun hiddenIds(context: Context, chatId: String): Set<Long> {
        return prefs(context).getStringSet(key(chatId), emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()
    }
}
