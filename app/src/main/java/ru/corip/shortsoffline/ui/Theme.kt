package ru.corip.shortsoffline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Магента = занятое место и действие. Нефрит = освобождённое. */
object Palette {
    val Void = Color(0xFF16141B)
    val Panel = Color(0xFF1F1C26)
    val PanelHi = Color(0xFF272332)
    val Edge = Color(0x17F0EBFF)
    val EdgeStrong = Color(0x2EF0EBFF)
    val Ink = Color(0xFFEFE9F4)
    val Muted = Color(0xFF8D859C)
    val Signal = Color(0xFFFF3D84)
    val Jade = Color(0xFF4FD6A6)
    val Sink = Color(0xFF100E15)
}

object Type {
    /** Всё, что цифры и служебное, — моноширинным. Это лицо приложения. */
    val Data = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Palette.Ink)
    val Label = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
        letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium, color = Palette.Muted,
    )
    val Display = TextStyle(
        fontFamily = FontFamily.Default, fontSize = 30.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = Palette.Ink,
    )
    val Body = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.5.sp, color = Palette.Ink)
    val Small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = Palette.Muted)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("▌", style = Type.Label.copy(color = Palette.Signal))
        Text(text.uppercase(), style = Type.Label, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Panel)
            .border(1.dp, Palette.Edge, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) { Column { content() } }
}

@Composable
fun AppButton(
    label: String,
    modifier: Modifier = Modifier,
    tail: String? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Palette.Panel.copy(alpha = 0.45f)
        primary -> Palette.Signal
        else -> Palette.Panel
    }
    val fg = when {
        !enabled -> Palette.Muted
        primary -> Color(0xFF180A10)
        else -> Palette.Ink
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .border(1.dp, if (primary) Palette.Signal else Palette.EdgeStrong, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = Type.Data.copy(fontWeight = FontWeight.SemiBold, color = fg),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        tail?.let {
            Text(
                it,
                style = Type.Small.copy(color = if (primary) fg.copy(alpha = 0.65f) else Palette.Muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
fun Bar(fraction: Float, color: Color = Palette.Signal, height: Int = 4) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(Palette.Sink)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color)
        )
    }
}

/**
 * Счётчик места: магентовый сегмент — что лежит на диске,
 * нефритовый — что освободилось за сеанс.
 */
@Composable
fun StorageMeter(used: Long, freed: Long, free: Long) {
    val scale = maxOf(free, used + freed, 1L).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(Palette.Sink)
            .border(1.dp, Palette.Edge, RoundedCornerShape(99.dp))
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth(used / scale)
                    .fillMaxHeight()
                    .background(Palette.Signal)
            )
            Box(
                Modifier
                    .fillMaxWidth(freed / scale)
                    .fillMaxHeight()
                    .background(Palette.Jade.copy(alpha = 0.55f))
            )
        }
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(text, style = Type.Small, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Кружок с буквой вместо аватарки: офлайн картинки всё равно не загрузятся. */
fun avatarColor(name: String): Color {
    var h = 0
    name.ifEmpty { "?" }.forEach { h = (h * 31 + it.code) % 360 }
    return Color.hsl(h.toFloat(), 0.62f, 0.68f)
}
