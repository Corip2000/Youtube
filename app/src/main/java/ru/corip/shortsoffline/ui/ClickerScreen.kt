package ru.corip.shortsoffline.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.corip.shortsoffline.AppViewModel
import ru.corip.shortsoffline.Screen
import ru.corip.shortsoffline.UiState

/**
 * Автокликер: приложение само нажимает «Поделиться» и «Копировать ссылку»
 * в чужой ленте, забирает адрес ролика и листает дальше.
 *
 * Способ грубоватый, но других нет: заглянуть внутрь чужого приложения
 * система не даёт, а повторить за тобой касания — разрешает, через службу
 * специальных возможностей.
 */
@Composable
fun ClickerScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current

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
        SectionLabel("Автосбор")
        Spacer(Modifier.height(8.dp))
        Text("Собрать ленту за тебя", style = Type.Display.copy(fontSize = 24.sp))

        Spacer(Modifier.height(20.dp))

        Panel {
            Text("СОСТОЯНИЕ", style = Type.Label)
            Spacer(Modifier.height(8.dp))
            Text(
                state.clickerStatus,
                style = Type.Data.copy(
                    color = if (state.clickerRunning) Palette.Jade else Palette.Ink
                ),
            )
            if (state.clickerLinks > 0) {
                Spacer(Modifier.height(6.dp))
                Text("Собрано ссылок: ${state.clickerLinks}", style = Type.Small)
            }
        }

        Spacer(Modifier.height(16.dp))

        AppButton("Включить службу", tail = "один раз, в настройках") {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        Spacer(Modifier.height(10.dp))

        if (state.clickerRunning) {
            AppButton("Остановить", primary = true) { vm.stopClicker() }
        } else {
            AppButton("Начать сбор", tail = "${state.count} роликов", primary = true) {
                vm.startClicker()
            }
        }

        if (state.clickerLinks > 0 && !state.clickerRunning) {
            Spacer(Modifier.height(10.dp))
            AppButton("Скачать собранное", tail = "${state.clickerLinks} шт.") {
                vm.useClickerLinks()
            }
        }

        Spacer(Modifier.height(20.dp))

        Panel {
            Text("ПОДПИСИ КНОПОК", style = Type.Label)
            Spacer(Modifier.height(6.dp))
            Text(
                "Кнопки ищутся по надписи, а не по месту на экране — так " +
                    "переживается смена разметки. Если в твоём приложении они " +
                    "названы иначе, впиши через запятую.",
                style = Type.Small,
            )
            Spacer(Modifier.height(12.dp))
            LabelField(state.shareLabels, "Кнопка «Поделиться»") { vm.setShareLabels(it) }
            Spacer(Modifier.height(10.dp))
            LabelField(state.copyLabels, "Кнопка «Ссылка»") { vm.setCopyLabels(it) }
        }

        Spacer(Modifier.height(12.dp))

        Panel {
            Text("МЕСТО КНОПКИ «ПОДЕЛИТЬСЯ»", style = Type.Label)
            Spacer(Modifier.height(6.dp))
            Text(
                "В TikTok это стрелка без подписи — найти её по слову нельзя. " +
                    "Если по описанию тоже не находится, приложение тычет по " +
                    "этому месту. Ползунки в долях экрана: слева направо и " +
                    "сверху вниз.",
                style = Type.Small,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "По ширине: ${(state.shareX * 100).toInt()}%",
                style = Type.Data.copy(fontSize = 12.sp),
            )
            Slider(
                value = state.shareX,
                onValueChange = { vm.setSharePoint(it, state.shareY) },
                valueRange = 0.5f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Signal,
                    activeTrackColor = Palette.Signal,
                    inactiveTrackColor = Palette.PanelHi,
                ),
            )
            Text(
                "По высоте: ${(state.shareY * 100).toInt()}%",
                style = Type.Data.copy(fontSize = 12.sp),
            )
            Slider(
                value = state.shareY,
                onValueChange = { vm.setSharePoint(state.shareX, it) },
                valueRange = 0.3f..0.9f,
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Signal,
                    activeTrackColor = Palette.Signal,
                    inactiveTrackColor = Palette.PanelHi,
                ),
            )
        }

        Spacer(Modifier.height(20.dp))

        Text("КАК ЭТО РАБОТАЕТ", style = Type.Label)
        Spacer(Modifier.height(10.dp))
        Step(1, "Включи службу в настройках телефона — раздел «Специальные возможности», пункт ShortsOffline.")
        Step(2, "Вернись сюда, задай количество роликов на экране загрузки и нажми «Начать сбор».")
        Step(3, "За пять секунд переключись в приложение с лентой и открой её.")
        Step(4, "Дальше телефон работает сам: жмёт «Поделиться», копирует ссылку, листает. Звук выключается.")
        Step(5, "Когда наберётся нужное — возвращайся и жми «Скачать собранное».")

        Spacer(Modifier.height(20.dp))
        Text(
            "Служба видит содержимое экранов всех приложений — это цена такой " +
                "автоматизации. Выключай её в настройках, когда не пользуешься.",
            style = Type.Small.copy(color = Palette.Signal),
        )
    }
}

@Composable
private fun LabelField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.Label) },
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
}

@Composable
private fun Step(number: Int, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "$number",
            style = Type.Data.copy(color = Palette.Signal, fontWeight = FontWeight.Bold),
        )
        Text(text, style = Type.Small)
    }
}
