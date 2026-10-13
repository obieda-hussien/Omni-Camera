package com.omni.camera.imaging

import org.junit.Assert.*
import org.junit.Test

class ExposureFusionTest {
    @Test fun noFramesKeepsTheBaseExactly() {
        val base = intArrayOf(0xff133255.toInt(), -1, 0xff000000.toInt())
        val out = IntArray(3)
        ExposureFusion.mergeRow(base, emptyList(), emptyList(), 3, 1, 0, out)
        assertArrayEquals(base, out)
    }
    @Test fun badAlignmentFallsBackToBase() {
        val base = IntArray(8) { 0xff434343.toInt() }
        val out = IntArray(8)
        ExposureFusion.mergeRow(base, listOf(ExposureFrame(IntArray(8) { -1 }, 1f)), listOf(Alignment(0, 0, 0.8f)), 8, 1, 0, out)
        assertArrayEquals(base, out)
    }
    @Test fun fusionUsesUnderexposedFrameToReduceClippedHighlights() {
        val out = IntArray(4)
        ExposureFusion.mergeRow(IntArray(4) { -1 }, listOf(ExposureFrame(IntArray(4) { 0xff999999.toInt() }, -1.5f)),
            listOf(Alignment(0, 0, 0f)), 4, 1, 0, out)
        assertTrue(out.all { ImagingMath.luma(it) < 0.9f })
    }
    @Test fun translatedFrameAlignsToBase() {
        val w = 96; val h = 96
        val base = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val v = if ((x / 7 + y / 11) % 2 == 0) 50 else 180
            -0x1000000 or (v shl 16) or (v shl 8) or v
        }
        val shifted = IntArray(base.size) { i -> base[(i / w) * w + (i % w - 3).coerceIn(0, w - 1)] }
        val result = ExposureFusion.align(base, shifted, w, h)
        assertEquals(3, result.dx)
        assertTrue(result.error < 0.02f)
    }
    @Test fun shiftedEdgesNeverReadOutsideFrame() {
        val out = IntArray(5); val base = IntArray(25) { 0xff555555.toInt() }
        for (y in 0..4) ExposureFusion.mergeRow(base, listOf(ExposureFrame(base, 0f)), listOf(Alignment(4, -4, 0f)), 5, 5, y, out)
        assertTrue(out.all { it ushr 24 == 255 })
    }
}
