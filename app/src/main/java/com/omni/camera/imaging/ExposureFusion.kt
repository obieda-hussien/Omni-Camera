package com.omni.camera.imaging

import kotlin.math.*

data class ExposureFrame(val pixels: IntArray, val stops: Float)
data class Alignment(val dx: Int, val dy: Int, val error: Float)

/** JPEG exposure fusion, not RAW radiance reconstruction. Base frame anchors moving regions. */
object ExposureFusion {
    fun align(base: IntArray, candidate: IntArray, width: Int, height: Int): Alignment {
        require(base.size == width * height && candidate.size == base.size)
        val stride = max(1, width / 96)
        val maxShift = 4 * stride
        var best = Float.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -maxShift..maxShift step stride) for (dx in -maxShift..maxShift step stride) {
            var error = 0f; var n = 0
            for (y in maxShift + stride until height - maxShift - stride step stride * 2)
                for (x in maxShift + stride until width - maxShift - stride step stride * 2) {
                    val i = y * width + x; val j = (y + dy) * width + x + dx
                    // Relative gradients tolerate exposure changes better than raw brightness.
                    fun gradient(a: IntArray, index: Int): Float {
                        val left = ImagingMath.luma(a[index - stride]); val right = ImagingMath.luma(a[index + stride])
                        return (right - left) / (right + left + 0.1f)
                    }
                    error += abs(gradient(base, i) - gradient(candidate, j)); n++
                }
            val score = error / max(n, 1) + (abs(dx) + abs(dy)) * 0.00001f
            if (score < best) { best = score; bx = dx; by = dy }
        }
        return Alignment(bx, by, best)
    }

    fun mergeRow(base: IntArray, frames: List<ExposureFrame>, offsets: List<Alignment>, width: Int, height: Int, y: Int, output: IntArray) {
        require(frames.size == offsets.size && output.size >= width)
        for (x in 0 until width) {
            val anchor = base[y * width + x]
            val baseY = ImagingMath.luma(anchor)
            fun wellExposed(v: Float) = exp(-((v - 0.5f) / 0.28f).pow(2)) + 0.025f
            var weight = wellExposed(baseY)
            var r = ImagingMath.linearChannel((anchor ushr 16) and 255) * weight
            var g = ImagingMath.linearChannel((anchor ushr 8) and 255) * weight
            var b = ImagingMath.linearChannel(anchor and 255) * weight
            for (i in frames.indices) {
                val offset = offsets[i]
                if (offset.error > 0.13f) continue
                val xx = x + offset.dx; val yy = y + offset.dy
                if (xx !in 0 until width || yy !in 0 until height) continue
                val p = frames[i].pixels[yy * width + xx]
                val py = ImagingMath.luma(p)
                val normalized = (ImagingMath.linearChannel((p ushr 16) and 255) * 0.2126f +
                    ImagingMath.linearChannel((p ushr 8) and 255) * 0.7152f + ImagingMath.linearChannel(p and 255) * 0.0722f) /
                    2f.pow(frames[i].stops)
                val baseLinear = ImagingMath.linearChannel((anchor ushr 16) and 255) * 0.2126f +
                    ImagingMath.linearChannel((anchor ushr 8) and 255) * 0.7152f + ImagingMath.linearChannel(anchor and 255) * 0.0722f
                val motion = if (baseY > 0.94f || baseY < 0.05f) 1f else
                    (1 - abs(normalized - baseLinear) / (0.14f + baseLinear * 0.3f)).coerceIn(0f, 1f)
                val w = wellExposed(py) * motion * motion
                r += ImagingMath.linearChannel((p ushr 16) and 255) * w
                g += ImagingMath.linearChannel((p ushr 8) and 255) * w
                b += ImagingMath.linearChannel(p and 255) * w; weight += w
            }
            output[x] = -0x1000000 or (ImagingMath.encodeLinear(r / weight) shl 16) or
                (ImagingMath.encodeLinear(g / weight) shl 8) or ImagingMath.encodeLinear(b / weight)
        }
    }
}
