package ru.corip.shortsoffline

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * yt-dlp и Python лежат внутри APK — сервер не нужен.
 * YouTube Data API не отдаёт видеофайлы, поэтому качаем ими.
 */
object Downloader {

    // Готовый прогрессивный mp4: одна дорожка, склейка не нужна.
    private const val FORMAT = "b[ext=mp4]/b"

    @Volatile private var ready = false

    private val http = OkHttpClient()

    class Probe(val size: Long, val width: Int, val height: Int) {
        val isVertical: Boolean get() = width > 0 && height > width
    }

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (ready) return@withContext
        YoutubeDL.getInstance().init(context)
        ready = true
    }

    private fun url(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Обновляет встроенный yt-dlp — YouTube ломает старые версии примерно раз в месяц. */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context)
            "yt-dlp обновлён"
        }.getOrElse { "Обновить не вышло: ${it.message}" }
    }

    /**
     * Узнаёт вес и размер кадра, ничего не качая.
     * Разбираем JSON сами, чтобы не зависеть от формы VideoInfo в библиотеке.
     */
    suspend fun probe(id: String): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            val request = YoutubeDLRequest(url(id)).apply {
                addOption("-f", FORMAT)
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--skip-download")
            }
            val out = YoutubeDL.getInstance().execute(request).out
            val json = JSONObject(out.substring(out.indexOf('{')))

            var size = 0L
            json.optJSONArray("requested_downloads")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val part = arr.getJSONObject(i)
                    size += part.optLong("filesize").takeIf { it > 0 }
                        ?: part.optLong("filesize_approx")
                }
            }
            if (size == 0L) {
                size = json.optLong("filesize").takeIf { it > 0 }
                    ?: json.optLong("filesize_approx").takeIf { it > 0 }
                            ?: (json.optDouble("tbr", 0.0) * 125 * json.optInt("duration")).toLong()
            }

            var width = json.optInt("width")
            var height = json.optInt("height")
            if (width == 0 || height == 0) {
                json.optJSONArray("requested_downloads")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val part = arr.getJSONObject(i)
                        if (part.optInt("width") > 0) {
                            width = part.optInt("width"); height = part.optInt("height")
                        }
                    }
                }
            }
            Probe(size, width, height)
        }.getOrNull()
    }

    /** Качает файл в [dir]. [onProgress] отдаёт долю 0..1. */
    suspend fun download(
        id: String,
        dir: File,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val request = YoutubeDLRequest(url(id)).apply {
            addOption("-f", FORMAT)
            addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
            addOption("--no-warnings")
            addOption("--no-mtime")
        }
        YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
            onProgress((progress / 100f).coerceIn(0f, 1f))
        }

        dir.listFiles { f ->
            f.name.startsWith("$id.") &&
                !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") &&
                !f.name.endsWith(".json") && !f.name.endsWith(".jpg")
        }?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp отработал, но файла нет.")
    }

    fun cancel(id: String) = runCatching { YoutubeDL.getInstance().destroyProcessById(id) }

    /** Превью для списка — маленький jpg, чтобы библиотека выглядела живой офлайн. */
    suspend fun thumbnail(urlString: String?, target: File): File? = withContext(Dispatchers.IO) {
        if (urlString.isNullOrBlank()) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(urlString).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                target.writeBytes(response.body!!.bytes())
            }
            target
        }.getOrNull()
    }
}
