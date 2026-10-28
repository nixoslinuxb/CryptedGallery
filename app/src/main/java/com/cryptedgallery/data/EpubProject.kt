package com.cryptedgallery.data

import java.io.File

data class EpubChapter(
    val id: String,
    val href: String,
    val title: String? = null
)

data class EpubProject(
    val baseDir: File,
    val opfDir: String, // Directory relative to baseDir where .opf resides
    val chapters: List<EpubChapter>
)
