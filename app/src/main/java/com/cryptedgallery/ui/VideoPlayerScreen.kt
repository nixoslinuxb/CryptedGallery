package com.cryptedgallery.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cryptedgallery.data.MediaItem as AppMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
@ExperimentalMaterial3Api
@Composable
fun VideoPlayerScreen(
    videos: List<AppMediaItem>,
    initialVideoId: Long,
    onBack: () -> Unit,
    onDelete: (AppMediaItem) -> Unit
) {
    val initialIndex = videos.indexOfFirst { it.id == initialVideoId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex) { videos.size }

    var isAnyVideoPlaying by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val verticalOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 150.dp.toPx() }

    Scaffold(
        containerColor = Color.Black.copy(alpha = (1f - abs(verticalOffset.value) / (threshold * 2)).coerceIn(0f, 1f))
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = verticalOffset.value
                        val scale = (1f - (verticalOffset.value / (threshold * 4))).coerceIn(0.8f, 1f)
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - (verticalOffset.value / (threshold * 2))).coerceIn(0f, 1f)
                    },
                userScrollEnabled = !isAnyVideoPlaying && verticalOffset.value == 0f,
                key = { index -> videos.getOrNull(index)?.id ?: -1L }
            ) { pageIndex ->
                VideoPlayerItem(
                    item = videos[pageIndex],
                    onPlayingChanged = { isPlaying ->
                        if (pagerState.currentPage == pageIndex) {
                            isAnyVideoPlaying = isPlaying
                        }
                    },
                    onBack = onBack,
                    onDelete = onDelete,
                    onVerticalSwipe = { dragAmount ->
                        if (!isAnyVideoPlaying) {
                            scope.launch {
                                val newOffset = verticalOffset.value + dragAmount
                                if (newOffset >= 0f) {
                                    verticalOffset.snapTo(newOffset)
                                }
                            }
                        }
                    },
                    onSwipeEnd = {
                        if (!isAnyVideoPlaying) {
                            if (verticalOffset.value > threshold) {
                                onBack()
                            } else {
                                scope.launch {
                                    verticalOffset.animateTo(0f, tween(250))
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerItem(
    item: AppMediaItem,
    onPlayingChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDelete: (AppMediaItem) -> Unit,
    onVerticalSwipe: (Float) -> Unit = {},
    onSwipeEnd: () -> Unit = {}
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val activity = context as? Activity

    val exoPlayer = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(item.uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = false
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }

    // Scrubbing state
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubbingPosition by remember { mutableLongStateOf(0L) }

    var volumeLevel by remember { mutableFloatStateOf(0f) }
    var brightnessLevel by remember { mutableFloatStateOf(0f) }
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var showBrightnessOverlay by remember { mutableStateOf(false) }

    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onPlayingChanged(playing)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            currentPosition = exoPlayer.currentPosition
            delay(500)
        }
    }

    LaunchedEffect(showControls) {
        if (showControls && isPlaying && !isScrubbing) {
            delay(3000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { layoutSize = it.size }
            .pointerInput(isPlaying) {
                if (!isPlaying) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var isVerticalDrag = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val panChange = event.calculatePan()

                            if (isVerticalDrag) {
                                onVerticalSwipe(panChange.y)
                                event.changes.forEach { it.consume() }
                            } else {
                                if (abs(panChange.y) > abs(panChange.x) * 1.5f && abs(panChange.y) > 1f) {
                                    isVerticalDrag = true
                                    onVerticalSwipe(panChange.y)
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            if (event.changes.all { !it.pressed }) {
                                if (isVerticalDrag) onSwipeEnd()
                                break
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        val width = size.width
                        if (offset.x < width / 3) {
                            exoPlayer.seekBack()
                        } else if (offset.x > width * 2 / 3) {
                            exoPlayer.seekForward()
                        } else {
                            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                exoPlayer.seekTo(0)
                                exoPlayer.play()
                            } else {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                        }
                    }
                )
            }
            .pointerInput(isPlaying) {
                if (isPlaying) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            if (offset.x > layoutSize.width / 2) {
                                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                                volumeLevel =
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                                showVolumeOverlay = true
                            } else {
                                val lp = activity?.window?.attributes
                                brightnessLevel =
                                    if (lp != null && lp.screenBrightness >= 0) lp.screenBrightness else 0.5f
                                showBrightnessOverlay = true
                            }
                        },
                        onDragEnd = {
                            showVolumeOverlay = false
                            showBrightnessOverlay = false
                        },
                        onDragCancel = {
                            showVolumeOverlay = false
                            showBrightnessOverlay = false
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val width = layoutSize.width
                            val height = layoutSize.height
                            if (change.position.x > width / 2) {
                                // Volume (Right side)
                                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                                val delta = -dragAmount / height * 2.0f
                                volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (volumeLevel * maxVolume).toInt(),
                                    0
                                )
                            } else {
                                // Brightness (Left side)
                                activity?.let {
                                    val delta = -dragAmount / height * 2.0f
                                    brightnessLevel = (brightnessLevel + delta).coerceIn(0.01f, 1f)
                                    val lp = it.window.attributes
                                    lp.screenBrightness = brightnessLevel
                                    it.window.attributes = lp
                                }
                            }
                        }
                    )
                }
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            VideoControls(
                displayName = item.displayName,
                isPlaying = isPlaying,
                isScrubbing = isScrubbing,
                currentPosition = if (isScrubbing) scrubbingPosition else currentPosition,
                totalDuration = totalDuration,
                onPlayPause = {
                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0)
                        exoPlayer.play()
                    } else {
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                },
                onScrubbingStart = {
                    isScrubbing = true
                    scrubbingPosition = currentPosition
                },
                onScrubbingChange = {
                    scrubbingPosition = it
                    exoPlayer.seekTo(it)
                },
                onScrubbingEnd = {
                    isScrubbing = false
                    currentPosition = scrubbingPosition
                },
                onBack = onBack,
                onShare = { shareVideo(context, item) },
                onDelete = { onDelete(item) }
            )
        }

        SystemControlOverlay(
            visible = showVolumeOverlay,
            value = volumeLevel,
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        SystemControlOverlay(
            visible = showBrightnessOverlay,
            value = brightnessLevel,
            icon = Icons.Default.BrightnessLow,
            modifier = Modifier.align(Alignment.CenterStart)
        )
    }
}

@Composable
fun VideoControls(
    displayName: String,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    onPlayPause: () -> Unit,
    onScrubbingStart: () -> Unit,
    onScrubbingChange: (Long) -> Unit,
    onScrubbingEnd: () -> Unit,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                displayName,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )

            if (!isPlaying && !isScrubbing) {
                Row {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, "Share", tint = Color.White)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = Color.White)
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Video?") },
                text = { Text("Are you sure you want to permanently delete this video? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            onDelete()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(80.dp).align(Alignment.Center),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.5f)).padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTime(currentPosition),
                    color = if (isScrubbing) MaterialTheme.colorScheme.primary else Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(formatTime(totalDuration), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = {
                    if (!isScrubbing) onScrubbingStart()
                    onScrubbingChange(it.toLong())
                },
                onValueChangeFinished = { onScrubbingEnd() },
                valueRange = 0f..totalDuration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = if (isScrubbing) MaterialTheme.colorScheme.primary else Color.White
                )
            )
        }
    }
}

@Composable
fun SystemControlOverlay(
    visible: Boolean,
    value: Float,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxHeight().padding(48.dp).wrapContentWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.medium).padding(8.dp)
        ) {
            Icon(icon, null, tint = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.width(4.dp).height(150.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(value).align(Alignment.BottomCenter)
                        .background(Color.White, MaterialTheme.shapes.small)
                )
            }
        }
    }
}

private fun shareVideo(context: Context, item: AppMediaItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Video"))
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
