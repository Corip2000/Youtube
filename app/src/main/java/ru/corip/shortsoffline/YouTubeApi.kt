package ru.corip.shortsoffline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ApiError(message: String) : Exception(message)

/**
 * Доступ к YouTube Data API v3.
 * Работает либо по API-ключу (публичные данные), либо по OAuth-токену
 * (то же самое + твои подписки).
 */
class YouTubeApi(
    private val apiKey: () -> String,
    private val accessToken: () -> String?,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val MAX_SHORT_SECONDS = 180

        private val SEEDS = listOf(
            "#shorts", "#shorts прикол", "#shorts мемы", "shorts funny", "shorts satisfying",
            "shorts cooking", "shorts животные", "shorts котики", "shorts diy", "shorts music",
            "shorts гейминг", "shorts minecraft", "shorts speedrun", "shorts asmr",
            "shorts лайфхак", "shorts art", "shorts dance", "shorts football", "shorts science",
            "shorts анимация", "shorts фокус", "shorts nature", "shorts cars", "shorts history",
        )
        private val ORDERS = listOf("relevance", "date", "viewCount", "rating")
        private val REGIONS = listOf("RU", "US", "GB", "DE", "BR", "JP", "KR", "IN")
        private val DAY_WINDOWS = listOf(3, 14, 60, 365, 1500)

        private val ISO = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")

        fun parseDuration(text: String?): Int {
            val m = ISO.matchEntire(text ?: "") ?: return 0
            val (h, mi, s) = m.destructured
            return (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        }
    }

    fun hasCredentials(): Boolean = accessToken() != null || apiKey().isNotBlank()

    // ---------------------------------------------------------- транспорт

    private suspend fun get(path: String, params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val token = accessToken()
            val builder = "https://www.googleapis.com/youtube/v3/$path".toHttpUrl().newBuilder()
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            if (token == null) {
                val key = apiKey()
                if (key.isBlank()) throw ApiError("Нужен API-ключ или вход в Google.")
                builder.addQueryParameter("key", key)
            }

            val request = Request.Builder().url(builder.build()).apply {
                if (token != null) header("Authorization", "Bearer $token")
            }.build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiError(readableError(response.code, body))
                JSONObject(body)
            }
        }

    private fun readableError(code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).getJSONObject("error").getJSONArray("errors")
                .getJSONObject(0).optString("reason")
        }.getOrDefault("")
        return when {
            reason == "quotaExceeded" -> "Дневная квота YouTube API кончилась. Сбрасывается в 10:00 МСК."
            reason == "commentsDisabled" -> "Комментарии отключены."
            reason == "keyInvalid" || code == 400 -> "API-ключ не принят. Проверь его в настройках."
            code == 403 -> "Доступ закрыт: включи YouTube Data API v3 в своём проекте Google Cloud."
            code == 401 -> "Токен протух — войди в Google заново."
            else -> "Ошибка YouTube API ($code)."
        }
    }

    // ---------------------------------------------------------- кандидаты

    /** Случайные короткие видео. Настоящего рандома у API нет — мешаем параметры. */
    suspend fun randomCandidates(wanted: Int, exclude: Set<String>): List<String> {
        val out = LinkedHashSet<String>()
        var attempts = 0
        while (out.size < wanted * 3 && attempts < 5) {
            attempts++
            val after = System.currentTimeMillis() - DAY_WINDOWS.random() * 86_400_000L
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(after))

            val res = get("search", mapOf(
                "part" to "id",
                "q" to SEEDS.random(),
                "type" to "video",
                "videoDuration" to "short",
                "order" to ORDERS.random(),
                "regionCode" to REGIONS.random(),
                "publishedAfter" to stamp,
                "maxResults" to "50",
            ))
            val items = res.optJSONArray("items") ?: continue
            val page = (0 until items.length()).mapNotNull {
                items.getJSONObject(it).optJSONObject("id")?.optString("videoId")?.ifBlank { null }
            }.shuffled(Random.Default)
            page.forEach { if (it !in exclude) out.add(it) }
        }
        return out.toList()
    }

    /** Свежие короткие видео с каналов, на которые ты подписан. Дешевле по квоте. */
    suspend fun subscriptionCandidates(wanted: Int, exclude: Set<String>): List<String> {
        val token = accessToken() ?: throw ApiError("Для подписок нужен вход в Google.")
        val channels = mutableListOf<String>()
        var page: String? = null
        do {
            val params = mutableMapOf(
                "part" to "snippet", "mine" to "true", "maxResults" to "50"
            )
            page?.let { params["pageToken"] = it }
            val res = get("subscriptions", params)
            val items = res.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                items.getJSONObject(i).optJSONObject("snippet")
                    ?.optJSONObject("resourceId")?.optString("channelId")
                    ?.ifBlank { null }?.let(channels::add)
            }
            page = res.optString("nextPageToken").ifBlank { null }
        } while (page != null && channels.size < 200)

        if (channels.isEmpty()) throw ApiError("На этом аккаунте нет подписок — возьми режим «Случайные».")

        val uploads = mutableListOf<String>()
        channels.shuffled().take(50).chunked(50).forEach { batch ->
            val res = get("channels", mapOf(
                "part" to "contentDetails", "id" to batch.joinToString(","), "maxResults" to "50"
            ))
            val items = res.optJSONArray("items") ?: return@forEach
            for (i in 0 until items.length()) {
                items.getJSONObject(i).optJSONObject("contentDetails")
                    ?.optJSONObject("relatedPlaylists")?.optString("uploads")
                    ?.ifBlank { null }?.let(uploads::add)
            }
        }

        val videos = LinkedHashSet<String>()
        for (playlist in uploads.shuffled()) {
            if (videos.size >= wanted * 4) break
            runCatching {
                val res = get("playlistItems", mapOf(
                    "part" to "contentDetails", "playlistId" to playlist, "maxResults" to "25"
                ))
                val items = res.optJSONArray("items") ?: return@runCatching
                for (i in 0 until items.length()) {
                    val vid = items.getJSONObject(i).optJSONObject("contentDetails")?.optString("videoId")
                    if (!vid.isNullOrBlank() && vid !in exclude) videos.add(vid)
                }
            }
        }
        return videos.shuffled().take(wanted * 4)
    }

    /** Детали видео. Отсеивает всё длиннее трёх минут. */
    suspend fun details(ids: List<String>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        ids.chunked(50).forEach { batch ->
            val res = get("videos", mapOf(
                "part" to "snippet,contentDetails,statistics",
                "id" to batch.joinToString(","),
                "maxResults" to "50",
            ))
            val items = res.optJSONArray("items") ?: return@forEach
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val seconds = parseDuration(o.optJSONObject("contentDetails")?.optString("duration"))
                if (seconds == 0 || seconds > MAX_SHORT_SECONDS) continue
                val snippet = o.optJSONObject("snippet") ?: continue
                val stats = o.optJSONObject("statistics") ?: JSONObject()
                val thumbs = snippet.optJSONObject("thumbnails")
                val thumb = listOf("high", "medium", "default")
                    .firstNotNullOfOrNull { thumbs?.optJSONObject(it)?.optString("url")?.ifBlank { null } }
                out.add(
                    Candidate(
                        id = o.getString("id"),
                        title = snippet.optString("title"),
                        channel = snippet.optString("channelTitle"),
                        duration = seconds,
                        views = stats.optString("viewCount").toLongOrNull() ?: 0,
                        likes = stats.optString("likeCount").toLongOrNull() ?: 0,
                        commentCount = stats.optString("commentCount").toLongOrNull() ?: 0,
                        thumbUrl = thumb,
                    )
                )
            }
        }
        return out
    }

    /** Название канала вошедшего пользователя. */
    suspend fun me(): String? = runCatching {
        val res = get("channels", mapOf("part" to "snippet", "mine" to "true"))
        val items = res.optJSONArray("items") ?: return null
        if (items.length() == 0) return "Google-аккаунт"
        items.getJSONObject(0).optJSONObject("snippet")?.optString("title")
    }.getOrNull()

    // ---------------------------------------------------------- комментарии

    /**
     * Топ-100 комментариев по лайкам. API отдаёт максимум 5 ответов инлайном,
     * поэтому у [expandTop] самых залайканных дотягиваем до [maxReplies]
     * отдельными запросами — иначе квота улетит на одном видео.
     */
    suspend fun comments(
        videoId: String,
        maxTop: Int = 100,
        maxReplies: Int = 10,
        expandTop: Int = 25,
    ): List<Comment> {
        val res = runCatching {
            get("commentThreads", mapOf(
                "part" to "snippet,replies",
                "videoId" to videoId,
                "order" to "relevance",
                "maxResults" to "100",
                "textFormat" to "plainText",
            ))
        }.getOrElse { return emptyList() }

        val items = res.optJSONArray("items") ?: return emptyList()
        val threads = mutableListOf<Pair<String, Comment>>()
        for (i in 0 until items.length()) {
            val thread = items.getJSONObject(i)
            val snippet = thread.optJSONObject("snippet") ?: continue
            val top = snippet.optJSONObject("topLevelComment")?.optJSONObject("snippet") ?: continue
            val replyArr = thread.optJSONObject("replies")?.optJSONArray("comments")
            val replies = buildList {
                if (replyArr != null) for (j in 0 until replyArr.length()) {
                    val s = replyArr.getJSONObject(j).optJSONObject("snippet") ?: continue
                    add(Reply(s.optString("authorDisplayName"), commentText(s), s.optLong("likeCount")))
                }
            }
            threads.add(
                thread.optString("id") to Comment(
                    author = top.optString("authorDisplayName"),
                    text = commentText(top),
                    likes = top.optLong("likeCount"),
                    replyCount = snippet.optInt("totalReplyCount"),
                    replies = replies,
                )
            )
        }

        val sorted = threads.sortedByDescending { it.second.likes }.take(maxTop).toMutableList()

        for (i in 0 until minOf(expandTop, sorted.size)) {
            val (threadId, comment) = sorted[i]
            if (comment.replyCount <= comment.replies.size || comment.replies.size >= maxReplies) continue
            runCatching {
                val sub = get("comments", mapOf(
                    "part" to "snippet",
                    "parentId" to threadId,
                    "maxResults" to maxReplies.toString(),
                    "textFormat" to "plainText",
                ))
                val arr = sub.optJSONArray("items") ?: return@runCatching
                val fetched = (0 until arr.length()).mapNotNull { j ->
                    val s = arr.getJSONObject(j).optJSONObject("snippet") ?: return@mapNotNull null
                    Reply(s.optString("authorDisplayName"), commentText(s), s.optLong("likeCount"))
                }
                sorted[i] = threadId to comment.copy(replies = fetched)
            }
        }

        return sorted.map { (_, c) ->
            c.copy(replies = c.replies.sortedByDescending { it.likes }.take(maxReplies))
        }
    }

    private fun commentText(snippet: JSONObject): String =
        snippet.optString("textDisplay").ifBlank { snippet.optString("textOriginal") }
}
