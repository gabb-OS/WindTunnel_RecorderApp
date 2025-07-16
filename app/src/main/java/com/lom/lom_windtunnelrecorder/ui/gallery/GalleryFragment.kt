package com.lom.lom_windtunnelrecorder.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.lom.lom_windtunnelrecorder.R
import com.lom.lom_windtunnelrecorder.databinding.FragmentGalleryBinding
import kotlinx.coroutines.launch


/**
 * A [Fragment] that displays a list of video files recorded by the application.
 * It handles requesting necessary storage permissions (READ_MEDIA_VIDEO or READ_EXTERNAL_STORAGE)
 * and uses a [GalleryViewModel] to load video data from the MediaStore.
 * Videos are displayed in a RecyclerView using a [VideoAdapter].
 * Users can tap on a video to open it with an appropriate video player application.
 */
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private val galleryViewModel: GalleryViewModel by viewModels()
    private lateinit var videoAdapter: VideoAdapter

    /**
     * Lazily determines the correct storage permission string based on the Android API level.
     * - For Android 13 (API 33, TIRAMISU) and above, uses [Manifest.permission.READ_MEDIA_VIDEO].
     * - For Android 12L (API 32) and below, uses [Manifest.permission.READ_EXTERNAL_STORAGE].
     */
    private val requiredPermission: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    //Handles the user's response to the permission dialog.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                loadVideosFromViewModel()
            } else {
                // Permission denied.
                binding.emptyView.text = getString(R.string.access_to_storage_denied)
                binding.emptyView.visibility = View.VISIBLE
                binding.videosRecyclerView.visibility = View.GONE
                Toast.makeText(requireContext(), getString(R.string.permission_denied_video_access), Toast.LENGTH_LONG).show()

                // If rationale should NOT be shown for this permission, it implies the user
                // likely selected "Don't ask again". In this case, guide them to app settings.
                if (!shouldShowRequestPermissionRationale(requiredPermission)) {
                    showSettingsRedirectDialog()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observeViewModel()
        checkAndRequestStoragePermission()
    }

    /**
     * Checks the current state of the required storage permission.
     * - If granted, proceeds to load videos.
     * - If rationale should be shown, displays an explanation dialog before requesting.
     * - Otherwise (first time or "Don't ask again" selected), directly requests the permission.
     */
    private fun checkAndRequestStoragePermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), requiredPermission) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                loadVideosFromViewModel()
            }
            shouldShowRequestPermissionRationale(requiredPermission) -> {
                showPermissionRationaleDialog {
                    requestPermissionLauncher.launch(requiredPermission)
                }
            }
            else -> {
                requestPermissionLauncher.launch(requiredPermission)
            }
        }
    }

    /**
     * Initiates the process of loading videos by calling the [GalleryViewModel.loadVideos] method.
     * Updates UI elements to reflect the loading state (hides empty view, shows recycler view).
     */
    private fun loadVideosFromViewModel() {
        binding.emptyView.visibility = View.GONE
        binding.videosRecyclerView.visibility = View.VISIBLE
        // Launch as a coroutine within the fragment's lifecycle scope.
        lifecycleScope.launch {
            galleryViewModel.loadVideos()
        }
    }

    /**
     * Sets up the RecyclerView with its [VideoAdapter].
     * The adapter handles clicks on video items, launching an Intent to view the video.
     */
    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter(requireContext()) { videoItem ->
            try {
                // Create an Intent to view the video using its content URI.
                val intent = Intent(Intent.ACTION_VIEW, videoItem.uri)
                intent.setDataAndType(videoItem.uri, videoItem.mimeType ?: "video/*")       // Fallback to "video/*" if MIME type is null.
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.no_app_can_handle_video), Toast.LENGTH_SHORT).show()
            }
        }
        binding.videosRecyclerView.adapter = videoAdapter
    }

    /**
     * Observes LiveData objects exposed by the [GalleryViewModel] to update the UI
     * in response to data changes (video list, loading state, errors).
     */
    private fun observeViewModel() {
        galleryViewModel.videos.observe(viewLifecycleOwner) { videos ->
            videoAdapter.submitList(videos)

            // Determine if the "no videos found" message should be shown.
            // This message is shown only if the list is empty, not currently loading, and permission is granted.
            val permissionGranted = ContextCompat.checkSelfPermission(requireContext(), requiredPermission) == PackageManager.PERMISSION_GRANTED
            if (videos.isEmpty() && galleryViewModel.isLoading.value == false && permissionGranted) {
                binding.emptyView.text = getString(R.string.no_videos_found)
                binding.emptyView.visibility = View.VISIBLE
                binding.videosRecyclerView.visibility = View.GONE

            // If videos are present, ensure the RecyclerView is visible and empty view is hidden.
            } else if (videos.isNotEmpty()) {
                binding.emptyView.visibility = View.GONE
                binding.videosRecyclerView.visibility = View.VISIBLE
            }
        }

        // Observe changes to the loading state.
        galleryViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.emptyView.visibility = View.GONE
            }
        }

        // Observe error messages from the ViewModel.
        galleryViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                binding.emptyView.text = it
                binding.emptyView.visibility = View.VISIBLE
                binding.videosRecyclerView.visibility = View.GONE
                galleryViewModel.clearError()
            }
        }
    }

    /**
     * Displays an AlertDialog explaining why the storage permission is needed before formally requesting it.
     * @param onConfirm A lambda function to be executed when the user confirms (e.g., clicks "OK").
     *                   This is typically used to re-trigger the permission request.
     */
    private fun showPermissionRationaleDialog(onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.permission_needed_title))
            .setMessage(getString(R.string.permission_rationale_video_access))
            .setPositiveButton(getString(R.string.ok)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Displays an AlertDialog when permission has been denied and "Don't ask again" was likely selected.
     * This dialog informs the user and provides a button to go directly to the app's settings screen
     * where they can manually grant the permission.
     */
    private fun showSettingsRedirectDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.permission_denied_title))
            .setMessage(getString(R.string.permission_denied_video_settings_redirect))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                // Create an Intent to open the application's details settings screen.
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                // Create a Uri pointing to this application's package.
                val uri = Uri.fromParts("package", requireActivity().packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.videosRecyclerView.adapter = null // Help clear adapter resources
        _binding = null
    }
}
