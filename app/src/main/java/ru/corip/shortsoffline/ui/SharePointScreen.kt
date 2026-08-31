package ru.corip.shortsoffline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Визуальный выбор места кнопки «Поделиться».
 *
 * Экран у приложения тот же, что у TikTok, поэтому достаточно ткнуть туда,
 * где стоит стрелка: доли экрана совпадут один в один. Считать проценты
 * в уме не нужно.
 */
@Composable
fun SharePointScreen(
    x: Float,
    y: Float,
    onPick: (Float, Float) -> Unit,
    onDone: () -> Unit,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Void)
            .onSizeChanged { box = it }
            .pointerInput(box) {
                detectTapGestures { offset ->
                    if (box.width > 0 && box.height > 0) {
                        onPick(
                            (offset.x / box.width).coerceIn(0f, 1f),
                            (offset.y / box.height).coerceIn(0f, 1f),
                        )
                    }
                }
            }
    ) {
        // Метка там, куда ткнули.
        if (box.width > 0) {
            val markerX = with(density) { (box.width * x).toDp() }
            val markerY = with(density) { (box.height * y).toDp() }
            Box(
                Modifier
                    .offset(x = markerX - 22.dp, y = markerY - 22.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Palette.Signal.copy(alpha = 0.35f))
            )
            Box(
                Modifier
                    .offset(x = markerX - 1.dp, y = markerY - 22.dp)
                    .width(2.dp)
                    .height(44.dp)
                    .background(Palette.Signal)
            )
            Box(
                Modifier
                    .offset(x = markerX - 22.dp, y = markerY - 1.dp)
                    .width(44.dp)
                    .height(2.dp)
                    .background(Palette.Signal)
            )
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp, start = 24.dp, end = 24.dp)
        ) {
            SectionLabel("Где кнопка «Поделиться»")
            Spacer(Modifier.height(10.dp))
            Text(
                "Открой TikTok, запомни, где стоит стрелка, вернись и ткни " +
                    "в это же место здесь. Экран тот же, так что попадание " +
                    "будет точным.",
                style = Type.Small,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Сейчас: ${(x * 100).toInt()}% по ширине, ${(y * 100).toInt()}% по высоте",
                style = Type.Data.copy(fontSize = 12.sp, color = Palette.Jade),
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                .fillMaxWidth()
        ) {
            AppButton("Готово", primary = true, onClick = onDone)
        }

        Text(
            "Нижняя кнопка не считается — жми в любое другое место",
            style = Type.Label.copy(color = Color(0xFF6E6779)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
    }
}
