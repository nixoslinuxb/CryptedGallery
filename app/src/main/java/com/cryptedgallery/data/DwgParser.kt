package com.cryptedgallery.data

import android.content.Context
import android.net.Uri
import com.onespatial.dwglib.Reader
import com.onespatial.dwglib.objects.Arc
import com.onespatial.dwglib.objects.BlockHeader
import com.onespatial.dwglib.objects.Circle
import com.onespatial.dwglib.objects.EntityObject
import com.onespatial.dwglib.objects.Insert
import com.onespatial.dwglib.objects.Leader
import com.onespatial.dwglib.objects.Line
import com.onespatial.dwglib.objects.LwPolyline
import com.onespatial.dwglib.objects.MText
import com.onespatial.dwglib.objects.Solid
import com.onespatial.dwglib.objects.Text
import com.onespatial.dwglib.objects.TwoDPolyline
import com.onespatial.dwglib.objects.Vertex2D
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.atan2

/**
 * Reads AutoCAD DWG files produced by AutoCAD 2010 and later using the
 * MIT licensed `com.1spatial:dwg-lib` parser.
 *
 * The library only understands the object layout of the 2010 (AC1024) and
 * 2013 (AC1027) releases. Later releases (for example 2018 / AC1032) use the
 * same container, so the version marker is rewritten before parsing and the
 * file is decoded best effort. Entities that cannot be decoded are skipped
 * instead of failing the whole drawing.
 */
object DwgParser {

    private const val MAX_BLOCK_DEPTH = 10
    private val SUPPORTED_VERSIONS = setOf("AC1024", "AC1027")

    fun parse(context: Context, uri: Uri, displayName: String): CadDrawing {
        val cacheFile = copyToCacheFile(context, uri, displayName)
        try {
            if (readVersion(cacheFile) == "AC1032") {
                relabelAs2013(cacheFile)
            }
            val version = readVersion(cacheFile)
            if (version !in SUPPORTED_VERSIONS) {
                throw IllegalStateException(unsupportedVersionMessage(version))
            }
            return Reader(cacheFile).use { reader ->
                val builder = CadDrawingBuilder()
                val control = reader.blockControlObject
                val modelSpace = (control?.modelSpace as? BlockHeader)
                    ?: (reader.blockRecordModelSpace as? BlockHeader)
                if (modelSpace != null) {
                    addBlock(modelSpace, CadTransform.IDENTITY, builder, 0)
                }
                builder.build(displayName)
            }
        } finally {
            cacheFile.delete()
        }
    }

    private fun addBlock(
        block: BlockHeader,
        transform: CadTransform,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        if (depth > MAX_BLOCK_DEPTH || builder.isFull) return
        val ownedObjects = try {
            block.ownedObjects
        } catch (e: Exception) {
            return
        }
        val count = ownedObjects.size
        for (index in 0 until count) {
            if (builder.isFull) return
            val entity = try {
                ownedObjects[index]
            } catch (e: Exception) {
                continue
            }
            if (entity is EntityObject) {
                try {
                    addEntity(entity, transform, builder, depth)
                } catch (e: Exception) {
                    // A single unsupported entity should not hide the whole drawing.
                }
            }
        }
    }

