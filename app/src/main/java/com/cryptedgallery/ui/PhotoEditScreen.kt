package com.cryptedgallery.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.cryptedgallery.data.MediaItem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// --- Enums and Models ---

enum class AspectRatio(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    THREE_FOUR("3:4", 3f / 4f),
    NINE_SIXTEEN("9:16", 9f / 16f)
}

enum class EditTab {
    FILTERS, ADJUST, CROP
}

data class FilterInfo(val name: String, val matrix: ColorMatrix)

private data class PhotoState(
    val editedBitmap: Bitmap?,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val selectedFilter: FilterInfo,
    val photoScale: Float,
    val photoOffset: Offset,
    val selectedRatio: AspectRatio
)

// --- Constants ---

val Filters = listOf(
    FilterInfo("Original", ColorMatrix()),
    FilterInfo("B&W", ColorMatrix().apply { setToSaturation(0f) }),
    FilterInfo("Sepia", ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))),
    FilterInfo("Vivid", ColorMatrix().apply { setToSaturation(1.5f) }),
    FilterInfo("Vintage", ColorMatrix(floatArrayOf(
        0.9f, 0f, 0f, 0f, 0f,
        0f, 0.8f, 0f, 0f, 0f,
        0f, 0f, 0.5f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))),
    FilterInfo("Cool", ColorMatrix(floatArrayOf(
        0.5f, 0f, 0f, 0f, 0f,
        0f, 0.7f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )))
)

