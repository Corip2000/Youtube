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
    // Один жёсткий формат не годится: для части роликов YouTube готовый файл
    // со звуком больше не отдаёт, только раздельные дорожки. Поэтому цепочка:
    // сначала одиночный mp4, потом склейка, в конце что угодно подходящее.
    private const val FORMAT_MERGE =
        "b[ext=mp4][acodec!=none]/bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
    private const val FORMAT_PLAIN = "b[acodec!=none][vcodec!=none]/b"

    private fun format() = if (ffmpegReady) FORMAT_MERGE else FORMAT_PLAIN

    /** Шортсом считаем вертикальное видео не длиннее трёх минут. */
    const val MAX_SHORT_SECONDS = 180

    @Volatile private var ready = false
    @Volatile private var ffmpegReady = false

    /** Последняя ошибка от yt-dlp целиком — её показываем пользователю. */
    @Volatile
    var lastError: String? = null
        private set

    private fun run(req: YoutubeDLRequest): String =
        try {
            YoutubeDL.getInstance().execute(req).out
        } catch (e: Exception) {
            lastError = (e.message ?: e.toString())
                .lineSequence()
                .filter { it.isNotBlank() }
                .filter { !it.startsWith("WARNING") }
                .joinToString(" ")
                .take(300)
            throw e
        }

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (!ready) {
            YoutubeDL.getInstance().init(context)
            ffmpegReady = initFfmpeg(context)
            ready = true
        }
    }

    /**
     * ffmpeg поднимаем через рефлексию: в разных версиях библиотеки класс лежит
     * то в com.yausername.ffmpeg, то в com.yausername.youtubedl_android.ffmpeg.
     * Так сборка не зависит от того, угадал ли я пакет.
     */
    private fun initFfmpeg(context: Context): Boolean {
        val names = listOf(
            "com.yausername.ffmpeg.FFmpeg",
            "com.yausername.youtubedl_android.ffmpeg.FFmpeg",
        )
        for (name in names) {
            val ok = runCatching {
                val cls = Class.forName(name)
                val instance = cls.getMethod("getInstance").invoke(null)
                cls.getMethod("init", Context::class.java).invoke(instance, context)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun hasFfmpeg(): Boolean = ffmpegReady

    fun version(context: Context): String? =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /** Обновление бинарника. Возвращает подробный отчёт, а не короткий тост. */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        val before = version(context) ?: "неизвестна"
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context)
            val after = version(context) ?: "всё ещё неизвестна"
            if (after == before) {
                "Версия не изменилась: $before\n\n" +
                    "Если она \"неизвестна\", обновление не скачалось. " +
                    "Проверь, что VPN включён для всего телефона, а не только для браузера — " +
                    "бинарник тянется с github.com."
            } else {
                "Обновлено\nБыло: $before\nСтало: $after"
            }
        }.getOrElse {
            "Обновить не вышло.\n\n${it.message}\n\n" +
                "Чаще всего это блокировка github.com. Включи VPN на весь телефон и повтори."
        }
    }

    private fun url(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Номер ролика из ссылки — им называем файлы и отсеиваем повторы. */
    fun idFromUrl(link: String): String {
        Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(link)?.let { return it.groupValues[1] }
        Regex("""/shorts/([A-Za-z0-9_-]{11})""").find(link)?.let { return it.groupValues[1] }
        Regex("""/video/(\d{15,21})""").find(link)?.let { return it.groupValues[1] }
        return link.substringAfterLast('/').substringBefore('?').take(24)
    }

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

    /**
     * Откуда берём ленту. Скачивание у yt-dlp одинаковое для обеих площадок,
     * различаются только адреса и вид ссылки на ролик.
     */
    enum class Platform(
        val title: String,
        val feedUrl: String,
        val loginUrl: String,
        val maxSeconds: Int,
        /**
         * Каким браузером представляемся. TikTok на мобильную строку отдаёт
         * урезанную страницу, которая толком не грузится, — ему нужен
         * настольный вид. YouTube наоборот удобнее в мобильном.
         */
        val userAgent: String,
    ) {
        YOUTUBE(
            "YouTube",
            "https://m.youtube.com/shorts",
            "https://accounts.google.com/ServiceLogin?service=youtube",
            MAX_SHORT_SECONDS,
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        ),
        TIKTOK(
            "TikTok",
            "https://www.tiktok.com/foryou",
            "https://www.tiktok.com/login/phone-or-email",
            600,
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        ),
    }

    enum class Feed(val target: String, val title: String, val needsLogin: Boolean) {
        // Рекомендации собираются не через yt-dlp, а из твоей ленты в браузере:
        // списка этой ленты наружу не существует. Здесь она стоит рядом с
        // остальными источниками только чтобы выбираться в одном месте.
        RECOMMENDED("", "Мои рекомендации", true),
        CUSTOM("", "Ссылка на канал", false),
    }

    /** Один запрос списком, без захода в каждый ролик. */
    private fun flat(target: String, cookies: File?, limit: Int): JSONObject {
        val req = request(target, cookies).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--playlist-end", limit.toString())
        }
        return parse(run(req))
    }

    private fun entryToCandidate(e: JSONObject, fromShorts: Boolean): Candidate? {
        val id = e.optString("id")
        if (id.isBlank() || id.length != 11) return null
        val duration = e.optInt("duration", -1)
        if (duration > MAX_SHORT_SECONDS) return null
        // Самый надёжный признак: YouTube отдаёт шортсы ссылкой вида
        // youtube.com/shorts/ID. Длительности в выдаче может не быть вовсе.
        val shortsUrl = e.optString("url").contains("/shorts/") ||
            e.optString("webpage_url").contains("/shorts/")
        return Candidate(
            id = id,
            title = e.optString("title").ifBlank { id },
            channel = e.optString("channel").ifBlank { e.optString("uploader") },
            duration = if (duration > 0) duration else 0,
            views = e.optLong("view_count"),
            likes = 0,
            commentCount = 0,
            thumbUrl = firstThumb(e),
            url = e.optString("url").ifBlank { url(id) },
            fromShortsFeed = fromShorts || shortsUrl,
        )
    }

    /** Один живой запрос к YouTube — чтобы видеть ответ, а не догадки. */
    suspend fun diagnose(context: Context): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        report.append(if (ffmpegReady) "ffmpeg: готов\n" else "ffmpeg: НЕ поднялся\n")
        runCatching {
            report.append("Версия yt-dlp: ").append(version(context) ?: "неизвестна").append("\n")
        }.onFailure { report.append("Версия yt-dlp: не определилась\n") }

        report.append("\n")
        runCatching {
            val json = flat("https://www.youtube.com/hashtag/shorts", null, 3)
            val n = json.optJSONArray("entries")?.length() ?: 0
            report.append("Хэштег #shorts: получено роликов ").append(n)
            if (n > 0) {
                report.append("\nПервый: ")
                    .append(json.optJSONArray("entries")?.optJSONObject(0)?.optString("title"))
            }
        }.onFailure {
            report.append("Хэштег #shorts не открылся.\n").append(lastError ?: it.message)
        }
        report.toString()
    }

    /**
     * Список роликов из ленты. Личные ленты отдают только id/title/duration,
     * поля канала в них нет — поэтому шортсы отбираем прямо по длительности,
     * а вкладку /shorts используем лишь там, где канал известен.
     */
    suspend fun feed(
        feed: Feed,
        limit: Int,
        cookies: File?,
        exclude: Set<String>,
        customTarget: String = "",
    ): List<Candidate> =
        withContext(Dispatchers.IO) {
            val target = when (feed) {
                Feed.CUSTOM -> customTarget.trim()
                else -> feed.target
            }
            require(target.isNotBlank()) { "Впиши ссылку на канал, плейлист или хэштег." }

            // В ленте шортсы разбавлены длинными роликами, поэтому берём с запасом.
            val depth = if (feed.needsLogin) minOf(limit * 20, 300) else limit * 6
            val root = flat(target, cookies, depth)
            val entries = root.optJSONArray("entries")
            if (entries == null || entries.length() == 0) {
                error(
                    "yt-dlp открыл \"" + root.optString("title").ifBlank { target } + "\", " +
                        "но роликов там нет (тип: " + root.optString("_type").ifBlank { "?" } + ")."
                )
            }

            // Хэштег #shorts и вкладка /shorts отдают только вертикальные ролики —
            // такие источники можно не перепроверять. Личные ленты перемешаны
            // с обычными видео, там без запроса не отличить.
            val guaranteed = feed == Feed.CUSTOM && target.contains("/shorts")

            val direct = mutableListOf<Candidate>()
            var longOnes = 0
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val seconds = e.optInt("duration", -1)
                if (seconds > MAX_SHORT_SECONDS) { longOnes++; continue }
                val c = entryToCandidate(e, guaranteed) ?: continue
                if (c.id !in exclude && direct.none { it.id == c.id }) direct.add(c)
            }

            if (direct.isEmpty()) {
                error(
                    "В ленте ${entries.length()} роликов, из них длиннее трёх минут $longOnes. " +
                        "Коротких нет — дай ссылку вида youtube.com/@канал/shorts."
                )
            }
            direct.shuffled()
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
    /**
     * Прикидка веса по длительности: шортсы YouTube отдаёт примерно на 2 Мбит/с.
     * Ошибка обычно в пределах трети, зато не нужен запрос на каждый ролик —
     * а именно эти запросы и делали поиск таким долгим.
     */
    fun estimateSize(durationSeconds: Int): Long =
        (durationSeconds.coerceAtLeast(1) * 250_000L)

    suspend fun probe(link: String): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            // Без куков: ролики публичные, а с куками YouTube отдаёт
            // "The page needs to be reloaded" (баг yt-dlp #17389).
            val req = request(link, null).apply {
                addOption("-f", format())
                addOption("--dump-single-json")
                addOption("--skip-download")
            }
            val json = parse(run(req))

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
    /** Разбор комментариев из готового JSON — общий для обоих путей. */
    private fun commentsFrom(root: JSONObject, maxTop: Int, maxReplies: Int): List<Comment> {
        val arr = root.optJSONArray("comments") ?: return emptyList()
        val tops = LinkedHashMap<String, MutableList<Reply>>()
        val meta = LinkedHashMap<String, Comment>()

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cid = c.optString("id")
            if (cid.isBlank()) continue
            val author = c.optString("author").removePrefix("@")
            val text = c.optString("text")
            val likes = c.optLong("like_count")
            val parent = c.optString("parent")

            if (parent.isBlank() || parent == "root") {
                meta[cid] = Comment(author, text, likes, 0, emptyList())
                tops.getOrPut(cid) { mutableListOf() }
            } else {
                val root2 = parent.substringBefore('.')
                tops.getOrPut(root2) { mutableListOf() }.add(Reply(author, text, likes))
            }
        }

        return meta.entries
            .map { (cid, c) ->
                val replies = tops[cid].orEmpty()
                c.copy(
                    replyCount = replies.size,
                    replies = replies.sortedByDescending { it.likes }.take(maxReplies),
                )
            }
            .sortedByDescending { it.likes }
            .take(maxTop)
    }

    private fun commentArgs(maxTop: Int, maxReplies: Int) =
        "youtube:comment_sort=top;max_comments=all,$maxTop,all,$maxReplies"

    class LinkInfo(
        val id: String,
        val title: String,
        val channel: String,
        val duration: Int,
        val size: Long,
        val isShort: Boolean,
    )

    /** Разбор одиночной ссылки: что это за видео и сколько весит. */
    suspend fun linkInfo(link: String): LinkInfo = withContext(Dispatchers.IO) {
        val req = request(link.trim(), null).apply {
            addOption("-f", format())
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
        }
        val json = parse(run(req))
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
        val duration = json.optInt("duration")
        LinkInfo(
            id = json.optString("id"),
            title = json.optString("title").ifBlank { "Без названия" },
            channel = json.optString("channel").ifBlank { json.optString("uploader") },
            duration = duration,
            size = size,
            isShort = duration in 1..MAX_SHORT_SECONDS,
        )
    }

    /** Скачивание одиночного видео: без комментариев и без ограничений по длине. */
    suspend fun downloadPlain(
        link: String,
        dir: File,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val req = request(link.trim(), null).apply {
            addOption("-f", format())
            addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--concurrent-fragments", "4")
            if (ffmpegReady) addOption("--merge-output-format", "mp4")
        }
        YoutubeDL.getInstance().execute(req, "solo") { progress, _, _ ->
            onProgress((progress / 100f).coerceIn(0f, 1f))
        }
        dir.listFiles { f ->
            !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") &&
                !f.name.endsWith(".json") && !f.name.endsWith(".jpg")
        }?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("yt-dlp отработал, но файла нет.")
    }

    class Downloaded(
        val file: File,
        val comments: List<Comment>,
        val title: String,
        val channel: String,
        val duration: Int,
        val likes: Long,
        val views: Long,
        val commentCount: Long,
        val thumbUrl: String?,
    )

    /**
     * Скачивание и комментарии одним запуском yt-dlp.
     * Раньше это были два отдельных процесса: каждый заново поднимал питон
     * и заново разбирал страницу ролика. Один запуск экономит примерно половину
     * времени на каждом видео.
     */
    suspend fun download(
        id: String,
        link: String,
        dir: File,
        maxTop: Int,
        maxReplies: Int,
        requireVertical: Boolean,
        maxSeconds: Int,
        onProgress: (Float) -> Unit,
    ): Downloaded = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val req = request(link, null).apply {
            addOption("-f", format())
            addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
            addOption("--no-mtime")
            addOption("--concurrent-fragments", "4")
            addOption("--retries", "2")
            // Последний рубеж: даже если длинный ролик проскочил фильтры,
            // yt-dlp откажется его качать.
            // Для роликов из твоей ленты это заведомо шортсы, и требовать
            // размеры кадра нельзя: yt-dlp их не всегда знает, а тогда фильтр
            // отбрасывал вообще всё.
            addOption(
                "--match-filter",
                if (requireVertical) "duration <= $maxSeconds & height > width"
                else "duration <= $maxSeconds",
            )
            if (ffmpegReady) addOption("--merge-output-format", "mp4")
            addOption("--write-info-json")
            if (maxTop > 0) {
                addOption("--write-comments")
                addOption("--extractor-args", commentArgs(maxTop, maxReplies))
            }
        }
        YoutubeDL.getInstance().execute(req, id) { progress, _, _ ->
            onProgress((progress / 100f).coerceIn(0f, 1f))
        }

        val video = dir.listFiles { f ->
            f.name.startsWith("$id.") &&
                !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") &&
                !f.name.endsWith(".json") && !f.name.endsWith(".jpg")
        }?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp отработал, но файла нет.")

        // yt-dlp кладёт в info.json и комментарии, и все метаданные —
        // значит отдельный запрос за названием и лайками не нужен.
        val info = File(dir, "$id.info.json")
        var comments = emptyList<Comment>()
        var title = ""
        var channel = ""
        var duration = 0
        var likes = 0L
        var views = 0L
        var commentCount = 0L
        var thumb: String? = null

        if (info.exists()) {
            runCatching {
                val json = JSONObject(info.readText())
                if (maxTop > 0) comments = commentsFrom(json, maxTop, maxReplies)
                title = json.optString("title")
                channel = json.optString("channel").ifBlank { json.optString("uploader") }
                duration = json.optInt("duration")
                likes = json.optLong("like_count")
                views = json.optLong("view_count")
                commentCount = json.optLong("comment_count")
                thumb = json.optString("thumbnail").ifBlank { null }
            }
            info.delete()
        }

        Downloaded(video, comments, title, channel, duration, likes, views, commentCount, thumb)
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
