package ru.corip.shortsoffline

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Хранение сессии YouTube в виде cookies.txt для yt-dlp.
 *
 * Куки — это полный доступ к аккаунту Google, а не токен «только чтение».
 * Файл лежит во внутреннем хранилище приложения: другим программам он
 * недоступен, но при рутованном телефоне защиты никакой.
 */
object Cookies {

    /** Без этих имён YouTube не считает сессию залогиненной. */
    private val REQUIRED = listOf("SID", "SAPISID", "__Secure-1PSID", "__Secure-3PSID")

    fun file(context: Context): File = File(context.filesDir, "cookies.txt")

    fun isSignedIn(context: Context): Boolean {
        val f = file(context)
        if (!f.exists()) return false
        val text = runCatching { f.readText() }.getOrDefault("")
        return REQUIRED.any { name -> text.lineSequence().any { it.contains("\t$name\t") } }
    }

    /**
     * Забирает куки из WebView и пишет их в формате Netscape.
     * Возвращает количество сохранённых записей.
     */
    fun captureFromWebView(context: Context): Int {
        val manager = CookieManager.getInstance()
        manager.flush()

        val jar = LinkedHashMap<String, String>()
        listOf("https://www.youtube.com", "https://youtube.com", "https://google.com")
            .forEach { origin ->
                manager.getCookie(origin)?.split(";")?.forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
                }
            }
        if (jar.isEmpty()) return 0

        val expiry = System.currentTimeMillis() / 1000 + 60L * 60 * 24 * 365
        val text = buildString {
            appendLine("# Netscape HTTP Cookie File")
            appendLine("# Записано ShortsOffline")
            jar.forEach { (name, value) ->
                appendLine(".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
        file(context).writeText(text)
        return jar.size
    }

    /** Импорт cookies.txt, выгруженного расширением из десктопного браузера. */
    fun import(context: Context, text: String): Boolean {
        if (!text.contains("youtube.com")) return false
        file(context).writeText(text)
        return isSignedIn(context)
    }

    /**
     * Переносит сохранённые куки в WebView. Без этого раздел шортсов покажет
     * обезличенную ленту, а нам нужна именно твоя.
     */
    fun injectIntoWebView(context: Context): Int {
        val f = file(context)
        if (!f.exists()) return 0
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)

        var count = 0
        f.readLines().forEach { line ->
            if (line.startsWith("#") || line.isBlank()) return@forEach
            val parts = line.split("\t")
            if (parts.size < 7) return@forEach
            val name = parts[5]
            val value = parts[6]
            if (name.isNotBlank() && value.isNotBlank()) {
                manager.setCookie(
                    "https://www.youtube.com",
                    "$name=$value; Domain=.youtube.com; Path=/; Secure",
                )
                count++
            }
        }
        manager.flush()
        return count
    }

    fun signOut(context: Context) {
        file(context).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
