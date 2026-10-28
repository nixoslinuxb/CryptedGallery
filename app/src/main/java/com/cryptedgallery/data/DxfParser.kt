package com.cryptedgallery.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Minimal ASCII DXF reader that turns the most common CAD entities into a
 * [CadDrawing]. Blocks and insertions are expanded so each drawing is fully
 * positioned in world coordinates.
 */
object DxfParser {

    fun parse(input: InputStream, sourceName: String? = null): CadDrawing {
        val reader = DxfReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16))
        return DxfDocumentParser(reader).parse(sourceName)
    }
}

private const val MAX_BLOCK_DEPTH = 12

private class DxfReader(private val reader: BufferedReader) {

    private var pushedBack: Pair<Int, String>? = null

    fun pushBack(pair: Pair<Int, String>) {
        pushedBack = pair
    }

    fun readPair(): Pair<Int, String>? {
        val pending = pushedBack
        if (pending != null) {
            pushedBack = null
            return pending
        }
        while (true) {
            val codeLine = reader.readLine() ?: return null
            val valueLine = reader.readLine() ?: return null
            val code = codeLine.trim().toIntOrNull() ?: continue
            return code to valueLine.trimEnd()
        }
    }
}

private class EntityRecord(val type: String, val ordered: List<Pair<Int, String>>) {

    fun values(code: Int): List<String> = ordered.filter { it.first == code }.map { it.second }

    fun string(code: Int): String? = ordered.firstOrNull { it.first == code }?.second

    fun double(code: Int, default: Double = 0.0): Double =
        string(code)?.trim()?.toDoubleOrNull() ?: default

    fun int(code: Int, default: Int = 0): Int =
        string(code)?.trim()?.toIntOrNull() ?: default
}

private class BlockDefinition(
    val baseX: Float,
    val baseY: Float,
    val entities: List<EntityRecord>
)

private class BlockAccumulator(val name: String, val baseX: Float, val baseY: Float) {
    val entities = ArrayList<EntityRecord>()
}

private class PolylineAccumulator(val closed: Boolean) {

    private val points = ArrayList<Float>()
    private var lastX = 0f
    private var lastY = 0f
    private var lastBulge = 0f
    private var hasPoint = false

    fun addVertex(record: EntityRecord) {
        val x = record.double(10).toFloat()
        val y = record.double(20).toFloat()
        if (!x.isFinite() || !y.isFinite()) return
        if (!hasPoint) {
            points.add(x)
            points.add(y)
            hasPoint = true
        } else {
            appendBulgeArc(points, lastX, lastY, x, y, lastBulge)
        }
        lastX = x
        lastY = y
        lastBulge = record.double(42).toFloat()
    }

    fun toPoints(): FloatArray {
        if (closed && hasPoint && lastBulge != 0f && points.size >= 4) {
            appendBulgeArc(points, lastX, lastY, points[0], points[1], lastBulge)
        }
        return points.toFloatArray()
    }
}

private class EntitySink(
    private val builder: CadDrawingBuilder,
    private val emit: (EntityRecord) -> Unit
) {
    private var polyline: PolylineAccumulator? = null

    fun accept(record: EntityRecord) {
        when (record.type) {
            "POLYLINE" -> {
                flush()
                polyline = PolylineAccumulator((record.int(70) and 1) != 0)
            }

            "VERTEX" -> polyline?.addVertex(record)
            "SEQEND" -> flush()
            else -> {
                flush()
                emit(record)
            }
        }
    }

    fun finish() {
        flush()
    }

    private fun flush() {
        val current = polyline ?: return
        polyline = null
        val points = current.toPoints()
        if (points.size >= 4) builder.addPolyline(points, current.closed)
    }
}

private class DxfDocumentParser(private val reader: DxfReader) {

    private val blocks = HashMap<String, BlockDefinition>()
    private val builder = CadDrawingBuilder()

    fun parse(sourceName: String?): CadDrawing {
        var section = ""
        var block: BlockAccumulator? = null
        var sink: EntitySink? = null

        while (true) {
            val pair = reader.readPair() ?: break
            if (pair.first != 0) continue
            val type = pair.second.trim().uppercase()
            when (type) {
                "SECTION" -> {
                    val namePair = reader.readPair() ?: break
                    section = namePair.second.trim().uppercase()
                    if (section == "ENTITIES") {
                        sink = EntitySink(builder) { record ->
                            addEntity(record, CadTransform.IDENTITY, blocks, builder, 0)
                        }
                    }
                }

                "ENDSEC" -> {
                    sink = null
                    block = null
                    section = ""
                }

                "EOF" -> {
                    break
                }

                "BLOCK" -> {
                    if (section == "BLOCKS") {
                        val record = readRecord(type)
                        block = BlockAccumulator(
                            record.string(2)?.uppercase() ?: "",
                            record.double(10).toFloat(),
                            record.double(20).toFloat()
                        )
                    }
                }

                "ENDBLK" -> {
                    readRecord(type)
                    val current = block
                    if (current != null && current.name.isNotEmpty()) {
                        blocks[current.name] = BlockDefinition(current.baseX, current.baseY, current.entities)
                    }
                    block = null
                }

                else -> {
                    val record = readRecord(type)
                    if (section == "BLOCKS") {
                        block?.entities?.add(record)
                    } else if (section == "ENTITIES") {
                        sink?.accept(record)
                    }
                }
            }
        }

        sink?.finish()
        return builder.build(sourceName)
    }

