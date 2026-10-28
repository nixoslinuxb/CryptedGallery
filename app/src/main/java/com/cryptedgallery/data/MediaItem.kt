package com.cryptedgallery.data

import android.net.Uri

enum class MediaType {
    PHOTO, VIDEO, DOCUMENT, AUDIO
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val type: MediaType,
    val mimeType: String? = null,
    val size: Long = 0L,
    val duration: Long? = null // For videos
)
