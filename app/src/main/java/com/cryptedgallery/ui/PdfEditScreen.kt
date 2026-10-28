package com.cryptedgallery.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptedgallery.data.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.focus.focusRequester
import kotlin.math.abs

// --- Models ---

enum class PdfTool {
    PEN, HIGHLIGHTER, ERASER, TEXT, SELECT
}

data class DrawPath(
    val path: Path,
    val tool: PdfTool,
    val color: Color,
    val strokeWidth: Float
)

data class TextAnnotation(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var x: Float,
    var y: Float,
    val color: Color,
    val fontSize: Float = 40f
)

data class ImageAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bitmap: Bitmap,
    var x: Float,
    var y: Float,
    var scale: Float = 1f
)

data class ExtractedText(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float
)

private data class PdfEditState(
    val pageOrder: List<Int>,
    val pagePaths: Map<Int, List<DrawPath>>,
    val pageTexts: Map<Int, List<TextAnnotation>>,
    val pageImages: Map<Int, List<ImageAnnotation>>,
    val pageWhitespaces: Map<Int, List<ExtractedText>> // Track where we blanked out original text
)

// --- Main Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditScreen(
    viewModel: GalleryViewModel,
    item: MediaItem,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    var pageOrder by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentTool by remember { mutableStateOf(PdfTool.PEN) }
    var currentColor by remember { mutableStateOf(Color.Red) }
    
    // Page Data
    val pageBitmaps = remember { mutableStateListOf<Bitmap?>() }
    val pagePaths = remember { mutableStateMapOf<Int, MutableList<DrawPath>>() }
    val pageTexts = remember { mutableStateMapOf<Int, MutableList<TextAnnotation>>() }
    val pageImages = remember { mutableStateMapOf<Int, MutableList<ImageAnnotation>>() }
    val pageWhitespaces = remember { mutableStateMapOf<Int, MutableList<ExtractedText>>() }
    val pageOriginalText = remember { mutableStateMapOf<Int, List<ExtractedText>>() }
    
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showOrganizer by remember { mutableStateOf(false) }
    var isToolsCollapsed by remember { mutableStateOf(false) }

    var showTextDialog by remember { mutableStateOf(false) }
    var textToEdit by remember { mutableStateOf<TextAnnotation?>(null) }
    var extractedTextToEdit by remember { mutableStateOf<ExtractedText?>(null) }

    // Undo/Redo stacks
    var undoStack by remember { mutableStateOf(emptyList<PdfEditState>()) }
    var redoStack by remember { mutableStateOf(emptyList<PdfEditState>()) }

    // Visual feedback for Undo/Redo
    var undoClickCount by remember { mutableIntStateOf(0) }
    var redoClickCount by remember { mutableIntStateOf(0) }

    fun captureState(): PdfEditState = PdfEditState(
        pageOrder = pageOrder.toList(),
        pagePaths = pagePaths.mapValues { it.value.map { p -> p.copy() } },
        pageTexts = pageTexts.mapValues { it.value.map { t -> t.copy() } },
        pageImages = pageImages.mapValues { it.value.map { i -> i.copy() } },
        pageWhitespaces = pageWhitespaces.mapValues { it.value.map { w -> w.copy() } }
    )

    fun pushToHistory() {
        undoStack = (undoStack + captureState()).takeLast(16)
        redoStack = emptyList()
    }

    fun restoreState(state: PdfEditState) {
        pageOrder = state.pageOrder
        pagePaths.clear()
        state.pagePaths.forEach { (k, v) -> pagePaths[k] = v.toMutableList() }
        pageTexts.clear()
        state.pageTexts.forEach { (k, v) -> pageTexts[k] = v.toMutableList() }
        pageImages.clear()
        state.pageImages.forEach { (k, v) -> pageImages[k] = v.toMutableList() }
        pageWhitespaces.clear()
        state.pageWhitespaces.forEach { (k, v) -> pageWhitespaces[k] = v.toMutableList() }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            undoClickCount++
            val currentState = captureState()
            val previousState = undoStack.last()
            redoStack = (redoStack + currentState).takeLast(16)
            undoStack = undoStack.dropLast(1)
            restoreState(previousState)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            redoClickCount++
            val currentState = captureState()
            val nextState = redoStack.last()
            undoStack = (undoStack + currentState).takeLast(16)
            redoStack = redoStack.dropLast(1)
            restoreState(nextState)
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { newUri ->
            scope.launch {
                isLoading = true
                val newPageCount = viewModel.getPdfPageCount(newUri)
                val startIdx = pageBitmaps.size
                val newIndices = (startIdx until startIdx + newPageCount).toList()
                
                repeat(newPageCount) { i ->
                    val bitmap = viewModel.renderPdfPage(newUri, i)
                    pageBitmaps.add(bitmap)
                    pagePaths[startIdx + i] = mutableListOf()
                    pageTexts[startIdx + i] = mutableListOf()
                }
                pushToHistory()
                pageOrder = pageOrder + newIndices
                isLoading = false
            }
        }
    }

    LaunchedEffect(item) {
        isLoading = true
        val count = viewModel.getPdfPageCount(item.uri)
        pageOrder = (0 until count).toList()
        repeat(count) { index ->
            pageBitmaps.add(viewModel.renderPdfPage(item.uri, index)) 
            pagePaths[index] = mutableListOf()
            pageTexts[index] = mutableListOf()
            
            val extracted = viewModel.extractPdfTextWithCoordinates(item.uri, index)
            pageWhitespaces[index] = mutableListOf()
            pageOriginalText[index] = extracted
        }
        isLoading = false
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            scope.launch {
                val bitmap = viewModel.loadBitmap(it)
                if (bitmap != null) {
                    val currentOriginalPageIndex = pageOrder.getOrNull(currentPageIndex)
                    if (currentOriginalPageIndex != null) {
                        pushToHistory()
                        val currentBmp = pageBitmaps.getOrNull(currentOriginalPageIndex)
                        val centerX = (currentBmp?.width ?: 1000) / 2f
                        val centerY = (currentBmp?.height ?: 1000) / 2f
                        val list = pageImages[currentOriginalPageIndex] ?: mutableListOf()
                        list.add(ImageAnnotation(bitmap = bitmap, x = centerX - (bitmap.width / 2f), y = centerY - (bitmap.height / 2f)))
                        pageImages[currentOriginalPageIndex] = list
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        // --- IMMERSIVE CANVAS ---
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        } else {
            val currentOriginalPageIndex = pageOrder.getOrNull(currentPageIndex)
            if (currentOriginalPageIndex != null) {
                val currentBitmap = pageBitmaps.getOrNull(currentOriginalPageIndex)
                val currentPaths = pagePaths[currentOriginalPageIndex] ?: mutableListOf()
                val currentTexts = pageTexts[currentOriginalPageIndex] ?: mutableListOf()
                val currentImages = pageImages[currentOriginalPageIndex] ?: mutableListOf()

                var lastTapOffset by remember { mutableStateOf(Offset.Zero) }

                var pdfScale by remember { mutableFloatStateOf(1f) }
                var pdfOffset by remember { mutableStateOf(Offset.Zero) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val activePointers = event.changes.filter { it.pressed }
                                    if (activePointers.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        pdfScale = (pdfScale * zoom).coerceIn(0.5f, 10f)
                                        pdfOffset += pan
                                        event.changes.forEach { it.consume() }
                                    } else if (activePointers.isEmpty()) {
                                        if (pdfScale < 1f) {
                                            pdfScale = 1f
                                            pdfOffset = Offset.Zero
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    currentBitmap?.let { bitmap ->
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = pdfScale
                                    scaleY = pdfScale
                                    translationX = pdfOffset.x
                                    translationY = pdfOffset.y
                                }
                                .padding(32.dp)
                                .shadow(24.dp, RoundedCornerShape(12.dp))
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                                .fillMaxHeight()
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                            
                            var activePath by remember { mutableStateOf<Path?>(null) }
                            var redrawTrigger by remember { mutableIntStateOf(0) }

                            val updatedTexts by rememberUpdatedState(currentTexts)
                            val updatedImages by rememberUpdatedState(currentImages)
                            val updatedPaths by rememberUpdatedState(currentPaths)



                            ComposeCanvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(currentTool, currentColor) {
                                        if (currentTool == PdfTool.TEXT) {
                                            detectTapGestures { offset ->
                                                val snapshotTexts = updatedTexts.toList()
                                                val hitText = snapshotTexts.findLast { 
                                                    offset.x >= it.x - 50f && offset.x <= it.x + (it.fontSize * it.text.length * 0.6f) + 50f &&
                                                    offset.y >= it.y - it.fontSize - 50f && offset.y <= it.y + 50f
                                                }
                                                
                                                if (hitText != null) {
                                                    textToEdit = hitText
                                                    showTextDialog = true
                                                } else {
                                                    pushToHistory()
                                                    val newText = TextAnnotation(
                                                        text = "",
                                                        x = offset.x,
                                                        y = offset.y + 40f,
                                                        color = currentColor
                                                    )
                                                    currentTexts.add(newText)
                                                    pageTexts[currentOriginalPageIndex] = currentTexts.toMutableList()
                                                    textToEdit = newText
                                                    showTextDialog = true
                                                }
                                            }
                                        } else if (currentTool == PdfTool.PEN || currentTool == PdfTool.HIGHLIGHTER || currentTool == PdfTool.ERASER) {
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    activePath = Path().apply { moveTo(offset.x, offset.y) }
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    activePath?.lineTo(change.position.x, change.position.y)
                                                    redrawTrigger++
                                                },
                                                onDragEnd = {
                                                    activePath?.let {
                                                        pushToHistory()
                                                        val mutablePaths = updatedPaths.toMutableList()
                                                        mutablePaths.add(DrawPath(
                                                            path = it,
                                                            tool = currentTool,
                                                            color = if (currentTool == PdfTool.ERASER) Color.White else currentColor,
                                                            strokeWidth = if (currentTool == PdfTool.HIGHLIGHTER || currentTool == PdfTool.ERASER) 40f else 10f
                                                        ))
                                                        pagePaths[currentOriginalPageIndex] = mutablePaths
                                                    }
                                                    activePath = null
                                                    redrawTrigger++
                                                }
                                            )
                                        } else if (currentTool == PdfTool.SELECT) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown()
                                                val snapshotImages = updatedImages.toList()
                                                val snapshotTexts = updatedTexts.toList()
                                                
                                                // Hit test
                                                var hitImage = snapshotImages.findLast { 
                                                    down.position.x >= it.x - 50f && down.position.x <= it.x + (it.bitmap.width * it.scale) + 50f &&
                                                    down.position.y >= it.y - 50f && down.position.y <= it.y + (it.bitmap.height * it.scale) + 50f
                                                }
                                                var hitText: TextAnnotation? = null
                                                if (hitImage == null) {
                                                    hitText = snapshotTexts.findLast { 
                                                        down.position.x >= it.x - 50f && down.position.x <= it.x + (it.fontSize * it.text.length * 0.6f) + 50f &&
                                                        down.position.y >= it.y - it.fontSize - 50f && down.position.y <= it.y + 50f
                                                    }
                                                }

                                                if (hitImage == null && hitText == null) {
                                                    // Empty tap logic
                                                } else {
                                                    pushToHistory()
                                                    var totalZoom = 1f
                                                    var upPosition = down.position
                                                    val startTime = System.currentTimeMillis()

                                                    do {
                                                        val event = awaitPointerEvent()
                                                        val zoom = event.calculateZoom()
                                                        val pan = event.calculatePan()
                                                        
                                                        totalZoom *= zoom
                                                        val change = event.changes.firstOrNull()
                                                        if (change != null) upPosition = change.position

                                                        val mutableImages = updatedImages.toMutableList()
                                                        hitImage?.let { img ->
                                                            val index = mutableImages.indexOfFirst { it.id == img.id }
                                                            if (index != -1) {
                                                                val newScale = (img.scale * zoom).coerceIn(0.1f, 5f)
                                                                mutableImages[index] = img.copy(
                                                                    x = img.x + pan.x, 
                                                                    y = img.y + pan.y,
                                                                    scale = newScale
                                                                )
                                                                hitImage = mutableImages[index]
                                                                pageImages[currentOriginalPageIndex] = mutableImages
                                                            }
                                                        }
                                                        
                                                        val mutableTexts = updatedTexts.toMutableList()
                                                        hitText?.let { txt ->
                                                            val index = mutableTexts.indexOfFirst { it.id == txt.id }
                                                            if (index != -1) {
                                                                mutableTexts[index] = txt.copy(x = txt.x + pan.x, y = txt.y + pan.y)
                                                                hitText = mutableTexts[index]
                                                                pageTexts[currentOriginalPageIndex] = mutableTexts
                                                            }
                                                        }
                                                        
                                                        if (pan != Offset.Zero || zoom != 1f) {
                                                            redrawTrigger++
                                                            event.changes.forEach { it.consume() }
                                                        }

                                                    } while (event.changes.any { it.pressed })

                                                    val distance = (upPosition - down.position).getDistance()
                                                    val duration = System.currentTimeMillis() - startTime
                                                    
                                                    // If it was just a tap on text, open edit dialog
                                                    if ((distance < 150f || duration < 400) && hitText != null) {
                                                        val finalHitText = hitText // make local copy for null safety
                                                        textToEdit = finalHitText
                                                        showTextDialog = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                redrawTrigger 
                                drawContext.canvas.nativeCanvas.apply {
                                    currentImages.forEach { img ->
                                        val matrix = android.graphics.Matrix().apply {
                                            postScale(img.scale, img.scale)
                                            postTranslate(img.x, img.y)
                                        }
                                        drawBitmap(img.bitmap, matrix, null)
                                    }

                                    currentPaths.forEach { drawPath ->
                                        val paint = Paint().apply {
                                            color = drawPath.color.toArgb()
                                            style = Paint.Style.STROKE
                                            strokeWidth = drawPath.strokeWidth
                                            isAntiAlias = true
                                            if (drawPath.tool == PdfTool.HIGHLIGHTER) alpha = 100
                                        }
                                        drawPath(drawPath.path, paint)
                                    }
                                    
                                    activePath?.let { path ->
                                        val paint = Paint().apply {
                                            color = if (currentTool == PdfTool.ERASER) Color.White.toArgb() else currentColor.toArgb()
                                            style = Paint.Style.STROKE
                                            strokeWidth = if (currentTool == PdfTool.HIGHLIGHTER || currentTool == PdfTool.ERASER) 40f else 10f
                                            isAntiAlias = true
                                            if (currentTool == PdfTool.HIGHLIGHTER) alpha = 100
                                        }
                                        drawPath(path, paint)
                                    }

                                    currentTexts.forEach { annotation ->
                                        val paint = Paint().apply {
                                            color = annotation.color.toArgb()
                                            textSize = annotation.fontSize
                                            isAntiAlias = true
                                        }
                                        drawText(annotation.text, annotation.x, annotation.y, paint)
                                    }
                                }
                            }
                        }
                    }
                }

                if (showTextDialog) {
                    TextAnnotationDialog(
                        initialText = textToEdit?.text ?: "",
                        onDismiss = { 
                            showTextDialog = false 
                            textToEdit = null
                        },
                        onConfirm = { text ->
                            pushToHistory()
                            if (textToEdit != null) {
                                val index = currentTexts.indexOfFirst { it.id == textToEdit?.id }
                                if (index != -1) {
                                    val updatedText = textToEdit!!.copy(text = text)
                                    val mutableTexts = currentTexts.toMutableList()
                                    mutableTexts[index] = updatedText
                                    pageTexts[currentOriginalPageIndex] = mutableTexts
                                }
                            } else {
                                val mutableTexts = currentTexts.toMutableList()
                                mutableTexts.add(TextAnnotation(
                                    text = text,
                                    x = lastTapOffset.x,
                                    y = lastTapOffset.y,
                                    color = currentColor
                                ))
                                pageTexts[currentOriginalPageIndex] = mutableTexts
                            }
                            showTextDialog = false
                            textToEdit = null
                        }
                    )
                }
            }
        }

        // --- FLOATING TOP BAR ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            color = Color.Black.copy(alpha = 0.4f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                
                Spacer(Modifier.width(4.dp))
                
                // UNDO
                val undoScale by animateFloatAsState(targetValue = if (undoClickCount % 2 == 0) 1f else 1.2f, label = "undoScale")
                IconButton(
                    onClick = { undo() },
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.scale(undoScale)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = if (undoStack.isNotEmpty()) Color.Cyan else Color.White.copy(alpha = 0.2f))
                }
                
                // REDO
                val redoScale by animateFloatAsState(targetValue = if (redoClickCount % 2 == 0) 1f else 1.2f, label = "redoScale")
                IconButton(
                    onClick = { redo() },
                    enabled = redoStack.isNotEmpty(),
                    modifier = Modifier.scale(redoScale)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Redo, null, tint = if (redoStack.isNotEmpty()) Color.Cyan else Color.White.copy(alpha = 0.2f))
                }

                Spacer(Modifier.weight(1f))
                
                IconButton(onClick = { pdfPicker.launch("application/pdf") }) {
                    Icon(Icons.Default.PostAdd, "Add PDF", tint = Color.White)
                }
                IconButton(onClick = { showOrganizer = !showOrganizer }) {
                    Icon(Icons.Default.Layers, "Organizer", tint = if (showOrganizer) Color.Cyan else Color.White)
                }
                if (!isSaving) {
                    IconButton(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                val editedBitmaps = finalizePages(pageOrder, pageBitmaps, pagePaths, pageTexts)
                                val success = viewModel.savePdfProject(item, editedBitmaps)
                                isSaving = false
                                if (success) onBack()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, null, tint = Color.Cyan)
                    }
                }
            }
        }

        // --- FLOATING TEXT LIST (When SELECT tool is active) ---
        AnimatedVisibility(
            visible = currentTool == PdfTool.SELECT && (pageTexts[pageOrder.getOrNull(currentPageIndex)]?.isNotEmpty() == true),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.9f)
            ) {
                LazyRow(
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val texts = pageTexts[pageOrder.getOrNull(currentPageIndex)] ?: emptyList()
                    itemsIndexed(texts) { _, textAnn ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.clickable {
                                textToEdit = textAnn
                                showTextDialog = true
                            }
                        ) {
                            Text(
                                text = if (textAnn.text.length > 15) textAnn.text.take(15) + "..." else textAnn.text,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // --- FLOATING TOOL PALETTE ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                onClick = { isToolsCollapsed = !isToolsCollapsed },
                color = Color.Black.copy(alpha = 0.4f),
                shape = CircleShape,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    if (isToolsCollapsed) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = Color.White, modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }

            AnimatedVisibility(visible = !isToolsCollapsed) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item { PdfToolButtonModern(Icons.Default.NearMe, "Select", currentTool == PdfTool.SELECT) { currentTool = PdfTool.SELECT } }
                            item { PdfToolButtonModern(Icons.Default.Image, "Image", false) { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
                            item { PdfToolButtonModern(Icons.Default.Create, "Pen", currentTool == PdfTool.PEN) { currentTool = PdfTool.PEN } }
                            item { PdfToolButtonModern(Icons.Default.Brush, "Highlighter", currentTool == PdfTool.HIGHLIGHTER) { currentTool = PdfTool.HIGHLIGHTER } }
                            item { PdfToolButtonModern(Icons.Default.AutoFixNormal, "Eraser", currentTool == PdfTool.ERASER) { currentTool = PdfTool.ERASER } }
                            item { PdfToolButtonModern(Icons.Default.TextFields, "Text", currentTool == PdfTool.TEXT) { currentTool = PdfTool.TEXT } }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Black, Color.White)
                            colors.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(color, CircleShape)
                                        .border(if (currentColor == color) 2.dp else 0.dp, Color.White, CircleShape)
                                        .clickable { currentColor = color }
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- PAGE ORGANIZER ---
        AnimatedVisibility(
            visible = showOrganizer,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(130.dp).fillMaxHeight(0.7f)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                PageOrganizerModern(
                    pageOrder = pageOrder,
                    pageBitmaps = pageBitmaps,
                    currentPageIndex = currentPageIndex,
                    onPageClick = { currentPageIndex = it },
                    onDelete = { idx ->
                        if (pageOrder.size > 1) {
                            pushToHistory()
                            val currentId = pageOrder.getOrNull(currentPageIndex)
                            pageOrder = pageOrder.toMutableList().apply { removeAt(idx) }
                            currentPageIndex = currentId?.let { pageOrder.indexOf(it).coerceAtLeast(0) } ?: 0
                        }
                    },
                    onMoveUp = { idx ->
                        if (idx > 0) {
                            pushToHistory()
                            pageOrder = pageOrder.toMutableList().apply {
                                val temp = removeAt(idx)
                                add(idx - 1, temp)
                            }
                            currentPageIndex = if (currentPageIndex == idx) idx - 1 else if (currentPageIndex == idx - 1) idx else currentPageIndex
                        }
                    },
                    onMoveDown = { idx ->
                        if (idx < pageOrder.size - 1) {
                            pushToHistory()
                            pageOrder = pageOrder.toMutableList().apply {
                                val temp = removeAt(idx)
                                add(idx + 1, temp)
                            }
                            currentPageIndex = if (currentPageIndex == idx) idx + 1 else if (currentPageIndex == idx + 1) idx else currentPageIndex
                        }
                    }
                )
            }
        }

        if (isSaving) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text("Compiling Professional PDF...", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun PdfToolButtonModern(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
        shape = CircleShape
    ) {
        Icon(icon, label, tint = if (isSelected) Color.Cyan else Color.White, modifier = Modifier.padding(12.dp).size(24.dp))
    }
}

@Composable
fun PageOrganizerModern(
    pageOrder: List<Int>,
    pageBitmaps: List<Bitmap?>,
    currentPageIndex: Int,
    onPageClick: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(pageOrder) { index, pageId ->
            val bitmap = pageBitmaps.getOrNull(pageId)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp, 110.dp)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(Color.DarkGray, RoundedCornerShape(8.dp))
                        .border(if (currentPageIndex == index) 2.dp else 0.dp, Color.Cyan, RoundedCornerShape(8.dp))
                        .clickable { onPageClick(index) }
                ) {
                    bitmap?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    IconButton(
                        onClick = { onDelete(index) },
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { onMoveUp(index) }, enabled = index > 0, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ArrowUpward, null, tint = if (index > 0) Color.White else Color.Gray, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onMoveDown(index) }, enabled = index < pageOrder.size - 1, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ArrowDownward, null, tint = if (index < pageOrder.size - 1) Color.White else Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
                Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun TextAnnotationDialog(initialText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialText.isEmpty()) "Add Text Annotation" else "Edit Text") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter text...") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(if (initialText.isEmpty()) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun finalizePages(
    pageOrder: List<Int>, 
    bitmaps: List<Bitmap?>, 
    pathsMap: Map<Int, List<DrawPath>>,
    textsMap: Map<Int, List<TextAnnotation>>
): List<Bitmap> {
    return pageOrder.mapNotNull { originalIdx ->
        val bitmap = bitmaps.getOrNull(originalIdx) ?: return@mapNotNull null
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        
        pathsMap[originalIdx]?.forEach { drawPath ->
            val paint = Paint().apply {
                color = drawPath.color.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = drawPath.strokeWidth
                isAntiAlias = true
                if (drawPath.tool == PdfTool.HIGHLIGHTER) alpha = 100
            }
            canvas.drawPath(drawPath.path, paint)
        }
        
        textsMap[originalIdx]?.forEach { annotation ->
            val paint = Paint().apply {
                color = annotation.color.toArgb()
                textSize = annotation.fontSize
                isAntiAlias = true
            }
            canvas.drawText(annotation.text, annotation.x, annotation.y, paint)
        }
        
        mutableBitmap
    }
}