    private fun readRecord(type: String): EntityRecord {
        val ordered = ArrayList<Pair<Int, String>>()
        while (true) {
            val pair = reader.readPair() ?: break
            if (pair.first == 0) {
                reader.pushBack(pair)
                break
            }
            ordered.add(pair)
        }
        return EntityRecord(type, ordered)
    }

    private fun addEntity(
        record: EntityRecord,
        transform: CadTransform,
        blocks: Map<String, BlockDefinition>,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        if (builder.isFull || depth > MAX_BLOCK_DEPTH) return
        when (record.type) {
            "LINE" -> builder.addPolyline(
                floatArrayOf(
                    record.double(10).toFloat(), record.double(20).toFloat(),
                    record.double(11).toFloat(), record.double(21).toFloat()
                ),
                closed = false,
                filled = false,
                transform = transform
            )

            "LWPOLYLINE" -> addLwPolyline(record, transform, builder)
            "CIRCLE" -> builder.addCircle(
                record.double(10).toFloat(),
                record.double(20).toFloat(),
                record.double(40).toFloat(),
                transform
            )

            "ARC" -> {
                val points = ArrayList<Float>()
                appendArc(
                    points,
                    record.double(10),
                    record.double(20),
                    record.double(40),
                    record.double(50),
                    record.double(51)
                )
                builder.addPolyline(points.toFloatArray(), transform = transform)
            }

            "ELLIPSE" -> addEllipse(record, transform, builder)
            "TEXT" -> {
                val value = cleanCadText(record.string(1) ?: record.string(3) ?: "")
                if (value.isNotEmpty()) {
                    builder.addText(
                        record.double(10).toFloat(),
                        record.double(20).toFloat(),
                        record.double(40).toFloat(),
                        record.double(50).toFloat(),
                        value,
                        transform
                    )
                }
            }

            "MTEXT" -> addMText(record, transform, builder)
            "INSERT" -> addInsert(record, transform, blocks, builder, depth)
            "DIMENSION" -> {
                val name = record.string(2)?.uppercase()
                if (name != null) expandBlock(name, transform, blocks, builder, depth + 1)
            }

            "SOLID", "TRACE", "3DFACE" -> addSolid(record, transform, builder)
            "LEADER", "SPLINE" -> addPointList(record, transform, builder)
        }
    }

    private fun addLwPolyline(
        record: EntityRecord,
        transform: CadTransform,
        builder: CadDrawingBuilder
    ) {
        val closed = (record.int(70) and 1) != 0
        val points = ArrayList<Float>()
        var x = 0f
        var y = 0f
        var lastX = 0f
        var lastY = 0f
        var lastBulge = 0f
        var firstX = 0f
        var firstY = 0f
        var hasPoint = false

        for (pair in record.ordered) {
            when (pair.first) {
                10 -> x = pair.second.trim().toFloatOrNull() ?: Float.NaN
                20 -> {
                    y = pair.second.trim().toFloatOrNull() ?: Float.NaN
                    if (x.isFinite() && y.isFinite()) {
                        if (!hasPoint) {
                            points.add(x)
                            points.add(y)
                            firstX = x
                            firstY = y
                            hasPoint = true
                        } else {
                            appendBulgeArc(points, lastX, lastY, x, y, lastBulge)
                        }
                        lastX = x
                        lastY = y
                        lastBulge = 0f
                    }
                }

                42 -> lastBulge = pair.second.trim().toFloatOrNull() ?: 0f
            }
        }

        if (closed && hasPoint && lastBulge != 0f) {
            appendBulgeArc(points, lastX, lastY, firstX, firstY, lastBulge)
        }
        builder.addPolyline(points.toFloatArray(), closed, false, transform)
    }

