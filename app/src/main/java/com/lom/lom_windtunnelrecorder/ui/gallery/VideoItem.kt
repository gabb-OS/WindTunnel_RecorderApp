package com.lom.lom_windtunnelrecorder.ui.gallery // Update with your package name

import android.net.Uri

/**
 * Data class representing a video item fetched from the MediaStore.
 *
 * @property uri The content URI of the video file.
 * @property name The display name of the video file.
 * @property durationMillis The duration of the video in milliseconds.
 * @property sizeBytes The size of the video file in bytes.
 * @property mimeType The MIME type of the video file (e.g., "video/mp4").
 */
data class VideoItem(
    val uri: Uri,
    val name: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val mimeType: String?
)