// --- Main Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditScreen(
    viewModel: GalleryViewModel,
    item: MediaItem,
    onCancel: () -> Unit,
    onSaved: () -> Unit
) {
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeTab by remember { mutableStateOf(EditTab.FILTERS) }
    
    // Adjustments
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var selectedFilter by remember { mutableStateOf(Filters[0]) }
    
    // Comparison mode
    var isComparing by remember { mutableStateOf(false) }

    // Crop / Viewfinder state
    var selectedRatio by remember { mutableStateOf(AspectRatio.FREE) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    
    var photoScale by remember { mutableFloatStateOf(1f) }
    var photoOffset by remember { mutableStateOf(Offset.Zero) }
    var viewfinderRect by remember { mutableStateOf(Rect.Zero) }
    var isInteracting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var undoStack by remember { mutableStateOf(emptyList<PhotoState>()) }
    var redoStack by remember { mutableStateOf(emptyList<PhotoState>()) }

    fun captureState(): PhotoState = PhotoState(
        editedBitmap = editedBitmap,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        selectedFilter = selectedFilter,
        photoScale = photoScale,
        photoOffset = photoOffset,
        selectedRatio = selectedRatio
    )

    fun pushToHistory() {
        undoStack = (undoStack + captureState()).takeLast(16)
        redoStack = emptyList()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.last()
            redoStack = redoStack + captureState()
            undoStack = undoStack.dropLast(1)
            
            editedBitmap = previousState.editedBitmap
            brightness = previousState.brightness
            contrast = previousState.contrast
            saturation = previousState.saturation
            selectedFilter = previousState.selectedFilter
            photoScale = previousState.photoScale
            photoOffset = previousState.photoOffset
            selectedRatio = previousState.selectedRatio
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.last()
            undoStack = undoStack + captureState()
            redoStack = redoStack.dropLast(1)
            
            editedBitmap = nextState.editedBitmap
            brightness = nextState.brightness
            contrast = nextState.contrast
            saturation = nextState.saturation
            selectedFilter = nextState.selectedFilter
            photoScale = nextState.photoScale
            photoOffset = nextState.photoOffset
            selectedRatio = nextState.selectedRatio
        }
    }
    
    val scope = rememberCoroutineScope()

    LaunchedEffect(item.uri) {
        originalBitmap = viewModel.loadBitmap(item.uri)
        editedBitmap = originalBitmap
    }

    // Initialize Viewfinder when entering CROP tab or changing ratio
    LaunchedEffect(activeTab, selectedRatio, layoutSize) {
        if (activeTab == EditTab.CROP && layoutSize != IntSize.Zero) {
            viewfinderRect = calculateViewfinderRect(layoutSize, selectedRatio.ratio)
            photoScale = 1f
            photoOffset = Offset.Zero
        }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            ModernBottomBar(
                activeTab = activeTab,
                onTabChange = { activeTab = it },
                brightness = brightness,
                onBrightnessChange = { brightness = it },
                contrast = contrast,
                onContrastChange = { contrast = it },
                saturation = saturation,
                onSaturationChange = { saturation = it },
                selectedFilter = selectedFilter,
                onFilterChange = { selectedFilter = it },
                selectedRatio = selectedRatio,
                onRatioChange = { selectedRatio = it },
                onPushHistory = { pushToHistory() },
                onApplyCrop = {
                    editedBitmap?.let { bitmap ->
                        pushToHistory()
                        val cropped = applyViewfinderCrop(bitmap, viewfinderRect, photoScale, photoOffset, layoutSize)
                        if (cropped != null) {
                            editedBitmap = cropped
                            viewfinderRect = Rect.Zero
                            activeTab = EditTab.FILTERS
                        }
                    }
                },
                onRotate = {
                    editedBitmap?.let { bitmap ->
                        pushToHistory()
                        val matrix = Matrix().apply { postRotate(90f) }
                        editedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        if (activeTab == EditTab.CROP) {
                            viewfinderRect = calculateViewfinderRect(layoutSize, selectedRatio.ratio)
                            photoScale = 1f
                            photoOffset = Offset.Zero
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
                .onGloballyPositioned { layoutSize = it.size }
                .pointerInput(activeTab, editedBitmap, viewfinderRect) {
                    if (activeTab == EditTab.CROP && editedBitmap != null && viewfinderRect != Rect.Zero) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val bitmap = editedBitmap!!
                            val screenW = layoutSize.width.toFloat()
                            val screenH = layoutSize.height.toFloat()
                            val imageInitialScale = min(screenW / bitmap.width, screenH / bitmap.height)
                            
                            val minScale = max(viewfinderRect.width / (bitmap.width * imageInitialScale), viewfinderRect.height / (bitmap.height * imageInitialScale))
                            
                            photoScale = (photoScale * zoom).coerceIn(minScale, 10f)
                            
                            val currentW = bitmap.width * imageInitialScale * photoScale
                            val currentH = bitmap.height * imageInitialScale * photoScale
                            
                            val displayedW = bitmap.width * imageInitialScale
                            val displayedH = bitmap.height * imageInitialScale
                            val imageInitialLeft = (screenW - displayedW) / 2
                            val imageInitialTop = (screenH - displayedH) / 2
                            
                            val newOffset = photoOffset + pan
                            
                            // Bounds calculation
                            val left = imageInitialLeft + newOffset.x - (currentW - displayedW) / 2
                            val top = imageInitialTop + newOffset.y - (currentH - displayedH) / 2
                            val right = left + currentW
                            val bottom = top + currentH
                            
                            var finalOffsetX = newOffset.x
                            var finalOffsetY = newOffset.y
                            
                            if (left > viewfinderRect.left) finalOffsetX -= (left - viewfinderRect.left)
                            if (right < viewfinderRect.right) finalOffsetX += (viewfinderRect.right - right)
                            if (top > viewfinderRect.top) finalOffsetY -= (top - viewfinderRect.top)
                            if (bottom < viewfinderRect.bottom) finalOffsetY += (viewfinderRect.bottom - bottom)
                            
                            photoOffset = Offset(finalOffsetX, finalOffsetY)
                            isInteracting = true
                        }
                    } else {
                        detectTapGestures(
                            onPress = {
                                isComparing = true
                                tryAwaitRelease()
                                isComparing = false
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            editedBitmap?.let { bitmap ->
                // Image Rendering
                val combinedMatrix = remember(brightness, contrast, saturation, selectedFilter, isComparing) {
                    if (isComparing) ColorMatrix() else {
                        val finalAndroidMatrix = AndroidColorMatrix()
                        finalAndroidMatrix.set(selectedFilter.matrix.values)
                        val adjMatrix = AndroidColorMatrix(floatArrayOf(
                            contrast, 0f, 0f, 0f, brightness * 255f,
                            0f, contrast, 0f, 0f, brightness * 255f,
                            0f, 0f, contrast, 0f, brightness * 255f,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        finalAndroidMatrix.postConcat(adjMatrix)
                        val satMatrix = AndroidColorMatrix()
                        satMatrix.setSaturation(saturation)
                        finalAndroidMatrix.postConcat(satMatrix)
                        ColorMatrix(finalAndroidMatrix.array)
                    }
                }

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (activeTab == EditTab.CROP) {
                                scaleX = photoScale
                                scaleY = photoScale
                                translationX = photoOffset.x
                                translationY = photoOffset.y
                            }
                        },
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.colorMatrix(combinedMatrix)
                )
                
                // Viewfinder Overlay
                if (activeTab == EditTab.CROP && viewfinderRect != Rect.Zero) {
                    ViewfinderOverlay(viewfinderRect, isInteracting)
                }
                
                if (isComparing) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    ) {
                        Text(
                            "Original", 
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } ?: CircularProgressIndicator(color = Color.White)

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { undo() },
                        enabled = undoStack.isNotEmpty(),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, tint = if (undoStack.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { redo() },
                        enabled = redoStack.isNotEmpty(),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, null, tint = if (redoStack.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f))
                    }
                }
                Text("Photo Editor", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            editedBitmap?.let { bitmap ->
                                // AUTO-COMMIT: Apply pending crop if in CROP tab
                                val baseBitmap = if (activeTab == EditTab.CROP && viewfinderRect != Rect.Zero) {
                                    applyViewfinderCrop(bitmap, viewfinderRect, photoScale, photoOffset, layoutSize) ?: bitmap
                                } else {
                                    bitmap
                                }
                                val finalBitmap = applyEffectsToBitmap(baseBitmap, brightness, contrast, saturation, selectedFilter.matrix)
                                val success = viewModel.saveBitmap(finalBitmap, item.displayName)
                                isSaving = false
                                if (success) onSaved()
                            }
                        }
                    },
                    enabled = editedBitmap != null && !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
fun ViewfinderOverlay(rect: Rect, isInteracting: Boolean) {
    val gridAlpha by animateFloatAsState(
        targetValue = if (isInteracting) 1f else 0.4f,
        animationSpec = tween(500),
        label = "gridAlpha"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val path = Path().apply { addRect(rect) }
        clipPath(path, clipOp = ClipOp.Difference) {
            drawRect(color = Color.Black.copy(alpha = 0.7f))
        }
        drawRect(color = Color.White, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 2.dp.toPx()))
        
        // Rule of Thirds
        val thirdW = rect.width / 3f
        val thirdH = rect.height / 3f
        val gridColor = Color.White.copy(alpha = 0.3f * gridAlpha)
        drawLine(gridColor, Offset(rect.left + thirdW, rect.top), Offset(rect.left + thirdW, rect.bottom), 1.dp.toPx())
        drawLine(gridColor, Offset(rect.left + 2 * thirdW, rect.top), Offset(rect.left + 2 * thirdW, rect.bottom), 1.dp.toPx())
        drawLine(gridColor, Offset(rect.left, rect.top + thirdH), Offset(rect.right, rect.top + thirdH), 1.dp.toPx())
        drawLine(gridColor, Offset(rect.left, rect.top + 2 * thirdH), Offset(rect.right, rect.top + 2 * thirdH), 1.dp.toPx())
    }
}

// --- Logic Helpers ---

fun calculateViewfinderRect(layoutSize: IntSize, aspectRatio: Float?): Rect {
    val screenW = layoutSize.width.toFloat()
    val screenH = layoutSize.height.toFloat()
    
    var rectW = screenW * 0.85f
    var rectH = screenH * 0.6f
    
    if (aspectRatio != null) {
        if (rectW / rectH > aspectRatio) {
            rectW = rectH * aspectRatio
        } else {
            rectH = rectW / aspectRatio
        }
        if (rectW > screenW * 0.9f) {
            rectW = screenW * 0.9f
            rectH = rectW / aspectRatio
        }
        if (rectH > screenH * 0.7f) {
            rectH = screenH * 0.7f
            rectW = rectH * aspectRatio
        }
    } else {
        rectW = screenW * 0.75f
        rectH = screenH * 0.75f
    }
    
    val left = (screenW - rectW) / 2
    val top = (screenH - rectH) / 2
    return Rect(left, top, left + rectW, top + rectH)
}

fun applyViewfinderCrop(
    bitmap: Bitmap,
    viewfinder: Rect,
    photoScale: Float,
    photoOffset: Offset,
    layoutSize: IntSize
): Bitmap? {
    if (viewfinder == Rect.Zero || layoutSize == IntSize.Zero) return null
    
    val screenW = layoutSize.width.toFloat()
    val screenH = layoutSize.height.toFloat()
    
    val imageInitialScale = min(screenW / bitmap.width, screenH / bitmap.height)
    val displayedW = bitmap.width * imageInitialScale
    val displayedH = bitmap.height * imageInitialScale
    
    val imageInitialLeft = (screenW - displayedW) / 2
    val imageInitialTop = (screenH - displayedH) / 2
    
    val currentW = displayedW * photoScale
    val currentH = displayedH * photoScale
    val currentLeft = imageInitialLeft + photoOffset.x - (currentW - displayedW) / 2
    val currentTop = imageInitialTop + photoOffset.y - (currentH - displayedH) / 2
    
    val relativeLeft = (viewfinder.left - currentLeft) / currentW
    val relativeTop = (viewfinder.top - currentTop) / currentH
    val relativeWidth = viewfinder.width / currentW
    val relativeHeight = viewfinder.height / currentH
    
    val bitmapX = (relativeLeft * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
    val bitmapY = (relativeTop * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
    val bitmapW = (relativeWidth * bitmap.width).roundToInt().coerceIn(1, bitmap.width - bitmapX)
    val bitmapH = (relativeHeight * bitmap.height).roundToInt().coerceIn(1, bitmap.height - bitmapY)
    
    return try {
        Bitmap.createBitmap(bitmap, bitmapX, bitmapY, bitmapW, bitmapH)
    } catch (e: Exception) { null }
}

// --- Shared Components ---

@Composable
fun ModernBottomBar(
    activeTab: EditTab,
    onTabChange: (EditTab) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    contrast: Float,
    onContrastChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    selectedFilter: FilterInfo,
    onFilterChange: (FilterInfo) -> Unit,
    selectedRatio: AspectRatio,
    onRatioChange: (AspectRatio) -> Unit,
    onPushHistory: () -> Unit,
    onApplyCrop: () -> Unit,
    onRotate: () -> Unit
) {
    Surface(color = Color.Black.copy(alpha = 0.8f), modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(modifier = Modifier.height(160.dp).padding(16.dp)) {
                when (activeTab) {
                    EditTab.FILTERS -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(Filters) { filter ->
                                FilterItem(filter = filter, isSelected = selectedFilter == filter, onClick = {
                                    if (selectedFilter != filter) {
                                        onPushHistory()
                                        onFilterChange(filter)
                                    }
                                })
                            }
                        }
                    }
                    EditTab.ADJUST -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AdjustmentSlider("Brightness", brightness, -0.5f, 0.5f, onBrightnessChange, onPushHistory)
                            AdjustmentSlider("Contrast", contrast, 0.5f, 1.5f, onContrastChange, onPushHistory)
                            AdjustmentSlider("Saturation", saturation, 0f, 2f, onSaturationChange, onPushHistory)
                        }
                    }
                    EditTab.CROP -> {
                        Column {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(AspectRatio.entries) { ratio ->
                                    FilterChip(
                                        selected = selectedRatio == ratio,
                                        onClick = { onRatioChange(ratio) },
                                        label = { Text(ratio.label) },
                                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedLabelColor = Color.Black, selectedContainerColor = MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Button(onClick = onRotate, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                                    Icon(Icons.AutoMirrored.Filled.RotateRight, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Rotate")
                                }
                                Button(onClick = onApplyCrop) {
                                    Icon(Icons.Default.Check, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }
            }
            NavigationBar(containerColor = Color.Transparent, contentColor = Color.White) {
                NavigationBarItem(selected = activeTab == EditTab.FILTERS, onClick = { onTabChange(EditTab.FILTERS) }, icon = { Icon(Icons.Default.FilterBAndW, null) }, label = { Text("Filters") })
                NavigationBarItem(selected = activeTab == EditTab.ADJUST, onClick = { onTabChange(EditTab.ADJUST) }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Adjust") })
                NavigationBarItem(selected = activeTab == EditTab.CROP, onClick = { onTabChange(EditTab.CROP) }, icon = { Icon(Icons.Default.Crop, null) }, label = { Text("Crop") })
            }
        }
    }
}

@Composable
fun FilterItem(filter: FilterInfo, isSelected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color.DarkGray).border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.linearGradient(listOf(Color.Red, Color.Green, Color.Blue)), colorFilter = ColorFilter.colorMatrix(filter.matrix))
            }
        }
        Text(filter.name, style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color.Cyan else Color.White)
    }
}

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(80.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

fun applyEffectsToBitmap(bitmap: Bitmap, brightness: Float, contrast: Float, saturation: Float, filterMatrix: ColorMatrix): Bitmap {
    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(result)
    val paint = AndroidPaint()
    val finalAndroidMatrix = AndroidColorMatrix()
    finalAndroidMatrix.set(filterMatrix.values)
    val adjMatrix = AndroidColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, brightness * 255f,
        0f, contrast, 0f, 0f, brightness * 255f,
        0f, 0f, contrast, 0f, brightness * 255f,
        0f, 0f, 0f, 1f, 0f
    ))
    finalAndroidMatrix.postConcat(adjMatrix)
    val satMatrix = AndroidColorMatrix()
    satMatrix.setSaturation(saturation)
    finalAndroidMatrix.postConcat(satMatrix)
    paint.colorFilter = ColorMatrixColorFilter(finalAndroidMatrix)
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return result
}