    private fun addEllipse(
        record: EntityRecord,
        transform: CadTransform,
        builder: CadDrawingBuilder
    ) {
        val centerX = record.double(10)
        val centerY = record.double(20)
        val majorX = record.double(11)
        val majorY = record.double(21)
        val ratio = record.double(40, 1.0)
        val minorX = -majorY * ratio
        val minorY = majorX * ratio
        val start = record.double(41, 0.0)
        var end = record.double(42, PI * 2.0)
        if (end <= start) end += PI * 2.0
        val sweep = end - start
        val segments = max(12, min(180, ceil(abs(sweep) / (PI / 36.0)).toInt()))
        val points = ArrayList<Float>()
        for (i in 0..segments) {
            val angle = start + sweep * i / segments
            val x = centerX + majorX * cos(angle) + minorX * sin(angle)
            val y = centerY + majorY * cos(angle) + minorY * sin(angle)
            points.add(x.toFloat())
            points.add(y.toFloat())
        }
        builder.addPolyline(points.toFloatArray(), transform = transform)
    }

    private fun addMText(
        record: EntityRecord,
        transform: CadTransform,
        builder: CadDrawingBuilder
    ) {
        val text = StringBuilder()
        for (pair in record.ordered) {
            if (pair.first == 1 || pair.first == 3) text.append(pair.second)
        }
        val value = cleanCadText(text.toString())
        if (value.isEmpty()) return

        var rotation = record.double(50, 0.0)
        val directionX = record.double(11, 0.0)
        val directionY = record.double(21, 0.0)
        if (directionX != 0.0 || directionY != 0.0) {
            rotation = Math.toDegrees(atan2(directionY, directionX))
        }
        builder.addText(
            record.double(10).toFloat(),
            record.double(20).toFloat(),
            record.double(40).toFloat(),
            rotation.toFloat(),
            value,
            transform
        )
    }

    private fun addSolid(
        record: EntityRecord,
        transform: CadTransform,
        builder: CadDrawingBuilder
    ) {
        val hasFourth = record.string(13) != null || record.string(23) != null
        val points = ArrayList<Float>(8)
        points.add(record.double(10).toFloat())
        points.add(record.double(20).toFloat())
        points.add(record.double(11).toFloat())
        points.add(record.double(21).toFloat())
        if (hasFourth) {
            points.add(record.double(13).toFloat())
            points.add(record.double(23).toFloat())
        }
        points.add(record.double(12).toFloat())
        points.add(record.double(22).toFloat())
        builder.addPolyline(points.toFloatArray(), closed = true, filled = true, transform = transform)
    }

    private fun addPointList(
        record: EntityRecord,
        transform: CadTransform,
        builder: CadDrawingBuilder
    ) {
        val xs = record.values(10)
        val ys = record.values(20)
        val points = ArrayList<Float>()
        for (i in 0 until min(xs.size, ys.size)) {
            val x = xs[i].trim().toFloatOrNull()
            val y = ys[i].trim().toFloatOrNull()
            if (x == null || y == null) continue
            points.add(x)
            points.add(y)
        }
        builder.addPolyline(points.toFloatArray(), transform = transform)
    }

    private fun addInsert(
        record: EntityRecord,
        transform: CadTransform,
        blocks: Map<String, BlockDefinition>,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        val name = record.string(2)?.uppercase() ?: return
        val block = blocks[name] ?: return
        var scaleX = record.double(41, 1.0).toFloat()
        var scaleY = record.double(42, 1.0).toFloat()
        if (scaleX == 0f || !scaleX.isFinite()) scaleX = 1f
        if (scaleY == 0f || !scaleY.isFinite()) scaleY = 1f
        val rotation = Math.toRadians(record.double(50, 0.0)).toFloat()
        val local = CadTransform.forInsert(
            record.double(10).toFloat(),
            record.double(20).toFloat(),
            scaleX,
            scaleY,
            rotation,
            block.baseX,
            block.baseY
        )
        expandRecords(block.entities, local.then(transform), blocks, builder, depth + 1)
    }

    private fun expandBlock(
        name: String,
        transform: CadTransform,
        blocks: Map<String, BlockDefinition>,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        val block = blocks[name] ?: return
        expandRecords(block.entities, transform, blocks, builder, depth)
    }

    private fun expandRecords(
        records: List<EntityRecord>,
        transform: CadTransform,
        blocks: Map<String, BlockDefinition>,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        if (depth > MAX_BLOCK_DEPTH) return
        var polyline: PolylineAccumulator? = null
        for (record in records) {
            if (builder.isFull) return
            when (record.type) {
                "POLYLINE" -> {
                    polyline?.let { builder.addPolyline(it.toPoints(), it.closed, false, transform) }
                    polyline = PolylineAccumulator((record.int(70) and 1) != 0)
                }

                "VERTEX" -> polyline?.addVertex(record)
                "SEQEND" -> {
                    polyline?.let { builder.addPolyline(it.toPoints(), it.closed, false, transform) }
                    polyline = null
                }

                else -> {
                    polyline?.let { builder.addPolyline(it.toPoints(), it.closed, false, transform) }
                    polyline = null
                    addEntity(record, transform, blocks, builder, depth)
                }
            }
        }
        polyline?.let { builder.addPolyline(it.toPoints(), it.closed, false, transform) }
    }
}
