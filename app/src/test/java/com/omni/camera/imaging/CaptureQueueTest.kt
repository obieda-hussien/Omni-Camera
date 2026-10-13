package com.omni.camera.imaging

import org.junit.Assert.*
import org.junit.Test

class CaptureQueueTest {
    @Test fun cameraUnlocksBeforeBackgroundWorkFinishes() {
        val queue = CaptureQueue()
        val first = requireNotNull(queue.reserve())
        assertNull(queue.reserve())
        queue.acquired(first)
        assertTrue(queue.canCapture)
        val second = requireNotNull(queue.reserve())
        queue.acquired(second)
        assertEquals(2, queue.count)
        assertFalse(queue.canCapture)
        queue.complete(first)
        assertTrue(queue.canCapture)
    }
    @Test fun olderCompletionCannotUnlockANewerCameraCapture() {
        val queue = CaptureQueue()
        val first = requireNotNull(queue.reserve())
        queue.acquired(first)
        val second = requireNotNull(queue.reserve())
        queue.complete(first)
        assertTrue(queue.acquiring)
        assertFalse(queue.canCapture)
        assertEquals(second, queue.latest)
        queue.complete(second)
        assertEquals(0, queue.count)
        assertTrue(queue.canCapture)
    }
}
