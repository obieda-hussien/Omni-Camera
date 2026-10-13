package com.omni.camera.imaging

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteOrder

data class PortraitAnalysis(val mask: Mask?, val faces: List<FaceRegion>, val modelsAvailable: Boolean)

class PortraitModels : AutoCloseable {
    private val mutex = Mutex()
    private var closed = false
    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
    }
    suspend fun analyze(bitmap: Bitmap): PortraitAnalysis = mutex.withLock {
        check(!closed)
        // ML Kit tasks cannot be cancelled. Keep the input alive until both native tasks finish.
        withContext(NonCancellable) { analyzeLocked(bitmap) }
    }
    private suspend fun analyzeLocked(bitmap: Bitmap): PortraitAnalysis = coroutineScope {
        val scale = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        try {
            val input = InputImage.fromBitmap(small, 0)
            val maskJob = async {
                runCatching {
                    val result = segmenter.process(input).await()
                    val values = FloatArray(result.width * result.height)
                    result.buffer.rewind()
                    result.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(values)
                    // No confident subject: avoid applying portrait lighting to spurious regions.
                    if (values.count { it > 0.7f } > values.size / 100)
                        Mask(result.width, result.height, values) else null
                }
            }
            val facesJob = async {
                runCatching {
                    faceDetector.process(input).await().map { face ->
                        val box = face.boundingBox
                        FaceRegion(box.left.toFloat() / small.width, box.top.toFloat() / small.height,
                            box.right.toFloat() / small.width, box.bottom.toFloat() / small.height)
                    }
                }
            }
            val m = maskJob.await(); val f = facesJob.await()
            PortraitAnalysis(m.getOrNull(), f.getOrDefault(emptyList()), m.isSuccess && f.isSuccess)
        } finally { if (small !== bitmap) small.recycle() }
    }
    suspend fun shutdown() = mutex.withLock {
        if (!closed) { closed = true; segmenter.close(); faceDetector.close() }
    }
    override fun close() { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { shutdown() } }
}
