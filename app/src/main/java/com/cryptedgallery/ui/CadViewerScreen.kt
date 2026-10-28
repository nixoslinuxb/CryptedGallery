package com.cryptedgallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cryptedgallery.data.CadDrawing
import com.cryptedgallery.data.CadLoadResult
import com.cryptedgallery.data.CadShape
import com.cryptedgallery.data.MediaItem
import kotlin.math.min

private val CadBackground = Color(0xFF0E1319)
private val CadLineColor = Color(0xFFDCE6F2)

@Composable
fun CadViewerScreen(viewModel: GalleryViewModel, item: MediaItem) {
    var result by remember(item.id) { mutableStateOf<CadLoadResult?>(null) }

    LaunchedEffect(item.id) {
        result = viewModel.loadCadDrawing(item)
    }

    Box(modifier = Modifier.fillMaxSize().background(CadBackground)) {
        when (val current = result) {
            null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CadLineColor)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Preparing drawing…",
                        color = CadLineColor.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            is CadLoadResult.Failure -> CadErrorState(current.message)

            is CadLoadResult.Success -> CadDrawingCanvas(current.drawing)
        }
    }
}

@Composable
private fun CadErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Drawing could not be opened",
                color = CadLineColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = CadLineColor.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CadDrawingCanvas(drawing: CadDrawing) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var fitScale by remember(drawing) { mutableFloatStateOf(0f) }
    var scale by remember(drawing) { mutableFloatStateOf(0f) }
    var offset by remember(drawing) { mutableStateOf(Offset.Zero) }

    val polylines = remember(drawing) {
        drawing.shapes.filterIsInstance<CadShape.Polyline>().map { it to buildCadPath(it) }
    }
    val circles = remember(drawing) { drawing.shapes.filterIsInstance<CadShape.Circle>() }
    val texts = remember(drawing) { drawing.shapes.filterIsInstance<CadShape.Text>() }

    LaunchedEffect(drawing, viewportSize) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            val fit = computeCadFitScale(drawing, viewportSize)
            fitScale = fit
            if (scale <= 0f) {
                scale = fit
                offset = Offset.Zero
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .pointerInput(drawing) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val currentScale = scale
                        if (currentScale <= 0f || fitScale <= 0f) return@detectTransformGestures
                        val newScale = (currentScale * zoom).coerceIn(fitScale * 0.35f, fitScale * 150f)
                        if (newScale == currentScale) return@detectTransformGestures
                        val viewportCenter = Offset(size.width / 2f, size.height / 2f)
                        val fromCenter = centroid - viewportCenter - offset
                        offset = centroid - viewportCenter - fromCenter * (newScale / currentScale) + pan
                        scale = newScale
                    }
                }
                .pointerInput(drawing) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (fitScale > 0f) {
                                scale = fitScale
                                offset = Offset.Zero
                            }
                        }
                    )
                }
        ) {
            val currentScale = scale
            if (currentScale <= 0f) return@Canvas
            val bounds = drawing.bounds
            val strokeWidth = 1.1.dp.toPx() / currentScale
            withTransform({
                translate(size.width / 2f + offset.x, size.height / 2f + offset.y)
                scale(currentScale, -currentScale, Offset.Zero)
                translate(-bounds.centerX, -bounds.centerY)
            }) {
                for ((polyline, path) in polylines) {
                    drawPath(
                        path = path,
                        color = CadLineColor,
                        style = if (polyline.filled) Fill else Stroke(width = strokeWidth)
                    )
                }
                for (circle in circles) {
                    drawCircle(
                        color = CadLineColor,
                        radius = circle.radius,
                        center = Offset(circle.cx, circle.cy),
                        style = Stroke(width = strokeWidth)
                    )
                }
                for (text in texts) {
                    drawCadText(text, CadLineColor)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CadControlButton(Icons.Default.Add, "Zoom in") {
                if (fitScale > 0f) scale = (scale * 1.4f).coerceAtMost(fitScale * 150f)
            }
            CadControlButton(Icons.Default.Remove, "Zoom out") {
                if (fitScale > 0f) scale = (scale / 1.4f).coerceAtLeast(fitScale * 0.35f)
            }
            CadControlButton(Icons.Default.FitScreen, "Fit to screen") {
                if (fitScale > 0f) {
                    scale = fitScale
                    offset = Offset.Zero
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, bottom = 32.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = CadLineColor
        ) {
            Text(
                text = "${drawing.shapes.size} entities",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun CadControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = CadLineColor
        )
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

private fun computeCadFitScale(drawing: CadDrawing, viewport: IntSize): Float {
    val bounds = drawing.bounds
    if (!bounds.isValid || viewport.width <= 0 || viewport.height <= 0) return 1f
    val width = if (bounds.width > 1e-6f) bounds.width else 1e-6f
    val height = if (bounds.height > 1e-6f) bounds.height else 1e-6f
    var fit = min(viewport.width / width, viewport.height / height) * 0.88f
    if (!fit.isFinite() || fit <= 0f || fit > 1e7f) fit = 1f
    return fit
}

private fun buildCadPath(polyline: CadShape.Polyline): Path {
    val path = Path()
    val points = polyline.points
    if (points.size >= 4) {
        path.moveTo(points[0], points[1])
        var index = 2
        while (index + 1 < points.size) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        if (polyline.closed) path.close()
    }
    return path
}

private fun DrawScope.drawCadText(text: CadShape.Text, color: Color) {
    if (text.value.isEmpty()) return
    val textHeight = text.height
    if (!textHeight.isFinite() || textHeight <= 0f || textHeight > 1e5f) return
    val argb = color.toArgb()
    val lines = text.value.split('\n').take(40)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = argb
            textSize = textHeight
        }
        native.save()
        native.translate(text.x, text.y)
        // Undo the Y flip of the drawing transform so glyphs are not mirrored.
        native.scale(1f, -1f)
        if (text.rotation != 0f) native.rotate(-text.rotation)
        var baseline = 0f
        for (line in lines) {
            if (line.isNotEmpty()) native.drawText(line.take(200), 0f, baseline, paint)
            baseline += textHeight * 1.35f
        }
        native.restore()
    }
}
