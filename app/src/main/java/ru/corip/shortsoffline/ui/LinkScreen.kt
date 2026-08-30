package ru.corip.shortsoffline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.corip.shortsoffline.AppViewModel
import ru.corip.shortsoffline.Screen
import ru.corip.shortsoffline.UiState
import ru.corip.shortsoffline.formatBytes
import ru.corip.shortsoffline.formatDuration

/**
 * Одно видео по ссылке. Годится любое — шортс, обычный ролик, плейлистовая
 * ссылка. Сначала показываем, что это, потом качаем прямо в галерею.
 * Комментарии и лайки тут не сохраняются: это просто файл.
 */
@Composable
fun LinkScreen(state: UiState, vm: AppViewModel) {
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
        SectionLabel("Одно видео")
        Spacer(Modifier.height(8.dp))
        Text("Скачать по ссылке", style = Type.Display.copy(fontSize = 24.sp))
        Spacer(Modifier.height(6.dp))
        Text("Файл попадёт в галерею, в папку Movies/ShortsOffline.", style = Type.Small)

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.linkUrl,
            onValueChange = { vm.setLinkUrl(it) },
            label = { Text("Ссылка на видео", style = Type.Label) },
            placeholder = { Text("https://www.youtube.com/watch?v=…", style = Type.Small) },
            singleLine = true,
            textStyle = Type.Data.copy(fontSize = 12.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.Sink,
                unfocusedContainerColor = Palette.Sink,
                focusedIndicatorColor = Palette.Signal,
                unfocusedIndicatorColor = Palette.Edge,
                cursorColor = Palette.Signal,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        AppButton(
            if (state.linkBusy) "Подожди…" else "Проверить ссылку",
            enabled = !state.linkBusy && state.linkUrl.isNotBlank(),
        ) { vm.checkLink() }

        state.linkInfo?.let { info ->
            Spacer(Modifier.height(16.dp))
            Panel {
                Text(info.title, style = Type.Body.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        info.channel.ifBlank { null },
                        if (info.duration > 0) formatDuration(info.duration) else null,
                        formatBytes(info.size),
                        if (info.isShort) "шортс" else null,
                    ).joinToString(" · "),
                    style = Type.Small,
                )
                Spacer(Modifier.height(14.dp))
                if (state.linkBusy) {
                    Bar(state.linkProgress)
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.linkProgress * 100).toInt()}%", style = Type.Small)
                } else {
                    AppButton(
                        "Скачать в галерею",
                        tail = formatBytes(info.size),
                        primary = true,
                    ) { vm.downloadLink() }
                }
            }
        }

        state.linkStatus?.let { text ->
            Spacer(Modifier.height(14.dp))
            Text(
                text,
                style = Type.Small.copy(
                    color = if (text.startsWith("Сохранено")) Palette.Jade else Palette.Signal
                ),
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Комментарии и лайки здесь не сохраняются —", style = Type.Small)
        }
        Text("это обычный файл для галереи.", style = Type.Small)
    }
}