    private fun addEntity(
        entity: EntityObject,
        transform: CadTransform,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        when (entity) {
            is Line -> builder.addPolyline(
                floatArrayOf(
                    entity.start.x.toFloat(), entity.start.y.toFloat(),
                    entity.end.x.toFloat(), entity.end.y.toFloat()
                ),
                closed = false,
                filled = false,
                transform = transform
            )

            is LwPolyline -> {
                val points = ArrayList<Float>()
                var lastX = 0f
                var lastY = 0f
                var hasPoint = false
                for (vertex in entity.points) {
                    val x = vertex.vertex.x.toFloat()
                    val y = vertex.vertex.y.toFloat()
                    if (!x.isFinite() || !y.isFinite()) continue
                    if (!hasPoint) {
                        points.add(x)
                        points.add(y)
                        hasPoint = true
                    } else {
                        appendBulgeArc(points, lastX, lastY, x, y, vertex.bulge.toFloat())
                    }
                    lastX = x
                    lastY = y
                }
                builder.addPolyline(points.toFloatArray(), entity.isClosed, false, transform)
            }

            is TwoDPolyline -> {
                val vertices = try {
                    entity.ownedObjects
                } catch (e: Exception) {
                    null
                }
                if (vertices != null) {
                    val points = ArrayList<Float>()
                    var lastX = 0f
                    var lastY = 0f
                    var hasPoint = false
                    for (vertex in vertices) {
                        if (vertex !is Vertex2D) continue
                        val x = vertex.point.x.toFloat()
                        val y = vertex.point.y.toFloat()
                        if (!x.isFinite() || !y.isFinite()) continue
                        if (!hasPoint) {
                            points.add(x)
                            points.add(y)
                            hasPoint = true
                        } else {
                            appendBulgeArc(points, lastX, lastY, x, y, vertex.bulge.toFloat())
                        }
                        lastX = x
                        lastY = y
                    }
                    builder.addPolyline(points.toFloatArray(), entity.isClosed, false, transform)
                }
            }

            is Circle -> builder.addCircle(
                entity.center.x.toFloat(),
                entity.center.y.toFloat(),
                entity.radius.toFloat(),
                transform
            )

            is Arc -> {
                val points = ArrayList<Float>()
                appendArc(
                    points,
                    entity.center.x,
                    entity.center.y,
                    entity.radius,
                    Math.toDegrees(entity.startAngle),
                    Math.toDegrees(entity.endAngle)
                )
                builder.addPolyline(points.toFloatArray(), transform = transform)
            }

            is Text -> {
                val value = cleanCadText(entity.textValue ?: "")
                if (value.isNotEmpty()) {
                    builder.addText(
                        entity.insertionPoint.x.toFloat(),
                        entity.insertionPoint.y.toFloat(),
                        entity.height.toFloat(),
                        Math.toDegrees(entity.rotationAngle).toFloat(),
                        value,
                        transform
                    )
                }
            }

            is MText -> {
                val value = cleanCadText(entity.text ?: "")
                if (value.isNotEmpty()) {
                    val directionX = entity.xAxisDir.x
                    val directionY = entity.xAxisDir.y
                    val rotation = if (directionX != 0.0 || directionY != 0.0) {
                        Math.toDegrees(atan2(directionY, directionX))
                    } else {
                        0.0
                    }
                    builder.addText(
                        entity.insertionPoint.x.toFloat(),
                        entity.insertionPoint.y.toFloat(),
                        entity.textHeight.toFloat(),
                        rotation.toFloat(),
                        value,
                        transform
                    )
                }
            }

            is Insert -> addInsert(entity, transform, builder, depth)

            is Solid -> {
                val corner1 = entity.corner1
                val corner2 = entity.corner2
                val corner3 = entity.corner3
                val corner4 = entity.corner4
                if (corner1 != null && corner2 != null && corner3 != null) {
                    val points = ArrayList<Float>(8)
                    points.add(corner1.x.toFloat())
                    points.add(corner1.y.toFloat())
                    points.add(corner2.x.toFloat())
                    points.add(corner2.y.toFloat())
                    if (corner4 != null) {
                        points.add(corner4.x.toFloat())
                        points.add(corner4.y.toFloat())
                    }
                    points.add(corner3.x.toFloat())
                    points.add(corner3.y.toFloat())
                    builder.addPolyline(points.toFloatArray(), closed = true, filled = true, transform = transform)
                }
            }

            is Leader -> {
                val leaderPoints = entity.points
                if (leaderPoints != null && leaderPoints.size >= 2) {
                    val points = ArrayList<Float>(leaderPoints.size * 2)
                    for (point in leaderPoints) {
                        points.add(point.x.toFloat())
                        points.add(point.y.toFloat())
                    }
                    builder.addPolyline(points.toFloatArray(), transform = transform)
                }
            }
        }
    }

    private fun addInsert(
        insert: Insert,
        transform: CadTransform,
        builder: CadDrawingBuilder,
        depth: Int
    ) {
        if (depth >= MAX_BLOCK_DEPTH) return
        val block: BlockHeader = try {
            insert.blockHeader ?: return
        } catch (e: Exception) {
            return
        }

        var scaleX = insert.xScaleFactor.toFloat()
        var scaleY = insert.yScaleFactor.toFloat()
        if (!scaleX.isFinite() || scaleX == 0f) scaleX = 1f
        if (!scaleY.isFinite() || scaleY == 0f) scaleY = 1f
        val local = CadTransform.forInsert(
            insert.insertPoint.x.toFloat(),
            insert.insertPoint.y.toFloat(),
            scaleX,
            scaleY,
            insert.rotation.toFloat(),
            block.basePoint.x.toFloat(),
            block.basePoint.y.toFloat()
        )
        addBlock(block, local.then(transform), builder, depth + 1)
    }

    private fun copyToCacheFile(context: Context, uri: Uri, displayName: String): File {
        val safeName = displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(48)
        val file = File(context.cacheDir, "cad_${System.nanoTime()}_$safeName")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("The drawing could not be read.")
        input.use { source ->
            file.outputStream().use { target ->
                source.copyTo(target, 128 * 1024)
            }
        }
        return file
    }

    private fun readVersion(file: File): String = try {
        RandomAccessFile(file, "r").use { randomAccess ->
            val bytes = ByteArray(6)
            randomAccess.readFully(bytes)
            String(bytes, Charsets.US_ASCII)
        }
    } catch (e: Exception) {
        ""
    }

    private fun relabelAs2013(file: File) {
        try {
            RandomAccessFile(file, "rw").use { randomAccess ->
                randomAccess.seek(0)
                randomAccess.write("AC1027".toByteArray(Charsets.US_ASCII))
            }
        } catch (e: Exception) {
            // Keep the original header; the reader will report the version error.
        }
    }

    private fun unsupportedVersionMessage(version: String): String = when (version) {
        "" -> "This file is not a readable DWG drawing."
        "AC1012", "AC1014", "AC1015", "AC1018", "AC1021" ->
            "This DWG was created by AutoCAD 2009 or older and cannot be opened."

        else -> "DWG version $version is not supported."
    }
}
