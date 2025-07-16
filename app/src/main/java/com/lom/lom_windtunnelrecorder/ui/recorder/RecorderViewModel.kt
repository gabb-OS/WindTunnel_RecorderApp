package com.lom.lom_windtunnelrecorder.ui.recorder

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class CameraState {
    IDLE,
    PERMISSION_REQUESTED,
    READY_TO_PREVIEW,
    RECORDING,
    ERROR
}

/**
 * ViewModel for [RecorderFragment].
 * Manages the state of the camera UI (e.g., whether it's recording, ready for preview),
 * handles permission results, and controls a recording timer.
 * It exposes state via [LiveData] for the Fragment to observe and react to.
 */
class HomeViewModel : ViewModel() {

    private val _cameraState = MutableLiveData<CameraState>(CameraState.IDLE)
    val cameraState: LiveData<CameraState> = _cameraState

    private val _showCameraPermissionDeniedMessage = MutableLiveData<Boolean>()
    val showCameraPermissionDeniedMessage: LiveData<Boolean> = _showCameraPermissionDeniedMessage

    private val _recordingDurationMillis = MutableLiveData<Long>(0L)
    val recordingDurationMillis: LiveData<Long> = _recordingDurationMillis // Exposed for the Fragment to observe

    private var timerJob: Job? = null
    private var recordingStartTime: Long = 0L

    fun onPermissionsGranted() {
        _cameraState.value = CameraState.READY_TO_PREVIEW
    }

    fun onCameraPermissionDenied() {
        _showCameraPermissionDeniedMessage.value = true
        _cameraState.value = CameraState.ERROR
    }

    /**
     * Handles clicks on the FloatingActionButton (FAB).
     *
     * - If the camera is [CameraState.READY_TO_PREVIEW], this click signifies an intent to start recording.
     *   The state transitions to [CameraState.RECORDING], and the recording timer starts.
     *   The actual CameraX recording is initiated by the observing Fragment in response to this state change.
     *
     * - If the camera is [CameraState.RECORDING], this click signifies an intent to stop recording.
     *   The ViewModel notes this intent but *does not* immediately change its state out of RECORDING.
     *   It waits for the Fragment to confirm that CameraX has actually finalized the recording
     *   (via a call to [recordingFinished]). This ensures the ViewModel's state accurately
     *   reflects the true status of the hardware/CameraX recording process.
     *   The timer continues to run until [recordingFinished] is called.
     */
    fun onFabClicked() {
        when (_cameraState.value) {
            CameraState.READY_TO_PREVIEW -> {
                // User wants to start recording
                _cameraState.value = CameraState.RECORDING
                startTimer()
            }
            CameraState.RECORDING -> {
                // User intends to stop recording.
                // The Fragment is responsible for calling CameraX's stopRecording().
                // The ViewModel will transition out of RECORDING state only when
                // recordingFinished() is called by the Fragment, confirming CameraX finalization.
                // No immediate state change here from the ViewModel's perspective regarding CameraState.
                // The timer continues until recordingFinished() calls stopTimer().
                Log.d("ViewModel", "FAB Clicked: Intent to Stop Recording received.")
            }
            else -> {
                Log.w("HomeViewModel", "FAB clicked in unexpected state: ${_cameraState.value}")
            }
        }
    }

    /**
     * Starts the recording timer.
     * Records the start time and launches a coroutine that updates [recordingDurationMillis] every second.
     * The timer continues as long as the [cameraState] remains [CameraState.RECORDING].
     */
    private fun startTimer() {
        recordingStartTime = SystemClock.elapsedRealtime()
        _recordingDurationMillis.value = 0L // Explicitly reset for observers
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000) // Update every second
                if (_cameraState.value == CameraState.RECORDING) {
                    _recordingDurationMillis.postValue(SystemClock.elapsedRealtime() - recordingStartTime)
                } else {
                    break // Stop timer if state changed unexpectedly
                }
            }
        }
    }

    /**
     * Stops the recording timer and resets the [recordingDurationMillis] to 0.
     * Cancels the active [timerJob].
     */
    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _recordingDurationMillis.value = 0L
    }

    /**
     * Called by the Fragment after CameraX has confirmed that video recording
     * has actually stopped and the file has been finalized (successfully or with an error).
     * This function transitions the state from [CameraState.RECORDING] to [CameraState.READY_TO_PREVIEW]
     * and stops the recording timer.
     */
    fun recordingFinished() {
        if (_cameraState.value == CameraState.RECORDING) {
            _cameraState.value = CameraState.READY_TO_PREVIEW
        }
        stopTimer()
    }

    /**
     * Called when a camera-related error occurs (e.g., binding failed, recording error from CameraX).
     * Transitions the state to [CameraState.ERROR] and stops the recording timer if it was running.
     * @param message Optional error message string (currently not used for LiveData, but could be).
     */
    fun onCameraError(message: String? = null) {
        if (_cameraState.value == CameraState.RECORDING) {
            // If an error occurs during recording, ensure we transition out of recording state
            _cameraState.value = CameraState.ERROR
        } else {
            _cameraState.value = CameraState.ERROR
        }
        stopTimer()
    }

    /**
     * Resets the flag for showing the "camera permission denied" message.
     * Called by the Fragment after the message has been displayed to prevent it from re-appearing
     * on configuration changes or subsequent observations.
     */
    fun resetPermissionDeniedMessages() {
        _showCameraPermissionDeniedMessage.value = false
    }

    /**
     * Called when the ViewModel is about to be destroyed.
     * Ensures the recording timer is stopped to prevent leaks or unexpected behavior.
     */
    override fun onCleared() {
        super.onCleared()
        stopTimer()
    }
}