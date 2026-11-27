package com.vineyard.editoraPRO.app.ui.components

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun ExoVideoPreview(
    uri: Uri,
    testTag: String,
    modifier: Modifier = Modifier,
    height: Dp = 195.dp,
    externalIsPlaying: Boolean? = null,
    externalSeekSec: Float? = null,
    loopPlayback: Boolean = false,
    useBuiltInController: Boolean = true,
    onPositionChanged: ((Float) -> Unit)? = null,
    onPlayingChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnPosChanged by rememberUpdatedState(onPositionChanged)
    val latestOnPlayingChanged by rememberUpdatedState(onPlayingChanged)
    var lastEmittedPosSec by remember(uri) { mutableFloatStateOf(-1f) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(loopPlayback) {
        exoPlayer.repeatMode = if (loopPlayback) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    LaunchedEffect(uri) {
        lastEmittedPosSec = -1f
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        if (externalIsPlaying == null) {
            exoPlayer.playWhenReady = false
        } else {
            val startMs = ((externalSeekSec ?: 0f).coerceAtLeast(0f) * 1000f).toLong()
            if (startMs > 0L) {
                exoPlayer.seekTo(startMs)
            }
            exoPlayer.playWhenReady = externalIsPlaying
        }
    }

    LaunchedEffect(externalIsPlaying) {
        val desired = externalIsPlaying ?: return@LaunchedEffect
        if (desired) {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }

    LaunchedEffect(externalSeekSec) {
        val targetSec = externalSeekSec ?: return@LaunchedEffect
        if (abs(targetSec - lastEmittedPosSec) < 0.04f) return@LaunchedEffect
        val targetMs = (targetSec.coerceAtLeast(0f) * 1000f).toLong()
        val diffMs = abs(targetMs - exoPlayer.currentPosition)
        val thresholdMs = if (exoPlayer.isPlaying) 220L else 60L
        if (diffMs > thresholdMs) {
            exoPlayer.seekTo(targetMs)
        }
    }

    LaunchedEffect(exoPlayer, uri, externalIsPlaying, onPositionChanged) {
        if (onPositionChanged == null) return@LaunchedEffect
        while (isActive) {
            if (exoPlayer.isPlaying) {
                val posSec = (exoPlayer.currentPosition.coerceAtLeast(0L) / 1000f)
                lastEmittedPosSec = posSec
                latestOnPosChanged?.invoke(posSec)
            }
            delay(50L)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (externalIsPlaying != null) {
                    latestOnPlayingChanged?.invoke(isPlaying)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !loopPlayback && externalIsPlaying != null) {
                    latestOnPlayingChanged?.invoke(false)
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
                if (externalIsPlaying != null) {
                    latestOnPlayingChanged?.invoke(false)
                }
            }
        }
        exoPlayer.addListener(playerListener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            exoPlayer.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, EditoraBorder, RoundedCornerShape(12.dp))
            .testTag(testTag)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = useBuiltInController
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = exoPlayer
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                if (view.useController != useBuiltInController) {
                    view.useController = useBuiltInController
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}