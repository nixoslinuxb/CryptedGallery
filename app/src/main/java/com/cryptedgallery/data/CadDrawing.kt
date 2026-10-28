package com.cryptedgallery.data

/**
 * Renderer independent 2D model of a CAD drawing.
 *
 * All coordinates are in drawing units (WCS for DXF, model space for DWG)
 * with the Y axis pointing up, exactly as stored in the file.
 */
data class CadDrawing(
    val shapes: List<CadShape>,
    val bounds: CadBounds,
    val sourceName: String? = null
) {
    val isEmpty: Boolean get() = shapes.isEmpty()
}

data class CadBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f

    val isValid: Boolean
        get() = minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite() &&
                maxX >= minX && maxY >= minY

    companion object {
        val EMPTY = CadBounds(0f, 0f, 0f, 0f)
    }
}

sealed interface CadShape {

    /** Flat list of x/y pairs, i.e. points[0] = x0, points[1] = y0, points[2] = x1 ... */
    class Polyline(
        val points: FloatArray,
        val closed: Boolean = false,
        val filled: Boolean = false
    ) : CadShape

    class Circle(val cx: Float, val cy: Float, val radius: Float) : CadShape

    class Text(
        val x: Float,
        val y: Float,
        val height: Float,
        /** Rotation in degrees, counter clockwise. */
        val rotation: Float,
        val value: String
    ) : CadShape
}

sealed interface CadLoadResult {
    data class Success(val drawing: CadDrawing) : CadLoadResult
    data class Failure(val message: String) : CadLoadResult
}
