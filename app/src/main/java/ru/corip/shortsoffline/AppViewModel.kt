package ru.corip.shortsoffline

import android.app.Application
import android.content.Intent
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

enum class Screen { MENU, DOWNLOAD, PLAYER }
enum class SourceMode { RANDOM, SUBS }
enum class JobState { IDLE, SEARCHING, READY, DOWNLOADING, DONE, FAILED }

data class UiState(
    val screen: Screen = Screen.MENU,
    val busy: Boolean = false,

    // аккаунт
    val signedIn: Boolean = false,
    val accountName: String? = null,
    val apiKey: String = "",
    val clientId: String = "",

    // хранилище
    val savedCount: Int = 0,
    val savedBytes: Long = 0,
    val freeSpace: Long = 0,
    val lifetime: Lifetime = Lifetime(0, 0, 0),

    // сеанс просмотра
    val sessionDeleted: Int = 0,
    val sessionFreed: Long = 0,
    val showReceipt: Boolean = false,

    // загрузка
    val mode: SourceMode = SourceMode.RANDOM,
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

    // плеер
    val queue: List<Saved> = emptyList(),
    val index: Int = 0,
    val comments: List<Comment> = emptyList(),
    val commentsOpen: Boolean = false,

    val toast: String? = null,
) {
    val current: Saved? get() = queue.getOrNull(index)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val store = Store(app)
    private val auth = Auth(app, store)

    @Volatile private var token: String? = null

    private val api = YouTubeApi(apiKey = { store.apiKey }, accessToken = { token })

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var jobHandle: Job? = null
    private val watched = mutableSetOf<String>()

    init {
        _state.update {
            it.copy(apiKey = store.apiKey, clientId = store.clientId, signedIn = auth.isSignedIn)
        }
        refreshStorage()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { Downloader.init(getApplication()) } }
            if (auth.isSignedIn) refreshToken()
        }
    }

    // ------------------------------------------------------------- аккаунт

    private suspend fun refreshToken() {
        token = auth.accessToken()
        val name = if (token != null) api.me() else null
        _state.update { it.copy(signedIn = token != null, accountName = name) }
    }

    fun authIntent(): Intent? = runCatching { auth.buildIntent() }.getOrElse {
        _state.update { s -> s.copy(toast = it.message) }
        null
    }

    fun onAuthResult(data: Intent?) = viewModelScope.launch {
        val result = auth.handleResult(data)
        result.onFailure { e -> _state.update { it.copy(toast = e.message) } }
        refreshToken()
    }

    fun signOut() {
        auth.signOut()
        token = null
        _state.update { it.copy(signedIn = false, accountName = null) }
    }

    fun setApiKey(value: String) {
        store.apiKey = value
        _state.update { it.copy(apiKey = value) }
    }

    fun setClientId(value: String) {
        store.clientId = value
        _state.update { it.copy(clientId = value) }
    }

    fun updateYtdlp() = viewModelScope.launch {
        _state.update { it.copy(toast = "Обновляю yt-dlp…") }
        val message = Downloader.update(getApplication())
        _state.update { it.copy(toast = message) }
    }

    fun dismissToast() = _state.update { it.copy(toast = null) }

    // ------------------------------------------------------------- навигация

    fun go(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun setMode(mode: SourceMode) = _state.update { it.copy(mode = mode) }
    fun setCount(count: Int) = _state.update { it.copy(count = count.coerceIn(1, 50)) }

    private fun refreshStorage() = _state.update {
        it.copy(
            savedCount = store.count(),
            savedBytes = store.totalBytes(),
            freeSpace = store.freeSpace(),
            lifetime = store.lifetime(),
        )
    }

    // ------------------------------------------------------------- поиск

    fun find() {
        if (!api.hasCredentials()) {
            _state.update { it.copy(toast = "Вставь API-ключ или войди в Google.") }
            return
        }
        jobHandle?.cancel()
        _state.update {
            it.copy(
                jobState = JobState.SEARCHING, jobMessage = "Ищу шортсы…",
                found = emptyList(), foundBytes = 0, checked = 0, toCheck = 0,
                downloaded = 0, downloadedBytes = 0, skipped = 0, currentTitle = null,
            )
        }
        jobHandle = viewModelScope.launch {
            runCatching {
                if (_state.value.signedIn) refreshToken()
                val wanted = _state.value.count
                val known = store.ids()

                val ids = when (_state.value.mode) {
                    SourceMode.RANDOM -> api.randomCandidates(wanted, known)
                    SourceMode.SUBS -> api.subscriptionCandidates(wanted, known)
                }
                val pool = api.details(ids).filter { it.id !in known }
                if (pool.isEmpty()) throw ApiError("Подходящих шортсов не нашлось. Попробуй ещё раз.")

                _state.update { it.copy(jobMessage = "Считаю вес…", toCheck = pool.size) }

                val picked = mutableListOf<Candidate>()
                for (candidate in pool) {
                    if (picked.size >= wanted) break
                    val probe = Downloader.probe(candidate.id)
                    _state.update { it.copy(checked = it.checked + 1) }
                    if (probe == null || !probe.isVertical || probe.size <= 0) continue
                    candidate.size = probe.size
                    candidate.width = probe.width
                    candidate.height = probe.height
                    picked.add(candidate)
                    _state.update {
                        it.copy(found = picked.toList(), foundBytes = picked.sumOf { c -> c.size })
                    }
                }

                if (picked.isEmpty()) {
                    throw ApiError("Вертикальных шортсов в выдаче не оказалось. Запусти поиск ещё раз.")
                }
                _state.update {
                    it.copy(jobState = JobState.READY, jobMessage = "Нашёл ${picked.size} шортсов")
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                _state.update {
                    it.copy(jobState = JobState.FAILED, jobMessage = e.message ?: "Что-то пошло не так.")
                }
            }
        }
    }

    // ------------------------------------------------------------- загрузка

    fun startDownload() {
        val batch = _state.value.found
        if (batch.isEmpty()) return
        jobHandle?.cancel()
        _state.update { it.copy(jobState = JobState.DOWNLOADING, jobMessage = "Качаю…") }
        jobHandle = viewModelScope.launch {
            for (candidate in batch) {
                _state.update { it.copy(currentTitle = candidate.title, currentId = candidate.id, currentProgress = 0f) }
                runCatching {
                    val file = Downloader.download(candidate.id, store.videosDir) { p ->
                        _state.update { it.copy(currentProgress = p) }
                    }
                    val thumb = Downloader.thumbnail(
                        candidate.thumbUrl, File(store.videosDir, "${candidate.id}.jpg")
                    )
                    val comments = runCatching { api.comments(candidate.id) }.getOrDefault(emptyList())
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
        _state.value.currentId?.let { Downloader.cancel(it) }
        _state.update {
            it.copy(jobState = JobState.IDLE, jobMessage = "Отменено", currentTitle = null, currentId = null)
        }
        refreshStorage()
    }

    // ------------------------------------------------------------- плеер

    fun openPlayer() {
        watched.clear()
        _state.update {
            it.copy(
                screen = Screen.PLAYER,
                queue = store.all(),
                index = 0,
                sessionDeleted = 0,
                sessionFreed = 0,
                commentsOpen = false,
                comments = emptyList(),
            )
        }
    }

    fun markWatched() {
        _state.value.current?.let { watched.add(it.id) }
    }

    /** [direction] = +1 дальше, -1 назад. Просмотренное при уходе вперёд стирается. */
    fun advance(direction: Int) {
        val s = _state.value
        val current = s.current
        if (direction > 0 && current != null && current.id in watched) {
            val freed = store.delete(current.id)
            watched.remove(current.id)
            val queue = s.queue.filterNot { it.id == current.id }
            if (queue.isEmpty()) {
                _state.update {
                    it.copy(
                        queue = emptyList(), index = 0,
                        sessionDeleted = it.sessionDeleted + 1,
                        sessionFreed = it.sessionFreed + freed,
                        commentsOpen = false,
                    )
                }
                refreshStorage()
                exitPlayer()
                return
            }
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

    override fun onCleared() {
        auth.dispose()
        super.onCleared()
    }
}
