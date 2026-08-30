package ru.corip.shortsoffline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { MENU, LOGIN, DOWNLOAD, PLAYER }
enum class JobState { IDLE, SEARCHING, READY, DOWNLOADING, DONE, FAILED }

data class UiState(
    val screen: Screen = Screen.MENU,

    val signedIn: Boolean = false,

    val savedCount: Int = 0,
    val savedBytes: Long = 0,
    val freeSpace: Long = 0,
    val lifetime: Lifetime = Lifetime(0, 0, 0),

    val sessionDeleted: Int = 0,
    val sessionFreed: Long = 0,
    val showReceipt: Boolean = false,

    val feed: YtDlp.Feed = YtDlp.Feed.RECOMMENDED,
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

    val queue: List<Saved> = emptyList(),
    val index: Int = 0,
    val comments: List<Comment> = emptyList(),
    val commentsOpen: Boolean = false,

    val customUrl: String = "",
    val diagnostics: String? = null,
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

    /** Файл с куками, если вход выполнен. */
    private fun cookies(): File? =
        Cookies.file(getApplication()).takeIf { Cookies.isSignedIn(getApplication()) }

    init {
        val saved = runCatching { YtDlp.Feed.valueOf(store.lastFeed) }.getOrDefault(YtDlp.Feed.RECOMMENDED)
        _state.update {
            it.copy(feed = saved, signedIn = Cookies.isSignedIn(app), customUrl = store.customTarget)
        }
        refreshStorage()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { YtDlp.init(getApplication()) } }
        }
    }

    // ------------------------------------------------------------ вход

    fun openLogin() = _state.update { it.copy(screen = Screen.LOGIN) }

    /** Вызывается, когда пользователь закрывает окно входа. */
    fun finishLogin() {
        val saved = Cookies.captureFromWebView(getApplication())
        val ok = Cookies.isSignedIn(getApplication())
        _state.update {
            it.copy(
                screen = Screen.MENU,
                signedIn = ok,
                toast = if (ok) "Вход выполнен, сохранено $saved куки"
                else "Сессия не найдена — похоже, вход не завершён",
            )
        }
    }

    fun signOut() {
        Cookies.signOut(getApplication())
        _state.update { it.copy(signedIn = false, toast = "Сессия удалена") }
    }

    fun importCookies(text: String) {
        val ok = Cookies.import(getApplication(), text)
        _state.update {
            it.copy(signedIn = ok, toast = if (ok) "Куки приняты" else "В файле нет сессии YouTube")
        }
    }

    fun updateYtdlp() = viewModelScope.launch {
        _state.update { it.copy(toast = "Обновляю yt-dlp…") }
        _state.update { it.copy(toast = YtDlp.update(getApplication())) }
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

    fun runDiagnostics() = viewModelScope.launch {
        _state.update { it.copy(diagnostics = "Проверяю…") }
        val report = YtDlp.diagnose(cookies())
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
        if (feed.needsLogin && !_state.value.signedIn) {
            _state.update { it.copy(toast = "Для ленты «${feed.title}» нужен вход в YouTube.") }
            return
        }
        jobHandle?.cancel()
        _state.update {
            it.copy(
                jobState = JobState.SEARCHING, jobMessage = "Читаю ленту «${feed.title}»…",
                found = emptyList(), foundBytes = 0, checked = 0, toCheck = 0,
                downloaded = 0, downloadedBytes = 0, skipped = 0, currentTitle = null,
            )
        }
        jobHandle = viewModelScope.launch {
            runCatching {
                val wanted = _state.value.count
                val jar = cookies()
                val pool = YtDlp.feed(feed, wanted, jar, store.ids(), _state.value.customUrl)
                if (pool.isEmpty()) {
                    error(
                        if (feed.needsLogin)
                            "Лента пустая — обычно это значит, что сессия YouTube не подхватилась. Проверь вход."
                        else "Лента пустая. Попробуй ещё раз."
                    )
                }

                _state.update { it.copy(jobMessage = "Считаю вес…", toCheck = pool.size) }

                val picked = mutableListOf<Candidate>()
                var noProbe = 0
                var notShort = 0
                var notVertical = 0
                for (candidate in pool) {
                    if (picked.size >= wanted) break
                    val probe = YtDlp.probe(candidate.id, jar)
                    _state.update { it.copy(checked = it.checked + 1) }
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
            val jar = cookies()
            for (candidate in batch) {
                _state.update {
                    it.copy(currentTitle = candidate.title, currentId = candidate.id, currentProgress = 0f)
                }
                runCatching {
                    val file = YtDlp.download(candidate.id, store.videosDir, jar) { p ->
                        _state.update { it.copy(currentProgress = p) }
                    }
                    val thumb = YtDlp.thumbnail(
                        candidate.thumbUrl, File(store.videosDir, "${candidate.id}.jpg")
                    )
                    val comments = YtDlp.comments(candidate.id, jar)
                    val saved = store.save(candidate, file, thumb, comments)
                    _state.update {
                        it.copy(
                            downloaded = it.downloaded + 1,
                            downloadedBytes = it.downloadedBytes + saved.bytes,
                        )
                    }
                }.onFailure {
                    _state.update { s -> s.copy(skipped = s.skipped + 1) }
                }
                refreshStorage()
            }
            _state.update {
                it.copy(
                    jobState = JobState.DONE, currentTitle = null, currentId = null,
                    jobMessage = "Скачано ${it.downloaded} · ${formatBytes(it.downloadedBytes)}" +
                        if (it.skipped > 0) " · пропущено ${it.skipped}" else "",
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
