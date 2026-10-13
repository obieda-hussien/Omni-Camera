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

data class ProcessedPhoto(val bitmap: Bitmap, val stats: SceneStats, val modelsAvailable: Boolean)

class PhotoProcessor(private val models: PortraitModels) {
    suspend fun process(bundle: CaptureBundle, options: ProcessingOptions, mirror: Boolean): ProcessedPhoto = withContext(Dispatchers.Default) {
        val bitmap = decode(bundle.base, options.quality, mirror)
        try {
            if (bundle.brackets.isNotEmpty()) fuse(bitmap, bundle, options.quality, mirror)
            val analysis = if (options.enabled) models.analyze(bitmap) else PortraitAnalysis(null, emptyList(), true)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val stats = ImagingMath.stats(pixels, bitmap.width, bitmap.height, analysis.mask, analysis.faces)
            if (options.enabled) {
                // Rolling rows keep denoising/sharpening memory O(width), not another full image.
                filterDetail(pixels, bitmap.width, bitmap.height, options)
                for (y in 0 until bitmap.height) {
                    if (y % 32 == 0) coroutineContext.ensureActive()
                    val ny = (y + 0.5f) / bitmap.height
                    for (x in 0 until bitmap.width) {
                        val nx = (x + 0.5f) / bitmap.width
                        val person = analysis.mask?.sample(nx, ny) ?: 0f
                        val face = analysis.faces.maxOfOrNull { it.weight(nx, ny) } ?: 0f
                        val i = y * bitmap.width + x
                        pixels[i] = ImagingMath.tone(pixels[i], person, face, stats, options)
                    }
                }
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            ProcessedPhoto(bitmap, stats, analysis.modelsAvailable)
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }

    private suspend fun fuse(base: Bitmap, bundle: CaptureBundle, quality: ProcessingQuality, mirror: Boolean) {
        val pixels = IntArray(base.width * base.height)
        base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        val frames = bundle.brackets.mapNotNull { (file, stops) ->
            val bitmap = decode(file, quality, mirror, base.width * base.height)
            try {
                if (bitmap.width != base.width || bitmap.height != base.height) null else {
                    val frame = IntArray(pixels.size)
                    bitmap.getPixels(frame, 0, base.width, 0, 0, base.width, base.height)
                    ExposureFrame(frame, stops)
                }
            } finally { bitmap.recycle() }
        }
        val offsets = frames.map { ExposureFusion.align(pixels, it.pixels, base.width, base.height) }
        val row = IntArray(base.width)
        for (y in 0 until base.height) {
            if (y % 16 == 0) coroutineContext.ensureActive()
            ExposureFusion.mergeRow(pixels, frames, offsets, base.width, base.height, y, row)
            base.setPixels(row, 0, base.width, 0, y, base.width, 1)
        }
    }

    fun decode(file: File, quality: ProcessingQuality, mirror: Boolean, pixelBudget: Int? = null): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid photo" }
        val runtime = Runtime.getRuntime()
        val available = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) - 24L * 1024 * 1024).coerceAtLeast(16L * 1024 * 1024)
        // The first HDR frame reserves the whole fusion budget; subsequent frames must match it.
        val budget = pixelBudget ?: ImagingMath.maxPixels(available, quality)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > budget) sample *= 2
        val raw = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
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
        if (oriented.isMutable) return oriented
        return try { oriented.copy(Bitmap.Config.ARGB_8888, true) } finally { oriented.recycle() }
    }

    private suspend fun filterDetail(pixels: IntArray, width: Int, height: Int, options: ProcessingOptions) {
        val previous = IntArray(width); val current = IntArray(width); val next = IntArray(width)
        val rows = arrayOf(previous, current, next)
        pixels.copyInto(previous, 0, 0, width); pixels.copyInto(current, 0, 0, width)
        for (y in 0 until height) {
            if (y % 32 == 0) coroutineContext.ensureActive()
            val start = min(y + 1, height - 1) * width
            pixels.copyInto(next, 0, start, start + width)
            for (x in 0 until width) {
                val center = current[x]; val centerLuma = ImagingMath.luma(center)
                var r = 0f; var g = 0f; var b = 0f; var total = 0f
                for (row in rows) for (xx in max(0, x - 1)..min(width - 1, x + 1)) {
                    val p = row[xx]; val diff = abs(ImagingMath.luma(p) - centerLuma)
                    val weight = exp(-diff * diff / 0.0025f)
                    r += ((p ushr 16) and 255) * weight; g += ((p ushr 8) and 255) * weight
                    b += (p and 255) * weight; total += weight
                }
                val noiseMix = options.denoise * options.strength * (1 - centerLuma) * 0.75f
                val sharpMix = options.detail * options.strength * centerLuma * 0.5f
                fun channel(c: Int, smooth: Float): Int = (c + (smooth / total - c) * (noiseMix - sharpMix))
                    .roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = (center and -0x1000000) or
                    (channel((center ushr 16) and 255, r) shl 16) or
                    (channel((center ushr 8) and 255, g) shl 8) or channel(center and 255, b)
            }
            current.copyInto(previous); next.copyInto(current)
        }
    }
}
