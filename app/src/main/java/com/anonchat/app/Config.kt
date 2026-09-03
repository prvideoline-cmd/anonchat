package com.anonchat.app

/**
 * НАСТРОЙКИ СЕРВЕРА — поменяйте под свой VPS перед сборкой APK.
 *
 * Пример: если сервер поднят на IP 203.0.113.10 и слушает порт 8080:
 *   SERVER_HOST = "203.0.113.10"
 *   SERVER_PORT = 8080
 *   USE_TLS = false
 *
 * Если настроили домен + https/wss через nginx (см. server/nginx.conf.example):
 *   SERVER_HOST = "chat.ваш-домен.ru"
 *   SERVER_PORT = 443
 *   USE_TLS = true
 */
object Config {

    /** IP-адрес или домен вашего VPS. */
    const val SERVER_HOST = "195-19-202-16.sslip.io"

    /** Порт сервера (8080 по умолчанию в server/.env.example, 443 если через nginx+https). */
    const val SERVER_PORT = 443

    /** true — использовать wss:// и https:// (когда сервер закрыт сертификатом через nginx). */
    const val USE_TLS = true

    /**
     * Необязательный общий секрет — должен совпадать с CHAT_SECRET на сервере
     * (см. server/.env.example). Оставьте пустой строкой, если секрет не используется.
     */
    const val CHAT_SECRET = ""

    val httpScheme: String get() = if (USE_TLS) "https" else "http"
    val wsScheme: String get() = if (USE_TLS) "wss" else "ws"

    val restBaseUrl: String get() = "$httpScheme://$SERVER_HOST:$SERVER_PORT"
    val wsUrl: String get() = "$wsScheme://$SERVER_HOST:$SERVER_PORT/ws"

    /** Превращает относительный путь вида "/media/123/foo.jpg" в полный URL. */
    fun mediaUrl(relativePath: String): String {
        if (relativePath.startsWith("http")) return relativePath
        return "$restBaseUrl$relativePath"
    }
}
