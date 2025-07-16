package com.lom.lom_windtunnelrecorder.ui.gallery

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lom.lom_windtunnelrecorder.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * ViewModel for the [GalleryFragment].
 * Responsible for loading video items from the Android MediaStore.
 * It uses [AndroidViewModel] to get access to the Application context, which is needed
 * for accessing string resources and the content resolver.
 *
 * @param application The application instance.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _videos = MutableLiveData<List<VideoItem>>()
    val videos: LiveData<List<VideoItem>> = _videos

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /**
     * Asynchronously loads video files from the MediaStore.
     * This function is a suspend function and performs the query on the IO dispatcher
     * to avoid blocking the main thread.
     *
     * It filters videos based on a relative path defined in string resources,
     * effectively loading only videos recorded by this application into its specific directory.
     * Videos are sorted by date added in descending order (newest first).
     */
    suspend fun loadVideos() {
        withContext(Dispatchers.IO) {
            _isLoading.postValue(true)
            val videoList = mutableListOf<VideoItem>()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE
                // MediaStore.Video.Media.DATE_ADDED
            )

            // This matches the path used in RecorderFragment.
            val appRecDirectory = getApplication<Application>().getString(R.string.path_to_recordings_in_device)
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("$appRecDirectory/")

            // Sort by date added in descending order (newest first)
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            // Perform the query on the MediaStore using the content resolver.
            val query = getApplication<Application>().contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, // URI for external video content.
                projection,    // Columns to retrieve.
                selection,     // SQL WHERE clause (without "WHERE").
                selectionArgs, // Values for the '?' placeholders in the selection.
                sortOrder      // How to sort the results.
            )

            query?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimetypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val mimeType = cursor.getString(mimetypeColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    // Create a VideoItem and add it to the list.
                    videoList.add(VideoItem(contentUri, name, duration, size, mimeType))
                }
            }
            _videos.postValue(videoList)            // Post the final list of videos to LiveData (for UI update on the main thread).
            _isLoading.postValue(false)       // Signal the UI that loading has finished.
        }
    }

    fun clearError() {
        _error.value = null
    }
}