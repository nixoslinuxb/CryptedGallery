package com.cryptedgallery.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaScanner(private val context: Context) {

    suspend fun getImages(): List<MediaItem> = withContext(Dispatchers.IO) {
        val images = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val selection = "${MediaStore.Images.Media.MIME_TYPE} != ?"
        val selectionArgs = arrayOf("image/svg+xml")

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    images.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: "Unknown",
                            dateAdded = cursor.getLong(dateCol),
                            type = MediaType.PHOTO,
                            mimeType = cursor.getString(mimeCol),
                            size = cursor.getLong(sizeCol)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        images
    }

    suspend fun getAudioFiles(): List<MediaItem> = withContext(Dispatchers.IO) {
        val audio = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION
        )
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    audio.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: "Unknown Audio",
                            dateAdded = cursor.getLong(dateCol),
                            type = MediaType.AUDIO,
                            mimeType = cursor.getString(mimeCol),
                            size = cursor.getLong(sizeCol),
                            duration = cursor.getLong(durCol)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        audio
    }

    suspend fun getVideos(): List<MediaItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idCol)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    videos.add(
                        MediaItem(
                            id = id,
                            uri = itemUri,
                            displayName = cursor.getString(nameCol) ?: "Unknown Video",
                            dateAdded = cursor.getLong(dateCol),
                            type = MediaType.VIDEO,
                            mimeType = cursor.getString(mimeCol),
                            size = cursor.getLong(sizeCol),
                            duration = cursor.getLong(durCol)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Fallback: If MediaStore finds nothing or we want a thorough check
        if (videos.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val externalDir = Environment.getExternalStorageDirectory()
            val fileVideos = mutableListOf<MediaItem>()
            fun scanDir(dir: File) {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory && !file.name.startsWith(".")) {
                        scanDir(file)
                    } else if (file.isFile && (file.name.endsWith(".mp4", true) || file.name.endsWith(
                            ".mkv",
                            true
                        ) || file.name.endsWith(".avi", true))
                    ) {
                        fileVideos.add(
                            MediaItem(
                                id = file.absolutePath.hashCode().toLong(),
                                uri = Uri.fromFile(file),
                                displayName = file.name,
                                dateAdded = file.lastModified() / 1000,
                                type = MediaType.VIDEO,
                                mimeType = "video/${file.extension.lowercase()}",
                                size = file.length(),
                                duration = 0L // Cannot easily get without retriever
                            )
                        )
                    }
                }
            }
            try {
                scanDir(externalDir)
                videos.addAll(fileVideos.sortedByDescending { it.dateAdded })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        videos
    }

    suspend fun getDocuments(): List<MediaItem> = withContext(Dispatchers.IO) {
        val docs = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE
        )

        val supportedMimeTypes = listOf(
            "application/pdf",
            "application/epub+zip",
            "text/plain",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.mobi8-kfx",
            "application/x-fb2",
            "application/x-cbz",
            "image/svg+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "image/vnd.dwg",
            "application/acad",
            "application/x-acad",
            "application/dwg",
            "application/x-dwg",
            "image/vnd.dxf",
            "application/dxf",
            "application/x-dxf",
            "text/x-dxf"
        )

        val supportedExtensions = listOf(
            "pdf", "epub", "mobi", "kf8", "fb2", "cbz", "txt", "svg",
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt",
            "dwg", "dxf"
        )

        val mimeSelection = supportedMimeTypes.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        }
        val extensionSelection = supportedExtensions.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.${it}'"
        }

        val selection = "($mimeSelection) OR ($extensionSelection)"
        val selectionArgs = supportedMimeTypes.toTypedArray()

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        val volumeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        context.contentResolver.query(
            volumeUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idCol)
                    val uri =
                        ContentUris.withAppendedId(volumeUri, id) // FIXED: use volumeUri instead of hardcoded external
                    docs.add(
                        MediaItem(
                            id = id,
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: "Unknown Document",
                            dateAdded = cursor.getLong(dateCol),
                            type = MediaType.DOCUMENT,
                            mimeType = cursor.getString(mimeCol),
                            size = cursor.getLong(sizeCol)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        docs
    }
}
