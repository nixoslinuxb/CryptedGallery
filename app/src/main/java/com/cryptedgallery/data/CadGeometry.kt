package com.cryptedgallery.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D affine transform: x' = a*x + c*y + e and y' = b*x + d*y + f.
 */
internal data class CadTransform(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f
) {
    fun mapX(x: Float, y: Float): Float = a * x + c * y + e

    fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    /** Returns a transform that applies this one first and [outer] afterwards. */
    fun then(outer: CadTransform): CadTransform = CadTransform(
        a = outer.a * a + outer.c * b,
        b = outer.b * a + outer.d * b,
        c = outer.a * c + outer.c * d,
        d = outer.b * c + outer.d * d,
        e = outer.a * e + outer.c * f + outer.e,
        f = outer.b * e + outer.d * f + outer.f
    )

    /** Average scale factor, used for radii and text heights. */
    val averageScale: Float
        get() {
            val sx = hypot(a, b)
            val sy = hypot(c, d)
            val scale = (sx + sy) / 2f
            return if (scale.isFinite() && scale > 0f) scale else 1f
        }

    /** Rotation of the X axis in radians. */
    val rotationRadians: Float
        get() = atan2(b, a)

    companion object {
        val IDENTITY = CadTransform()

        fun forInsert(
            insertX: Float,
            insertY: Float,
            scaleX: Float,
            scaleY: Float,
            rotationRadians: Float,
            baseX: Float,
            baseY: Float
        ): CadTransform {
            val cosR = cos(rotationRadians)
            val sinR = sin(rotationRadians)
            val a = cosR * scaleX
            val b = sinR * scaleX
            val c = -sinR * scaleY
            val d = cosR * scaleY
            return CadTransform(
                a = a,
                b = b,
                c = c,
                d = d,
                e = insertX - (a * baseX + c * baseY),
                f = insertY - (b * baseX + d * baseY)
            )
        }
    }
}

/**
 * Collects shapes while keeping track of the drawing extents.
 */
internal class CadDrawingBuilder(private val maxShapes: Int = 200_000) {

    private val shapes = ArrayList<CadShape>()
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    val isFull: Boolean get() = shapes.size >= maxShapes

    fun addPolyline(
        points: FloatArray,
        closed: Boolean = false,
        filled: Boolean = false,
        transform: CadTransform = CadTransform.IDENTITY
    ) {
        if (isFull || points.size < 4) return
        val mapped = FloatArray(points.size)
        var index = 0
        while (index + 1 < points.size) {
            val x = transform.mapX(points[index], points[index + 1])
            val y = transform.mapY(points[index], points[index + 1])
            if (!x.isFinite() || !y.isFinite()) return
            mapped[index] = x
            mapped[index + 1] = y
            include(x, y)
            index += 2
        }
        shapes.add(CadShape.Polyline(mapped, closed, filled))
    }

    fun addCircle(
        cx: Float,
        cy: Float,
        radius: Float,
        transform: CadTransform = CadTransform.IDENTITY
    ) {
        if (isFull || !cx.isFinite() || !cy.isFinite() || !radius.isFinite() || radius <= 0f) return
        val x = transform.mapX(cx, cy)
        val y = transform.mapY(cx, cy)
        val r = radius * transform.averageScale
        if (!x.isFinite() || !y.isFinite() || !r.isFinite() || r <= 0f) return
        include(x - r, y - r)
        include(x + r, y + r)
        shapes.add(CadShape.Circle(x, y, r))
    }

