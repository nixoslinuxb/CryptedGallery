package com.cryptedgallery.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cryptedgallery.data.MediaItem
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos

@Composable
fun MusicPlayerScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    val currentTrack by viewModel.currentPlayingAudio.collectAsState()
    val isPlaying by viewModel.isAudioPlaying.collectAsState()
    val progress by viewModel.audioProgress.collectAsState()
    val duration by viewModel.audioDuration.collectAsState()
    val volume by viewModel.volume.collectAsState()
    
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var volumeIndicatorTimer by remember { mutableLongStateOf(0L) }
    
    var isSeekingForward by remember { mutableStateOf(false) }
    var isSeekingBackward by remember { mutableStateOf(false) }

    if (currentTrack == null) return

    LaunchedEffect(volumeIndicatorTimer) {
        if (showVolumeIndicator) {
            delay(1500)
            showVolumeIndicator = false
        }
    }
    
    LaunchedEffect(isSeekingForward) {
        if (isSeekingForward) {
            while (isSeekingForward) {
                viewModel.seekIncremental(5000L)
                delay(200)
            }
        }
    }
    LaunchedEffect(isSeekingBackward) {
        if (isSeekingBackward) {
            while (isSeekingBackward) {
                viewModel.seekIncremental(-5000L)
                delay(200)
            }
        }
    }

    val density = LocalDensity.current
    val swipeThreshold = with(density) { 70.dp.toPx() }

    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- Modern Mesh Background & Gestures ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isPlaying) {
                    awaitPointerEventScope {
                        while (true) {
                            // Default behavior: waits for an unconsumed down event.
                            // If a button (Surface) is clicked, it consumes the event, 
                            // and this layer will ignore it.
                            awaitFirstDown() 
                            
                            var totalX = 0f
                            var totalY = 0f
                            var dragCaptured = false
                            
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                
                                if (change.pressed) {
                                    val dragAmount = change.position - change.previousPosition
                                    totalX += dragAmount.x
                                    totalY += dragAmount.y
                                    
                                    if (!dragCaptured) {
                                        if (abs(totalX) > 20f || abs(totalY) > 20f) {
                                            dragCaptured = true
                                        }
                                    }
                                    
                                    if (dragCaptured) {
                                        if (abs(totalY) > abs(totalX)) {
                                            if (isPlaying) {
                                                viewModel.adjustVolume(-dragAmount.y / 1000f)
                                                showVolumeIndicator = true
                                                volumeIndicatorTimer = System.currentTimeMillis()
                                            } else if (totalY > swipeThreshold) {
                                                onBack()
                                                return@awaitPointerEventScope
                                            }
                                        } else {
                                            if (abs(totalX) > 150f) {
                                                if (totalX > 0) viewModel.playPrevious() else viewModel.playNext()
                                                totalX = 0f 
                                            }
                                        }
                                        change.consume()
                                    }
                                } else {
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            MeshBackground()
        }

        // --- Main Content ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { onBack() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                
                var showRingtoneConfirm by remember { mutableStateOf(false) }
                val context = LocalContext.current

                Surface(
                    onClick = { showRingtoneConfirm = true },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Set as Ringtone",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (showRingtoneConfirm) {
                    AlertDialog(
                        onDismissRequest = { showRingtoneConfirm = false },
                        title = { Text("Set as Ringtone?") },
                        text = { Text("Do you want to set '${currentTrack!!.displayName}' as your default phone ringtone?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showRingtoneConfirm = false
                                if (Settings.System.canWrite(context)) {
                                    val success = viewModel.setAsRingtone(currentTrack!!)
                                    if (success) {
                                        Toast.makeText(context, "Ringtone set successfully", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to set ringtone", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }
                            }) { Text("Confirm") }
                        },
                        dismissButton = { TextButton(onClick = { showRingtoneConfirm = false }) { Text("Cancel") } }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Artwork Area
            ModernArtwork(isPlaying)

            Spacer(modifier = Modifier.weight(0.5f))

            // Glassmorphic Info Card
            Surface(
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = currentTrack!!.displayName,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Immersive Audio Experience",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Progress slider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        val width = size.width
                                        if (offset.x < width * 0.2f) {
                                            isSeekingBackward = true
                                            tryAwaitRelease()
                                            isSeekingBackward = false
                                        } else if (offset.x > width * 0.8f) {
                                            isSeekingForward = true
                                            tryAwaitRelease()
                                            isSeekingForward = false
                                        }
                                    }
                                )
                            }
                    ) {
                        Slider(
                            value = if (duration > 0) progress.toFloat() / duration else 0f,
                            onValueChange = { viewModel.seekAudio((it * duration).toLong()) },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDuration(progress), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
                        Text(formatDuration(duration), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.playPrevious() }) {
                            Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(40.dp), tint = Color.White)
                        }
                        
                        FloatingActionButton(
                            onClick = { viewModel.toggleAudioPlayback() },
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.playNext() }) {
                            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(40.dp), tint = Color.White)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }

        // Floating Volume Indicator
        AnimatedVisibility(
            visible = showVolumeIndicator,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = CircleShape,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.size(100.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (volume > 0.5f) Icons.AutoMirrored.Filled.VolumeUp else if (volume > 0f) Icons.AutoMirrored.Filled.VolumeDown else Icons.AutoMirrored.Filled.VolumeMute,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "${(volume * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
fun MeshBackground() {
    val infiniteTransition = rememberInfiniteTransition()
    
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(modifier = Modifier.fillMaxSize().blur(100.dp)) {
        // Dynamic circles
        val colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
            Color(0xFF6200EE).copy(alpha = 0.3f)
        )

        colors.forEachIndexed { index, color ->
            val offset = index * (Math.PI / 2).toFloat()
            val x = 0.5f + 0.3f * sin(phase + offset)
            val y = 0.5f + 0.3f * cos(phase + offset)
            
            Box(
                modifier = Modifier
                    .offset(
                        x = (x * 400).dp - 200.dp,
                        y = (y * 800).dp - 400.dp
                    )
                    .size(400.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
fun ModernArtwork(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val scale = if (isPlaying) pulse else 1f
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(contentAlignment = Alignment.Center) {
        // Decorative outer ring
        Box(
            modifier = Modifier
                .size(320.dp)
                .border(1.dp, Color.White.copy(alpha = 0.05f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(360.dp)
                .border(1.dp, Color.White.copy(alpha = 0.03f), CircleShape)
        )

        Surface(
            modifier = Modifier
                .size(280.dp)
                .scale(scale)
                .graphicsLayer { rotationZ = if (isPlaying) rotation else 0f }
                .clip(RoundedCornerShape(48.dp)),
            color = Color.White.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Background pattern
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    modifier = Modifier.size(140.dp).graphicsLayer { alpha = 0.8f },
                    tint = Color.White
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
