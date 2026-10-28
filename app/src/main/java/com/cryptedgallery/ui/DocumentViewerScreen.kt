package com.cryptedgallery.ui

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.cryptedgallery.data.*
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    viewModel: GalleryViewModel,
    item: MediaItem,
    onBack: () -> Unit,
    onEditPdf: (MediaItem) -> Unit = {}
) {
    val extension = item.displayName.substringAfterLast(".").lowercase()
    var isControlsVisible by remember { mutableStateOf(true) }
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    val officeContent by viewModel.officeContent.collectAsState()

    Scaffold(
        containerColor = when {
            extension == "txt" -> MaterialTheme.colorScheme.background
            extension == "pdf" -> Color.White
            extension == "dwg" || extension == "dxf" -> Color(0xFF0E1319)
            isDark -> Color(0xFF121212)
            else -> Color(0xFFF5F5F5)
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (extension) {
                "pdf" -> PdfViewer(
                    viewModel = viewModel,
                    item = item,
                    isControlsVisible = isControlsVisible,
                    onBack = onBack
                ) { isControlsVisible = !isControlsVisible }

                "txt" -> TxtViewer(
                    viewModel = viewModel,
                    item = item,
                    isControlsVisible = isControlsVisible,
                    modifier = Modifier.padding(innerPadding)
                ) { isControlsVisible = !isControlsVisible }

                "epub", "mobi" -> EpubViewer(
                    viewModel = viewModel,
                    item = item,
                    isControlsVisible = isControlsVisible,
                    onToggleControls = { isControlsVisible = !isControlsVisible }
                )

                "docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt" -> OfficeViewer(
                    viewModel = viewModel,
                    item = item,
                    onBack = onBack
                )

                "dwg", "dxf" -> CadViewerScreen(
                    viewModel = viewModel,
                    item = item
                )

                else -> UnsupportedViewer(extension)
            }

            // Floating Header
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp)
                        .zIndex(2f)
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.3f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }

                    Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                        // Save Button for Office
                        if (officeContent != null) {
                            val isEditable = extension in listOf("docx", "xlsx", "pptx", "xls")
                            if (isEditable) {
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            viewModel.saveCurrentOfficeDocument()
                                        }
                                    },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Save, "Save")
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }

                        if (extension == "pdf") {
                            FilledTonalIconButton(
                                onClick = { onEditPdf(item) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Edit, "Edit PDF")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfViewer(
    viewModel: GalleryViewModel,
    item: MediaItem,
    isControlsVisible: Boolean,
    onBack: () -> Unit,
    onToggleControls: () -> Unit
) {
    var pageCount by remember { mutableIntStateOf(0) }
    val pages = remember { mutableStateListOf<Bitmap?>() }
    val pagerState = rememberPagerState { pageCount }
    val verticalOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 150.dp.toPx() }

    LaunchedEffect(item.uri) {
        pageCount = viewModel.getPdfPageCount(item.uri)
        pages.clear()
        repeat(pageCount) { pages.add(null) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = verticalOffset.value
                alpha = (1f - (verticalOffset.value / (threshold * 2))).coerceIn(0f, 1f)
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleControls() })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        scope.launch {
                            val newOffset = verticalOffset.value + dragAmount
                            if (newOffset >= 0f) verticalOffset.snapTo(newOffset)
                        }
                    },
                    onDragEnd = {
                        if (verticalOffset.value > threshold) onBack()
                        else scope.launch { verticalOffset.animateTo(0f, tween(200)) }
                    }
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { index ->
            var bitmap by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(index) {
                if (pages.getOrNull(index) == null) {
                    pages[index] = viewModel.renderPdfPage(item.uri, index)
                }
                bitmap = pages.getOrNull(index)
            }

            Box(modifier = Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } ?: CircularProgressIndicator()
            }
        }

        if (isControlsVisible && pageCount > 0) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White
            ) {
                Text(
                    "${pagerState.currentPage + 1} / $pageCount",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun TxtViewer(
    viewModel: GalleryViewModel,
    item: MediaItem,
    isControlsVisible: Boolean,
    modifier: Modifier = Modifier,
    onToggleControls: () -> Unit
) {
    var text by remember { mutableStateOf("Loading...") }
    LaunchedEffect(item.uri) { text = viewModel.readTextFile(item.uri) }

    LazyColumn(
        modifier = modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) },
        contentPadding = PaddingValues(
            top = if (isControlsVisible) 100.dp else 32.dp,
            start = 24.dp,
            end = 24.dp,
            bottom = 32.dp
        )
    ) {
        item {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

@Composable
fun EpubViewer(
    viewModel: GalleryViewModel,
    item: MediaItem,
    isControlsVisible: Boolean,
    onToggleControls: () -> Unit
) {
    var epubProject by remember { mutableStateOf<EpubProject?>(null) }
    var currentChapterIndex by remember { mutableIntStateOf(viewModel.getBookPosition(item.id)) }
    var isLoading by remember { mutableStateOf(true) }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(item) {
        isLoading = true
        epubProject = viewModel.prepareEpub(item)
        isLoading = false
    }

    LaunchedEffect(currentChapterIndex) { viewModel.saveBookPosition(item.id, currentChapterIndex) }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    } else if (epubProject != null) {
        val project = epubProject!!
        val chapter = project.chapters.getOrNull(currentChapterIndex)
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) }) {
            if (chapter != null) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            setBackgroundColor(0)
                        }
                    },
                    update = { webView ->
                        val chapterFile = File(project.baseDir, "${project.opfDir}/${chapter.href}")
                        if (chapterFile.exists()) {
                            val backgroundColor = if (isDark) "#121212" else "#F5F5F5"
                            val textColor = if (isDark) "#E0E0E0" else "#333333"
                            val styledHtml =
                                "<html><body style=\"background-color:$backgroundColor;color:$textColor;font-family:serif;line-height:1.6;padding:24px;font-size:18px;\">${chapterFile.readText()}</body></html>"
                            webView.loadDataWithBaseURL(
                                "file://${project.baseDir.absolutePath}/${project.opfDir}/",
                                styledHtml,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Simple navigation overlay
            if (isControlsVisible) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp)
                        .background(Color.Black.copy(0.6f), CircleShape).padding(8.dp)
                ) {
                    IconButton(
                        onClick = { if (currentChapterIndex > 0) currentChapterIndex-- },
                        enabled = currentChapterIndex > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, null, tint = Color.White)
                    }
                    Text(
                        "Chapter ${currentChapterIndex + 1} / ${project.chapters.size}",
                        Modifier.align(Alignment.CenterVertically),
                        color = Color.White
                    )
                    IconButton(
                        onClick = { if (currentChapterIndex < project.chapters.size - 1) currentChapterIndex++ },
                        enabled = currentChapterIndex < project.chapters.size - 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun OfficeViewer(viewModel: GalleryViewModel, item: MediaItem, onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    val officeContent by viewModel.officeContent.collectAsState()

    LaunchedEffect(item.id) {
        isLoading = true
        viewModel.loadOfficeContent(item)
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (officeContent != null) {
            val content = officeContent!!
            when (content.type) {
                OfficeType.WORD -> WordContentViewer(viewModel, content.wordContent!!)
                OfficeType.SPREADSHEET -> SpreadsheetContentViewer(viewModel, content.spreadsheetContent!!)
                OfficeType.PRESENTATION -> PresentationContentViewer(viewModel, content.slideContent!!)
            }
        } else {
            OfficeMetadataFallback(item)
        }
    }
}

@Composable
fun WordContentViewer(viewModel: GalleryViewModel, content: WordContent) {
    var activeIndex by remember { mutableIntStateOf(-1) }
    var activeText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Debounced real-time sync
    LaunchedEffect(activeText) {
        if (activeIndex != -1) {
            val original = content.paragraphs.getOrNull(activeIndex) ?: ""
            if (activeText != original) {
                kotlinx.coroutines.delay(300)
                viewModel.updateWordParagraph(activeIndex, activeText)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 80.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(content.paragraphs) { index, para ->
            val isActive = activeIndex == index
            val focusRequester = remember { FocusRequester() }

            if (isActive) {
                BasicTextField(
                    value = activeText,
                    onValueChange = { activeText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 28.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        if (index < content.paragraphs.size - 1) {
                            activeIndex = index + 1
                            activeText = content.paragraphs[index + 1]
                        } else {
                            focusManager.clearFocus()
                            activeIndex = -1
                        }
                    })
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Text(
                    text = para,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 28.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activeIndex = index
                            activeText = para
                        }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SpreadsheetContentViewer(viewModel: GalleryViewModel, content: SpreadsheetContent) {
    var selectedSheetIndex by remember { mutableIntStateOf(0) }
    val sheet = content.sheets.getOrNull(selectedSheetIndex)

    Scaffold(
        bottomBar = {
            if (content.sheets.size > 1) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedSheetIndex,
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        content.sheets.forEachIndexed { index, sheetData ->
                            Tab(
                                selected = selectedSheetIndex == index,
                                onClick = { selectedSheetIndex = index },
                                text = { Text(sheetData.name) }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (sheet != null) {
                SpreadsheetGrid(
                    sheet = sheet,
                    viewModel = viewModel,
                    sheetIndex = selectedSheetIndex
                )
            }
        }
    }
}

@Composable
fun SpreadsheetGrid(
    sheet: SheetData,
    viewModel: GalleryViewModel,
    sheetIndex: Int
) {
    val hScrollState = rememberScrollState()
    val isDark = isSystemInDarkTheme()
    val headerColor = if (isDark) Color(0xFF252525) else Color(0xFFF0F0F0)
    val borderColor = if (isDark) Color.DarkGray else Color.LightGray

    var activeCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Debounced real-time sync
    LaunchedEffect(activeText) {
        activeCell?.let { (r, c) ->
            // Only sync if the text actually changed
            val original =
                sheet.rows.getOrNull(r)?.getOrNull(c)?.formula ?: sheet.rows.getOrNull(r)?.getOrNull(c)?.text ?: ""
            if (activeText != original) {
                kotlinx.coroutines.delay(300)
                viewModel.updateSpreadsheetCell(sheetIndex, r, c, activeText)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Formula Bar ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 80.dp), // Space for header
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(0.5.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Functions,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = activeText,
                    onValueChange = { activeText = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }
        }

        // --- The Grid ---
        Row(modifier = Modifier.horizontalScroll(hScrollState)) {
            Box(modifier = Modifier.size(40.dp, 32.dp).background(headerColor).border(0.5.dp, borderColor))
            val maxCols = sheet.rows.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxCols) {
                Box(
                    modifier = Modifier
                        .size(100.dp, 32.dp)
                        .background(headerColor)
                        .border(0.5.dp, borderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(getColumnLabel(i), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (sheet.rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data in this sheet", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(sheet.rows) { rowIndex, row ->
                        Row(modifier = Modifier.horizontalScroll(hScrollState)) {
                            // Row Number
                            Box(
                                modifier = Modifier
                                    .size(40.dp, 40.dp)
                                    .background(headerColor)
                                    .border(0.5.dp, borderColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text((rowIndex + 1).toString(), style = MaterialTheme.typography.labelSmall)
                            }

                            val maxCols = sheet.rows.maxOfOrNull { it.size } ?: 0
                            row.forEachIndexed { colIndex, cell ->
                                val isActive = activeCell == (rowIndex to colIndex)
                                val cellBg =
                                    if (rowIndex % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.3f
                                    )

                                Surface(
                                    modifier = Modifier
                                        .size(100.dp, 40.dp)
                                        .border(
                                            width = if (isActive) 2.dp else 0.5.dp,
                                            color = if (isActive) MaterialTheme.colorScheme.primary else borderColor.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                        .clickable {
                                            activeCell = rowIndex to colIndex
                                            activeText = cell.formula ?: cell.text
                                        },
                                    color = cellBg
                                ) {
                                    if (isActive) {
                                        BasicTextField(
                                            value = activeText,
                                            onValueChange = { activeText = it },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(8.dp)
                                                .focusRequester(focusRequester),
                                            textStyle = TextStyle(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 12.sp,
                                                fontWeight = if (cell.isBold) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = {
                                                // Move to next row
                                                if (rowIndex < sheet.rows.size - 1) {
                                                    activeCell = (rowIndex + 1) to colIndex
                                                    activeText = sheet.rows[rowIndex + 1].getOrNull(colIndex)
                                                        ?.let { it.formula ?: it.text } ?: ""
                                                }
                                            })
                                        )
                                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                    } else {
                                        Text(
                                            text = cell.text,
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = if (cell.isBold) FontWeight.Bold else FontWeight.Normal,
                                                fontStyle = if (cell.isItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            // Trailing empty cells
                            if (row.size < maxCols) {
                                for (i in 0 until (maxCols - row.size)) {
                                    val colIdx = row.size + i
                                    val isActive = activeCell == (rowIndex to colIdx)
                                    Box(
                                        modifier = Modifier
                                            .size(100.dp, 40.dp)
                                            .border(
                                                width = if (isActive) 2.dp else 0.5.dp,
                                                color = if (isActive) MaterialTheme.colorScheme.primary else borderColor.copy(
                                                    alpha = 0.5f
                                                )
                                            )
                                            .clickable {
                                                activeCell = rowIndex to colIdx
                                                activeText = ""
                                            },
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (isActive) {
                                            BasicTextField(
                                                value = activeText,
                                                onValueChange = { activeText = it },
                                                modifier = Modifier.padding(8.dp).focusRequester(focusRequester),
                                                textStyle = TextStyle(
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontSize = 12.sp
                                                ),
                                                singleLine = true
                                            )
                                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CellEditDialog(
    initialValue: String,
    title: String = "Edit Cell",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 10
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun getColumnLabel(index: Int): String {
    var n = index
    var label = ""
    while (n >= 0) {
        label = (('A'.code + (n % 26)).toChar()) + label
        n = (n / 26) - 1
    }
    return label
}

@Composable
fun PresentationContentViewer(viewModel: GalleryViewModel, content: SlideContent) {
    val pagerState = rememberPagerState { content.slides.size }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val focusManager = LocalFocusManager.current

    var activeText by remember { mutableStateOf("") }
    var lastInteractedIndex by remember { mutableIntStateOf(-1) }
    var isUiVisible by remember { mutableStateOf(true) }
    var isAutoPlaying by remember { mutableStateOf(false) }

    // Initialize activeText
    LaunchedEffect(pagerState.currentPage) {
        activeText = content.slides.getOrNull(pagerState.currentPage)?.text ?: ""
        lastInteractedIndex = pagerState.currentPage
    }

    // Debounced real-time sync
    LaunchedEffect(activeText) {
        if (lastInteractedIndex != -1) {
            val original = content.slides.getOrNull(lastInteractedIndex) ?: ""
            if (activeText != original) {
                kotlinx.coroutines.delay(300)
                viewModel.updateSlideText(lastInteractedIndex, activeText)
            }
        }
    }

    // Auto-play logic
    LaunchedEffect(isAutoPlaying) {
        if (isAutoPlaying) {
            while (isAutoPlaying) {
                kotlinx.coroutines.delay(3000)
                if (pagerState.currentPage < content.slides.size - 1) {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                } else {
                    isAutoPlaying = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color.Black else Color(0xFFF5F5F5))
            .clickable(interactionSource = null, indication = null) {
                isUiVisible = !isUiVisible
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp
        ) { pageIndex ->
            val slide = content.slides[pageIndex]
            val isCurrentPage = pagerState.currentPage == pageIndex
            val focusRequester = remember { FocusRequester() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 3D Cube Flip Effect
                        val pageOffset = ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)

                        translationX = size.width * pageOffset
                        rotationY = pageOffset * 90f
                        transformOrigin = if (pageOffset > 0) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)

                        val absOffset = abs(pageOffset)
                        alpha = (1f - absOffset).coerceIn(0f, 1f)
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(16f / 9f)
                        .shadow(if (isUiVisible) 16.dp else 4.dp, MaterialTheme.shapes.large)
                        .clickable {
                            if (isCurrentPage && isUiVisible) {
                                focusRequester.requestFocus()
                            } else {
                                isUiVisible = !isUiVisible
                            }
                        },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF2D2D2D) else Color.White
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "Slide ${pageIndex + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (isCurrentPage && isUiVisible) {
                            BasicTextField(
                                value = activeText,
                                onValueChange = { activeText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = if (isDark) Color.White else Color.Black,
                                    lineHeight = 26.sp
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                            )
                        } else {
                            Text(
                                text = slide.text,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = 26.sp,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Media Content
                        if (slide.images.isNotEmpty() || slide.videos.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            SlideMediaGallery(slide)
                        }
                    }
                }
            }
        }

        // Immersive Overlays
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Thumbnail Strip
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    items(count = content.slides.size) { index ->
                        val isSelected = index == pagerState.currentPage
                        Surface(
                            modifier = Modifier
                                .width(80.dp)
                                .fillMaxHeight()
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(0.4f),
                            border = if (isSelected) null else BorderStroke(1.dp, Color.White.copy(0.1f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = (index + 1).toString(),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Navigation & Play Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            IconButton(onClick = { isAutoPlaying = !isAutoPlaying }) {
                                Icon(if (isAutoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                            }
                            VerticalDivider(
                                modifier = Modifier.height(24.dp).width(1.dp).background(Color.White.copy(0.2f))
                            )
                            Text(
                                text = "${pagerState.currentPage + 1} / ${content.slides.size}",
                                modifier = Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }

        // Edge Tap Navigation (Invisible)
        if (isUiVisible) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable(interactionSource = null, indication = null) {
                            if (pagerState.currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            }
                        }
                )
                Spacer(modifier = Modifier.weight(4f))
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable(interactionSource = null, indication = null) {
                            if (pagerState.currentPage < content.slides.size - 1) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        }
                )
            }
        }
    }
}

@Composable
fun SlideMediaGallery(slide: SlideData) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (slide.images.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                items(slide.images) { uri ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Image(
                            painter = coil3.compose.rememberAsyncImagePainter(uri),
                            contentDescription = null,
                            modifier = Modifier
                                .aspectRatio(4f / 3f)
                                .fillMaxHeight(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        if (slide.videos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            slide.videos.forEach { uri ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    SlideVideoPlayer(uri)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
fun SlideVideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false // Don't start all videos at once
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun OfficeMetadataFallback(item: MediaItem) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Description, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text(item.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with..."))
        }) { Text("Try Opening with External App") }
    }
}

@Composable
fun UnsupportedViewer(extension: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Viewer for .$extension is not implemented.") }
}
