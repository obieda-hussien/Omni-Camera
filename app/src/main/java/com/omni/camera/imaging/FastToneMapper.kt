package com.omni.camera.imaging

import kotlin.math.*

/** Bounded Kotlin recovery path if native code cannot load. No per-pixel pow/exp. */
class FastToneMapper(private val stats: SceneStats, private val options: ProcessingOptions) {
    private val curves = Array(32) { bucket ->
        val region = bucket / 31f
        IntArray(256) { v ->
            val pixel = -0x1000000 or (v shl 16) or (v shl 8) or v
            ImagingMath.tone(pixel, region, region, stats, options) and 255
        }
    }
    fun map(pixel: Int, region: Float): Int {
        if (!options.enabled || options.strength <= 0) return pixel
        val r = (pixel ushr 16) and 255; val g = (pixel ushr 8) and 255; val b = pixel and 255
        val y = ((54 * r + 183 * g + 19 * b + 128) shr 8).coerceIn(0, 255)
        val target = curves[(region.coerceIn(0f, 1f) * 31).roundToInt()][y]
        val gain = if (y == 0) 1f else (target.toFloat() / y).coerceIn(.5f, 3f)
        return (pixel and -0x1000000) or ((r * gain).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * gain).roundToInt().coerceIn(0, 255) shl 8) or (b * gain).roundToInt().coerceIn(0, 255)
    }
}
