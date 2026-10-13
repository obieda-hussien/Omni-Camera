package com.omni.camera.imaging

import kotlin.math.*

data class Mask(val width: Int, val height: Int, val confidence: FloatArray) {
    init { require(width > 0 && height > 0 && confidence.size == width * height) }
    fun sample(x: Float, y: Float): Float {
        val px = (x * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val py = (y * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = px.toInt(); val y0 = py.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val fx = px - x0; val fy = py - y0
        val a = confidence[y0 * width + x0] * (1 - fx) + confidence[y0 * width + x1] * fx
        val b = confidence[y1 * width + x0] * (1 - fx) + confidence[y1 * width + x1] * fx
        return (a * (1 - fy) + b * fy).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    }
}

data class FaceRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun weight(x: Float, y: Float): Float {
        val rx = max((right - left) * 0.65f, 0.01f)
        val ry = max((bottom - top) * 0.7f, 0.01f)
        val d = ((x - (left + right) / 2) / rx).pow(2) + ((y - (top + bottom) / 2) / ry).pow(2)
        return (1 - d).coerceIn(0f, 1f).let { it * it }
    }
}

enum class ProcessingQuality(val megapixels: Int) { FAST(2), BALANCED(6), HIGH(12) }

data class ProcessingOptions(
    val enabled: Boolean = true,
    val strength: Float = 0.7f,
    val faceLight: Float = 0.65f,
    val backgroundLight: Float = 0f,
    val denoise: Float = 0.35f,
    val detail: Float = 0.25f,
    val warmth: Float = 0f,
    val quality: ProcessingQuality = ProcessingQuality.BALANCED,
    val keepOriginal: Boolean = false,
    val hdr: Boolean = false,
)

data class SceneStats(
    val average: Float = 0.5f,
    val subject: Float = 0.5f,
    val background: Float = 0.5f,
    val darkFraction: Float = 0f,
    val clippedFraction: Float = 0f,
    val histogram: List<Float> = List(64) { 0f },
    val faces: Int = 0,
    val motion: Float = 0f,
)

object ImagingMath {
    private val linear = FloatArray(256) { n ->
        val x = n / 255f
        if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }
    fun luma(pixel: Int): Float = 0.2126f * ((pixel ushr 16) and 255) / 255f +
        0.7152f * ((pixel ushr 8) and 255) / 255f + 0.0722f * (pixel and 255) / 255f
    fun linearChannel(channel: Int) = linear[channel.coerceIn(0, 255)]
    fun encodeLinear(value: Float): Int {
        val x = value.coerceIn(0f, 1f)
        val s = if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
        return (s * 255f).roundToInt().coerceIn(0, 255)
    }
    fun stats(pixels: IntArray, width: Int, height: Int, mask: Mask?, faces: List<FaceRegion>): SceneStats {
        require(pixels.size == width * height)
        val bins = IntArray(64)
        var total = 0f; var subject = 0f; var subjectWeight = 0f
        var bg = 0f; var bgWeight = 0f; var dark = 0; var clip = 0; var count = 0
        val step = max(1, sqrt(pixels.size / 24000.0).toInt())
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val p = pixels[y * width + x]; val lum = luma(p)
            val nx = (x + 0.5f) / width; val ny = (y + 0.5f) / height
            val face = faces.maxOfOrNull { it.weight(nx, ny) } ?: 0f
            // Meter the face when found; otherwise meter the segmented person.
            val s = if (faces.isNotEmpty()) face else mask?.sample(nx, ny) ?: 0f
            val b = 1 - (mask?.sample(nx, ny) ?: face)
            subject += lum * s; subjectWeight += s; bg += lum * b; bgWeight += b
            total += lum; bins[(lum * 63).toInt().coerceIn(0, 63)]++; count++
            if (lum < 0.12f) dark++
            if (maxOf((p ushr 16) and 255, (p ushr 8) and 255, p and 255) >= 250) clip++
        }
        val average = total / max(count, 1)
        val peak = max(bins.maxOrNull() ?: 1, 1)
        return SceneStats(average, if (subjectWeight > 1) subject / subjectWeight else average,
            if (bgWeight > 1) bg / bgWeight else average, dark.toFloat() / max(count, 1),
            clip.toFloat() / max(count, 1), bins.map { it.toFloat() / peak }, faces.size)
    }
    fun tone(pixel: Int, person: Float, face: Float, stats: SceneStats, options: ProcessingOptions): Int {
        if (!options.enabled || options.strength <= 0f) return pixel
        val r = linearChannel((pixel ushr 16) and 255)
        val g = linearChannel((pixel ushr 8) and 255)
        val b = linearChannel(pixel and 255)
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val amount = options.strength.coerceIn(0f, 1f)
        val globalLift = ((0.38f - stats.average) * 0.5f).coerceIn(0f, 0.12f)
        val backlit = ((stats.background - stats.subject - 0.08f) * 1.8f).coerceIn(0f, 0.65f)
        val subjectLift = ((0.48f - stats.subject).coerceIn(0f, 0.4f) + backlit * 0.5f) * options.faceLight
        val region = max(person * 0.7f, face)
        val lift = (globalLift + subjectLift * region + options.backgroundLight * (1 - person) * 0.12f) * amount
        val gamma = (1f - lift * 1.4f).coerceIn(0.55f, 1.2f)
        var target = y.pow(gamma)
        // Smooth highlight shoulder; cannot recover information already clipped by the sensor.
        val shoulder = (1 - region * 0.6f) * amount * 0.22f
        target -= shoulder * target * target * (1 - target)
        val gain = if (y > 0.0001f) (target / y).coerceIn(0.5f, 4f) else 1f
        val warm = options.warmth.coerceIn(-1f, 1f) * 0.08f * amount
        // Gamut compression avoids independently clipping colour channels after shadow lifting.
        var rr = r * gain * (1 + warm); var gg = g * gain; var bb = b * gain * (1 - warm)
        val peak = maxOf(rr, gg, bb)
        if (peak > 1) { rr /= peak; gg /= peak; bb /= peak }
        return (pixel and -0x1000000) or (encodeLinear(rr) shl 16) or (encodeLinear(gg) shl 8) or encodeLinear(bb)
    }
    fun maxPixels(heapBytes: Long, quality: ProcessingQuality): Int =
        min(quality.megapixels * 1_000_000L, heapBytes / 40).coerceIn(250_000L, 12_000_000L).toInt()
}
