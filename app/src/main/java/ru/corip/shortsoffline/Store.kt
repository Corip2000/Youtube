package ru.corip.shortsoffline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------- модели

data class Reply(val author: String, val text: String, val likes: Long)

data class Comment(
    val author: String,
    val text: String,
    val likes: Long,
    val replyCount: Int,
    val replies: List<Reply>,
)

/** Метаданные видео до скачивания. */
data class Candidate(
    val id: String,
    val title: String,
    val channel: String,
    val duration: Int,
    val views: Long,
    val likes: Long,
    val commentCount: Long,
    val thumbUrl: String?,
    /** Полный адрес страницы ролика: у YouTube и TikTok он разный. */
    val url: String = "",
    /** Откуда взят ролик: YOUTUBE или TIKTOK. Библиотеки не смешиваются. */
    val platform: String = "YOUTUBE",
    /** Взят из шортс-ленты — значит это точно вертикальный ролик. */
    val fromShortsFeed: Boolean = false,
    var size: Long = 0,
    var width: Int = 0,
    var height: Int = 0,
)

/** Уже лежит на диске. */
data class Saved(
    val id: String,
    val title: String,
    val channel: String,
    val duration: Int,
    val views: Long,
    val likes: Long,
    val commentCount: Long,
    val savedComments: Int,
    val platform: String,
    val file: String,
    val thumb: String?,
    val meta: String,
    val bytes: Long,
    val addedAt: Long,
)

data class Lifetime(val downloaded: Int, val deleted: Int, val freed: Long)

// ---------------------------------------------------------------- хранилище

class Store(context: Context) {

