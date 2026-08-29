package ru.corip.shortsoffline

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Весь доступ к YouTube идёт через встроенный yt-dlp: и ленты, и метаданные,
 * и комментарии, и сами файлы. YouTube Data API не используется — а значит
 * не нужны ни ключ, ни OAuth, ни квота.
 */
object YtDlp {

    // Готовый прогрессивный mp4: одна дорожка, склейка ffmpeg не нужна.
    private const val FORMAT = "b[ext=mp4]/b"

    /** Шортсом считаем вертикальное видео не длиннее трёх минут. */
    const val MAX_SHORT_SECONDS = 180

    @Volatile private var ready = false

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (!ready) {
            YoutubeDL.getInstance().init(context)
            ready = true
        }
    }

    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context)
            "yt-dlp обновлён"
        }.getOrElse { "Обновить не вышло: ${it.message}" }
    }

    private fun url(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Общие опции: куки подставляются, если вход выполнен. */
    private fun request(target: String, cookies: File?): YoutubeDLRequest =
        YoutubeDLRequest(target).apply {
            addOption("--no-warnings")
            addOption("--ignore-config")
            cookies?.let { if (it.exists()) addOption("--cookies", it.absolutePath) }
        }

    private fun parse(out: String): JSONObject {
        val start = out.indexOf('{')
        require(start >= 0) { "yt-dlp вернул не JSON" }
        return JSONObject(out.substring(start))
    }

    // ------------------------------------------------------------------ ленты

    enum class Feed(val target: String, val title: String, val needsLogin: Boolean) {
        RECOMMENDED(":ytrec", "Рекомендации", true),
        SUBSCRIPTIONS(":ytsubs", "Подписки", true),
        LIKED(":ytfav", "Понравившиеся", true),
        HISTORY(":ythistory", "История", true),
        TRENDING("https://www.youtube.com/feed/trending", "В тренде", false),
    }

    /**
     * Список роликов из ленты без захода в каждый — быстро.
     * Длинные отсекаем сразу, вертикальность выяснит [probe].
     */
    suspend fun feed(feed: Feed, limit: Int, cookies: File?, exclude: Set<String>): List<Candidate> =
        withContext(Dispatchers.IO) {
            val req = request(feed.target, cookies).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--playlist-end", (limit * 6).toString())
            }
            val json = parse(YoutubeDL.getInstance().execute(req).out)
            val entries = json.optJSONArray("entries") ?: return@withContext emptyList()

            buildList {
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val id = e.optString("id").ifBlank { continue }
                    if (id in exclude) continue
                    val duration = e.optInt("duration", -1)
                    if (duration > MAX_SHORT_SECONDS) continue   // -1 = неизвестно, оставляем
                    add(
                        Candidate(
                            id = id,
                            title = e.optString("title").ifBlank { id },
                            channel = e.optString("channel").ifBlank { e.optString("uploader") },
                            duration = if (duration > 0) duration else 0,
                            views = e.optLong("view_count"),
                            likes = 0,
                            commentCount = 0,
                            thumbUrl = firstThumb(e),
                        )
                    )
                }
            }
        }

    private fun firstThumb(entry: JSONObject): String? {
        entry.optJSONArray("thumbnails")?.let { arr ->
            for (i in arr.length() - 1 downTo 0) {
                val u = arr.optJSONObject(i)?.optString("url")
                if (!u.isNullOrBlank()) return u
            }
        }
        return entry.optString("thumbnail").ifBlank { null }
    }

    // --------------------------------------------------------------- метаданные

    class Probe(
        val size: Long,
        val width: Int,
        val height: Int,
        val duration: Int,
        val title: String,
        val channel: String,
        val likes: Long,
        val views: Long,
        val commentCount: Long,
    ) {
        val isVertical: Boolean get() = width > 0 && height > width
        val isShort: Boolean get() = duration in 1..MAX_SHORT_SECONDS
    }

    /** Вес, размеры кадра и статистика — без скачивания. */
    suspend fun probe(id: String, cookies: File?): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            val req = request(url(id), cookies).apply {
                addOption("-f", FORMAT)
                addOption("--dump-single-json")
                addOption("--skip-download")
            }
            val json = parse(YoutubeDL.getInstance().execute(req).out)

            var size = 0L
            json.optJSONArray("requested_downloads")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    size += p.optLong("filesize").takeIf { it > 0 } ?: p.optLong("filesize_approx")
                }
            }
            if (size == 0L) {
                size = json.optLong("filesize").takeIf { it > 0 }
                    ?: json.optLong("filesize_approx").takeIf { it > 0 }
                    ?: (json.optDouble("tbr", 0.0) * 125 * json.optInt("duration")).toLong()
            }

            var w = json.optInt("width")
            var h = json.optInt("height")
            if (w == 0 || h == 0) {
                json.optJSONArray("requested_downloads")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        if (p.optInt("width") > 0) { w = p.optInt("width"); h = p.optInt("height") }
                    }
                }
            }

            Probe(
                size = size,
                width = w,
                height = h,
                duration = json.optInt("duration"),
                title = json.optString("title"),
                channel = json.optString("channel").ifBlank { json.optString("uploader") },
                likes = json.optLong("like_count"),
                views = json.optLong("view_count"),
                commentCount = json.optLong("comment_count"),
            )
        }.getOrNull()
    }

    // -------------------------------------------------------------- комментарии

    /**
     * Топ комментариев с лайками и ответами.
     * yt-dlp отдаёт плоский список, где у ответа в поле parent лежит id родителя —
     * собираем ветки сами. В отличие от Data API здесь реально доступны
     * все [maxReplies] ответов, а не только первые пять.
     */
    suspend fun comments(
        id: String,
        cookies: File?,
        maxTop: Int = 100,
        maxReplies: Int = 10,
    ): List<Comment> = withContext(Dispatchers.IO) {
        runCatching {
            val req = request(url(id), cookies).apply {
                addOption("--dump-single-json")
                addOption("--skip-download")
                addOption("--write-comments")
                addOption(
                    "--extractor-args",
                    "youtube:comment_sort=top;max_comments=all,$maxTop,all,$maxReplies",
                )
            }
            val arr = parse(YoutubeDL.getInstance().execute(req).out)
                .optJSONArray("comments") ?: return@withContext emptyList()

            val tops = LinkedHashMap<String, MutableList<Reply>>()
            val meta = LinkedHashMap<String, Comment>()

            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val cid = c.optString("id").ifBlank { continue }
                val author = c.optString("author").removePrefix("@")
                val text = c.optString("text")
                val likes = c.optLong("like_count")
                val parent = c.optString("parent")

                if (parent.isBlank() || parent == "root") {
                    meta[cid] = Comment(author, text, likes, 0, emptyList())
                    tops.getOrPut(cid) { mutableListOf() }
                } else {
                    // id ответа выглядит как "<родитель>.<свой>"
                    val root = parent.substringBefore('.')
                    tops.getOrPut(root) { mutableListOf() }.add(Reply(author, text, likes))
                }
            }

            meta.entries
                .map { (cid, c) ->
                    val replies = tops[cid].orEmpty()
                    c.copy(
                        replyCount = replies.size,
                        replies = replies.sortedByDescending { it.likes }.take(maxReplies),
                    )
                }
                .sortedByDescending { it.likes }
                .take(maxTop)
        }.getOrDefault(emptyList())
    }

    // ----------------------------------------------------------------- загрузка

    suspend fun download(
        id: String,
        dir: File,
        cookies: File?,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val req = request(url(id), cookies).apply {
            addOption("-f", FORMAT)
            addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
            addOption("--no-mtime")
        }
        YoutubeDL.getInstance().execute(req, id) { progress, _, _ ->
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

    /** Превью для списка: маленький jpg, чтобы библиотека выглядела живой офлайн. */
    suspend fun thumbnail(link: String?, target: File): File? = withContext(Dispatchers.IO) {
        if (link.isNullOrBlank()) return@withContext null
        runCatching {
            val conn = URL(link).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        }.getOrNull()
    }
}
