package com.lom.lom_windtunnelrecorder.ui.recorder

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.lom.lom_windtunnelrecorder.R
import com.lom.lom_windtunnelrecorder.databinding.FragmentRecorderBinding
import androidx.core.view.isVisible


/**
 * A [Fragment] responsible for displaying the camera preview, handling video recording,
 * managing camera permissions, and interacting with [HomeViewModel] to reflect UI state.
 * It supports features like ultra-wide camera selection and zoom control.
 */
class RecorderFragment : Fragment() {

    // View binding instance for this fragment.
    // _binding is nullable and used to prevent memory leaks by nulling it in onDestroyView
    private var _binding: FragmentRecorderBinding? = null
    // Non-nullable accessor for binding. Should only be used between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    // ViewModel associated with this fragment, scoped to the fragment's lifecycle.
    private val homeViewModel: HomeViewModel by viewModels()

    //XCamera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService    // Dedicated executor for camera operations.
    private var camera: Camera? = null                      // To store the bound camera object

    // Target zoom for ultrawide recording
    private var targetZoomRatio = 0.5f

    /**
     * ActivityResultLauncher for requesting camera permission.
     * Handles the user's response to the permission dialog.
     */
    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                homeViewModel.onPermissionsGranted()        //notify ViewModel to proceed with camera setup
            } else {
                homeViewModel.onCameraPermissionDenied()
            }
        }


    /**
     * Called to have the fragment instantiate its user interface view.
     * Inflates the layout, initializes the camera executor.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecorderBinding.inflate(inflater, container, false)
        cameraExecutor = Executors.newSingleThreadExecutor()
        return binding.root
    }

    /**
     * Called immediately after [onCreateView] has returned, but before any saved state has been restored in to the view.
     * Sets up UI observers, the FloatingActionButton, and checks for initial camera permissions.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeViewModel()      // Start observing LiveData from the ViewModel
        setupFab()              // Configure the FloatingActionButton (the one for starting and stopping the recording)

        if (isCameraPermissionGranted()) {
            // If granted, notify ViewModel to proceed (READY_TO_PREVIEW state)
            homeViewModel.onPermissionsGranted()
        } else {
            requestCameraPermission()
        }
    }

    /**
     * Initiates the camera permission request using the [cameraPermissionRequest] launcher.
     */
    private fun requestCameraPermission() {
        cameraPermissionRequest.launch(Manifest.permission.CAMERA)
    }

    /**
     * Checks if the camera permission (Manifest.permission.CAMERA) has been granted.
     * @return True if permission is granted, false otherwise.
     */
    private fun isCameraPermissionGranted() =
        ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Configures the click listener for the recording FloatingActionButton (FAB).
     * Notifies the [HomeViewModel] of the FAB click, which then orchestrates the state changes
     * for starting or stopping the recording.
     * It also directly calls [stopActiveRecording] if the app was in a recording state to ensure
     * CameraX recording is halted.
     */
    private fun setupFab() {
        binding.recordingFab.setOnClickListener {

            // Get the current state before telling the ViewModel about the click
            val currentState = homeViewModel.cameraState.value
            // Tell ViewModel the FAB was clicked. ViewModel will update its state.
            homeViewModel.onFabClicked()

            // If the state before this click was RECORDING, then this click means "stop".
            if (currentState == CameraState.RECORDING) {
                if (activeRecording != null) {
                    stopActiveRecording()
                } else {
                    // This is an edge case: UI indicated recording, but CameraX wasn't actively recording.
                    // The ViewModel should handle transitioning out of the RECORDING state regardless.
                    Log.w(TAG, "FAB clicked to stop, but no active recording found in fragment.")
                }
            }
            //The observer for cameraState will then see this new RECORDING state and trigger startVideoRecording().
        }
    }

    /**
     * Sets up observers for LiveData objects exposed by the [HomeViewModel].
     * This function is crucial for updating the UI in response to state changes
     * (e.g., camera readiness, recording status, timer updates, error messages).
     */
    private fun observeViewModel() {
        homeViewModel.cameraState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CameraState.READY_TO_PREVIEW -> {
                    // UI setup for preview state: record icon, no recording timer shown
                    binding.recordingFab.setImageResource(R.drawable.ic_record)
                    binding.recordingFab.isEnabled = true
                    binding.recordingTimerText.visibility = View.GONE
                    binding.recordingIndicatorDot.visibility = View.GONE
                    // If a recording was somehow active (e.g., due to an unexpected state transition), stop it.
                    if (activeRecording != null) {
                        stopActiveRecording()
                    }
                    // Initialize or restart the camera preview, attempting to use ultra-wide if desired.
                    startCameraPreviewOnly(true)
                }
                CameraState.RECORDING -> {
                    // UI setup for recording state: stop icon, recording timer shown
                    binding.recordingFab.setImageResource(R.drawable.ic_stop)
                    binding.recordingFab.isEnabled = true
                    binding.recordingTimerText.text = getString(R.string.timer_placeholder)    // Reset timer string to 00:00
                    binding.recordingTimerText.visibility = View.VISIBLE
                    binding.recordingIndicatorDot.visibility = View.VISIBLE

                    // If CameraX is not already recording, initiate the video recording process.
                    if (activeRecording == null) {
                        startVideoRecording()
                    }
                }
                CameraState.ERROR -> {
                    binding.recordingFab.isEnabled = true
                    binding.recordingFab.setImageResource(R.drawable.ic_record)
                    binding.recordingTimerText.visibility = View.GONE
                    binding.recordingIndicatorDot.visibility = View.GONE
                    Toast.makeText(requireContext(), "An error occurred.", Toast.LENGTH_LONG).show()
                }
                else -> {  // Handles IDLE, PERMISSION_REQUESTED, or any other unhandled states.
                    binding.recordingFab.isEnabled = false
                    binding.recordingTimerText.visibility = View.GONE
                    binding.recordingIndicatorDot.visibility = View.GONE
                }
            }
        }

        // Observe the duration from ViewModel and update the TextView
        homeViewModel.recordingDurationMillis.observe(viewLifecycleOwner) { millis ->
            // Only update the text if the timer view is currently visible (i.e., we are recording)
            if (binding.recordingTimerText.isVisible) {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
                binding.recordingTimerText.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }

        // Observe events to show a "permission denied" message to the user.
        homeViewModel.showCameraPermissionDeniedMessage.observe(viewLifecycleOwner) { shouldShow ->
            if (shouldShow) {
                Toast.makeText(requireContext(), "Camera permission is required for Session Recorder mode", Toast.LENGTH_LONG).show()
                // Notify ViewModel that the message has been shown to prevent re-showing on config change.
                homeViewModel.resetPermissionDeniedMessages()
            }
        }
    }

    /**
     * Selects an ultra-wide camera lens if available, by inspecting its minimum zoom ratio.
     * This function utilizes Camera2 interop features and requires the corresponding OptIn.
     * @param cameraProvider The [ProcessCameraProvider] instance to query for available cameras.
     * @return A [CameraSelector] configured for the identified ultra-wide camera, or null if not found.
     */

    // The Kotlin standard library provides an opt-in mechanism for requiring and giving explicit consent when it comes to using certain elements of APIs.
    // This allows library developers to provide users with information about specific conditions that require user consent,
    // such as when an API is in an experimental state and is likely to undergo changes in the future.
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun getUltraWideCameraSelector(cameraProvider: ProcessCameraProvider): CameraSelector? {
        try {
            val availableCameraInfos = cameraProvider.availableCameraInfos
            val ultraWideCandidateInfo = availableCameraInfos.find { cameraInfo ->
                val isBackFacing = cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
                if (!isBackFacing) return@find false

                val zoomState = cameraInfo.zoomState.value
                val minRatio = zoomState?.minZoomRatio ?: 1.0f      // Default to 1.0f if zoom state is null.
                Log.d(TAG, "Camera LENS_FACING: ${cameraInfo.lensFacing}, minZoomRatio: $minRatio, targetZoom: $targetZoomRatio")

                // Heuristic: Ultra-wide cameras typically have minZoomRatio <= targetZoomRatio (e.g., 0.5x).
                // Adding a small tolerance (0.15f) for flexibility.
                minRatio <= targetZoomRatio + 0.15f
            }

            if (ultraWideCandidateInfo != null) {
                // If an ultra-wide candidate is found, get its Camera2 ID for precise selection.
                val ultraWideCameraId = Camera2CameraInfo.from(ultraWideCandidateInfo).cameraId
                Log.i(TAG, "Found potential ultra-wide camera: ID $ultraWideCameraId with minZoom ${ultraWideCandidateInfo.zoomState.value?.minZoomRatio}")

                // Build a CameraSelector that specifically filters for this camera ID.
                return CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == ultraWideCameraId }
                    }
                    .build()
            } else {
                Log.w(TAG, "No distinct ultra-wide camera found based on minZoomRatio. Using default back camera.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera info for ultra-wide selection: ${e.message}", e)
        }
        return null     // Return null if no ultra-wide camera is found or an error occurs.
    }

    /**
     * Initializes and starts the camera preview.
     * This function binds only the [Preview] use case to the camera.
     * @param tryUltraWide If true, attempts to select and use an ultra-wide camera; otherwise, uses the default back camera.
     */
    private fun startCameraPreviewOnly(tryUltraWide: Boolean) { // parameter name changed for clarity
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val currentCameraProvider = cameraProvider ?: run {
                Log.e(TAG, "Camera provider is null in startCameraPreviewOnly.")
                homeViewModel.onCameraError("Camera provider unavailable.")
                return@addListener
            }
            // Build the Preview use case.
            preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            // Select camera: ultra-wide if requested and available, otherwise default back camera.
            val cameraSelector = if (tryUltraWide) {
                getUltraWideCameraSelector(currentCameraProvider) ?: CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                // Unbind all use cases before rebinding to prevent conflicts.
                currentCameraProvider.unbindAll()
                // Bind the Preview use case to the lifecycle and the selected camera.
                // Store the bound camera instance for potential controls like zoom.
                camera = currentCameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview
                )

                // Apply desired zoom based on selected camera
                if (cameraSelector != CameraSelector.DEFAULT_BACK_CAMERA && tryUltraWide) {
                    applyZoom(targetZoomRatio)
                } else {
                    applyZoom(1.0f)     // It's the default back camera
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Preview Use case binding failed", exc)
                homeViewModel.onCameraError("Preview failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))     // Run on the main thread.
    }

    /**
     * Initializes and starts video recording.
     * This function sets up the [VideoCapture] use case, binds it along with [Preview] to the camera,
     * applies the desired zoom, and starts the CameraX recording process.
     * Videos are saved to the device's MediaStore.
     */
    private fun startVideoRecording() {
        if (cameraProvider == null || !isCameraPermissionGranted()) {
            Log.e(TAG, "CameraProvider not initialized or camera permission not granted.")
            homeViewModel.onCameraError("Cannot start recording.")
            return
        }
        if (activeRecording != null) {
            Log.w(TAG, "startVideoRecording called while already recording.")
            return
        }
        // Should not happen due to above check, but good for safety.
        val currentCameraProvider = cameraProvider ?: run {
            Log.e(TAG, "Camera provider is null in startVideoRecording.")
            homeViewModel.onCameraError("Camera provider unavailable for recording.")
            return
        }

        // Build the Recorder for VideoCapture.
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Re-build Preview for consistent binding if it was unbound or to ensure it's current.
        preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }
        val cameraSelectorForRecording = getUltraWideCameraSelector(currentCameraProvider)
            ?: CameraSelector.DEFAULT_BACK_CAMERA // Fallback if ultra-wide not found
        Log.i(TAG, "Recording: Using camera selector: ${if (cameraSelectorForRecording == CameraSelector.DEFAULT_BACK_CAMERA) "Default Back" else "Selected (Ultra-Wide candidate)"}")

        try {
            // Unbind all use cases before rebinding with androidx . camera . video . VideoCapture.
            cameraProvider?.unbindAll()
            // Bind Preview and VideoCapture use cases. Store the bound camera instance.
            camera = cameraProvider?.bindToLifecycle(
                viewLifecycleOwner, cameraSelectorForRecording, preview, videoCapture
            )

            // --- Apply 0.5x Zoom ---
            if (cameraSelectorForRecording != CameraSelector.DEFAULT_BACK_CAMERA) { // It's our ultra-wide
                applyZoom(targetZoomRatio) // Apply 0.5x zoom if using the selected (ultra-wide)
            } else {
                applyZoom(1.0f)
            }
        } catch (exc: Exception) {
            Log.e(TAG, "VideoCapture or Preview Use case binding failed", exc)
            homeViewModel.onCameraError("Binding for recording failed: ${exc.message}")
            return  // Exit if binding fails.
        }


        // --- Configure MediaStore for saving the video ---
        val name = "WT-Session_" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {                    // For API 29 and above.
                // Use RELATIVE_PATH for Scoped Storage. Saves to Movies/YourApp-Videos directory.
                put(MediaStore.Video.Media.RELATIVE_PATH, getString(R.string.path_to_recordings_in_device))
            }
        }
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireActivity().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // --- Start the actual recording ---
        activeRecording = videoCapture?.output
            ?.prepareRecording(requireContext(), mediaStoreOutputOptions)
            ?.start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.i(TAG, "Recording started (video only)")
                    }
                    is VideoRecordEvent.Finalize -> {
                        val wasError = recordEvent.hasError()
                        // It's good practice to explicitly close the recording session.
                        activeRecording?.close()
                        activeRecording = null  // Clear the active recording session

                        if (!wasError) {
                            val msg = "Video capture succeeded"
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        } else {
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error} - ${recordEvent.cause?.message}")
                            Toast.makeText(requireContext(), "Video Error: ${recordEvent.cause?.message}", Toast.LENGTH_LONG).show()
                        }
                        // This call will stop the timer and set state to READY_TO_PREVIEW
                        homeViewModel.recordingFinished()
                    }
                }
            }
    }

    /**
     * Stops the currently active CameraX recording session.
     * This function calls `stop()` on the [activeRecording] object.
     * The actual cleanup and state transitions occur in the [VideoRecordEvent.Finalize] callback.
     */
    private fun stopActiveRecording() {
        if (activeRecording != null) {
            activeRecording?.stop() // Request CameraX to stop
            Log.i(TAG, "Requested to stop active recording. Waiting for Finalize event.")
        }
    }

    /**
     * Applies the specified zoom ratio to the currently bound camera.
     * The ratio is automatically clamped by CameraX to the camera's supported min/max zoom ratios.
     * @param ratio The desired zoom ratio (e.g., 0.5f for zoom-out, 1.0f for no zoom, 2.0f for zoom-in).
     */
    private fun applyZoom(ratio: Float) {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value
            if (zoomState != null) {
                val clampedRatio = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                if (clampedRatio != ratio) {
                    Log.w(TAG, "Requested zoom ratio $ratio was out of range [${zoomState.minZoomRatio}, ${zoomState.maxZoomRatio}]. Clamped to $clampedRatio.")
                }
                Log.i(TAG, "Applying zoom ratio: $clampedRatio (Min: ${zoomState.minZoomRatio}, Max: ${zoomState.maxZoomRatio})")
                // Asynchronously set the zoom ratio.
                cam.cameraControl.setZoomRatio(clampedRatio).addListener({
                    // This listener is called when the setZoomRatio operation completes (success or failure).
                    val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: -1f      // Get current zoom after attempt.
                    Log.i(TAG, "Zoom ratio set/attempted. Current actual zoom: $currentZoom")
                }, ContextCompat.getMainExecutor(requireContext()))
            } else {
                Log.e(TAG, "ZoomState is null, cannot set zoom.")
            }
        } ?: Log.e(TAG, "Camera object is null, cannot set zoom.")
    }

    /**
     * Called when the Fragment is no longer resumed.
     * If recording is in progress, it stops the recording and notifies the ViewModel
     * to prevent resource leaks and ensure consistent state.
     */
    override fun onPause() {
        super.onPause()
        if (homeViewModel.cameraState.value == CameraState.RECORDING) {
            stopActiveRecording()
            homeViewModel.recordingFinished()
        }
    }

    /**
     * Called when the view previously created by [onCreateView] has been detached from the fragment.
     * Cleans up resources: shuts down the camera executor, unbinds all CameraX use cases,
     * and nullifies the view binding object to prevent memory leaks.
     */
    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        _binding = null
    }


    /**
     * Companion object for constants used within the RecorderFragment.
     */
    companion object {
        private const val TAG = "RecorderFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd_HH-mm-ss"
    }
}