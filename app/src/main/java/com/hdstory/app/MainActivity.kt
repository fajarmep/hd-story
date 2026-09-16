package com.hdstory.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.hdstory.app.databinding.ActivityMainBinding
import com.hdstory.app.engine.ImageOptimizer
import com.hdstory.app.engine.VideoOptimizer
import com.hdstory.app.model.MediaTypeTab
import com.hdstory.app.model.PhotoPlatform
import com.hdstory.app.model.PlatformPresets
import com.hdstory.app.model.VideoPlatform
import com.hdstory.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentTab: MediaTypeTab = MediaTypeTab.PHOTO
    private var selectedUri: Uri? = null
    private var isVideoFile: Boolean = false
    private var processedFile: File? = null

    // Photo picker
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onMediaSelected(it, false) }
    }

    // Video picker
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onMediaSelected(it, true) }
    }

    // Fallback for devices without Photo Picker (SDK < 30)
    private val pickFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onMediaSelected(it, currentTab == MediaTypeTab.VIDEO) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    /**
     * Handle ACTION_SEND from gallery / other apps — auto-detect photo vs video
     */
    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val mimeType = contentResolver.getType(uri) ?: return

        if (mimeType.startsWith("video/")) {
            binding.toggleMediaType.check(R.id.btnTabVideo)
            updateTabUI(MediaTypeTab.VIDEO)
            onMediaSelected(uri, true)
        } else if (mimeType.startsWith("image/")) {
            binding.toggleMediaType.check(R.id.btnTabPhoto)
            updateTabUI(MediaTypeTab.PHOTO)
            onMediaSelected(uri, false)
        }
    }

    private fun setupUI() {
        updateTabUI(MediaTypeTab.PHOTO)
    }

    private fun updateTabUI(tab: MediaTypeTab) {
        currentTab = tab
        selectedUri = null
        processedFile = null

        binding.ivPreview.visibility = View.GONE
        binding.layoutExportActions.visibility = View.GONE
        binding.btnOptimize.isEnabled = false
        binding.progressBar.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.tvStatus.text = getString(R.string.status_ready)
        binding.tvMediaInfo.text = getString(R.string.no_media_selected)

        if (tab == MediaTypeTab.PHOTO) {
            binding.cardPhotoTargets.visibility = View.VISIBLE
            binding.cardVideoTargets.visibility = View.GONE
            binding.switchFpsLock.visibility = View.GONE
            binding.btnSelectMedia.text = getString(R.string.select_photo)
            binding.btnOptimize.text = getString(R.string.btn_optimize_photo)
        } else {
            binding.cardPhotoTargets.visibility = View.GONE
            binding.cardVideoTargets.visibility = View.VISIBLE
            binding.switchFpsLock.visibility = View.VISIBLE
            binding.btnSelectMedia.text = getString(R.string.select_video)
            binding.btnOptimize.text = getString(R.string.btn_optimize_video)
        }
    }

    private fun setupListeners() {
        binding.toggleMediaType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnTabPhoto) {
                    updateTabUI(MediaTypeTab.PHOTO)
                } else if (checkedId == R.id.btnTabVideo) {
                    updateTabUI(MediaTypeTab.VIDEO)
                }
            }
        }

        binding.btnSelectMedia.setOnClickListener {
            launchMediaPicker()
        }

        binding.btnOptimize.setOnClickListener {
            selectedUri?.let { uri -> startOptimization(uri) }
        }

        binding.btnShare.setOnClickListener {
            processedFile?.let { file ->
                val shareIntent = FileUtils.createShareIntent(this, file, isVideoFile)
                startActivity(Intent.createChooser(shareIntent, "Bagikan Media HD"))
            }
        }

        binding.btnSave.setOnClickListener {
            processedFile?.let { file ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val savedUri = FileUtils.saveToGallery(this@MainActivity, file, isVideoFile)
                    withContext(Dispatchers.Main) {
                        if (savedUri != null) {
                            Toast.makeText(this@MainActivity, "Tersimpan di Galeri!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Gagal menyimpan ke galeri", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun launchMediaPicker() {
        val isPhotoPicker = ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)

        if (isPhotoPicker) {
            if (currentTab == MediaTypeTab.PHOTO) {
                pickPhotoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                pickVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
        } else {
            // Fallback for older devices without Photo Picker API
            val mimeType = if (currentTab == MediaTypeTab.PHOTO) "image/*" else "video/*"
            pickFallbackLauncher.launch(mimeType)
        }
    }

    private fun onMediaSelected(uri: Uri, isVideo: Boolean) {
        selectedUri = uri
        isVideoFile = isVideo

        if (isVideo) {
            val duration = FileUtils.getVideoDurationSeconds(this, uri)
            binding.tvMediaInfo.text = "Video dipilih (%.1f dtk) - Siap optimasi HD".format(duration)
            binding.ivPreview.visibility = View.GONE
        } else {
            binding.tvMediaInfo.text = "Foto dipilih - Siap dioptimasi HD & Anti-Pecah"
            binding.ivPreview.setImageURI(uri)
            binding.ivPreview.visibility = View.VISIBLE
        }

        binding.btnSelectMedia.text = getString(R.string.change_media)
        binding.btnOptimize.isEnabled = true
        binding.layoutExportActions.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.tvStatus.text = getString(R.string.status_ready)
    }

    private fun getSelectedPhotoPlatform(): PhotoPlatform {
        return when (binding.rgPhotoTarget.checkedRadioButtonId) {
            R.id.rbPhotoPortrait -> PhotoPlatform.IG_FEED_PORTRAIT
            R.id.rbPhotoSquare -> PhotoPlatform.IG_FEED_SQUARE
            R.id.rbPhotoOriginal -> PhotoPlatform.ORIGINAL_MAX_2048
            else -> PhotoPlatform.IG_WA_STORY
        }
    }

    private fun getSelectedVideoPlatform(): VideoPlatform {
        return when (binding.rgVideoTarget.checkedRadioButtonId) {
            R.id.rbVideoWhatsApp -> VideoPlatform.WHATSAPP_STATUS
            R.id.rbVideoTikTok -> VideoPlatform.TIKTOK_HD
            else -> VideoPlatform.INSTAGRAM_REELS_STORY
        }
    }

    private fun startOptimization(uri: Uri) {
        val applySharpen = binding.switchSharpen.isChecked

        // Clean old cache files (>1 hour old)
        cleanOldCache()

        binding.btnOptimize.isEnabled = false
        binding.btnSelectMedia.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.progressBar.isIndeterminate = (currentTab == MediaTypeTab.PHOTO)
        binding.tvStatus.text = getString(R.string.status_processing)
        binding.layoutExportActions.visibility = View.GONE

        lifecycleScope.launch {
            try {
                if (currentTab == MediaTypeTab.VIDEO) {
                    val platform = getSelectedVideoPlatform()
                    val duration = FileUtils.getVideoDurationSeconds(this@MainActivity, uri)
                    val config = PlatformPresets.getVideoConfig(platform, duration)
                    val out = File(cacheDir, "HDStory_video_${System.currentTimeMillis()}.mp4")

                    val result = VideoOptimizer.optimizeVideo(
                        context = this@MainActivity,
                        inputUri = uri,
                        outputFile = out,
                        config = config,
                        onProgress = { percent ->
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.progress = percent
                        }
                    )
                    handleResult(result)
                } else {
                    val platform = getSelectedPhotoPlatform()
                    val config = PlatformPresets.getImageConfig(platform, applySharpen)
                    val out = File(cacheDir, "HDStory_photo_${System.currentTimeMillis()}.jpg")

                    val result = ImageOptimizer.optimizeImage(
                        context = this@MainActivity,
                        inputUri = uri,
                        outputFile = out,
                        config = config
                    )
                    handleResult(result)
                }
            } catch (e: Exception) {
                binding.tvStatus.text = getString(R.string.status_error, e.localizedMessage ?: "Unknown error")
                binding.btnOptimize.isEnabled = true
                binding.btnSelectMedia.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun handleResult(result: Result<File>) {
        binding.progressBar.visibility = View.GONE
        binding.progressBar.progress = 0
        binding.btnOptimize.isEnabled = true
        binding.btnSelectMedia.isEnabled = true

        result.onSuccess { file ->
            processedFile = file
            val sizeMb = file.length() / (1024f * 1024f)
            val info = if (currentTab == MediaTypeTab.VIDEO) "Video HD" else "Foto HD"
            binding.tvStatus.text = getString(R.string.status_done_size, info, sizeMb)
            binding.layoutExportActions.visibility = View.VISIBLE

            // Show result preview for photos
            if (currentTab == MediaTypeTab.PHOTO) {
                binding.ivPreview.setImageURI(Uri.fromFile(file))
                binding.ivPreview.visibility = View.VISIBLE
            }
        }.onFailure { error ->
            binding.tvStatus.text = getString(R.string.status_error, error.localizedMessage ?: "Processing error")
        }
    }

    /**
     * Clean old HDStory cache files (>1 hour) to prevent storage bloat
     */
    private fun cleanOldCache() {
        try {
            val threshold = System.currentTimeMillis() - 3_600_000
            cacheDir.listFiles()?.filter {
                it.name.startsWith("HDStory_") && it.lastModified() < threshold
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
