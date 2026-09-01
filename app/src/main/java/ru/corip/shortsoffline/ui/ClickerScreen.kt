package ru.corip.shortsoffline.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.corip.shortsoffline.AppViewModel
import ru.corip.shortsoffline.Screen
import ru.corip.shortsoffline.UiState

/**
 * Автосбор чужой ленты.
 *
 * Читать другое приложение система не даёт, поэтому приложение повторяет
 * за тобой касания: жмёт «Поделиться», копирует ссылку и листает дальше.
 *
 * Экран разбит надвое: «Как настроить» — то, что делается один раз,
 * «Запуск» — то, чем пользуешься каждый раз.
 */
@Composable
fun ClickerScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var setupOpen by remember { mutableStateOf(false) }
    var runOpen by remember { mutableStateOf(true) }

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

        // ---------------------------------------------------- как настроить
        Foldable("Как настроить", setupOpen) { setupOpen = !setupOpen }
        if (setupOpen) {
            Spacer(Modifier.height(12.dp))

            AppButton("Включить службу", tail = "один раз, в настройках") {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Spacer(Modifier.height(10.dp))
            AppButton(
                "Поставить перекрестие",
                tail = "${(state.shareX * 100).toInt()}% и ${(state.shareY * 100).toInt()}%",
            ) { vm.pickSharePointOnScreen() }
            Spacer(Modifier.height(8.dp))
            Text(
                "Перекрестие появится поверх всех приложений. Открой TikTok, " +
                    "перетащи метку на стрелку «Поделиться» и нажми «Готово» — " +
                    "координаты запомнятся.",
                style = Type.Small,
            )

            Spacer(Modifier.height(16.dp))
            Panel {
                Text("ПОДПИСИ КНОПОК", style = Type.Label)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Кнопки ищутся по надписи и по описанию. Ролик пересылается " +
                        "прямо в приложение: копирование в буфер не работает, " +
                        "Android не даёт читать его в фоне. Если приложение " +
                        "подписано иначе — впиши название через запятую.",
                    style = Type.Small,
                )
                Spacer(Modifier.height(12.dp))
                LabelField(state.shareLabels, "Кнопка «Поделиться»") { vm.setShareLabels(it) }
                Spacer(Modifier.height(10.dp))
                LabelField(state.appLabels, "Название приложения в списке") {
                    vm.setAppLabels(it)
                }
            }

            Spacer(Modifier.height(16.dp))
            Step(1, "Включи службу в настройках телефона — «Специальные возможности», пункт ShortsOffline.")
            Step(2, "Поставь перекрестие на стрелку «Поделиться» в TikTok.")
            Step(3, "Открой «Запуск», задай количество и нажми «Начать сбор».")
            Step(4, "За пять секунд переключись в TikTok и открой ленту.")
            Step(5, "Дальше телефон работает сам: жмёт «Поделиться», находит ShortsOffline в списке и пересылает ролик. Звук выключается.")

            Spacer(Modifier.height(12.dp))
            Text(
                "Служба видит содержимое экранов всех приложений — это цена такой " +
                    "автоматизации. Выключай её, когда не пользуешься.",
                style = Type.Small.copy(color = Palette.Signal),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- запуск
        Foldable("Запуск", runOpen) { runOpen = !runOpen }
        if (runOpen) {
            Spacer(Modifier.height(12.dp))

            Panel {
                Text("СКОЛЬКО РОЛИКОВ", style = Type.Label)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (state.count == 0) "\u221E" else "${state.count}",
                        style = Type.Display.copy(fontSize = 34.sp),
                    )
                    Text(
                        if (state.count == 0) "  без предела" else "  штук",
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
            }

            Spacer(Modifier.height(12.dp))

            Panel {
                Text("СОСТОЯНИЕ", style = Type.Label)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.clickerStatus,
                    style = Type.Data.copy(
                        color = if (state.clickerRunning) Palette.Jade else Palette.Ink
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text("Собрано ссылок: ${state.clickerLinks}", style = Type.Small)
                if (state.clipboardStuck) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Буфер обмена не читается. Android с десятой версии " +
                            "отдаёт его только приложению на переднем плане — " +
                            "похоже, мы упёрлись в это. Скажи, переделаю сбор " +
                            "на прямую передачу ссылки вместо копирования.",
                        style = Type.Small.copy(color = Palette.Signal),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.clickerRunning) {
                AppButton("Остановить", primary = true) { vm.stopClicker() }
            } else {
                AppButton("Начать сбор", tail = "${state.count} роликов", primary = true) {
                    vm.startClicker()
                }
            }

            // Кнопка доступна всегда, когда что-то собрано: сбор мог оборваться
            // на середине, и терять уже полученное незачем.
            if (state.clickerLinks > 0) {
                Spacer(Modifier.height(10.dp))
                AppButton(
                    "Скачать собранное",
                    tail = "${state.clickerLinks} шт.",
                ) { vm.useClickerLinks() }
                if (state.clickerRunning) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Сбор ещё идёт — скачается то, что набралось на этот момент.",
                        style = Type.Small,
                    )
                }
            }
        }
    }
}

/** Заголовок сворачиваемой части. */
@Composable
private fun Foldable(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = Type.Data.copy(fontWeight = FontWeight.SemiBold))
        Text(if (open) "\u2013" else "+", style = Type.Data.copy(color = Palette.Signal))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.Edge)
    )
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
