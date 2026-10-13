package com.omni.camera.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.omni.camera.camera.CaptureBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.*

enum class DevelopStage { DECODING, ALIGNING, PORTRAIT, DEVELOPING, SAVING }
data class ProcessedPhoto(val bitmap: Bitmap, val stats: SceneStats, val modelsAvailable: Boolean, val native: Boolean, val acceptedFrames: Int)

class PhotoProcessor(private val models: PortraitModels) {
    suspend fun process(bundle: CaptureBundle, options: ProcessingOptions, mirror: Boolean,
        stage: (DevelopStage) -> Unit = {}): ProcessedPhoto = withContext(Dispatchers.Default) {
        stage(DevelopStage.DECODING)
        val bitmap = decode(bundle.base, options.quality, mirror, reserveFusion = bundle.brackets.isNotEmpty())
        try {
            var accepted = 1
            if (bundle.brackets.isNotEmpty()) {
                stage(DevelopStage.ALIGNING)
                accepted += fuse(bitmap, bundle, options.quality, mirror)
            }
            coroutineContext.ensureActive()
            stage(DevelopStage.PORTRAIT)
            val analysis = if (options.enabled) models.analyze(bitmap,
                if (options.quality == ProcessingQuality.FAST) 384 else 512) else PortraitAnalysis(null, emptyList(), true)
            val small = thumbnail(bitmap, 384)
            val stats = try {
                val pixels = IntArray(small.width * small.height)
                small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                ImagingMath.stats(pixels, small.width, small.height, analysis.mask, analysis.faces)
            } finally { small.recycle() }
            stage(DevelopStage.DEVELOPING)
            val native = if (options.enabled) NativeImaging.develop(bitmap, analysis, stats, options) else NativeImaging.available
            if (options.enabled && !native) fallback(bitmap, analysis, stats, options)
            coroutineContext.ensureActive()
            ProcessedPhoto(bitmap, stats, analysis.modelsAvailable, native, accepted)
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }

    private suspend fun fuse(base: Bitmap, bundle: CaptureBundle, quality: ProcessingQuality, mirror: Boolean): Int {
        var accepted = 0
        for ((file, stops) in bundle.brackets) {
            coroutineContext.ensureActive()
            val frame = decode(file, quality, mirror, base.width * base.height, true, base.width to base.height)
            try {
                if (frame.width == base.width && frame.height == base.height && NativeImaging.fuse(base, frame, stops)) accepted++
            } finally { frame.recycle() }
        }
        return accepted
    }

    private suspend fun fallback(bitmap: Bitmap, analysis: PortraitAnalysis, stats: SceneStats, options: ProcessingOptions) {
        val row = IntArray(bitmap.width)
        val mapper = FastToneMapper(stats, options)
        for (y in 0 until bitmap.height) {
            if (y % 32 == 0) coroutineContext.ensureActive()
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                val nx = (x + .5f) / bitmap.width; val ny = (y + .5f) / bitmap.height
                val region = max(analysis.mask?.sample(nx, ny) ?: 0f, analysis.faces.maxOfOrNull { it.weight(nx, ny) } ?: 0f)
                row[x] = mapper.map(row[x], region)
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
    }

    fun thumbnail(bitmap: Bitmap, longest: Int = 720): Bitmap {
        val scale = minOf(1f, longest.toFloat() / maxOf(bitmap.width, bitmap.height))
        val copy = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        return if (copy === bitmap) copy.copy(Bitmap.Config.ARGB_8888, false) else copy
    }

    fun decode(file: File, quality: ProcessingQuality, mirror: Boolean, pixelBudget: Int? = null, reserveFusion: Boolean = false, targetSize: Pair<Int, Int>? = null): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid photo" }
        val runtime = Runtime.getRuntime()
        val available = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) - 24L * 1024 * 1024).coerceAtLeast(16L * 1024 * 1024)
        // The first HDR frame reserves the whole fusion budget; subsequent frames must match it.
        val bytesPerPixel = if (reserveFusion || !NativeImaging.available) 32 else 16
        val budget = pixelBudget ?: min(quality.megapixels * 1_000_000L, available / bytesPerPixel).coerceAtLeast(250_000).toInt()
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > budget * 1.8) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
        // Decode enough detail first, then downscale to the exact budget instead of dropping to one-quarter resolution.
        val decodedPixels = decoded.width.toLong() * decoded.height
        val raw = if (decodedPixels > budget) {
            val scale = sqrt(budget.toDouble() / decodedPixels)
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val exif = ExifInterface(file)
        val matrix = Matrix()
        // All eight EXIF orientations, including mirrored images, are normalized once.
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        if (mirror) matrix.postScale(-1f, 1f)
        val oriented = try { if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true) }
            catch (e: Throwable) { raw.recycle(); throw e }
        if (oriented !== raw) raw.recycle()
        val target = if (targetSize != null && abs(oriented.width.toFloat() / oriented.height - targetSize.first.toFloat() / targetSize.second) < .01f &&
            (oriented.width != targetSize.first || oriented.height != targetSize.second)) {
            Bitmap.createScaledBitmap(oriented, targetSize.first, targetSize.second, true).also { if (it !== oriented) oriented.recycle() }
        } else oriented
        if (target.isMutable) return target
        return try { target.copy(Bitmap.Config.ARGB_8888, true) } finally { target.recycle() }
    }

}