    fun addText(
        x: Float,
        y: Float,
        height: Float,
        rotationDegrees: Float,
        value: String,
        transform: CadTransform = CadTransform.IDENTITY
    ) {
        if (isFull) return
        val text = value.trim().take(1000)
        if (text.isEmpty() || !height.isFinite() || height <= 0f || height > 1e6f) return
        val tx = transform.mapX(x, y)
        val ty = transform.mapY(x, y)
        if (!tx.isFinite() || !ty.isFinite()) return
        val textHeight = height * transform.averageScale
        if (!textHeight.isFinite() || textHeight <= 0f) return
        val rotation = rotationDegrees + Math.toDegrees(transform.rotationRadians.toDouble()).toFloat()
        val longestLine = text.split('\n').maxOfOrNull { it.length } ?: 0
        include(tx, ty)
        include(tx + longestLine * textHeight * 0.6f, ty + textHeight * text.split('\n').size)
        shapes.add(CadShape.Text(tx, ty, textHeight, rotation, text))
    }

    private fun include(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    fun build(sourceName: String? = null): CadDrawing {
        val bounds = if (shapes.isEmpty() || !minX.isFinite()) CadBounds.EMPTY
        else CadBounds(minX, minY, maxX, maxY)
        return CadDrawing(shapes.toList(), bounds, sourceName)
    }
}

/**
 * Appends the next point of a polyline, expanding a bulge (arc) segment when needed.
 * The starting point must already be contained in [out].
 */
internal fun appendBulgeArc(
    out: MutableList<Float>,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    bulge: Float
) {
    if (bulge == 0f || !bulge.isFinite()) {
        out.add(x2)
        out.add(y2)
        return
    }
    val dx = (x2 - x1).toDouble()
    val dy = (y2 - y1).toDouble()
    if (hypot(dx, dy) < 1e-9) {
        out.add(x2)
        out.add(y2)
        return
    }
    val b = bulge.toDouble()
    val theta = 4.0 * atan(b)
    if (abs(theta) < 1e-6) {
        out.add(x2)
        out.add(y2)
        return
    }
    val k = (1.0 - b * b) / (4.0 * b)
    val centerX = (x1 + x2) / 2.0 - dy * k
    val centerY = (y1 + y2) / 2.0 + dx * k
    val radius = hypot(x1 - centerX, y1 - centerY)
    if (!radius.isFinite() || radius <= 0.0) {
        out.add(x2)
        out.add(y2)
        return
    }
    val start = atan2(y1 - centerY, x1 - centerX)
    val segments = max(4, min(128, ceil(abs(theta) / (PI / 24.0)).toInt()))
    for (i in 1..segments) {
        val angle = start + theta * i / segments
        out.add((centerX + radius * cos(angle)).toFloat())
        out.add((centerY + radius * sin(angle)).toFloat())
    }
}

/** Appends a tessellated arc, angles in degrees and counter clockwise. */
internal fun appendArc(
    out: MutableList<Float>,
    centerX: Double,
    centerY: Double,
    radius: Double,
    startDegrees: Double,
    endDegrees: Double
) {
    if (!centerX.isFinite() || !centerY.isFinite() || !radius.isFinite() || radius <= 0.0) return
    if (!startDegrees.isFinite() || !endDegrees.isFinite()) return
    var start = startDegrees % 360.0
    var end = endDegrees % 360.0
    if (end <= start) end += 360.0
    val sweep = end - start
    val segments = max(6, min(180, ceil(sweep / 6.0).toInt()))
    for (i in 0..segments) {
        val angle = Math.toRadians(start + sweep * i / segments)
        out.add((centerX + radius * cos(angle)).toFloat())
        out.add((centerY + radius * sin(angle)).toFloat())
    }
}

/** Strips the formatting codes used by MTEXT / TEXT entities. */
internal fun cleanCadText(raw: String): String {
    if (raw.isEmpty()) return ""
    var text = raw
    text = text.replace("\\P", "\n").replace("\\p", "\n")
    text = text.replace(Regex("\\\\[A-Za-z][^;\\\\]*;"), "")
    text = text.replace("\\~", " ")
    text = text.replace(Regex("[{}]"), "")
    text = text.replace("%%d", "°").replace("%%D", "°")
    text = text.replace("%%p", "±").replace("%%P", "±")
    text = text.replace("%%c", "⌀").replace("%%C", "⌀")
    text = text.replace("%%%", "%")
    return text.trim()
}
