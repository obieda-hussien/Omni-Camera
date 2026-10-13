package com.omni.camera.imaging

/** Camera acquisition and background development have separate lifetimes. Main-thread owned. */
class CaptureQueue(private val limit: Int = 2) {
    private val pending = linkedSetOf<Long>()
    private var cameraOwner: Long? = null
    var latest: Long = 0; private set
    val count: Int get() = pending.size
    val acquiring: Boolean get() = cameraOwner != null
    val canCapture: Boolean get() = !acquiring && count < limit
    fun reserve(): Long? {
        if (!canCapture) return null
        latest++
        pending += latest; cameraOwner = latest
        return latest
    }
    fun acquired(id: Long) { if (cameraOwner == id) cameraOwner = null }
    fun complete(id: Long) { acquired(id); pending.remove(id) }
}
