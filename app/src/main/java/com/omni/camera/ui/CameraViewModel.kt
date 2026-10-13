package com.omni.camera.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.camera.camera.*
import com.omni.camera.imaging.*
import com.omni.camera.media.MediaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PhotoReview(val uri: Uri, val original: Uri?, val image: Bitmap, val before: Bitmap,
    val stats: SceneStats, val milliseconds: Long, val modelsAvailable: Boolean, val fusedFrames: Int)

data class CameraUiState(
    val settings: CameraSettings = CameraSettings(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val scene: SceneStats = SceneStats(),
    val busy: Boolean = false,
    val recording: Boolean = false,
    val stopping: Boolean = false,
    val seconds: Long = 0,
    val review: PhotoReview? = null,
    val lastMedia: Uri? = null,
    val lastIsVideo: Boolean = false,
    val error: String? = null,
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    val media = MediaRepository(application)
    private val models = PortraitModels()
    private val processor = PhotoProcessor(models)
    private val mutable = MutableStateFlow(CameraUiState(settings = store.load()))
    val state = mutable.asStateFlow()
    fun change(transform: (CameraSettings) -> CameraSettings) {
        if (mutable.value.busy || mutable.value.recording) return
        mutable.update { it.copy(settings = transform(it.settings), error = null) }
        store.save(mutable.value.settings)
    }
    fun capabilities(c: CameraCapabilities) { mutable.update { it.copy(capabilities = c) } }
    fun scene(s: SceneStats) { mutable.update { it.copy(scene = s) } }
    fun error(message: String) { mutable.update { it.copy(error = message) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun dismissReview() { mutable.update { it.copy(review = null) } }
    fun reset() { change { CameraSettings(front = it.front, mode = it.mode) } }

    fun capture(controller: CameraController) {
        if (mutable.value.busy || !mutable.value.capabilities.ready) return
        val s = mutable.value.settings
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var bundle: CaptureBundle? = null
            var result: ProcessedPhoto? = null
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                bundle = controller.takePhoto()
                result = processor.process(bundle, s.processing, s.front && s.mirrorSelfie)
                val original = if (s.processing.keepOriginal) media.saveOriginal(bundle.base, media.name("_original")) else null
                val uri = media.savePhoto(result.bitmap, bundle.base, media.name())
                val review = withContext(Dispatchers.Default) {
                    fun thumb(bitmap: Bitmap): Bitmap {
                        val scale = minOf(1f, 1000f / maxOf(bitmap.width, bitmap.height))
                        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1), true).let {
                            if (it === bitmap) it.copy(Bitmap.Config.ARGB_8888, false) else it
                        }
                    }
                    val before = processor.decode(bundle.base, ProcessingQuality.FAST, s.front && s.mirrorSelfie)
                    try { PhotoReview(uri, original, thumb(result.bitmap), thumb(before), result.stats,
                        android.os.SystemClock.elapsedRealtime() - started, result.modelsAvailable, bundle.brackets.size + 1) }
                    finally { before.recycle() }
                }
                mutable.update { it.copy(review = review, lastMedia = uri, lastIsVideo = false) }
            } catch (e: CancellationException) { throw e
            } catch (e: OutOfMemoryError) { error(getApplication<Application>().getString(com.omni.camera.R.string.memory_low))
            } catch (e: Exception) { error(e.message ?: "Capture failed")
            } finally {
                result?.bitmap?.recycle(); bundle?.delete()
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun startVideo(controller: CameraController, audio: Boolean) {
        if (mutable.value.recording || mutable.value.busy || !mutable.value.capabilities.ready) return
        mutable.update { it.copy(recording = true, stopping = false, seconds = 0, error = null) }
        runCatching { controller.startVideo(audio) }.onFailure { e -> mutable.update { it.copy(recording = false, error = e.message) } }
    }
    fun stopVideo(controller: CameraController) {
        if (mutable.value.recording && !mutable.value.stopping) {
            mutable.update { it.copy(stopping = true) }; controller.stopVideo()
        }
    }
    fun video(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> mutable.update { it.copy(seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000) }
            is VideoRecordEvent.Finalize -> {
                val uri = event.outputResults.outputUri
                if (event.hasError()) {
                    if (uri != Uri.EMPTY) viewModelScope.launch { runCatching { media.discard(uri) } }
                    mutable.update { it.copy(recording = false, stopping = false, error = "Video error ${event.error}") }
                } else mutable.update { it.copy(recording = false, stopping = false, lastMedia = uri, lastIsVideo = true) }
            }
        }
    }
    override fun onCleared() { super.onCleared(); models.close() }
}
