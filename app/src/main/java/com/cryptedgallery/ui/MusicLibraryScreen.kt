package com.cryptedgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptedgallery.data.MediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(
    viewModel: GalleryViewModel,
    onTrackClick: (MediaItem) -> Unit
) {
    val audioItems by viewModel.audio.collectAsState()
    val currentTrack by viewModel.currentPlayingAudio.collectAsState()
    val isPlaying by viewModel.isAudioPlaying.collectAsState()
    val scope = rememberCoroutineScope()
    
    var searchQuery by remember { mutableStateOf("") }
    
    var itemToDelete by remember { mutableStateOf<MediaItem?>(null) }
    var itemToRename by remember { mutableStateOf<MediaItem?>(null) }
    
    val filteredItems = remember(audioItems, searchQuery) {
        if (searchQuery.isEmpty()) audioItems
        else audioItems.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    placeholder = { Text("Search music & records...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = MaterialTheme.shapes.medium
                )
            }
        }
    ) { innerPadding ->
        if (audioItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No audio files found", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.EndToStart -> {
                                    itemToDelete = item
                                    false // Don't dismiss yet, wait for dialog
                                }
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    itemToRename = item
                                    false // Don't dismiss yet, wait for dialog
                                }
                                else -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            MusicSwipeBackground(dismissState)
                        }
                    ) {
                        AudioListItem(
                            item = item,
                            isCurrent = currentTrack?.id == item.id,
                            isPlaying = isPlaying && currentTrack?.id == item.id,
                            onClick = { onTrackClick(item) }
                        )
                    }
                }
            }
        }
    }

    // DIALOGS
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Track?") },
            text = { Text("Are you sure you want to permanently remove '${item.displayName}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.deleteMediaDirectly(item)
                            itemToDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    itemToRename?.let { item ->
        var newName by remember { mutableStateOf(item.displayName.substringBeforeLast(".")) }
        val extension = item.displayName.substringAfterLast(".", "")
        
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("Rename Track") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("New Name") },
                        singleLine = true
                    )
                    if (extension.isNotEmpty()) {
                        Text(
                            "Extension: .$extension",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                            color = Color.Gray
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val finalName = if (extension.isNotEmpty()) "$newName.$extension" else newName
                            viewModel.renameMedia(item, finalName)
                            itemToRename = null
                        }
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSwipeBackground(dismissState: SwipeToDismissBoxState) {
    val direction = dismissState.dismissDirection
    val color = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Color.Blue.copy(alpha = 0.8f)
        SwipeToDismissBoxValue.EndToStart -> Color.Red.copy(alpha = 0.8f)
        else -> Color.Transparent
    }
    
    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    
    val icon = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
        SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
        else -> Icons.Default.Delete
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(icon, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Rename", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(icon, null, tint = Color.White)
                }
            }
        }
    }
}


@Composable
fun AudioListItem(
    item: MediaItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val isVoiceRecord = item.mimeType?.contains("voice") == true || item.displayName.lowercase().contains("record")
    val icon = if (isVoiceRecord) Icons.Default.Mic else Icons.Default.MusicNote
    val tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = tint.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isCurrent && isPlaying) {
                        // We could add an animation here
                        Icon(Icons.Default.Pause, null, tint = tint)
                    } else if (isCurrent) {
                        Icon(Icons.Default.PlayArrow, null, tint = tint)
                    } else {
                        Icon(icon, null, tint = tint)
                    }
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatDuration(item.duration ?: 0L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
