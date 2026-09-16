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
import com.hdstory.app.model.PhotoTargetFormat
import com.hdstory.app.model.PlatformPresets
import com.hdstory.app.model.PlatformType
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

    // Photo only picker
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onMediaSelected(it, false) }
    }

    // Video only picker
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onMediaSelected(it, true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
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
        binding.tvStatus.text = getString(R.string.status_ready)
        binding.tvMediaInfo.text = getString(R.string.no_media_selected)

        if (tab == MediaTypeTab.PHOTO) {
            binding.cardPhotoRatio.visibility = View.VISIBLE
            binding.switchFpsLock.visibility = View.GONE
            binding.btnSelectMedia.text = getString(R.string.select_photo)
            binding.btnOptimize.text = getString(R.string.btn_optimize_photo)
        } else {
            binding.cardPhotoRatio.visibility = View.GONE
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
            if (currentTab == MediaTypeTab.PHOTO) {
                pickPhotoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                pickVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
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

    private fun onMediaSelected(uri: Uri, isVideo: Boolean) {
        selectedUri = uri
        isVideoFile = isVideo

        if (isVideo) {
            val duration = FileUtils.getVideoDurationSeconds(this, uri)
            binding.tvMediaInfo.text = "Video dipilih: %.1f dtk (Target 9:16 1080p)".format(duration)
        } else {
            binding.tvMediaInfo.text = "Foto dipilih: Siap dioptimasi HD & Anti-Pecah"
            binding.ivPreview.setImageURI(uri)
            binding.ivPreview.visibility = View.VISIBLE
        }

        binding.btnSelectMedia.text = getString(R.string.change_media)
        binding.btnOptimize.isEnabled = true
        binding.layoutExportActions.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.status_ready)
    }

    private fun getSelectedPlatform(): PlatformType {
        return when (binding.rgPlatform.checkedRadioButtonId) {
            R.id.rbWhatsApp -> PlatformType.WHATSAPP
            R.id.rbTikTok -> PlatformType.TIKTOK
            else -> PlatformType.INSTAGRAM
        }
    }

    private fun getSelectedPhotoFormat(): PhotoTargetFormat {
        return when (binding.rgPhotoAspect.checkedRadioButtonId) {
            R.id.rbRatioPortrait -> PhotoTargetFormat.FEED_PORTRAIT
            R.id.rbRatioSquare -> PhotoTargetFormat.FEED_SQUARE
            R.id.rbRatioOriginal -> PhotoTargetFormat.ORIGINAL_RES_HD
            else -> PhotoTargetFormat.STORY_VERTICAL
        }
    }

    private fun startOptimization(uri: Uri) {
        val platform = getSelectedPlatform()
        val applySharpen = binding.switchSharpen.isChecked

        binding.btnOptimize.isEnabled = false
        binding.btnSelectMedia.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = getString(R.string.status_processing)
        binding.layoutExportActions.visibility = View.GONE

        lifecycleScope.launch {
            try {
                if (currentTab == MediaTypeTab.VIDEO) {
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
                    val photoFormat = getSelectedPhotoFormat()
                    val config = PlatformPresets.getImageConfig(platform, photoFormat, applySharpen)
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
        binding.btnOptimize.isEnabled = true
        binding.btnSelectMedia.isEnabled = true

        result.onSuccess { file ->
            processedFile = file
            val sizeMb = file.length() / (1024f * 1024f)
            val info = if (currentTab == MediaTypeTab.VIDEO) "Video HD" else "Foto HD"
            binding.tvStatus.text = "Selesai! $info (Ukuran: %.2f MB)".format(sizeMb)
            binding.layoutExportActions.visibility = View.VISIBLE
        }.onFailure { error ->
            binding.tvStatus.text = getString(R.string.status_error, error.localizedMessage ?: "Processing error")
        }
    }
}
