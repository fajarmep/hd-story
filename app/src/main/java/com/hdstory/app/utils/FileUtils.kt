package com.hdstory.app.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileUtils {

    fun copyUriToCache(context: Context, uri: Uri, tempName: String): File {
        val cacheFile = File(context.cacheDir, tempName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    fun getVideoDurationSeconds(context: Context, uri: Uri): Float {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15_000L
            (timeMs / 1000f).coerceAtLeast(1.0f)
        } catch (e: Exception) {
            15.0f
        } finally {
            retriever.release()
        }
    }

    fun saveToGallery(context: Context, file: File, isVideo: Boolean): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "HDStory_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) Environment.DIRECTORY_MOVIES + "/HDStory" else Environment.DIRECTORY_PICTURES + "/HDStory")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        resolver.openOutputStream(uri)?.use { outStream ->
            file.inputStream().use { inStream ->
                inStream.copyTo(outStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        return uri
    }

    fun createShareIntent(context: Context, file: File, isVideo: Boolean): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        return Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
