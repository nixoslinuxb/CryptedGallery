package com.cryptedgallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.app.WallpaperManager
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cryptedgallery.data.MediaItem
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    viewModel: GalleryViewModel,
    photos: List<MediaItem>,
    initialPhotoId: Long,
    onBack: () -> Unit,
    onEdit: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    val initialIndex = photos.indexOfFirst { it.id == initialPhotoId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    val currentPhoto = photos.getOrNull(pagerState.currentPage)
    
    val scope = rememberCoroutineScope()
    val verticalOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 150.dp.toPx() }

    var globalScale by remember { mutableStateOf(1f) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showMetadataDialog by remember { mutableStateOf(false) }

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
                userScrollEnabled = globalScale <= 1.01f && verticalOffset.value == 0f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = verticalOffset.value
                        val scale = (1f - (verticalOffset.value / (threshold * 4))).coerceIn(0.8f, 1f)
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - (verticalOffset.value / (threshold * 2))).coerceIn(0f, 1f)
                    },
                key = { index -> photos.getOrNull(index)?.id ?: -1L }
            ) { pageIndex ->
                ZoomableImage(
                    model = photos[pageIndex].uri,
                    contentDescription = photos[pageIndex].displayName,
                    onScaleChange = { 
                        if (pagerState.currentPage == pageIndex) {
                            globalScale = it
                        }
                    },
                    onVerticalSwipe = { dragAmount ->
                        if (globalScale <= 1.01f) {
                            scope.launch {
                                val newOffset = verticalOffset.value + dragAmount
                                if (newOffset >= 0f) {
                                    verticalOffset.snapTo(newOffset)
                                }
                            }
                        }
                    },
                    onSwipeEnd = {
                        if (globalScale <= 1.01f) {
                            if (verticalOffset.value > threshold) {
                                onBack()
                            } else {
                                scope.launch {
                                    verticalOffset.animateTo(0f, tween(200))
                                }
                            }
                        }
                    }
                )
            }

            // --- UI Overlays ---
            val controlAlpha = (1f - (verticalOffset.value / (threshold / 2))).coerceIn(0f, 1f) * 
                               (if (globalScale > 1.01f) 0f else 1f)

            // 1. Floating Top Header (Back Button and Properties)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp)
                    .graphicsLayer { alpha = controlAlpha },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Properties",
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                showRenameDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Set as Background") },
                            onClick = {
                                showMenu = false
                                showWallpaperDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Metadata") },
                            onClick = {
                                showMenu = false
                                showMetadataDialog = true
                            }
                        )
                    }
                }
            }

            // 2. Floating Bottom Actions (Share, Delete, Edit)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .graphicsLayer { alpha = controlAlpha },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        currentPhoto?.let { photo ->
                            IconButton(onClick = { onShare(photo) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }
                            IconButton(onClick = { onEdit(photo) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteDialog) {
            PhotoDeleteConfirmationDialog(
                onDismiss = { showDeleteDialog = false },
                onConfirm = {
                    currentPhoto?.let { onDelete(it) }
                    showDeleteDialog = false
                }
            )
        }

        if (showRenameDialog) {
            RenamePhotoDialog(
                currentName = currentPhoto?.displayName ?: "",
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    currentPhoto?.let { photo ->
                        scope.launch {
                            viewModel.renameDocument(photo, newName)
                        }
                    }
                    showRenameDialog = false
                }
            )
        }

        if (showWallpaperDialog) {
            SetWallpaperDialog(
                onDismiss = { showWallpaperDialog = false },
                onSet = { which ->
                    currentPhoto?.let { photo ->
                        scope.launch {
                            viewModel.setAsWallpaper(photo.uri, which)
                        }
                    }
                    showWallpaperDialog = false
                }
            )
        }

        if (showMetadataDialog) {
            AlertDialog(
                onDismissRequest = { showMetadataDialog = false },
                title = { Text("Clear Metadata") },
                text = { Text("This will permanently remove location, camera info, and other metadata from the photo. Proceed?") },
                confirmButton = {
                    TextButton(onClick = {
                        currentPhoto?.let { photo ->
                            scope.launch {
                                viewModel.clearPhotoMetadata(photo)
                            }
                        }
                        showMetadataDialog = false
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showMetadataDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun RenamePhotoDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Photo") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("New Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SetWallpaperDialog(
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set as Background") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Home Screen") },
                    modifier = Modifier.clickable { onSet(WallpaperManager.FLAG_SYSTEM) }
                )
                ListItem(
                    headlineContent = { Text("Lock Screen") },
                    modifier = Modifier.clickable { onSet(WallpaperManager.FLAG_LOCK) }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ListItem(
                        headlineContent = { Text("Both") },
                        modifier = Modifier.clickable { onSet(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PhotoDeleteConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Delete") },
        text = { Text("Are you sure you want to permanently delete this photo?") },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    onScaleChange: (Float) -> Unit,
    onVerticalSwipe: (Float) -> Unit,
    onSwipeEnd: () -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    
    // Track double tap manually for integration
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val startTime = System.currentTimeMillis()
                    
                    var isVerticalDrag = false
                    var isHorizontalDrag = false
                    var isTransforming = false
                    var maxPointers = 1
                    
                    // Double tap logic integration
                    val isDoubleTap = startTime - lastTapTime < 300 && 
                                     (firstDown.position - lastTapPosition).getDistance() < 100f

                    if (isDoubleTap) {
                        // Execute double tap zoom immediately
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val x = (centerX - firstDown.position.x) * (scale - 1f)
                            val y = (centerY - firstDown.position.y) * (scale - 1f)
                            offset = Offset(x, y)
                        }
                        onScaleChange(scale)
                        lastTapTime = 0L // reset
                    } else {
                        lastTapTime = startTime
                        lastTapPosition = firstDown.position
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }
                        maxPointers = maxOf(maxPointers, pointers.size)

                        if (pointers.size >= 2) {
                            isTransforming = true
                            isVerticalDrag = false
                            isHorizontalDrag = false
                            
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            onScaleChange(scale)
                            
                            if (scale > 1.01f) {
                                offset += panChange
                            } else {
                                offset = Offset.Zero
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pointers.size == 1 && !isTransforming) {
                            val change = event.changes.first()
                            val dragAmount = change.position - change.previousPosition
                            
                            if (scale > 1.01f) {
                                offset += dragAmount
                                change.consume()
                            } else {
                                if (isVerticalDrag) {
                                    onVerticalSwipe(dragAmount.y)
                                    change.consume()
                                } else if (isHorizontalDrag) {
                                    // Let HorizontalPager handle it
                                } else {
                                    // Decision phase: Ultra-sensitive
                                    if (abs(dragAmount.y) > 2f || abs(dragAmount.x) > 2f) {
                                        if (abs(dragAmount.y) > abs(dragAmount.x)) {
                                            isVerticalDrag = true
                                            onVerticalSwipe(dragAmount.y)
                                            change.consume()
                                        } else {
                                            isHorizontalDrag = true
                                            // Don't consume, let pager have it
                                        }
                                    }
                                }
                            }
                        }

                        if (event.changes.all { !it.pressed }) {
                            val endTime = System.currentTimeMillis()
                            val duration = endTime - startTime
                            
                            // Two-finger tap detection
                            if (maxPointers == 2 && duration < 300 && !isTransforming) {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    offset = Offset.Zero
                                }
                                onScaleChange(scale)
                            }
                            
                            if (isVerticalDrag) onSwipeEnd()
                            break
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit
        )
    }
}
