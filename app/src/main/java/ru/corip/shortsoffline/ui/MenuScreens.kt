package ru.corip.shortsoffline.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.corip.shortsoffline.AppViewModel
import ru.corip.shortsoffline.JobState
import ru.corip.shortsoffline.Screen
import ru.corip.shortsoffline.Tab
import ru.corip.shortsoffline.UiState
import ru.corip.shortsoffline.YtDlp
import ru.corip.shortsoffline.formatBytes
import ru.corip.shortsoffline.formatDuration


@Composable
fun MenuScreen(state: UiState, vm: AppViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.openLocal(uri.toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 34.dp, bottom = 40.dp)
    ) {
        SectionLabel("Офлайн-хранилище")
        Spacer(Modifier.height(10.dp))
        Row {
            Text("Shorts", style = Type.Display)
            Text("Offline", style = Type.Display.copy(color = Palette.Muted))
        }

        Spacer(Modifier.height(20.dp))

        // Вкладки: слева всё про файлы, справа две площадки со своими лентами.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab.entries.forEach { tab ->
                Box(Modifier.weight(1f)) {
                    SegButton(tab.title, state.tab == tab, Modifier.fillMaxWidth()) {
                        vm.setTab(tab)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        when (state.tab) {
            Tab.VIDEO -> VideoTab(state, vm, picker) { confirmClear = true }
            Tab.YOUTUBE -> PlatformTab(state, vm, YtDlp.Platform.YOUTUBE)
            Tab.TIKTOK -> PlatformTab(state, vm, YtDlp.Platform.TIKTOK)
        }
    }

    state.diagnostics?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.dismissDiagnostics() },
            containerColor = Palette.Panel,
            title = { Text("Что ответил сервис", style = Type.Data) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(report, style = Type.Small.copy(color = Palette.Ink))
                }
            },
            confirmButton = {
                Text(
                    "Закрыть",
                    style = Type.Data.copy(color = Palette.Signal),
                    modifier = Modifier.clickable { vm.dismissDiagnostics() },
                )
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Palette.Panel,
            title = { Text("Стереть хранилище?", style = Type.Data) },
            text = {
                Text(
                    "Удалятся все ${state.savedCount} скачанных роликов " +
                        "(${formatBytes(state.savedBytes)}).",
                    style = Type.Small,
                )
            },
            confirmButton = {
                Text(
                    "Стереть",
                    style = Type.Data.copy(color = Palette.Signal),
                    modifier = Modifier.clickable { vm.clearAll(); confirmClear = false },
                )
            },
            dismissButton = {
                Text(
                    "Отмена",
                    style = Type.Data.copy(color = Palette.Muted),
                    modifier = Modifier.clickable { confirmClear = false },
                )
            },
        )
    }
}

/** Вкладка «Видео»: скачать по ссылке, открыть свой файл, смотреть скачанное. */
@Composable
private fun VideoTab(
    state: UiState,
    vm: AppViewModel,
    picker: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onClear: () -> Unit,
) {
    AppButton("Скачать по ссылке", tail = "в галерею", primary = true) { vm.openLink() }
    Spacer(Modifier.height(10.dp))
    AppButton("Открыть видео с телефона", tail = "посмотреть тут") {
        picker.launch(arrayOf("video/*"))
    }
    Spacer(Modifier.height(10.dp))
    AppButton(
        "Смотреть скачанное",
        tail = if (state.savedCount > 0)
            "${state.savedCount} · ${formatBytes(state.savedBytes)}" else "пусто",
        enabled = state.savedCount > 0,
    ) { vm.openPlayer() }

    Spacer(Modifier.height(16.dp))

    Panel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("ХРАНИЛИЩЕ", style = Type.Label)
            Text(
                "${formatBytes(state.savedBytes)} / ${formatBytes(state.freeSpace)} свободно",
                style = Type.Small,
            )
        }
        Spacer(Modifier.height(8.dp))
        StorageMeter(state.savedBytes, state.sessionFreed, state.freeSpace)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(Palette.Signal, "${state.savedCount} видео")
            LegendDot(Palette.Jade, "освобождено ${formatBytes(state.sessionFreed)}")
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "По ссылке качаются YouTube, TikTok, Rutube и VK Видео. " +
            "Файл попадает в галерею, без лайков и комментариев.",
        style = Type.Small,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Проще всего так: в самом приложении TikTok или YouTube нажми " +
            "«Поделиться» и выбери ShortsOffline — ссылка прилетит сюда " +
            "и разберётся сама.",
        style = Type.Small.copy(color = Palette.Jade),
    )

    Spacer(Modifier.height(16.dp))
    Text(
        "yt-dlp: " + (state.ytdlpVersion ?: "не обновлён"),
        style = Type.Small.copy(
            color = if (state.ytdlpVersion == null) Palette.Signal else Palette.Jade
        ),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "За всё время: скачано ${state.lifetime.downloaded} · " +
            "удалено ${state.lifetime.deleted} · " +
            "освобождено ${formatBytes(state.lifetime.freed)}",
        style = Type.Small,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Обновить yt-dlp",
            style = Type.Small.copy(color = Palette.Muted),
            modifier = Modifier.clickable { vm.updateYtdlp() },
        )
        Text(
            "Проверить связь",
            style = Type.Small.copy(color = Palette.Muted),
            modifier = Modifier.clickable { vm.runDiagnostics() },
        )
        Text(
            "Стереть всё",
            style = Type.Small.copy(color = Palette.Signal),
            modifier = Modifier.clickable(onClick = onClear),
        )
    }
}

