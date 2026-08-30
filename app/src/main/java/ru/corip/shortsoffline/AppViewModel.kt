package ru.corip.shortsoffline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { MENU, FEED, DOWNLOAD, PLAYER }
enum class JobState { IDLE, SEARCHING, READY, DOWNLOADING, DONE, FAILED }

data class UiState(
    val screen: Screen = Screen.MENU,


    val savedCount: Int = 0,
    val savedBytes: Long = 0,
    val freeSpace: Long = 0,
    val lifetime: Lifetime = Lifetime(0, 0, 0),

    val sessionDeleted: Int = 0,
    val sessionFreed: Long = 0,
    val showReceipt: Boolean = false,

    val feed: YtDlp.Feed = YtDlp.Feed.HASHTAG,
    val count: Int = 10,
    val jobState: JobState = JobState.IDLE,
    val jobMessage: String = "",
    val found: List<Candidate> = emptyList(),
    val foundBytes: Long = 0,
    val checked: Int = 0,
    val toCheck: Int = 0,
    val downloaded: Int = 0,
    val downloadedBytes: Long = 0,
    val currentTitle: String? = null,
    val currentId: String? = null,
    val currentProgress: Float = 0f,
    val skipped: Int = 0,
    val downloadError: String? = null,

    val queue: List<Saved> = emptyList(),
    val index: Int = 0,
    val comments: List<Comment> = emptyList(),
    val commentsOpen: Boolean = false,

    val customUrl: String = "",
    val commentDepth: Int = 30,
    val fastSize: Boolean = true,
    val running: Int = 0,
    val collected: List<String> = emptyList(),
    val diagnostics: String? = null,
    val ytdlpVersion: String? = null,
    val toast: String? = null,
) {
    val current: Saved? get() = queue.getOrNull(index)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val store = Store(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var jobHandle: Job? = null
    private val watched = mutableSetOf<String>()

    init {
        val saved = runCatching { YtDlp.Feed.valueOf(store.lastFeed) }.getOrDefault(YtDlp.Feed.HASHTAG)
        _state.update {
            it.copy(
                feed = saved,
                customUrl = store.customTarget,
                commentDepth = store.commentDepth,
                fastSize = store.fastSize,
            )
        }
        refreshStorage()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { YtDlp.init(getApplication()) } }
            val v = YtDlp.version(getApplication())
            _state.update { it.copy(ytdlpVersion = v) }
            // Версия неизвестна — бинарник ни разу не обновлялся. Тянем сразу,
            // иначе YouTube не отдаст ни одного ролика.
            if (v == null) {
                _state.update { it.copy(toast = "Обновляю yt-dlp при первом запуске…") }
                YtDlp.update(getApplication())
                _state.update { it.copy(ytdlpVersion = YtDlp.version(getApplication())) }
            }
        }
    }

    // ------------------------------------------------------------ моя лента

    fun openFeed() = _state.update {
        it.copy(screen = Screen.FEED, collected = emptyList())
    }

    fun collectShort(id: String) = _state.update {
        if (id in it.collected) it else it.copy(collected = it.collected + id)
    }

    /**
     * Собранное из живой ленты сразу готово к загрузке: это шортсы по
     * определению, проверять нечего. Скачивание запускается само.
     */
    fun useCollected() {
        val ids = _state.value.collected.filter { it !in store.ids() }
        if (ids.isEmpty()) {
            _state.update { it.copy(screen = Screen.MENU, toast = "Всё это уже скачано.") }
            return
        }
        val found = ids.take(_state.value.count).map { id ->
            Candidate(
                id = id, title = "Шортс", channel = "", duration = 0,
                views = 0, likes = 0, commentCount = 0, thumbUrl = null,
                fromShortsFeed = true,
            ).also { it.size = YtDlp.estimateSize(30) }
        }
        _state.update {
            it.copy(
                screen = Screen.DOWNLOAD,
                found = found,
                foundBytes = found.sumOf { c -> c.size },
                jobState = JobState.READY,
                jobMessage = "Из твоей ленты: ${found.size}",
                downloaded = 0, downloadedBytes = 0, skipped = 0, downloadError = null,
            )
        }
        startDownload()
    }

    fun updateYtdlp() = viewModelScope.launch {
        _state.update { it.copy(diagnostics = "Обновляю yt-dlp, это займёт до минуты…") }
        val report = YtDlp.update(getApplication())
        _state.update { it.copy(diagnostics = report, ytdlpVersion = YtDlp.version(getApplication())) }
    }

    fun dismissToast() = _state.update { it.copy(toast = null) }

    // ------------------------------------------------------------ навигация

    fun go(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun setFeed(feed: YtDlp.Feed) {
        store.lastFeed = feed.name
        _state.update { it.copy(feed = feed) }
    }

    fun setCustomUrl(value: String) {
        store.customTarget = value
        _state.update { it.copy(customUrl = value) }
    }

    fun setFastSize(value: Boolean) {
        store.fastSize = value
        _state.update { it.copy(fastSize = value) }
    }

    fun setCommentDepth(value: Int) {
        store.commentDepth = value
        _state.update { it.copy(commentDepth = value) }
    }

    fun runDiagnostics() = viewModelScope.launch {
        _state.update { it.copy(diagnostics = "Проверяю…") }
        val report = YtDlp.diagnose(getApplication(), cookies())
        _state.update { it.copy(diagnostics = report) }
    }

    fun dismissDiagnostics() = _state.update { it.copy(diagnostics = null) }

    fun setCount(count: Int) = _state.update { it.copy(count = count.coerceIn(1, 50)) }

    private fun refreshStorage() = _state.update {
        it.copy(
            savedCount = store.count(),
            savedBytes = store.totalBytes(),
            freeSpace = store.freeSpace(),
            lifetime = store.lifetime(),
        )
    }

    // ------------------------------------------------------------ поиск

    fun find() {
        val feed = _state.value.feed
        jobHandle?.cancel()
        _state.update {
            it.copy(
                jobState = JobState.SEARCHING, jobMessage = "Читаю ленту «${feed.title}»…",
                found = emptyList(), foundBytes = 0, checked = 0, toCheck = 0,
                downloaded = 0, downloadedBytes = 0, skipped = 0, currentTitle = null,
                downloadError = null,
            )
        }
        jobHandle = viewModelScope.launch {
            runCatching {
                val wanted = _state.value.count
                val pool = YtDlp.feed(feed, wanted, null, store.ids(), _state.value.customUrl)
                if (pool.isEmpty()) {
                    error(
                        if (feed.needsLogin)
                            "Лента пустая — обычно это значит, что сессия YouTube не подхватилась. Проверь вход."
                        else "Лента пустая. Попробуй ещё раз."
                    )
                }

                if (_state.value.fastSize) {
                    // Доверяем без проверки только шортсовым источникам: хэштегу
                    // и вкладке /shorts. Короткая длительность ничего не доказывает —
                    // горизонтальный ролик тоже бывает на сорок секунд.
                    val sure = pool.filter { it.fromShortsFeed }

                    // Остальное всё равно придётся запросить: соотношение сторон
                    // в ленте не приходит, узнать его можно только у самого ролика.
                    val quick = if (sure.size >= wanted) {
                        sure.take(wanted)
                    } else {
                        val unclear = pool.filter { it !in sure }.take((wanted - sure.size) * 3)
                        val checked = mutableListOf<Candidate>()
                        for (chunk in unclear.chunked(4)) {
                            if (sure.size + checked.size >= wanted) break
                            val part = coroutineScope {
                                chunk.map { c -> async(Dispatchers.IO) { c to YtDlp.probe(c.id) } }
                                    .awaitAll()
                            }
                            for ((c, pr) in part) {
                                if (pr != null && pr.isShort && pr.isVertical && pr.size > 0) {
                                    c.size = pr.size
                                    checked.add(c)
                                }
                            }
                            _state.update { it.copy(checked = it.checked + chunk.size) }
                        }
                        (sure + checked).take(wanted)
                    }

                    if (quick.isEmpty()) {
                        error(
                            "В ленте ${pool.size} записей, вертикальных среди них нет. " +
                                "Личные ленты состоят в основном из обычных видео — " +
                                "для потока шортсов возьми ленту #shorts или ссылку " +
                                "youtube.com/@канал/shorts."
                        )
                    }
                    // Вес: у досмотренных он уже точный, остальным ставим оценку.
                    quick.forEach { c ->
                        if (c.size <= 0) c.size = YtDlp.estimateSize(c.duration)
                    }
                    _state.update {
                        it.copy(
                            found = quick,
                            foundBytes = quick.sumOf { c -> c.size },
                            jobState = JobState.READY,
                            jobMessage = "Нашёл ${quick.size} шортсов · вес примерный",
                        )
                    }
                    return@runCatching
                }

                _state.update { it.copy(jobMessage = "Считаю вес…", toCheck = pool.size) }

                val picked = mutableListOf<Candidate>()
                var noProbe = 0
                var notShort = 0
                var notVertical = 0

                // Вес считаем пачками по четыре: каждый запрос — отдельный запуск
                // yt-dlp с ожиданием сети, последовательно это тянулось минутами.
                val probed = mutableListOf<Pair<Candidate, YtDlp.Probe?>>()
                for (chunk in pool.chunked(4)) {
                    if (probed.count { it.second != null } >= wanted * 2) break
                    val part = coroutineScope {
                        chunk.map { c -> async(Dispatchers.IO) { c to YtDlp.probe(c.id) } }.awaitAll()
                    }
                    probed.addAll(part)
                    _state.update { it.copy(checked = it.checked + chunk.size) }
                }

                for ((candidate, probe) in probed) {
                    if (picked.size >= wanted) break
                    if (probe == null || probe.size <= 0) { noProbe++; continue }
                    if (!probe.isShort) { notShort++; continue }
                    // Из вкладки /shorts всё вертикальное по определению,
                    // но yt-dlp иногда не отдаёт размеры кадра — не бракуем зря.
                    if (!probe.isVertical && !candidate.fromShortsFeed) { notVertical++; continue }
                    picked.add(
                        candidate.copy(
                            title = probe.title.ifBlank { candidate.title },
                            channel = probe.channel.ifBlank { candidate.channel },
                            duration = probe.duration,
                            views = probe.views,
                            likes = probe.likes,
                            commentCount = probe.commentCount,
                        ).also {
                            it.size = probe.size
                            it.width = probe.width
                            it.height = probe.height
                        }
                    )
                    _state.update {
                        it.copy(found = picked.toList(), foundBytes = picked.sumOf { c -> c.size })
                    }
                }

                if (picked.isEmpty()) {
                    error(
                        "Ничего не подошло из ${pool.size}. " +
                            "Не отозвались: $noProbe, длинные: $notShort, не вертикальные: $notVertical." +
                            (YtDlp.lastError?.let { "\n\nyt-dlp сказал: $it" } ?: "")
                    )
                }
                _state.update {
                    it.copy(jobState = JobState.READY, jobMessage = "Нашёл ${picked.size} шортсов")
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                val detail = YtDlp.lastError
                val text = (e.message ?: "Что-то пошло не так.") +
                    if (detail != null && e.message?.contains(detail) != true) "\n\nyt-dlp сказал: $detail" else ""
                _state.update { it.copy(jobState = JobState.FAILED, jobMessage = text) }
            }
        }
    }

    // ------------------------------------------------------------ загрузка

    fun startDownload() {
        val batch = _state.value.found
        if (batch.isEmpty()) return
        jobHandle?.cancel()
        _state.update { it.copy(jobState = JobState.DOWNLOADING, jobMessage = "Качаю…") }
        jobHandle = viewModelScope.launch {
            val depth = _state.value.commentDepth
            // Три потока: узкое место — не полоса, а запуск yt-dlp, разбор
            // страницы и расшифровка ссылки. Такая работа ждёт сеть и процессор,
            // поэтому параллельно идёт почти втрое быстрее.
            for (group in batch.chunked(3)) {
                if (!isActive) break
                _state.update {
                    it.copy(
                        running = group.size,
                        currentTitle = group.first().title,
                        currentProgress = 0f,
                    )
                }
                coroutineScope {
                    group.map { candidate ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val result = YtDlp.download(
                                    candidate.id, store.videosDir,
                                    maxTop = depth,
                                    maxReplies = if (depth >= 100) 10 else 5,
                                ) { }
                                val thumb = YtDlp.thumbnail(
                                    candidate.thumbUrl ?: result.thumbUrl,
                                    File(store.videosDir, "${candidate.id}.jpg"),
                                )
                                // Название и лайки берём из info.json: у роликов,
                                // собранных в ленте, их изначально нет.
                                val enriched = candidate.copy(
                                    title = result.title.ifBlank { candidate.title },
                                    channel = result.channel.ifBlank { candidate.channel },
                                    duration = if (result.duration > 0) result.duration else candidate.duration,
                                    likes = if (result.likes > 0) result.likes else candidate.likes,
                                    views = if (result.views > 0) result.views else candidate.views,
                                    commentCount = if (result.commentCount > 0) result.commentCount
                                        else candidate.commentCount,
                                )
                                val saved = store.save(enriched, result.file, thumb, result.comments)
                                _state.update {
                                    it.copy(
                                        downloaded = it.downloaded + 1,
                                        downloadedBytes = it.downloadedBytes + saved.bytes,
                                        currentProgress =
                                            (it.downloaded + 1f) / batch.size.coerceAtLeast(1),
                                    )
                                }
                            }.onFailure { e ->
                                val detail = YtDlp.lastError ?: e.message ?: e.toString()
                                _state.update { s ->
                                    s.copy(
                                        skipped = s.skipped + 1,
                                        downloadError = s.downloadError ?: detail,
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
                refreshStorage()
            }
            _state.update {
                val base = "Скачано ${it.downloaded} · ${formatBytes(it.downloadedBytes)}" +
                    if (it.skipped > 0) " · пропущено ${it.skipped}" else ""
                it.copy(
                    jobState = if (it.downloaded == 0 && it.skipped > 0) JobState.FAILED else JobState.DONE,
                    currentTitle = null, currentId = null, running = 0,
                    jobMessage = base + (it.downloadError?.let { d -> "\n\nyt-dlp сказал: $d" } ?: ""),
                )
            }
        }
    }

    fun cancelJob() {
        jobHandle?.cancel()
        _state.value.currentId?.let { YtDlp.cancel(it) }
        _state.update {
            it.copy(jobState = JobState.IDLE, jobMessage = "Отменено", currentTitle = null, currentId = null)
        }
        refreshStorage()
    }

    // ------------------------------------------------------------ плеер

    fun openPlayer() {
        watched.clear()
        _state.update {
            it.copy(
                screen = Screen.PLAYER, queue = store.all(), index = 0,
                sessionDeleted = 0, sessionFreed = 0,
                commentsOpen = false, comments = emptyList(),
            )
        }
    }

    fun markWatched() {
        _state.value.current?.let { watched.add(it.id) }
    }

    fun advance(direction: Int) {
        val s = _state.value
        val current = s.current
        if (direction > 0 && current != null && current.id in watched) {
            val freed = store.delete(current.id)
            watched.remove(current.id)
            val queue = s.queue.filterNot { it.id == current.id }
            _state.update {
                it.copy(
                    queue = queue,
                    index = if (it.index >= queue.size) 0 else it.index,
                    sessionDeleted = it.sessionDeleted + 1,
                    sessionFreed = it.sessionFreed + freed,
                    commentsOpen = false, comments = emptyList(),
                )
            }
            refreshStorage()
            if (queue.isEmpty()) exitPlayer()
            return
        }
        val size = s.queue.size
        if (size == 0) return
        val next = ((s.index + direction) % size + size) % size
        _state.update { it.copy(index = next, commentsOpen = false, comments = emptyList()) }
    }

    fun openComments() {
        val id = _state.value.current?.id ?: return
        _state.update { it.copy(commentsOpen = true) }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { store.comments(id) }
            _state.update { it.copy(comments = items) }
        }
    }

    fun closeComments() = _state.update { it.copy(commentsOpen = false) }

    fun exitPlayer() {
        refreshStorage()
        _state.update { it.copy(screen = Screen.MENU, showReceipt = true, commentsOpen = false) }
    }

    fun dismissReceipt() = _state.update { it.copy(showReceipt = false) }

    fun clearAll() {
        store.clear()
        refreshStorage()
        _state.update { it.copy(toast = "Хранилище очищено") }
    }
}
