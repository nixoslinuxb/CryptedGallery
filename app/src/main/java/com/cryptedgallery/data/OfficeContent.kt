package com.cryptedgallery.data

import android.net.Uri

data class OfficeContent(
    val type: OfficeType,
    val wordContent: WordContent? = null,
    val spreadsheetContent: SpreadsheetContent? = null,
    val slideContent: SlideContent? = null
)

enum class OfficeType {
    WORD, SPREADSHEET, PRESENTATION
}

data class WordContent(
    val paragraphs: List<String>
)

data class SpreadsheetContent(
    val sheets: List<SheetData>
)

data class SheetData(
    val name: String,
    val rows: List<List<CellData>>
)

data class CellData(
    val text: String,
    val formula: String? = null,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val textColor: Int? = null, // ARGB
    val bgColor: Int? = null    // ARGB
)

data class SlideContent(
    val slides: List<SlideData>
)

data class SlideData(
    val text: String,
    val images: List<Uri> = emptyList(),
    val videos: List<Uri> = emptyList()
)

data class SlideContentLegacy(
    val slides: List<String>
)
