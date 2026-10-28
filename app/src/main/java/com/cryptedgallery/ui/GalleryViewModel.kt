package com.cryptedgallery.ui

import android.app.Application
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.app.WallpaperManager
import android.media.RingtoneManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cryptedgallery.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.*
import org.apache.poi.xslf.usermodel.*
import org.apache.poi.sl.usermodel.*
import org.apache.poi.hssf.usermodel.*
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackagePart

import com.itextpdf.kernel.pdf.PdfDocument as ItextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = MediaScanner(application)

    private val masterKey = MasterKey.Builder(application)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        application,
        "book_positions_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val thumbnailCache = LruCache<Long, Bitmap>(100)
    private val frameCache = LruCache<String, Bitmap>(50)
    private val cadDrawingCache = LruCache<Long, CadDrawing>(3)

    // --- Office Editing State ---
    private var currentWorkbook: Workbook? = null
    private var currentWordDocument: XWPFDocument? = null
    private var currentPresentation: XMLSlideShow? = null
    private var currentMediaItem: MediaItem? = null

    private val _officeContent = MutableStateFlow<OfficeContent?>(null)
    val officeContent: StateFlow<OfficeContent?> = _officeContent.asStateFlow()

    // --- Audio Player Logic ---
    private val _audioPlayer = ExoPlayer.Builder(application).build()

    private val _currentPlayingAudio = MutableStateFlow<MediaItem?>(null)
    val currentPlayingAudio: StateFlow<MediaItem?> = _currentPlayingAudio.asStateFlow()

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private val _audioProgress = MutableStateFlow(0L)
    val audioProgress: StateFlow<Long> = _audioProgress.asStateFlow()

    private val _audioDuration = MutableStateFlow(0L)
    val audioDuration: StateFlow<Long> = _audioDuration.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    init {
        _audioPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isAudioPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    _audioDuration.value = _audioPlayer.duration
                }
                // Disable Auto-Play: When ended, don't advance
                if (state == Player.STATE_ENDED) {
                    _audioPlayer.pause()
                    _audioPlayer.seekTo(0)
                }
            }
        })

        _audioPlayer.volume = 1.0f

        // Update progress
        viewModelScope.launch {
            while (true) {
                if (_isAudioPlaying.value) {
                    _audioProgress.value = _audioPlayer.currentPosition
                }
                kotlinx.coroutines.delay(500)
            }
        }

        // Initialize Apache POI XML parsers for Android
        System.setProperty(
            "org.apache.poi.javax.xml.stream.XMLInputFactory",
            "com.fasterxml.aalto.stax.InputFactoryImpl"
        )
        System.setProperty(
            "org.apache.poi.javax.xml.stream.XMLOutputFactory",
            "com.fasterxml.aalto.stax.OutputFactoryImpl"
        )
        System.setProperty(
            "org.apache.poi.javax.xml.stream.XMLEventFactory",
            "com.fasterxml.aalto.stax.EventFactoryImpl"
        )
    }

    fun playAudio(item: MediaItem, autoPlay: Boolean = false) {
        if (_currentPlayingAudio.value?.id == item.id) {
            if (_isAudioPlaying.value) _audioPlayer.pause() else _audioPlayer.play()
            return
        }
        _currentPlayingAudio.value = item
        _audioPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
        _audioPlayer.prepare()
        _audioPlayer.playWhenReady = autoPlay
    }

    fun playNext() {
        val currentList = _audio.value
        val currentIndex = currentList.indexOfFirst { it.id == _currentPlayingAudio.value?.id }
        if (currentIndex != -1 && currentIndex < currentList.size - 1) {
            playAudio(currentList[currentIndex + 1])
        } else if (currentList.isNotEmpty()) {
            playAudio(currentList[0])
        }
    }

    fun playPrevious() {
        val currentList = _audio.value
        val currentIndex = currentList.indexOfFirst { it.id == _currentPlayingAudio.value?.id }
        if (currentIndex > 0) {
            playAudio(currentList[currentIndex - 1])
        } else if (currentList.isNotEmpty()) {
            playAudio(currentList.last())
        }
    }

    fun adjustVolume(delta: Float) {
        val newVolume = (_volume.value + delta).coerceIn(0f, 1f)
        _volume.value = newVolume
        _audioPlayer.volume = newVolume
    }

    fun toggleAudioPlayback() {
        if (_audioPlayer.isPlaying) _audioPlayer.pause() else _audioPlayer.play()
    }

    fun seekAudio(positionMs: Long) {
        _audioPlayer.seekTo(positionMs)
    }

    fun seekIncremental(amountMs: Long) {
        val newPos = (_audioPlayer.currentPosition + amountMs).coerceIn(0L, _audioPlayer.duration)
        _audioPlayer.seekTo(newPos)
    }

    fun setAsRingtone(item: MediaItem): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(
                getApplication(),
                RingtoneManager.TYPE_RINGTONE,
                item.uri
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private val _photos = MutableStateFlow<List<MediaItem>>(emptyList())
    val photos: StateFlow<List<MediaItem>> = _photos.asStateFlow()

    private val _videos = MutableStateFlow<List<MediaItem>>(emptyList())
    val videos: StateFlow<List<MediaItem>> = _videos.asStateFlow()

    private val _audio = MutableStateFlow<List<MediaItem>>(emptyList())
    val audio: StateFlow<List<MediaItem>> = _audio.asStateFlow()

    private val _documents = MutableStateFlow<List<MediaItem>>(emptyList())
    val documents: StateFlow<List<MediaItem>> = _documents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()


    private val _hasFullFileAccess = MutableStateFlow(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    )
    val hasFullFileAccess: StateFlow<Boolean> = _hasFullFileAccess.asStateFlow()

    fun updateFullFileAccessStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            _hasFullFileAccess.value = Environment.isExternalStorageManager()
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _photos.value = scanner.getImages()
            _videos.value = scanner.getVideos()
            _audio.value = scanner.getAudioFiles()
            _documents.value = scanner.getDocuments()
            _isLoading.value = false
        }
    }

    suspend fun getDocumentThumbnail(item: MediaItem): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache.get(item.id)?.let { return@withContext it }

        val extension = item.displayName.substringAfterLast(".").lowercase()
        val bitmap = when (extension) {
            "pdf" -> generatePdfThumbnail(item.uri)
            "txt" -> generateTxtThumbnail(item.uri)
            "epub", "mobi" -> generateEpubThumbnail(item)
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt" -> generateStylizedOfficeCover(item)
            "dwg", "dxf" -> generateCadThumbnail(item)
            else -> null
        }

        if (bitmap != null) {
            thumbnailCache.put(item.id, bitmap)
        }
        bitmap
    }

    /**
     * Parses a DWG/DXF document into a renderable 2D model. Parsed drawings are
     * kept in a small cache because the viewer and the thumbnail list both ask
     * for the same file.
     */
    suspend fun loadCadDrawing(item: MediaItem): CadLoadResult = withContext(Dispatchers.IO) {
        cadDrawingCache.get(item.id)?.let { return@withContext CadLoadResult.Success(it) }

        val extension = item.displayName.substringAfterLast(".").lowercase()
        try {
            val drawing = when (extension) {
                "dxf" -> {
                    val stream = getApplication<Application>().contentResolver
                        .openInputStream(item.uri)
                        ?: return@withContext CadLoadResult.Failure("The drawing could not be read.")
                    stream.use { DxfParser.parse(it, item.displayName) }
                }

                "dwg" -> DwgParser.parse(getApplication(), item.uri, item.displayName)

                else -> return@withContext CadLoadResult.Failure("This drawing format is not supported.")
            }

            if (drawing.isEmpty) {
                CadLoadResult.Failure("No drawable geometry was found in this file.")
            } else {
                if (drawing.shapes.size <= 30_000) {
                    cadDrawingCache.put(item.id, drawing)
                }
                CadLoadResult.Success(drawing)
            }
        } catch (e: Exception) {
            CadLoadResult.Failure(e.message ?: "The drawing could not be opened.")
        }
    }

    private suspend fun generateCadThumbnail(item: MediaItem): Bitmap? {
        if (item.size > 24L * 1024L * 1024L) return null
        return when (val result = loadCadDrawing(item)) {
            is CadLoadResult.Success ->
                if (result.drawing.isEmpty) null else renderCadThumbnail(result.drawing)

            is CadLoadResult.Failure -> null
        }
    }

    private fun renderCadThumbnail(drawing: CadDrawing): Bitmap {
        val width = 300
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#101721"))

        val bounds = drawing.bounds
        if (!bounds.isValid) return bitmap

        val drawingWidth = if (bounds.width > 1e-6f) bounds.width else 1f
        val drawingHeight = if (bounds.height > 1e-6f) bounds.height else 1f
        val padding = 24f
        var scale = minOf(
            (width - padding * 2f) / drawingWidth,
            (height - padding * 2f) / drawingHeight
        )
        if (!scale.isFinite() || scale <= 0f) scale = 1f

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, -scale)
        canvas.translate(-bounds.centerX, -bounds.centerY)

        val paint = Paint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.parseColor("#D8E3F0")
            style = Paint.Style.STROKE
            strokeWidth = 1.6f / scale
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (shape in drawing.shapes) {
            when (shape) {
                is CadShape.Polyline -> {
                    val points = shape.points
                    if (points.size < 4) continue
                    val path = android.graphics.Path()
                    path.moveTo(points[0], points[1])
                    var index = 2
                    while (index + 1 < points.size) {
                        path.lineTo(points[index], points[index + 1])
                        index += 2
                    }
                    if (shape.closed) path.close()
                    if (shape.filled) {
                        paint.style = Paint.Style.FILL
                        canvas.drawPath(path, paint)
                        paint.style = Paint.Style.STROKE
                    } else {
                        canvas.drawPath(path, paint)
                    }
                }

                is CadShape.Circle -> canvas.drawCircle(shape.cx, shape.cy, shape.radius, paint)

                is CadShape.Text -> Unit
            }
        }

        canvas.restore()
        return bitmap
    }

    private fun generatePdfThumbnail(uri: Uri): Bitmap? {
        return try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun generateTxtThumbnail(uri: Uri): Bitmap? {
        return try {
            val content = getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val lines = mutableListOf<String>()
                    repeat(15) {
                        reader.readLine()?.let { lines.add(it) }
                    }
                    lines.joinToString("\n")
                }
            } ?: ""

            val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val paint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 14f
                isAntiAlias = true
            }

            val lines = content.split("\n")
            var y = 40f
            for (line in lines) {
                val text = if (line.length > 30) line.substring(0, 28) + ".." else line
                canvas.drawText(text, 20f, y, paint)
                y += 22f
                if (y > 380f) break
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun generateEpubThumbnail(item: MediaItem): Bitmap? {
        return try {
            val context = getApplication<Application>()
            var coverBitmap: Bitmap? = null

            context.contentResolver.openInputStream(item.uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry: ZipEntry? = zipInputStream.nextEntry

                while (entry != null) {
                    val name = entry.name.lowercase()
                    if ((name.contains("cover") || name.contains("thumb")) &&
                        (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                    ) {
                        coverBitmap = BitmapFactory.decodeStream(zipInputStream)
                        if (coverBitmap != null) break
                    }

                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }

            if (coverBitmap != null) {
                Bitmap.createScaledBitmap(coverBitmap!!, 300, 400, true)
            } else {
                generateStylizedEpubCover(item.displayName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun generateStylizedEpubCover(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colors = intArrayOf(
            android.graphics.Color.parseColor("#1e3c72"),
            android.graphics.Color.parseColor("#2a5298")
        )
        val gradient = LinearGradient(0f, 0f, 0f, 400f, colors, null, Shader.TileMode.CLAMP)
        val bgPaint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, 300f, 400f, bgPaint)

        val linePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = 40
            strokeWidth = 2f
        }
        canvas.drawLine(30f, 0f, 30f, 400f, linePaint)

        val textPaint = TextPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        val cleanTitle = title.substringBeforeLast(".")
        val staticLayout = StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, textPaint, 240)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        canvas.save()
        canvas.translate(150f, 100f)
        canvas.translate(-staticLayout.width / 2f, 0f)
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun generateStylizedOfficeCover(item: MediaItem): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val extension = item.displayName.substringAfterLast(".").lowercase()

        val bgStartColor: Int
        val bgEndColor: Int
        val iconText: String

        when (extension) {
            "docx", "doc", "odt" -> {
                bgStartColor = android.graphics.Color.parseColor("#4A90E2")
                bgEndColor = android.graphics.Color.parseColor("#2B579A")
                iconText = "W"
            }

            "xlsx", "xls", "ods" -> {
                bgStartColor = android.graphics.Color.parseColor("#43A047")
                bgEndColor = android.graphics.Color.parseColor("#1B5E20")
                iconText = "X"
            }

            "pptx", "ppt", "odp" -> {
                bgStartColor = android.graphics.Color.parseColor("#FF7043")
                bgEndColor = android.graphics.Color.parseColor("#BF360C")
                iconText = "P"
            }

            else -> {
                bgStartColor = android.graphics.Color.parseColor("#9E9E9E")
                bgEndColor = android.graphics.Color.parseColor("#424242")
                iconText = "D"
            }
        }

        // 1. Vibrant Gradient Background
        val bgGradient = LinearGradient(0f, 0f, 0f, 400f, bgStartColor, bgEndColor, Shader.TileMode.CLAMP)
        val bgPaint = Paint().apply { shader = bgGradient }
        canvas.drawRect(0f, 0f, 300f, 400f, bgPaint)

        // 2. Draw Skeleton Content
        val skeletonPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = 40
            isAntiAlias = true
        }

        when (extension) {
            "docx", "doc", "odt" -> {
                // Word Skeleton: Text lines
                var y = 60f
                val lineHeights = listOf(20f, 12f, 12f, 12f, 12f, 12f, 12f, 12f)
                val lineWidths = listOf(0.6f, 0.9f, 0.85f, 0.4f, 0.92f, 0.7f, 0.88f, 0.5f)
                lineHeights.forEachIndexed { i, h ->
                    canvas.drawRoundRect(40f, y, 40f + (220f * lineWidths[i]), y + h, 4f, 4f, skeletonPaint)
                    y += h + 15f
                }
            }

            "xlsx", "xls", "ods" -> {
                // Excel Skeleton: 4x4 Grid
                skeletonPaint.style = Paint.Style.STROKE
                skeletonPaint.strokeWidth = 2f
                for (i in 0..4) {
                    val x = 40f + (i * 55f)
                    canvas.drawLine(x, 60f, x, 340f, skeletonPaint)
                    val y = 60f + (i * 70f)
                    canvas.drawLine(40f, y, 260f, y, skeletonPaint)
                }
            }

            "pptx", "ppt", "odp" -> {
                // PPT Skeleton: Slide Layout
                canvas.drawRoundRect(40f, 100f, 260f, 240f, 8f, 8f, skeletonPaint)
                skeletonPaint.alpha = 60
                canvas.drawRect(60f, 120f, 140f, 220f, skeletonPaint) // Image placeholder
                canvas.drawRect(155f, 130f, 240f, 140f, skeletonPaint) // Text lines
                canvas.drawRect(155f, 155f, 230f, 165f, skeletonPaint)
                canvas.drawRect(155f, 180f, 245f, 190f, skeletonPaint)
            }
        }

        // 3. Small Corner Type Badge (Glassmorphic look)
        val badgeRadius = 35f
        val badgeX = 250f
        val badgeY = 350f

        val badgePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = 60
            isAntiAlias = true
        }
        canvas.drawCircle(badgeX, badgeY, badgeRadius, badgePaint)

        val badgeTextPaint = TextPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textBounds = Rect()
        badgeTextPaint.getTextBounds(iconText, 0, iconText.length, textBounds)
        canvas.drawText(iconText, badgeX - textBounds.centerX(), badgeY - textBounds.centerY(), badgeTextPaint)

        // 4. Document Title at bottom
        val titlePaint = TextPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 22f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val cleanTitle = item.displayName.substringBeforeLast(".")
        val staticLayout = StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, titlePaint, 220)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

        canvas.save()
        canvas.translate(150f, 280f) // Adjusted Y position
        canvas.translate(-staticLayout.width / 2f, 0f)
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    suspend fun loadBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun readTextFile(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val stringBuilder = StringBuilder()
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        stringBuilder.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
            }
            stringBuilder.toString()
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    suspend fun getPdfPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    suspend fun renderPdfPage(uri: Uri, pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun extractPdfTextWithCoordinates(uri: Uri, pageIndex: Int): List<ExtractedText> =
        withContext(Dispatchers.IO) {
            val texts = mutableListOf<ExtractedText>()
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = PdfReader(inputStream)
                    val pdfDoc = ItextPdfDocument(reader)

                    if (pageIndex < pdfDoc.numberOfPages) {
                        val page = pdfDoc.getPage(pageIndex + 1)
                        val strategy = LocationTextExtractionStrategy()
                        PdfTextExtractor.getTextFromPage(page, strategy)

                        val textContent = strategy.resultantText
                        var yOffset = 100f
                        textContent.split("\n").forEach { line ->
                            if (line.trim().isNotEmpty()) {
                                texts.add(ExtractedText(text = line, x = 100f, y = yOffset, fontSize = 35f))
                                yOffset += 45f
                            }
                        }
                    }
                    pdfDoc.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            texts
        }

    suspend fun extractVideoFrame(uri: Uri, timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${uri}_$timeMs"
        frameCache.get(key)?.let { return@withContext it }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication<Application>(), uri)
            val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                val scaled = Bitmap.createScaledBitmap(bitmap, 120, 120, true)
                frameCache.put(key, scaled)
                scaled
            } else null
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    suspend fun saveBitmap(bitmap: Bitmap, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val now = System.currentTimeMillis() / 1000
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_$displayName")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, now)
            put(MediaStore.Images.Media.DATE_TAKEN, now * 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri = resolver.insert(imageCollection, contentValues) ?: return@withContext false

        try {
            resolver.openOutputStream(imageUri)?.use { outputStream: OutputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                    throw Exception("Failed to compress bitmap")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }
            loadAll()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(imageUri, null, null)
            false
        }
    }

    fun createDeleteRequest(uris: List<Uri>): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris)
        } else {
            null
        }
    }

    fun saveBookPosition(bookId: Long, index: Int) {
        prefs.edit().putInt("book_$bookId", index).apply()
    }

    fun getBookPosition(bookId: Long): Int {
        return prefs.getInt("book_$bookId", 0)
    }

    suspend fun setAsWallpaper(uri: Uri, which: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            val wallpaperManager = WallpaperManager.getInstance(context)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wallpaperManager.setStream(inputStream, null, true, which)
                } else {
                    wallpaperManager.setStream(inputStream)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun renameMedia(item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            val rowsUpdated = resolver.update(item.uri, contentValues, null, null)
            if (rowsUpdated > 0) {
                loadAll()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteMediaDirectly(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            var deleted = false

            // 1. Direct File Deletion (Bypasses MediaStore Trash for permanent removal)
            if (item.uri.scheme == "file") {
                val file = File(item.uri.path ?: "")
                if (file.exists() && file.delete()) deleted = true
            } else {
                try {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    resolver.query(item.uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                            val path = cursor.getString(dataIndex)
                            val file = File(path)
                            if (file.exists() && file.delete()) {
                                deleted = true
                                // Clean up orphaned MediaStore entry
                                try {
                                    resolver.delete(item.uri, null, null)
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2. Fallback to standard MediaStore deletion
                if (!deleted) {
                    try {
                        val rowsDeleted = resolver.delete(item.uri, null, null)
                        if (rowsDeleted > 0) deleted = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            if (deleted) {
                loadAll()
                return@withContext true
            } else {
                return@withContext false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun clearPhotoMetadata(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            context.contentResolver.openFileDescriptor(item.uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_USER_COMMENT,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT
                )
                for (tag in tags) {
                    exif.setAttribute(tag, null)
                }
                exif.saveAttributes()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun renameDocument(item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, newName)
            }
            val rowsUpdated = resolver.update(item.uri, contentValues, null, null)
            if (rowsUpdated > 0) {
                loadAll()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteDocumentDirectly(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val rowsDeleted = resolver.delete(item.uri, null, null)
            if (rowsDeleted > 0) {
                loadAll()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun savePdfProject(originalItem: MediaItem, pageBitmaps: List<Bitmap>): Boolean =
        withContext(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            try {
                pageBitmaps.forEachIndexed { index, bitmap ->
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdfDocument.finishPage(page)
                }

                val resolver = getApplication<Application>().contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }

                val now = System.currentTimeMillis() / 1000
                val contentValues = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, "Edited_${originalItem.displayName}")
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.Files.FileColumns.DATE_ADDED, now)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                    }
                }

                val pdfUri = resolver.insert(collection, contentValues) ?: return@withContext false

                resolver.openOutputStream(pdfUri)?.use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    resolver.update(pdfUri, contentValues, null, null)
                }

                loadAll()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                pdfDocument.close()
            }
        }

    suspend fun prepareEpub(item: MediaItem): EpubProject? = withContext(Dispatchers.IO) {
        val baseDir = File(getApplication<Application>().cacheDir, "epub_work/${item.id}")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
            unzipEpub(item.uri, baseDir)
        }

        val opfPath = getOpfPath(baseDir) ?: return@withContext null
        val opfFile = File(baseDir, opfPath)
        val opfDir = opfPath.substringBeforeLast("/", "")

        val chapters = parseSpine(opfFile)
        EpubProject(baseDir, opfDir, chapters)
    }

    private fun unzipEpub(uri: Uri, destDir: File) {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
            val zipInputStream = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipInputStream.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { outputStream ->
                        zipInputStream.copyTo(outputStream)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
    }

    private fun getOpfPath(baseDir: File): String? {
        val containerFile = File(baseDir, "META-INF/container.xml")
        if (!containerFile.exists()) return null

        val parser = Xml.newPullParser()
        parser.setInput(containerFile.inputStream(), null)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            eventType = parser.next()
        }
        return null
    }

    private fun parseSpine(opfFile: File): List<EpubChapter> {
        val parser = Xml.newPullParser()
        parser.setInput(opfFile.inputStream(), null)

        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                    }

                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (idref != null) spine.add(idref)
                    }
                }
            }
            eventType = parser.next()
        }

        return spine.mapNotNull { id ->
            manifest[id]?.let { EpubChapter(id, it) }
        }
    }

    suspend fun loadOfficeContent(item: MediaItem): OfficeContent? = withContext(Dispatchers.IO) {
        val extension = item.displayName.substringAfterLast(".").lowercase()
        try {
            // Close previous documents if any
            currentWorkbook?.close()
            currentWorkbook = null
            currentWordDocument?.close()
            currentWordDocument = null
            currentPresentation?.close()
            currentPresentation = null

            currentMediaItem = item

            val content = when (extension) {
                "docx", "doc" -> parseWord(item.uri)
                "xlsx", "xls" -> parseSpreadsheet(item.uri)
                "pptx", "ppt" -> parsePresentation(item.uri)
                "odt" -> parseOdt(item.uri)
                "ods" -> parseOds(item.uri)
                "odp" -> parseOdp(item.uri)
                else -> null
            }
            _officeContent.value = content
            content
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun updateWordParagraph(index: Int, newText: String) {
        val doc = currentWordDocument ?: return
        val paragraphs = doc.paragraphs
        if (index in paragraphs.indices) {
            try {
                val p = paragraphs[index]
                // Remove all existing runs and add a single new run
                while (p.runs.isNotEmpty()) {
                    p.removeRun(0)
                }
                p.createRun().setText(newText)

                // Refresh UI
                val currentContent = _officeContent.value ?: return
                val word = currentContent.wordContent ?: return
                val updatedParas = word.paragraphs.toMutableList()
                updatedParas[index] = newText
                _officeContent.value = currentContent.copy(wordContent = WordContent(updatedParas))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSlideText(slideIndex: Int, newText: String) {
        val ppt = currentPresentation ?: return
        val slides = ppt.slides
        if (slideIndex in slides.indices) {
            try {
                val slide = slides[slideIndex]
                // For simplicity, we find the first text shape and update it
                // We avoid createAutoShape() as it requires java.awt classes missing on Android
                val textShapes = slide.shapes.filterIsInstance<XSLFTextShape>()
                if (textShapes.isNotEmpty()) {
                    textShapes[0].text = newText

                    // Refresh UI
                    val currentContent = _officeContent.value ?: return
                    val slideContent = currentContent.slideContent ?: return
                    val updatedSlides = slideContent.slides.toMutableList()
                    val currentSlide = updatedSlides[slideIndex]
                    updatedSlides[slideIndex] = currentSlide.copy(text = newText)
                    _officeContent.value = currentContent.copy(slideContent = SlideContent(updatedSlides))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSpreadsheetCell(sheetIndex: Int, rowIndex: Int, colIndex: Int, newValue: String) {
        val workbook = currentWorkbook ?: return
        val sheet = workbook.getSheetAt(sheetIndex)
        val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
        val cell = row.getCell(colIndex) ?: row.createCell(colIndex)

        if (newValue.startsWith("=") && newValue.length > 1) {
            try {
                cell.cellFormula = newValue.substring(1)
            } catch (e: Exception) {
                // If parsing fails, set it as a normal string so user can continue editing
                cell.setCellValue(newValue)
            }
        } else {
            cell.setCellValue(newValue)
        }

        recalculateAndRefreshUI()
    }

    fun formatSpreadsheetCell(sheetIndex: Int, rowIndex: Int, colIndex: Int, action: String, value: Any? = null) {
        val workbook = currentWorkbook ?: return
        val sheet = workbook.getSheetAt(sheetIndex)
        val row = sheet.getRow(rowIndex) ?: sheet.createRow(rowIndex)
        val cell = row.getCell(colIndex) ?: row.createCell(colIndex)

        val oldStyle = cell.cellStyle
        val newStyle = workbook.createCellStyle()
        newStyle.cloneStyleFrom(oldStyle)

        val font = workbook.createFont()
        val oldFont = workbook.getFontAt(oldStyle.fontIndex)
        // Copy old font properties
        font.bold = oldFont.bold
        font.italic = oldFont.italic
        font.color = oldFont.color
        font.fontHeightInPoints = oldFont.fontHeightInPoints
        font.fontName = oldFont.fontName

        when (action) {
            "bold" -> font.bold = !(value as? Boolean ?: oldFont.bold)
            "italic" -> font.italic = !(value as? Boolean ?: oldFont.italic)
            "bg_color" -> {
                val color = value as Int
                newStyle.fillForegroundColor = getIndexedColor(workbook, color)
                newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
            }
        }

        newStyle.setFont(font)
        cell.cellStyle = newStyle
        recalculateAndRefreshUI()
    }

    private fun getIndexedColor(workbook: Workbook, colorInt: Int): Short {
        // Simplified mapping for common colors
        return when (colorInt) {
            0xFFFF0000.toInt() -> IndexedColors.RED.index
            0xFF00FF00.toInt() -> IndexedColors.GREEN.index
            0xFF0000FF.toInt() -> IndexedColors.BLUE.index
            0xFFFFFF00.toInt() -> IndexedColors.YELLOW.index
            else -> IndexedColors.GREY_25_PERCENT.index
        }
    }

    private fun recalculateAndRefreshUI() {
        val workbook = currentWorkbook ?: return
        val evaluator = workbook.creationHelper.createFormulaEvaluator()
        try {
            evaluator.evaluateAll()
        } catch (e: Exception) {
            // Log error and continue to show existing values
            e.printStackTrace()
        }

        val formatter = DataFormatter()
        val currentContent = _officeContent.value ?: return
        val spreadsheet = currentContent.spreadsheetContent ?: return

        val updatedSheets = mutableListOf<SheetData>()
        for (i in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(i)
            val rows = mutableListOf<List<CellData>>()
            for (r in 0..maxOf(sheet.lastRowNum, 20)) {
                val row = sheet.getRow(r)
                val cells = mutableListOf<CellData>()
                for (c in 0..maxOf(row?.lastCellNum?.toInt() ?: 0, 10)) {
                    val cell = row?.getCell(c)
                    val style = cell?.cellStyle
                    val font = if (style != null) workbook.getFontAt(style.fontIndex) else null

                    cells.add(
                        CellData(
                            text = formatter.formatCellValue(cell, evaluator),
                            formula = if (cell?.cellType == CellType.FORMULA) "=" + cell.cellFormula else null,
                            isBold = font?.bold ?: false,
                            isItalic = font?.italic ?: false,
                            bgColor = if (style?.fillForegroundColorColor != null) 0xFF000000.toInt() else null // Simplified
                        )
                    )
                }
                rows.add(cells)
            }
            updatedSheets.add(SheetData(sheet.sheetName, rows))
        }

        _officeContent.value = currentContent.copy(
            spreadsheetContent = SpreadsheetContent(updatedSheets)
        )
    }


    suspend fun saveCurrentOfficeDocument(): Boolean = withContext(Dispatchers.IO) {
        val item = currentMediaItem ?: return@withContext false

        try {
            val outputStream = getApplication<Application>().contentResolver.openOutputStream(item.uri, "wt")
            outputStream?.use { os ->
                currentWorkbook?.write(os)
                currentWordDocument?.write(os)
                currentPresentation?.write(os)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        _audioPlayer.release()
        currentWorkbook?.close()
        currentWordDocument?.close()
        currentPresentation?.close()
    }

    private fun parseWord(uri: Uri): OfficeContent {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
            val extension = getExtension(uri)
            if (extension == "docx") {
                val doc = XWPFDocument(inputStream)
                currentWordDocument = doc
                val paragraphs = doc.paragraphs.map { it.text }
                return OfficeContent(OfficeType.WORD, wordContent = WordContent(paragraphs))
            } else {
                val doc = HWPFDocument(inputStream)
                val extractor = WordExtractor(doc)
                val paragraphs = extractor.paragraphText.toList()
                return OfficeContent(OfficeType.WORD, wordContent = WordContent(paragraphs))
            }
        }
        throw Exception("Failed to open stream")
    }

    private fun parseSpreadsheet(uri: Uri): OfficeContent {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
            val workbook = WorkbookFactory.create(inputStream)
            currentWorkbook = workbook
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            val formatter = DataFormatter()
            val sheets = mutableListOf<SheetData>()

            for (i in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(i)
                val rows = mutableListOf<List<CellData>>()
                // Load a decent default area if empty
                val rowCount = maxOf(sheet.lastRowNum, 20)
                for (rowIndex in 0..rowCount) {
                    val row = sheet.getRow(rowIndex)
                    val cells = mutableListOf<CellData>()
                    val colCount = maxOf(row?.lastCellNum?.toInt() ?: 0, 10)
                    for (cellIndex in 0 until colCount) {
                        val cell = row?.getCell(cellIndex)
                        val style = cell?.cellStyle
                        val font = if (style != null) workbook.getFontAt(style.fontIndex) else null

                        cells.add(
                            CellData(
                                text = formatter.formatCellValue(cell, evaluator),
                                formula = if (cell?.cellType == CellType.FORMULA) "=" + cell.cellFormula else null,
                                isBold = font?.bold ?: false,
                                isItalic = font?.italic ?: false,
                                bgColor = null // Can add complex color parsing later
                            )
                        )
                    }
                    rows.add(cells)
                }
                sheets.add(SheetData(sheet.sheetName, rows))
            }
            return OfficeContent(OfficeType.SPREADSHEET, spreadsheetContent = SpreadsheetContent(sheets))
        }
        throw Exception("Failed to open stream")
    }

    private fun parsePresentation(uri: Uri): OfficeContent {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
            val extension = getExtension(uri)
            if (extension == "pptx") {
                val ppt = XMLSlideShow(inputStream)
                currentPresentation = ppt

                val cacheDir = File(getApplication<Application>().cacheDir, "pptx_media_${System.currentTimeMillis()}")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val slides = ppt.slides.map { slide ->
                    val text = slide.shapes.filterIsInstance<XSLFTextShape>()
                        .joinToString("\n") { it.text }

                    val images = mutableListOf<Uri>()
                    val videos = mutableListOf<Uri>()

                    // Extract images from this slide
                    slide.shapes.filterIsInstance<XSLFPictureShape>().forEach { shape ->
                        val picData = shape.pictureData
                        val fileName = picData.fileName
                        val destFile = File(cacheDir, fileName)
                        if (!destFile.exists()) {
                            destFile.writeBytes(picData.data)
                        }
                        images.add(Uri.fromFile(destFile))
                    }

                    // For videos, they are often linked in XSLFGraphicFrame
                    // This is complex, but we can also scan the package for all media
                    // and try to map them. For now, let's extract all media from the package
                    // and show them on the first slide or relevant slides if possible.

                    SlideData(text, images, videos)
                }

                // Video extraction from package parts
                try {
                    val pkg = OPCPackage.open(getApplication<Application>().contentResolver.openInputStream(uri))
                    pkg.parts.forEach { part ->
                        if (part.contentType.startsWith("video/")) {
                            val fileName = part.partName.name.substringAfterLast("/")
                            val destFile = File(cacheDir, fileName)
                            part.inputStream.use { it.copyTo(destFile.outputStream()) }
                            // Add videos to the first slide for now as mapping is complex
                            if (slides.isNotEmpty()) {
                                (slides[0].videos as? MutableList)?.add(Uri.fromFile(destFile))
                            }
                        }
                    }
                    pkg.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                return OfficeContent(OfficeType.PRESENTATION, slideContent = SlideContent(slides))
            } else {
                val slideshow = SlideShowFactory.create(inputStream)
                val slides = slideshow.slides.map { slide ->
                    val text = slide.shapes.filterIsInstance<TextShape<*, *>>()
                        .joinToString("\n") { it.text }
                    SlideData(text)
                }
                return OfficeContent(OfficeType.PRESENTATION, slideContent = SlideContent(slides))
            }
        }
        throw Exception("Failed to open stream")
    }

    private fun getExtension(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val mimeType = resolver.getType(uri)
        return when (mimeType) {
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/msword" -> "doc"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.ms-powerpoint" -> "ppt"
            else -> uri.toString().substringAfterLast(".").lowercase()
        }
    }


    private fun parseOdt(uri: Uri): OfficeContent {
        // Basic ODT parsing (unzip and read content.xml)
        val text = extractOdfText(uri)
        return OfficeContent(OfficeType.WORD, wordContent = WordContent(listOf(text)))
    }

    private fun parseOds(uri: Uri): OfficeContent {
        // ODS is complex, for now extract text as sheets
        val text = extractOdfText(uri)
        return OfficeContent(
            OfficeType.SPREADSHEET,
            spreadsheetContent = SpreadsheetContent(listOf(SheetData("Content", listOf(listOf(CellData(text))))))
        )
    }

    private fun parseOdp(uri: Uri): OfficeContent {
        val text = extractOdfText(uri)
        return OfficeContent(OfficeType.PRESENTATION, slideContent = SlideContent(listOf(SlideData(text))))
    }

    private fun extractOdfText(uri: Uri): String {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
            val zipInputStream = ZipInputStream(inputStream)
            var entry: ZipEntry? = zipInputStream.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") {
                    val content = zipInputStream.bufferedReader().readText()
                    // Very crude tag stripping for ODF content.xml
                    return content.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
        return ""
    }

}
