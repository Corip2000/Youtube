package ru.corip.shortsoffline.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Просмотр одного видео с телефона.
 *
 * Управление то же, что в ленте: тап слева — назад на десять секунд,
 * справа — вперёд, по центру — пауза, удержание — ускорение вдвое.
 */
@OptIn(UnstableApi::class)
@Composable
fun SoloPlayerScreen(uri: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }

    val activity = context.findActivity()

    // Приложение заперто в вертикальном положении, а тут нужен поворот.
    // Снимаем замок на время просмотра и возвращаем при выходе.
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            player.release()
        }
    }

    // Горизонтальное видео сразу разворачиваем набок, вертикальное оставляем
    // стоймя. Дальше телефон слушается поворота как обычно.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                activity?.requestedOrientation =
                    if (videoSize.width > videoSize.height)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    LaunchedEffect(uri) {
        while (true) {
            val d = player.duration
            if (d > 0) progress = (player.currentPosition.toFloat() / d).coerceIn(0f, 1f)
            delay(200)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(uri) {
                    val third = size.width / 3f
                    detectTapGestures(
                        onPress = {
                            val quick = withTimeoutOrNull(280) { tryAwaitRelease() }
                            if (quick == null) {
                                holding = true
                                player.setPlaybackSpeed(2f)
                                tryAwaitRelease()
                                player.setPlaybackSpeed(1f)
                                holding = false
                            }
                        },
                        onTap = { offset ->
                            if (holding) return@detectTapGestures
                            when {
                                offset.x < third ->
                                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                                offset.x > third * 2 ->
                                    player.seekTo(player.currentPosition + 10_000)
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                    )
                }
        )

        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.13f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(Palette.Signal)
            )
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 14.dp)
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onClose() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("← Меню", style = Type.Label.copy(color = Color.White))
            }
        }

        if (holding) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("\u00D72", style = Type.Data.copy(color = Palette.Jade))
            }
        }

        Text(
            "Слева −10 с · справа +10 с · удержание ×2",
            style = Type.Label.copy(color = Color(0xFF9A93A8), letterSpacing = 0.sp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

/** Activity лежит под слоями обёрток — достаём её оттуда. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
