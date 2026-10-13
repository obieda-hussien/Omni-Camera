package com.omni.camera.imaging

import org.junit.Assert.*
import org.junit.Test

class ImagingMathTest {
    @Test fun disabledProcessingIsBitExact() {
        for (p in listOf(0xff123456.toInt(), 0xff000000.toInt(), -1, 0x7f885522)) {
            assertEquals(p, ImagingMath.tone(p, 1f, 1f, SceneStats(), ProcessingOptions(enabled = false)))
            assertEquals(p, ImagingMath.tone(p, 1f, 1f, SceneStats(), ProcessingOptions(strength = 0f)))
        }
    }
    @Test fun linearSrgbRoundTripPreservesEveryByte() {
        for (v in 0..255) assertEquals(v, ImagingMath.encodeLinear(ImagingMath.linearChannel(v)))
    }
    @Test fun portraitLiftIsStrongerThanBackgroundForBacklitFace() {
        val scene = SceneStats(average = 0.45f, subject = 0.17f, background = 0.78f)
        val p = 0xff404040.toInt()
        val options = ProcessingOptions(strength = 1f, faceLight = 1f)
        val face = ImagingMath.luma(ImagingMath.tone(p, 1f, 1f, scene, options))
        val background = ImagingMath.luma(ImagingMath.tone(p, 0f, 0f, scene, options))
        assertTrue("Face exposure should rise without globally lifting the background", face > background + 0.05f)
    }
    @Test fun neutralColorsRemainNeutralAndAlphaSurvives() {
        val scene = SceneStats(average = 0.2f, subject = 0.15f, background = 0.6f)
        for (v in 0..255) {
            val pixel = (0x72 shl 24) or (v shl 16) or (v shl 8) or v
            val result = ImagingMath.tone(pixel, 1f, 1f, scene, ProcessingOptions())
            assertEquals(0x72, result ushr 24)
            assertEquals((result ushr 16) and 255, (result ushr 8) and 255)
            assertEquals(result and 255, (result ushr 8) and 255)
        }
    }
    @Test fun toneCurveDoesNotReverseBrightnessOrInventHighlights() {
        var previous = -1f
        for (v in 0..255) {
            val p = -0x1000000 or (v shl 16) or (v shl 8) or v
            val y = ImagingMath.luma(ImagingMath.tone(p, 0.5f, 0f, SceneStats(average = 0.2f), ProcessingOptions()))
            assertTrue(y >= previous); previous = y
        }
        assertEquals(-1, ImagingMath.tone(-1, 1f, 1f, SceneStats(), ProcessingOptions()))
        assertEquals(0xff000000.toInt(), ImagingMath.tone(0xff000000.toInt(), 1f, 1f, SceneStats(), ProcessingOptions()))
    }
    @Test fun faceMeteringDoesNotIncludeBrightBackground() {
        val width = 100; val height = 100
        val pixels = IntArray(width * height) { i -> if (i % width in 35..65 && i / width in 35..65) 0xff303030.toInt() else 0xffdddddd.toInt() }
        val stats = ImagingMath.stats(pixels, width, height, null, listOf(FaceRegion(0.4f, 0.4f, 0.6f, 0.6f)))
        assertEquals(1, stats.faces)
        assertTrue(stats.background > stats.subject + 0.5f)
        assertEquals(64, stats.histogram.size)
    }
    @Test fun noFaceUsesWholeImageWithoutFalseBacklight() {
        val stats = ImagingMath.stats(IntArray(100) { 0xff808080.toInt() }, 10, 10, null, emptyList())
        assertEquals(stats.average, stats.subject, 0.001f)
        assertEquals(stats.average, stats.background, 0.001f)
    }
    @Test fun maskIsSmoothClampedAndHandlesInvalidConfidence() {
        val mask = Mask(2, 2, floatArrayOf(0f, 1f, 0f, 1f))
        assertEquals(0.5f, mask.sample(0.5f, 0.5f), 0.001f)
        assertEquals(0f, mask.sample(-1f, 0f), 0.001f)
        assertEquals(1f, mask.sample(2f, 1f), 0.001f)
        assertEquals(0f, Mask(1, 1, floatArrayOf(Float.NaN)).sample(0f, 0f), 0f)
    }
    @Test fun heapBudgetLimitsHighResolution() {
        val low = ImagingMath.maxPixels(128L * 1024 * 1024, ProcessingQuality.HIGH)
        val high = ImagingMath.maxPixels(512L * 1024 * 1024, ProcessingQuality.HIGH)
        assertTrue(low < high)
        assertTrue(low * 40L <= 128L * 1024 * 1024)
        assertTrue(high <= 12_000_000)
    }
}
