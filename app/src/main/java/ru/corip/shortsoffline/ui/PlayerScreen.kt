package ru.corip.shortsoffline.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import ru.corip.shortsoffline.AppViewModel
import ru.corip.shortsoffline.Comment
import ru.corip.shortsoffline.UiState
import ru.corip.shortsoffline.formatBytes
import ru.corip.shortsoffline.formatCount
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val current = state.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Досмотрел до конца — помечаем и листаем дальше.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    vm.markWatched()
                    vm.advance(1)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(current?.id) {
        val file = current?.file ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(file))))
        player.prepare()
        player.play()
    }

    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(current?.id) {
        while (true) {
            val duration = player.duration
            if (duration > 0) {
                val p = player.currentPosition.toFloat() / duration
                progress = p.coerceIn(0f, 1f)
                if (p > 0.9f) vm.markWatched()
            }
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

        // Жесты: свайп листает, тап ставит на паузу.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(current?.id) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -80f) vm.advance(1)
                            else if (total > 80f) vm.advance(-1)
                        },
                    ) { _, amount -> total += amount }
                }
                .pointerInput(current?.id) {
                    detectTapGestures {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                }
        )

        // Затемнение снизу под текст
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.86f),
                    )
                )
        )

        // Полоса прогресса
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

        // Верхние метки
        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("← Меню", Modifier.clickable { vm.exitPlayer() })
            Chip("${state.index + 1} / ${state.queue.size}")
        }

        // Нижний HUD
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current?.channel.orEmpty(),
                    style = Type.Small.copy(color = Color(0xFFC6BED3)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    current?.title.orEmpty(),
                    style = Type.Body.copy(
                        fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    ),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RailItem("♥", formatCount(current?.likes ?: 0))
                RailItem(
                    "💬", formatCount(current?.commentCount ?: 0),
                    Modifier.clickable { vm.openComments() },
                )
                RailItem("▦", formatBytes(current?.bytes ?: 0))
            }
        }

        AnimatedVisibility(
            visible = state.commentsOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            CommentsSheet(state, onClose = { vm.closeComments() })
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(text, style = Type.Label.copy(color = Color.White))
    }
}

@Composable
private fun RailItem(glyph: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = Type.Body.copy(fontSize = 17.sp, color = Color.White))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, style = Type.Label.copy(color = Color.White, letterSpacing = 0.sp))
    }
}

@Composable
private fun CommentsSheet(state: UiState, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Void)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.comments.isEmpty()) "Комментарии"
                else "Комментарии · ${state.comments.size}",
                style = Type.Data.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Закрыть ✕",
                style = Type.Label,
                modifier = Modifier.clickable(onClick = onClose),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.Edge)
        )

        if (state.comments.isEmpty()) {
            Text(
                "Комментариев нет — автор их отключил или их не было на момент загрузки.",
                style = Type.Small,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp, end = 18.dp, top = 6.dp, bottom = 40.dp,
                ),
            ) {
                itemsIndexed(state.comments) { i, comment ->
                    CommentRow(comment, i + 1)
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, rank: Int) {
    Column(Modifier.padding(vertical = 13.dp)) {
        Row {
            Text(
                "$rank",
                style = Type.Small.copy(fontSize = 10.sp),
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            )
            Avatar(comment.author, 30)
            Column(Modifier.padding(start = 11.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        comment.author,
                        style = Type.Small.copy(fontSize = 11.5.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        "♥ ${formatCount(comment.likes)}",
                        style = Type.Small.copy(color = Palette.Signal, fontSize = 11.sp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(comment.text, style = Type.Body)

                if (comment.replies.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    Row {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(((comment.replies.size) * 52).dp)
                                .background(Palette.Edge)
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            comment.replies.forEach { reply ->
                                Row(Modifier.padding(vertical = 6.dp)) {
                                    Avatar(reply.author, 23)
                                    Column(Modifier.padding(start = 9.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                reply.author,
                                                style = Type.Small.copy(fontSize = 11.sp),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Text(
                                                "♥ ${formatCount(reply.likes)}",
                                                style = Type.Small.copy(
                                                    color = Palette.Signal, fontSize = 10.5.sp,
                                                ),
                                            )
                                        }
                                        Text(
                                            reply.text,
                                            style = Type.Body.copy(fontSize = 12.5.sp),
                                        )
                                    }
                                }
                            }
                            if (comment.replyCount > comment.replies.size) {
                                Text(
                                    "Ещё ${comment.replyCount - comment.replies.size} ответов не сохранено",
                                    style = Type.Small.copy(fontSize = 10.5.sp),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x0DF0EBFF))
        )
    }
}

@Composable
private fun Avatar(name: String, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 2).dp))
            .background(avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase().ifBlank { "?" },
            style = Type.Data.copy(
                fontSize = (size * 0.4).sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Void,
            ),
        )
    }
}