    private val app = context.applicationContext
    val videosDir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "videos").apply { mkdirs() }
    private val indexFile = File(app.filesDir, "index.json")
    private val statsFile = File(app.filesDir, "stats.json")
    private val prefs = app.getSharedPreferences("shorts", Context.MODE_PRIVATE)

    private val index = linkedMapOf<String, Saved>()

    init {
        readIndex()
    }

    // ---------- настройки ----------

    /** Последняя выбранная лента, чтобы не переключать её каждый запуск. */
    /** Сколько комментариев тянуть: 100, 30 или 0 (не тянуть вовсе). */
    /** Быстрый поиск: вес прикидываем по длительности, не опрашивая каждый ролик. */
    var fastSize: Boolean
        get() = prefs.getBoolean("fast_size", true)
        set(v) = prefs.edit().putBoolean("fast_size", v).apply()

    var commentDepth: Int
        get() = prefs.getInt("comment_depth", 30)
        set(v) = prefs.edit().putInt("comment_depth", v).apply()

    /** Выбранная площадка: YOUTUBE или TIKTOK. */
    /** Вид сайта для площадки: настольный или мобильный. */
    fun desktopView(platform: String, fallback: Boolean): Boolean =
        prefs.getBoolean("desktop_$platform", fallback)

    fun setDesktopView(platform: String, value: Boolean) =
        prefs.edit().putBoolean("desktop_$platform", value).apply()

    var platform: String
        get() = prefs.getString("platform", "YOUTUBE") ?: "YOUTUBE"
        set(v) = prefs.edit().putString("platform", v).apply()

    var customTarget: String
        get() = prefs.getString("custom_target", "") ?: ""
        set(v) = prefs.edit().putString("custom_target", v.trim()).apply()

    var lastFeed: String
        get() = prefs.getString("last_feed", "RECOMMENDED") ?: "RECOMMENDED"
        set(v) = prefs.edit().putString("last_feed", v).apply()

    // ---------- индекс ----------

    private fun readIndex() {
        index.clear()
        if (!indexFile.exists()) return
        runCatching {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entry = Saved(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    channel = o.optString("channel"),
                    duration = o.optInt("duration"),
                    views = o.optLong("views"),
                    likes = o.optLong("likes"),
                    commentCount = o.optLong("commentCount"),
                    savedComments = o.optInt("savedComments"),
                    platform = o.optString("platform").ifBlank { "YOUTUBE" },
                    file = o.getString("file"),
                    thumb = o.optString("thumb").ifBlank { null },
                    meta = o.getString("meta"),
                    bytes = o.optLong("bytes"),
                    addedAt = o.optLong("addedAt"),
                )
                if (File(entry.file).exists()) index[entry.id] = entry
            }
        }
    }

    private fun writeIndex() {
        val arr = JSONArray()
        index.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("title", e.title); put("channel", e.channel)
                put("duration", e.duration); put("views", e.views); put("likes", e.likes)
                put("commentCount", e.commentCount); put("savedComments", e.savedComments)
                put("platform", e.platform)
                put("file", e.file); put("thumb", e.thumb ?: ""); put("meta", e.meta)
                put("bytes", e.bytes); put("addedAt", e.addedAt)
            })
        }
        indexFile.writeText(arr.toString())
    }

    fun all(): List<Saved> = index.values.sortedBy { it.addedAt }

    /** Скачанное с одной площадки: ленты не перемешиваются. */
    fun all(platform: String): List<Saved> =
        index.values.filter { it.platform == platform }.sortedBy { it.addedAt }

    fun count(platform: String): Int = index.values.count { it.platform == platform }

    fun bytes(platform: String): Long =
        index.values.filter { it.platform == platform }.sumOf { it.bytes }
    fun ids(): Set<String> = index.keys.toSet()
    fun count(): Int = index.size
    fun totalBytes(): Long = index.values.sumOf { it.bytes }

    fun freeSpace(): Long = videosDir.usableSpace

    // ---------- запись ----------

    fun save(candidate: Candidate, videoFile: File, thumbFile: File?, comments: List<Comment>): Saved {
        val metaFile = File(videosDir, "${candidate.id}.json")
        metaFile.writeText(commentsToJson(comments).toString())

        var bytes = videoFile.length() + metaFile.length()
        thumbFile?.let { if (it.exists()) bytes += it.length() }

        val entry = Saved(
            id = candidate.id,
            title = candidate.title,
            channel = candidate.channel,
            duration = candidate.duration,
            views = candidate.views,
            likes = candidate.likes,
            commentCount = candidate.commentCount,
            savedComments = comments.size,
            platform = video.platform,
            file = videoFile.absolutePath,
            thumb = thumbFile?.absolutePath,
            meta = metaFile.absolutePath,
            bytes = bytes,
            addedAt = System.currentTimeMillis(),
        )
        index[entry.id] = entry
        writeIndex()
        bumpStats(downloaded = 1)
        return entry
    }

    /** Удаляет видео с диска. Возвращает сколько байт освободилось. */
    fun delete(id: String): Long {
        val entry = index.remove(id) ?: return 0
        var freed = 0L
        listOfNotNull(entry.file, entry.thumb, entry.meta).forEach { path ->
            val f = File(path)
            if (f.exists()) {
                freed += f.length()
                f.delete()
            }
        }
        writeIndex()
        bumpStats(deleted = 1, freed = freed)
        return freed
    }

    fun clear(): Long = all().sumOf { delete(it.id) }

    fun comments(id: String): List<Comment> {
        val entry = index[id] ?: return emptyList()
        val f = File(entry.meta)
        if (!f.exists()) return emptyList()
        return runCatching { commentsFromJson(JSONObject(f.readText())) }.getOrDefault(emptyList())
    }

    // ---------- статистика за всё время ----------

    fun lifetime(): Lifetime {
        if (!statsFile.exists()) return Lifetime(0, 0, 0)
        return runCatching {
            val o = JSONObject(statsFile.readText())
            Lifetime(o.optInt("downloaded"), o.optInt("deleted"), o.optLong("freed"))
        }.getOrDefault(Lifetime(0, 0, 0))
    }

    private fun bumpStats(downloaded: Int = 0, deleted: Int = 0, freed: Long = 0) {
        val cur = lifetime()
        statsFile.writeText(
            JSONObject().apply {
                put("downloaded", cur.downloaded + downloaded)
                put("deleted", cur.deleted + deleted)
                put("freed", cur.freed + freed)
            }.toString()
        )
    }

    // ---------- сериализация комментариев ----------

    private fun commentsToJson(items: List<Comment>): JSONObject {
        val arr = JSONArray()
        items.forEach { c ->
            val replies = JSONArray()
            c.replies.forEach { r ->
                replies.put(JSONObject().apply {
                    put("a", r.author); put("t", r.text); put("l", r.likes)
                })
            }
            arr.put(JSONObject().apply {
                put("a", c.author); put("t", c.text); put("l", c.likes)
                put("rc", c.replyCount); put("r", replies)
            })
        }
        return JSONObject().put("items", arr)
    }

    private fun commentsFromJson(root: JSONObject): List<Comment> {
        val arr = root.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val rArr = o.optJSONArray("r") ?: JSONArray()
            Comment(
                author = o.optString("a"),
                text = o.optString("t"),
                likes = o.optLong("l"),
                replyCount = o.optInt("rc"),
                replies = (0 until rArr.length()).map { j ->
                    val r = rArr.getJSONObject(j)
                    Reply(r.optString("a"), r.optString("t"), r.optLong("l"))
                }
            )
        }
    }
}

// ---------------------------------------------------------------- форматирование

fun formatBytes(n: Long): String {
    if (n <= 0) return "0 Б"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = n.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024; i++
    }
    return if (value >= 100 || i == 0) "${value.toInt()} ${units[i]}"
    else String.format("%.1f %s", value, units[i])
}

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:" + s.toString().padStart(2, '0')
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}
