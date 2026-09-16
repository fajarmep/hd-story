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

    private var selectedUri: Uri? = null
    private var isVideo: Boolean = false
    private var processedFile: File? = null

    // Photo & Video Picker contract
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onMediaSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnSelectMedia.setOnClickListener {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }

        binding.btnOptimize.setOnClickListener {
            selectedUri?.let { uri -> startOptimization(uri) }
        }

        binding.btnShare.setOnClickListener {
            processedFile?.let { file ->
                val shareIntent = FileUtils.createShareIntent(this, file, isVideo)
                startActivity(Intent.createChooser(shareIntent, "Bagikan Media HD"))
            }
        }

        binding.btnSave.setOnClickListener {
            processedFile?.let { file ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val savedUri = FileUtils.saveToGallery(this@MainActivity, file, isVideo)
                    withContext(Dispatchers.Main) {
                        if (savedUri != null) {
                            Toast.makeText(this@MainActivity, "Berhasil disimpan ke Galeri!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Gagal menyimpan ke galeri", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun onMediaSelected(uri: Uri) {
        selectedUri = uri
        val mime = contentResolver.getType(uri) ?: ""
        isVideo = mime.startsWith("video")

        binding.tvMediaInfo.text = if (isVideo) {
            val duration = FileUtils.getVideoDurationSeconds(this, uri)
            "Video dipilih (%.1f dtk) - Format: 9:16 target".format(duration)
        } else {
            "Foto dipilih - Siap dioptimalkan 1080x1920 HD"
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

    private fun startOptimization(uri: Uri) {
        val platform = getSelectedPlatform()
        val applySharpen = binding.switchSharpen.isChecked

        binding.btnOptimize.isEnabled = false
        binding.btnSelectMedia.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.status_processing)
        binding.layoutExportActions.visibility = View.GONE

        lifecycleScope.launch {
            try {
                if (isVideo) {
                    val duration = FileUtils.getVideoDurationSeconds(this@MainActivity, uri)
                    val config = PlatformPresets.getVideoConfig(platform, duration)
                    val out = File(cacheDir, "HDStory_opt_${System.currentTimeMillis()}.mp4")

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
                    val config = PlatformPresets.getImageConfig(platform, applySharpen)
                    val out = File(cacheDir, "HDStory_opt_${System.currentTimeMillis()}.jpg")

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
            binding.tvStatus.text = "Selesai! Ukuran output: %.2f MB (1080x1920 HD)".format(sizeMb)
            binding.layoutExportActions.visibility = View.VISIBLE
        }.onFailure { error ->
            binding.tvStatus.text = getString(R.string.status_error, error.localizedMessage ?: "Processing error")
        }
    }
}
