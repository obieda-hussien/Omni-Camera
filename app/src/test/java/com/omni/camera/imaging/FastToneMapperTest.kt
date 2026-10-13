package com.omni.camera.imaging

import org.junit.Assert.*
import org.junit.Test

class FastToneMapperTest {
    @Test fun recoveryPreservesDisabledImagesAndAlpha() {
        val disabled = FastToneMapper(SceneStats(), ProcessingOptions(enabled = false))
        assertEquals(0x72405566, disabled.map(0x72405566, 1f))
        val active = FastToneMapper(SceneStats(subject = .1f, background = .8f), ProcessingOptions())
        assertEquals(0x72, active.map(0x72405566, 1f) ushr 24)
    }
    @Test fun recoveryGraysRemainMonotonicAndNeutral() {
        val mapper = FastToneMapper(SceneStats(subject = .2f, background = .8f), ProcessingOptions())
        var previous = -1
        for (v in 0..255) {
            val p = -0x1000000 or (v shl 16) or (v shl 8) or v
            val result = mapper.map(p, 1f)
            val channel = result and 255
            assertTrue(channel >= previous)
            assertEquals(channel, (result ushr 16) and 255)
            assertEquals(channel, (result ushr 8) and 255)
            previous = channel
        }
    }
}
