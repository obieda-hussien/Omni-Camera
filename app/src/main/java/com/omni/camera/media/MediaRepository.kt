package com.omni.camera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver
    fun name(suffix: String = "", extension: String = "jpg") = "OMNI_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}$suffix.$extension"
    private fun photoValues(name: String) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Omni Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    suspend fun savePhoto(bitmap: Bitmap, source: File, name: String): Uri = withContext(Dispatchers.IO) {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(name)))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, output)) { "Photo encoding failed" }
            }
            // Copy camera metadata but remove stale orientation, dimensions and embedded thumbnail.
            val original = ExifInterface(source)
            resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_FOCAL_LENGTH).forEach { tag -> exif.setAttribute(tag, original.getAttribute(tag)) }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmap.width.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmap.height.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Omni Camera")
                exif.saveAttributes()
            }
            publish(uri); uri
        } catch (e: Throwable) { resolver.delete(uri, null, null); throw e }
    }
    suspend fun saveOriginal(file: File, name: String): Uri = withContext(Dispatchers.IO) {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues(name)))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            publish(uri); uri
        } catch (e: Throwable) { resolver.delete(uri, null, null); throw e }
    }
    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
    fun videoValues() = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name(extension = "mp4"))
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Omni Camera")
    }
    suspend fun discard(uri: Uri) = withContext(Dispatchers.IO) { resolver.delete(uri, null, null) }
}
