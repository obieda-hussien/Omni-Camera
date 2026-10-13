package com.omni.camera.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omni.camera.R
import com.omni.camera.camera.*
import com.omni.camera.imaging.*
import com.omni.camera.media.MediaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PhotoReview(val uri: Uri, val original: Uri?, val image: Bitmap, val before: Bitmap,
    val stats: SceneStats, val milliseconds: Long, val modelsAvailable: Boolean, val fusedFrames: Int)

data class CameraUiState(
    val settings: CameraSettings = CameraSettings(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val scene: SceneStats = SceneStats(),
    val busy: Boolean = false,
    val processing: Int = 0,
    val stage: DevelopStage? = null,
    val thumbnail: Bitmap? = null,
    val shutterCount: Long = 0,
    val lastPhoto: PhotoReview? = null,
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
    private val queue = CaptureQueue()
    private val development = Mutex()
    @Volatile private var mediaEpoch = 0L
    private var preferenceJob: Job? = null
    private val mutable = MutableStateFlow(CameraUiState(settings = store.load()))
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch(Dispatchers.Default) {
            val seed = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try { models.analyze(seed, 64) } catch (_: Exception) { } finally { seed.recycle() }
        }
    }
    fun change(transform: (CameraSettings) -> CameraSettings) {
        val current = mutable.value
        if (current.busy || current.stopping) return
        val next = transform(current.settings)
        if (current.recording && (next.mode != current.settings.mode || next.front != current.settings.front ||
            next.profile != current.settings.profile || next.resolution != current.settings.resolution ||
            next.fps != current.settings.fps || next.bitrateMbps != current.settings.bitrateMbps)) return
        mutable.update { it.copy(settings = next, error = null) }
        preferenceJob?.cancel()
        preferenceJob = viewModelScope.launch { delay(300); withContext(Dispatchers.IO) { store.save(next) } }
    }
    fun capabilities(c: CameraCapabilities) { mutable.update { it.copy(capabilities = c) } }
    fun scene(s: SceneStats) { mutable.update { it.copy(scene = s) } }
    fun error(message: String) { mutable.update { it.copy(error = message) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun dismissReview() { mutable.update { it.copy(review = null) } }
    fun openReview() { mutable.update { it.copy(review = it.lastPhoto) } }
    fun reset() { change { CameraSettings(front = it.front, mode = it.mode) } }
    private fun queueState() { mutable.update { it.copy(busy = queue.acquiring, processing = queue.count, stage = if (queue.count == 0) null else it.stage) } }

    fun capture(controller: CameraController) {
        if (!mutable.value.capabilities.ready || mutable.value.recording) return
        val id = queue.reserve() ?: return
        val s = mutable.value.settings
        val epoch = ++mediaEpoch
        mutable.update { it.copy(busy = true, processing = queue.count, error = null, lastPhoto = null,
            stage = DevelopStage.DECODING, shutterCount = id) }
        viewModelScope.launch {
            var bundle: CaptureBundle? = null
            var result: ProcessedPhoto? = null
            var before: Bitmap? = null
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val captured = controller.takePhoto()
                bundle = captured
                before = withContext(Dispatchers.Default) {
                    val decoded = processor.decode(captured.base, ProcessingQuality.FAST, s.front && s.mirrorSelfie)
                    try { processor.thumbnail(decoded, 720) } finally { decoded.recycle() }
                }
                // Shutter is available again while this immutable capture develops in the queue.
                queue.acquired(id)
                if (id == queue.latest && epoch == mediaEpoch) mutable.update { it.copy(thumbnail = before, lastMedia = null, lastIsVideo = false) }
                queueState()
                development.withLock {
                    val effective = if (captured.vendorProcessed) s.processing.copy(strength = s.processing.strength * .45f,
                        denoise = s.processing.denoise * .25f, detail = s.processing.detail * .5f) else s.processing
                    result = processor.process(captured, effective, s.front && s.mirrorSelfie) { stage ->
                        if (id == queue.latest && epoch == mediaEpoch) mutable.update { it.copy(stage = stage) }
                    }
                    val photo = requireNotNull(result)
                    val after = withContext(Dispatchers.Default) { processor.thumbnail(photo.bitmap, 720) }
                    if (id == queue.latest && epoch == mediaEpoch) mutable.update { it.copy(stage = DevelopStage.SAVING) }
                    val original = if (s.processing.keepOriginal) media.saveOriginal(captured.base, media.name("_original")) else null
                    val uri = media.savePhoto(photo.bitmap, captured.base, media.name())
                    val review = PhotoReview(uri, original, after, requireNotNull(before), photo.stats,
                        android.os.SystemClock.elapsedRealtime() - started, photo.modelsAvailable, photo.acceptedFrames)
                    if (id == queue.latest && epoch == mediaEpoch) mutable.update { it.copy(lastPhoto = review, thumbnail = after, lastMedia = uri, lastIsVideo = false) }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                val source = bundle?.base
                val recovered = if (source != null && source.exists()) withContext(NonCancellable) {
                    runCatching { media.saveOriginal(source, media.name("_recovered")) }.getOrNull()
                } else null
                if (id == queue.latest && epoch == mediaEpoch) mutable.update { it.copy(lastMedia = recovered,
                    error = if (recovered != null) getApplication<Application>().getString(R.string.processing_recovered)
                    else e.message ?: getApplication<Application>().getString(R.string.error)) }
            } finally {
                result?.bitmap?.recycle(); bundle?.delete()
                queue.complete(id); queueState()
            }
        }
    }

    fun startVideo(controller: CameraController, audio: Boolean) {
        if (mutable.value.recording || mutable.value.busy || !mutable.value.capabilities.ready) return
        mediaEpoch++
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
                } else mutable.update { it.copy(recording = false, stopping = false, lastMedia = uri, lastIsVideo = true, lastPhoto = null) }
            }
        }
    }
    override fun onCleared() { store.save(mutable.value.settings); super.onCleared(); models.close() }
}
