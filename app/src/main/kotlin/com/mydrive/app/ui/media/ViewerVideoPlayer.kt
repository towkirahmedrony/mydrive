package com.mydrive.app.ui.media

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.media.FullImageLoader
import com.mydrive.app.data.model.MediaItem
import com.mydrive.app.MyDriveApp
import com.mydrive.app.ui.components.MediaImage
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import kotlinx.coroutines.delay

data class VideoPlaybackState(
    val playing: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = true,
    val error: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

@Composable
fun ViewerVideoPlayer(
    item: MediaItem,
    active: Boolean,
    onTap: () -> Unit,
    onState: (VideoPlaybackState) -> Unit,
    seekRequestMs: Int?,
    seekNonce: Int,
    playRequest: Boolean?,
    playNonce: Int,
    modifier: Modifier = Modifier
) {
    val latestOnTap by rememberUpdatedState(onTap)
    var player by remember(item.id) { mutableStateOf<VideoView?>(null) }
    var playbackUri by remember(item.id) { mutableStateOf(item.uri) }
    var state by remember(item.id) {
        mutableStateOf(
            VideoPlaybackState(
                durationMs = item.durationMillis?.toInt()?.coerceAtLeast(0) ?: 0
            )
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(item.id) {
        playbackUri = FullImageLoader.ensureOriginalFile(
            context = context,
            uriString = item.uri,
            mediaId = item.remoteMediaId,
            sessionProvider = (context.applicationContext as? MyDriveApp)?.sessionProvider
        )?.toString() ?: item.uri
    }

    LaunchedEffect(state) {
        onState(state)
    }

    LaunchedEffect(active, item.id) {
        if (!active) {
            player?.pause()
            state = state.copy(playing = false)
        }
    }

    // Keyed on state.ready as well so requests issued while the surface is
    // still buffering are applied as soon as playback is prepared, instead of
    // being silently dropped.
    LaunchedEffect(seekNonce, player, state.ready) {
        val target = seekRequestMs ?: return@LaunchedEffect
        val view = player ?: return@LaunchedEffect
        if (state.ready) {
            view.seekTo(target)
            state = state.copy(positionMs = target)
        }
    }

    LaunchedEffect(playNonce, player, state.ready) {
        val shouldPlay = playRequest ?: return@LaunchedEffect
        val view = player ?: return@LaunchedEffect
        if (!state.ready) return@LaunchedEffect
        if (shouldPlay) {
            view.start()
            state = state.copy(playing = true)
        } else {
            view.pause()
            state = state.copy(playing = false)
        }
    }

    LaunchedEffect(state.playing, state.ready, player) {
        val view = player ?: return@LaunchedEffect
        while (state.playing && state.ready) {
            state = state.copy(positionMs = view.currentPosition.coerceAtLeast(0))
            delay(200)
        }
    }

    DisposableEffect(lifecycleOwner, item.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player?.pause()
                state = state.copy(playing = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.stopPlayback()
            player = null
            state = state.copy(playing = false, ready = false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        if (!active || state.error || item.uri.isBlank()) {
            if (state.error || item.uri.isBlank()) {
                MediaUnavailableState()
            } else {
                MediaImage(
                    uri = item.displayUri,
                    seed = item.thumbnailSeed,
                    type = item.type,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    sizePx = 720,
                    contentDescription = item.filename,
                    placeholderBitmap = ThumbnailLoader.peek(item.uri, 256)
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Ink.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Play",
                        tint = Ivory,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        } else {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setOnPreparedListener { mp ->
                            val duration = mp.duration.coerceAtLeast(0)
                            state = state.copy(
                                ready = true,
                                buffering = false,
                                durationMs = duration,
                                error = false
                            )
                            mp.isLooping = false
                            start()
                            state = state.copy(playing = true)
                        }
                        setOnErrorListener { _, _, _ ->
                            state = state.copy(
                                error = true,
                                buffering = false,
                                playing = false,
                                ready = false
                            )
                            true
                        }
                        setOnClickListener { latestOnTap() }
                        setOnCompletionListener {
                            pause()
                            seekTo(0)
                            state = state.copy(
                                playing = false,
                                positionMs = 0
                            )
                        }
                        setOnInfoListener { _, what, _ ->
                            if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                state = state.copy(buffering = true)
                            } else if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                                state = state.copy(buffering = false)
                            }
                            false
                        }
                        try {
                            setVideoURI(Uri.parse(playbackUri))
                        } catch (_: Exception) {
                            state = state.copy(error = true, buffering = false)
                        }
                        player = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (player !== view) player = view
                    if (playbackUri.isNotBlank() && view.tag != playbackUri) {
                        view.tag = playbackUri
                        runCatching { view.setVideoURI(Uri.parse(playbackUri)) }
                    }
                },
                onRelease = { view ->
                    view.stopPlayback()
                    if (player === view) player = null
                }
            )
            if (state.buffering && !state.error) {
                CircularProgressIndicator(
                    color = Copper,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