/** Вкладка площадки: вход и скачивание из твоей ленты. */
@Composable
private fun PlatformTab(state: UiState, vm: AppViewModel, platform: YtDlp.Platform) {
    AppButton("Вход в ${platform.title}", tail = "один раз") {
        vm.setPlatform(platform)
        vm.openLogin()
    }
    Spacer(Modifier.height(10.dp))
    AppButton(
        "Скачать из ленты",
        tail = "рекомендации ${platform.title}",
        primary = true,
    ) {
        vm.setPlatform(platform)
        vm.go(Screen.DOWNLOAD)
    }

    Spacer(Modifier.height(18.dp))
    Text(
        "Приложение открывает твою ленту ${platform.title} скрыто, листает её " +
            "и запоминает ролики. Смотреть их заранее не нужно — они окажутся " +
            "в плеере. Нужен вход в аккаунт.",
        style = Type.Small,
    )
}

@Composable
fun DownloadScreen(state: UiState, vm: AppViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 24.dp, bottom = 40.dp)
    ) {
        Text(
            "← В меню",
            style = Type.Label,
            modifier = Modifier.clickable { vm.go(Screen.MENU) },
        )
        Spacer(Modifier.height(18.dp))
        SectionLabel("Новая партия")
        Spacer(Modifier.height(8.dp))
        Text("Что качаем", style = Type.Display.copy(fontSize = 24.sp))

        Spacer(Modifier.height(20.dp))

        Panel {
            Text("ЛЕНТА", style = Type.Label)
            Spacer(Modifier.height(10.dp))
            if (state.feed == YtDlp.Feed.RECOMMENDED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Приложение пролистает твою ленту столько раз, сколько " +
                        "роликов заказано, и покажет найденное списком. " +
                        "Нужен вход в YouTube.",
                    style = Type.Small,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (state.feed == YtDlp.Feed.CUSTOM) {
                OutlinedTextField(
                    value = state.customUrl,
                    onValueChange = { vm.setCustomUrl(it) },
                    label = { Text("Ссылка на канал, плейлист или хэштег", style = Type.Label) },
                    placeholder = {
                        Text("https://www.youtube.com/@channel/shorts", style = Type.Small)
                    },
                    singleLine = true,
                    textStyle = Type.Data.copy(fontSize = 12.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Sink,
                        unfocusedContainerColor = Palette.Sink,
                        focusedIndicatorColor = Palette.Signal,
                        unfocusedIndicatorColor = Palette.Edge,
                        cursorColor = Palette.Signal,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }
            YtDlp.Feed.entries.forEach { feed ->
                Box(Modifier.padding(bottom = 8.dp)) {
                    SegButton(
                        label = feed.title,
                        active = state.feed == feed,
                        modifier = Modifier.fillMaxWidth(),
                    ) { vm.setFeed(feed) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Panel {
            Text("СКОЛЬКО ШТУК", style = Type.Label)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (state.count == 0) "\u221E" else "${state.count}",
                    style = Type.Display.copy(fontSize = 34.sp),
                )
                Text(
                    if (state.count == 0) "  без предела" else "  шортсов",
                    style = Type.Small,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Slider(
                value = state.count.toFloat(),
                onValueChange = { vm.setCount(it.toInt()) },
                valueRange = 1f..200f,
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Signal,
                    activeTrackColor = Palette.Signal,
                    inactiveTrackColor = Palette.PanelHi,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegButton("Столько и хватит", state.count > 0, Modifier.weight(1f)) {
                    vm.setCount(20)
                }
                SegButton("Без предела", state.count == 0, Modifier.weight(1f)) {
                    vm.setCount(0)
                }
            }
            if (state.count == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Лента будет листаться, пока ты не нажмёшь «Хватит». " +
                        "Скачается всё, что успело набраться.",
                    style = Type.Small,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Panel {
            Text("ВЕС ДО ЗАГРУЗКИ", style = Type.Label)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegButton("Примерный \u00B7 быстро", state.fastSize, Modifier.weight(1f)) {
                    vm.setFastSize(true)
                }
                SegButton("Точный \u00B7 долго", !state.fastSize, Modifier.weight(1f)) {
                    vm.setFastSize(false)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.fastSize)
                    "Считается по длительности. У роликов из ленты она заранее " +
                        "неизвестна, поэтому вес у всех выходит одинаковый — " +
                        "настоящий появится после загрузки."
                else "Точный размер, но приложение опрашивает каждый ролик отдельно.",
                style = Type.Small,
            )
        }

        Spacer(Modifier.height(12.dp))

        Panel {
            Text("КОММЕНТАРИИ", style = Type.Label)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegButton("100 + 10", state.commentDepth == 100, Modifier.weight(1f)) {
                    vm.setCommentDepth(100)
                }
                SegButton("30 + 5", state.commentDepth == 30, Modifier.weight(1f)) {
                    vm.setCommentDepth(30)
                }
                SegButton("Без них", state.commentDepth == 0, Modifier.weight(1f)) {
                    vm.setCommentDepth(0)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (state.commentDepth) {
                    100 -> "Полный набор. Самый медленный режим: комментарии тянутся страницами."
                    0 -> "Быстрее всего — комментарии не загружаются совсем."
                    else -> "Разумная середина: заметно быстрее, а верхушку обсуждения видно."
                },
                style = Type.Small,
            )
        }

        Spacer(Modifier.height(20.dp))

        val searching = state.jobState == JobState.SEARCHING
        val downloading = state.jobState == JobState.DOWNLOADING
        AppButton(
            if (searching) "Ищу…" else "Найти шортсы",
            primary = true,
            enabled = !searching && !downloading,
        ) { vm.find() }

        if (state.jobState != JobState.IDLE) {
            Spacer(Modifier.height(12.dp))
            JobPanel(state, vm)
        }
    }
}

@Composable
private fun JobPanel(state: UiState, vm: AppViewModel) {
    val fraction = when (state.jobState) {
        JobState.SEARCHING -> if (state.toCheck > 0) state.checked / state.toCheck.toFloat() else 0f
        JobState.DOWNLOADING -> {
            val total = state.found.size.coerceAtLeast(1)
            (state.downloaded + state.currentProgress) / total
        }
        JobState.READY, JobState.DONE -> 1f
        else -> 0f
    }

    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when (state.jobState) {
                    JobState.DOWNLOADING -> "Качаю: ${state.currentTitle?.take(24) ?: ""}"
                    else -> state.jobMessage
                }.uppercase(),
                style = Type.Label,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (state.jobState == JobState.READY) formatBytes(state.foundBytes)
                else "${(fraction * 100).toInt()}%",
                style = Type.Small,
            )
        }
        Spacer(Modifier.height(10.dp))
        Bar(fraction, if (state.jobState == JobState.FAILED) Palette.Muted else Palette.Signal)

        if (state.found.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                items(state.found, key = { it.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Palette.PanelHi)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            item.title,
                            style = Type.Body.copy(fontSize = 12.sp, color = Palette.Muted),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                        )
                        Text(
                            (if (item.duration > 0) formatDuration(item.duration) + " · " else "") +
                                (if (state.fastSize) "~" else "") + formatBytes(item.size),
                            style = Type.Small.copy(
                                // Длиннее трёх минут — не шортс, подсвечиваем.
                                color = if (item.duration > 180) Palette.Signal else Palette.Ink
                            ),
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                }
            }
        }

        when (state.jobState) {
            JobState.READY -> {
                Spacer(Modifier.height(12.dp))
                Text("Столько места займёт партия.", style = Type.Small)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton(
                        "Скачать", Modifier.weight(1f),
                        tail = formatBytes(state.foundBytes), primary = true,
                    ) { vm.startDownload() }
                    AppButton("Отмена", Modifier.weight(1f)) { vm.cancelJob() }
                }
            }
            JobState.DOWNLOADING -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${state.downloaded} из ${state.found.size} · " +
                        "на диск легло ${formatBytes(state.downloadedBytes)}" +
                        if (state.running > 1) " · в ${state.running} потока" else "",
                    style = Type.Small,
                )
            }
            JobState.DONE -> {
                Spacer(Modifier.height(10.dp))
                Text(state.jobMessage, style = Type.Small.copy(color = Palette.Jade))
            }
            JobState.FAILED -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.jobMessage,
                    style = Type.Small.copy(color = Palette.Signal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun SegButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) Palette.PanelHi else Color.Transparent)
            .border(1.dp, if (active) Palette.Signal else Palette.Edge, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.Small.copy(color = if (active) Palette.Ink else Palette.Muted),
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- чек

@Composable
fun ReceiptDialog(state: UiState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Panel,
        title = {
            Column {
                SectionLabel("Сеанс закрыт")
                Spacer(Modifier.height(10.dp))
                Text(
                    formatBytes(state.sessionFreed),
                    style = Type.Display.copy(fontSize = 26.sp, color = Palette.Jade),
                )
            }
        },
        text = {
            Column {
                ReceiptLine("Просмотрено и удалено", "${state.sessionDeleted}")
                ReceiptLine("Освобождено места", formatBytes(state.sessionFreed))
                ReceiptLine(
                    "Осталось в хранилище",
                    "${state.savedCount} · ${formatBytes(state.savedBytes)}",
                )
            }
        },
        confirmButton = {
            Text(
                "Понятно",
                style = Type.Data.copy(color = Palette.Signal),
                modifier = Modifier.clickable(onClick = onClose),
            )
        },
    )
}

@Composable
private fun ReceiptLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = Type.Small)
        Text(value, style = Type.Data.copy(fontWeight = FontWeight.SemiBold))
    }
}
