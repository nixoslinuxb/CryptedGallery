package com.cryptedgallery.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.cryptedgallery.data.MediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentLibraryScreen(
    viewModel: GalleryViewModel,
    onDocumentClick: (MediaItem) -> Unit,
    onRequestFullAccess: () -> Unit
) {
    val documents by viewModel.documents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasFullAccess by viewModel.hasFullFileAccess.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    var documentToShare by remember { mutableStateOf<MediaItem?>(null) }
    var documentToDelete by remember { mutableStateOf<MediaItem?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val filteredDocs = remember(documents, searchQuery) {
        if (searchQuery.isEmpty()) documents
        else documents.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            if (hasFullAccess) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        placeholder = { Text("Search documents...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = MaterialTheme.shapes.medium
                    )
                }
            }
        }
    ) { innerPadding ->
        if (!hasFullAccess) {
            FullAccessRequestScreen(
                onRequest = onRequestFullAccess,
                modifier = Modifier.padding(innerPadding)
            )
        } else if (isLoading && documents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (documents.isEmpty()) {
            EmptyDocumentsScreen(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(filteredDocs, key = { it.id }) { item ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    documentToDelete = item
                                    false
                                }

                                SwipeToDismissBoxValue.EndToStart -> {
                                    documentToShare = item
                                    false
                                }

                                else -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = { SwipeBackground(dismissState) },
                        content = {
                            DocumentItem(
                                item = item,
                                viewModel = viewModel,
                                onClick = { onDocumentClick(item) }
                            )
                        }
                    )
                }
            }
        }

        documentToShare?.let { item ->
            LaunchedEffect(item) {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = item.mimeType ?: "*/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, item.uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share Document"))
                documentToShare = null
            }
        }

        documentToDelete?.let { item ->
            DeleteConfirmationDialog(
                onDismiss = { documentToDelete = null },
                onConfirm = {
                    scope.launch {
                        viewModel.deleteDocumentDirectly(item)
                        documentToDelete = null
                    }
                }
            )
        }
    }
}

@Composable
fun DeleteConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Delete") },
        text = { Text("Are you sure you want to permanently delete this document?") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeBackground(state: SwipeToDismissBoxState) {
    val direction = state.dismissDirection
    val color = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Color.Red.copy(alpha = 0.8f)
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color, MaterialTheme.shapes.medium)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        val icon = if (direction == SwipeToDismissBoxValue.StartToEnd) Icons.Default.Delete else Icons.Default.Share
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

@Composable
fun FullAccessRequestScreen(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Storage,
                null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Full Access Required",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "To show PDF, EPUB and other documents, the app needs permission to scan your device's files. This is a standard requirement for file manager apps on modern Android.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequest) {
                Text("Grant All Files Access")
            }
        }
    }
}

@Composable
fun EmptyDocumentsScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No documents found", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DocumentItem(item: MediaItem, viewModel: GalleryViewModel, onClick: () -> Unit) {
    val extension = item.displayName.substringAfterLast(".").lowercase()
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        thumbnail = viewModel.getDocumentThumbnail(item)
    }

    val defaultIcon = when (extension) {
        "pdf" -> Icons.Default.PictureAsPdf
        "epub", "mobi", "kf8", "fb2" -> Icons.Default.Book
        "svg" -> Icons.Default.Description
        "docx", "doc", "odt" -> Icons.Default.Description
        "xlsx", "xls", "ods" -> Icons.Default.Description
        "pptx", "ppt", "odp" -> Icons.Default.Description
        "dwg", "dxf" -> Icons.Default.Architecture
        else -> Icons.Default.Description
    }

    val iconColor = when (extension) {
        "pdf" -> MaterialTheme.colorScheme.error
        "epub", "mobi" -> MaterialTheme.colorScheme.tertiary
        "svg" -> MaterialTheme.colorScheme.secondary
        "docx", "doc", "odt" -> Color(0xFF2B579A)
        "xlsx", "xls", "ods" -> Color(0xFF217346)
        "pptx", "ppt", "odp" -> Color(0xFFD24726)
        "dwg" -> Color(0xFFD9534F)
        "dxf" -> Color(0xFF26A69A)
        else -> MaterialTheme.colorScheme.primary
    }

    val isBook = extension in listOf("epub", "mobi", "kf8", "fb2")
    val isOffice = extension in listOf("docx", "doc", "odt", "xlsx", "xls", "ods", "pptx", "ppt", "odp")
    val isSvg = extension == "svg"

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // High-End Thumbnail Container with Glassmorphic Badge
            Box(
                modifier = Modifier
                    .size(80.dp, 110.dp)
                    .shadow(8.dp, MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
            ) {
                if (thumbnail != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (isSvg) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = defaultIcon,
                            contentDescription = null,
                            tint = iconColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Glassmorphic Extension Badge Overlay
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .height(20.dp)
                        .widthIn(min = 32.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = extension.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 9.sp
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // Spine Shadow for Document Look
                if (isBook || isOffice || extension == "pdf") {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(6.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent)
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatSize(item.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.size(3.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = extension.uppercase(),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = iconColor
                    )
                }
            }
        }
    }
}

private fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "%.1f %s".format(sizeBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
