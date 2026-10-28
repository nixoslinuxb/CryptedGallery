package com.cryptedgallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cryptedgallery.data.MediaItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onPhotoClick: (MediaItem) -> Unit,
    onShareMultiple: (List<MediaItem>) -> Unit,
    onDeleteMultiple: (List<MediaItem>) -> Unit
) {
    val images by viewModel.photos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var columnCount by remember { mutableIntStateOf(3) }
    var zoomAccumulator by remember { mutableFloatStateOf(1f) }
    
    var selectedItems by remember { mutableStateOf(setOf<MediaItem>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (selectedItems.isNotEmpty()) {
        androidx.activity.compose.BackHandler {
            selectedItems = emptySet()
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = selectedItems.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selectedItems.size} selected") },
                    actions = {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                        IconButton(onClick = { 
                            onShareMultiple(selectedItems.toList()) 
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { selectedItems = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    ) { innerPadding ->
        if (isLoading && images.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            zoomAccumulator *= zoom
                            if (zoomAccumulator > 1.3f) {
                                // Pinch OUT (spread fingers) -> Show LESS pics (Decrease columns)
                                if (columnCount > 2) columnCount--
                                zoomAccumulator = 1f
                            } else if (zoomAccumulator < 0.7f) {
                                // Pinch IN (bring fingers together) -> Show MORE pics (Increase columns)
                                if (columnCount < 6) columnCount++
                                zoomAccumulator = 1f
                            }
                        }
                    }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columnCount),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(images, key = { it.id }) { item ->
                        val isSelected = selectedItems.contains(item)
                        val selectionMode = selectedItems.isNotEmpty()

                        MediaThumbnail(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (selectionMode) {
                                    selectedItems = if (isSelected) selectedItems - item else selectedItems + item
                                } else {
                                    onPhotoClick(item)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectedItems = setOf(item)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Photos?") },
            text = { Text("Are you sure you want to permanently delete ${selectedItems.size} items from your device memory? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMultiple(selectedItems.toList())
                        selectedItems = emptySet()
                        showDeleteConfirm = false
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    item: MediaItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        border = if (isSelected) BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isSelected) Modifier
                            .padding(8.dp)
                            .clip(MaterialTheme.shapes.medium)
                        else Modifier
                    ),
                contentScale = ContentScale.Crop
            )
            
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
