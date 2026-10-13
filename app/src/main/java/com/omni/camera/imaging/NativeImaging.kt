package com.omni.camera.imaging

import android.graphics.Bitmap
import android.os.Process

/** One JNI call per photo, with native pixel access and no full-resolution Java pixel copies. */
object NativeImaging {
    val available: Boolean = runCatching { System.loadLibrary("omni_imaging"); true }.getOrDefault(false)
    private external fun nativeDevelop(bitmap: Bitmap, mask: FloatArray?, width: Int, height: Int, faces: FloatArray, parameters: FloatArray): Float
    private external fun nativeCreateBurst(base: Bitmap): Long
    private external fun nativeAddBurst(handle: Long, candidate: Bitmap, stops: Float): Boolean
    private external fun nativeFinishBurst(handle: Long, destination: Bitmap)
    private external fun nativeReleaseBurst(handle: Long)

    fun develop(bitmap: Bitmap, analysis: PortraitAnalysis, stats: SceneStats, options: ProcessingOptions): Boolean {
        if (!available) return false
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        val faces = analysis.faces.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toFloatArray()
        background {
            nativeDevelop(bitmap, analysis.mask?.confidence, analysis.mask?.width ?: 0, analysis.mask?.height ?: 0, faces,
                floatArrayOf(options.strength, options.faceLight, options.backgroundLight, options.denoise,
                    options.detail, options.warmth, stats.subject, stats.background))
        }
        return true
    }
    fun burst(base: Bitmap): Burst? = if (available) background { Burst(nativeCreateBurst(base)) } else null
    /** Owned by one development coroutine; always close in finally, including cancellation. */
    class Burst internal constructor(private var handle: Long) : AutoCloseable {
        init { check(handle != 0L) }
        fun add(frame: Bitmap, stops: Float): Boolean {
            check(handle != 0L)
            return NativeImaging.background { NativeImaging.nativeAddBurst(handle, frame, stops) }
        }
        fun finish(destination: Bitmap) {
            check(handle != 0L)
            require(destination.isMutable && destination.config == Bitmap.Config.ARGB_8888)
            NativeImaging.background { NativeImaging.nativeFinishBurst(handle, destination) }
        }
        override fun close() {
            val owned = handle
            handle = 0
            if (owned != 0L) NativeImaging.nativeReleaseBurst(owned)
        }
    }
    private inline fun <T> background(block: () -> T): T {
        val tid = Process.myTid()
        val priority = Process.getThreadPriority(tid)
        return try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); block() }
            finally { Process.setThreadPriority(priority) }
    }
}
