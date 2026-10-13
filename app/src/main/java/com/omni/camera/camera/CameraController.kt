package com.omni.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.omni.camera.imaging.*
import com.omni.camera.media.MediaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

data class CaptureBundle(val base: File, val brackets: List<Pair<File, Float>> = emptyList()) {
    fun delete() { base.delete(); brackets.forEach { it.first.delete() } }
}

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@SuppressLint("MissingPermission") // bind checks CAMERA; startVideo independently checks RECORD_AUDIO.
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val media: MediaRepository,
    private val onCapabilities: (CameraCapabilities) -> Unit,
    private val onStats: (SceneStats) -> Unit,
    private val onError: (String) -> Unit,
    private val onVideo: (VideoRecordEvent) -> Unit,
) : DefaultLifecycleObserver, AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val previewFace by lazy { FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()) }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var effect: FlatVideoEffect? = null
    private var useCases = emptyList<UseCase>()
    @Volatile private var generation = 0
    private var settings = CameraSettings()
    private var capabilities = CameraCapabilities()
    private var lastAnalysis = 0L
    private var latestScene = SceneStats()
    private var lastControls: List<Any?>? = null
    @Volatile private var disposed = false
    private val analyzing = AtomicBoolean(false)
    private var rotation = view.display?.rotation ?: Surface.ROTATION_0
    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            rotation = when (degrees) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            if (recording == null) {
                capture?.targetRotation = rotation; video?.targetRotation = rotation
                useCases.filterIsInstance<ImageAnalysis>().forEach { it.targetRotation = rotation }
            }
        }
    }
    init { owner.lifecycle.addObserver(this) }

    suspend fun bind(s: CameraSettings) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Camera permission is required" }
        val token = ++generation
        settings = s
        val p = provider ?: ProcessCameraProvider.getInstance(context).awaitMain().also { provider = it }
        if (disposed || token != generation) return
        recording?.stop(); recording = null
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        if (useCases.isNotEmpty()) p.unbind(*useCases.toTypedArray())
        effect?.close(); effect = null
        capture = null; video = null; camera = null; lastControls = null
        val selector = if (s.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val info = p.availableCameraInfos.firstOrNull { selector.filter(listOf(it)).isNotEmpty() }
            ?: throw IllegalStateException("Requested camera is unavailable")
        val videoCaps = Recorder.getVideoCapabilities(info)
        val hlg = videoCaps.supportedDynamicRanges.contains(DynamicRange.HLG_10_BIT)
        val profile = if (s.profile == VideoProfile.HLG10 && !hlg) VideoProfile.SDR else s.profile
        val dynamicRange = if (s.mode == CaptureMode.VIDEO && profile == VideoProfile.HLG10) DynamicRange.HLG_10_BIT else DynamicRange.SDR
        val qualities = videoCaps.getSupportedQualities(dynamicRange)
        val requested = quality(s.resolution)
        val selected = if (requested in qualities) requested else qualities.firstOrNull { size(it) <= s.resolution } ?: qualities.lastOrNull() ?: Quality.SD
        val chars = Camera2CameraInfo.from(info)
        val ranges = chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val rates = listOf(24, 30, 60).filter { rate -> ranges.any { it.contains(rate) } }.ifEmpty { listOf(30) }
        val fps = s.fps.takeIf { it in rates } ?: rates.minBy { abs(it - s.fps) }
        val previewBuilder = Preview.Builder().setDynamicRange(dynamicRange)
        var reportedAt = 0L
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val duration = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: return
                val now = SystemClock.elapsedRealtime()
                if (duration <= 0 || now - reportedAt < 1000) return
                reportedAt = now
                val observedFps = (1_000_000_000.0 / duration).roundToInt()
                main.execute { if (!disposed && token == generation && capabilities.ready) {
                    capabilities = capabilities.copy(actualFps = observedFps)
                    onCapabilities(capabilities)
                } }
            }
        })
        val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(android.util.Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(analysisExecutor) { analyze(it, token) }
        val group = UseCaseGroup.Builder().addUseCase(preview)
        view.viewPort?.let { group.setViewPort(it) }
        val active = mutableListOf<UseCase>(preview)
        if (s.mode == CaptureMode.VIDEO) {
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(selected))
                .setTargetVideoEncodingBitRate(s.bitrateMbps.coerceIn(4, 100) * 1_000_000).build()
            video = VideoCapture.Builder(recorder).setDynamicRange(dynamicRange).setTargetFrameRate(Range(fps, fps))
                .setTargetRotation(rotation).build()
            group.addUseCase(video!!); active.add(video!!)
            if (profile == VideoProfile.FLAT) {
                effect = FlatVideoEffect { e -> main.execute { if (!disposed && token == generation) onError(e.message ?: "Video effect failed") } }
                group.addEffect(effect!!.effect)
            }
        } else {
            capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100).setTargetRotation(rotation).build()
            group.addUseCase(capture!!); active.add(capture!!)
        }
        // Some cameras cannot combine HDR video with an SDR analysis stream.
        if (dynamicRange == DynamicRange.SDR) { group.addUseCase(analysis); active.add(analysis) }
        camera = try { p.bindToLifecycle(owner, selector, group.build()) } catch (e: IllegalArgumentException) {
            p.unbind(*active.toTypedArray())
            analysis.clearAnalyzer(); active.remove(analysis)
            val reduced = UseCaseGroup.Builder()
            active.forEach { reduced.addUseCase(it) }
            view.viewPort?.let { reduced.setViewPort(it) }
            effect?.let { reduced.addEffect(it.effect) }
            p.bindToLifecycle(owner, selector, reduced.build())
        }
        useCases = active
        val c = requireNotNull(camera)
        val deviceCapabilities = chars.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val iso = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutter = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = chars.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val exposure = c.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        val zoom = c.cameraInfo.zoomState.value
        val wb = chars.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        capabilities = CameraCapabilities(ready = true,
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in deviceCapabilities && iso != null && shutter != null,
            manualFocus = minFocus > 0f, raw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in deviceCapabilities,
            hlg10 = hlg, flash = c.cameraInfo.hasFlashUnit(), isoRange = (iso?.lower ?: 100)..(iso?.upper ?: 1600),
            shutterRange = (shutter?.lower ?: 1_000_000L)..minOf(shutter?.upper ?: 250_000_000L, 500_000_000L),
            focusDiopters = minFocus, minZoom = zoom?.minZoomRatio ?: 1f, maxZoom = zoom?.maxZoomRatio ?: 1f,
            exposureRange = (exposure.exposureCompensationRange.lower * step)..(exposure.exposureCompensationRange.upper * step),
            whiteBalances = WhiteBalance.entries.filter { it.request in wb }.ifEmpty { listOf(WhiteBalance.AUTO) },
            resolutions = qualities.map(::size).distinct().ifEmpty { listOf(480) }, frameRates = rates,
            actualProfile = if (s.mode == CaptureMode.VIDEO) profile else VideoProfile.SDR,
            actualResolution = size(selected), actualFps = 0)
        onCapabilities(capabilities)
        c.cameraInfo.cameraState.observe(owner) { state -> state.error?.let { if (token == generation) onError("Camera error ${it.code}") } }
        orientation.enable()
        update(s)
    }

    fun update(s: CameraSettings) {
        settings = s
        val c = camera ?: return
        val controls = listOf(s.zoom, s.torch, s.exposure, s.mode, s.manualSensor, s.iso, s.shutterNanos, s.focus, s.whiteBalance)
        if (controls == lastControls) return
        lastControls = controls
        c.cameraControl.setZoomRatio(s.zoom.coerceIn(capabilities.minZoom, capabilities.maxZoom))
        if (capabilities.flash) c.cameraControl.enableTorch(s.torch)
        val exposure = c.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        if (step > 0) c.cameraControl.setExposureCompensationIndex((s.exposure / step).roundToInt()
            .coerceIn(exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper))
        val options = CaptureRequestOptions.Builder()
        if (s.mode == CaptureMode.PRO && s.manualSensor && capabilities.manualSensor) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso.coerceIn(capabilities.isoRange))
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.shutterNanos.coerceIn(capabilities.shutterRange))
        }
        if (s.focus != null && capabilities.manualFocus) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, (s.focus * capabilities.focusDiopters).coerceIn(0f, capabilities.focusDiopters))
        }
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,
            s.whiteBalance.takeIf { it in capabilities.whiteBalances }?.request ?: CaptureRequest.CONTROL_AWB_MODE_AUTO)
        Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(options.build())
    }

    fun focus(x: Float, y: Float, lock: Boolean = false) {
        val c = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).apply {
            if (lock) disableAutoCancel() else setAutoCancelDuration(3, TimeUnit.SECONDS)
        }.build()
        c.cameraControl.startFocusAndMetering(action)
    }

    suspend fun takePhoto(): CaptureBundle {
        val c = requireNotNull(camera) { "Camera is not ready" }
        val shot = requireNotNull(capture) { "Photo mode is required" }
        val originalEv = settings.exposure
        val files = mutableListOf<File>()
        try {
            if (settings.mode == CaptureMode.PHOTO && originalEv == 0f && latestScene.clippedFraction > 0.12f) {
                val exposure = c.cameraInfo.exposureState
                val step = exposure.exposureCompensationStep.toFloat()
                if (step > 0) {
                    c.cameraControl.setExposureCompensationIndex((-0.7f / step).roundToInt()
                        .coerceIn(exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper)).awaitMain()
                    delay(120)
                }
            }
            val base = captureFile(shot).also { files += it }
            val brackets = mutableListOf<Pair<File, Float>>()
            if (settings.processing.hdr && settings.mode == CaptureMode.PHOTO) {
                val state = c.cameraInfo.exposureState
                val step = state.exposureCompensationStep.toFloat()
                if (step > 0) {
                    val awbLock = Camera2CameraInfo.from(c.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
                    if (awbLock) Camera2CameraControl.from(c.cameraControl).addCaptureRequestOptions(
                        CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true).build()).awaitMain()
                    val baseline = state.exposureCompensationIndex
                    for (stop in listOf(-1.5f, 1.5f)) {
                        val index = (baseline + (stop / step).roundToInt())
                            .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
                        if (abs((index - baseline) * step) < 0.5f) continue
                        c.cameraControl.setExposureCompensationIndex(index).awaitMain(); delay(160)
                        brackets += captureFile(shot).also { files += it } to ((index - baseline) * step)
                    }
                }
            }
            return CaptureBundle(base, brackets)
        } catch (e: Throwable) { files.forEach { it.delete() }; throw e
        } finally { lastControls = null; update(settings.copy(exposure = originalEv)) }
    }

    private suspend fun captureFile(shot: ImageCapture): File = suspendCancellableCoroutine { continuation ->
        val file = File.createTempFile("capture-", ".jpg", context.cacheDir)
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (continuation.isActive) continuation.resume(file) { _, value, _ -> value.delete() } else file.delete()
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete(); if (continuation.isActive) continuation.resumeWithException(exception)
            }
        })
    }

    fun startVideo(audio: Boolean) {
        check(recording == null)
        val recorder = requireNotNull(video).output
        val output = MediaStoreOutputOptions.Builder(context.contentResolver,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(media.videoValues()).build()
        var pending = recorder.prepareRecording(context, output)
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pending = pending.withAudioEnabled()
        recording = pending.start(main) { event ->
            if (event is VideoRecordEvent.Finalize) { recording?.close(); recording = null }
            onVideo(event)
        }
    }
    fun stopVideo() { recording?.stop() }
    override fun onStop(owner: LifecycleOwner) { stopVideo(); orientation.disable() }
    override fun onStart(owner: LifecycleOwner) { if (camera != null) orientation.enable() }

    private fun analyze(image: ImageProxy, token: Int) {
        if (disposed || token != generation || SystemClock.elapsedRealtime() - lastAnalysis < 500 || !analyzing.compareAndSet(false, true)) { image.close(); return }
        lastAnalysis = SystemClock.elapsedRealtime()
        var bitmap: Bitmap? = null
        try {
            val decoded = image.toBitmap()
            val crop = image.cropRect
            val raw = if (crop.left == 0 && crop.top == 0 && crop.width() == decoded.width && crop.height() == decoded.height) decoded
                else Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width(), crop.height()).also { if (it !== decoded) decoded.recycle() }
            val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            bitmap = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { raw.recycle() }
        } catch (_: Exception) { bitmap?.recycle() } finally { image.close() }
        val input = bitmap ?: run { analyzing.set(false); return }
        analysisScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val faces = withContext(NonCancellable + Dispatchers.Default) {
                    runCatching { previewFace.process(InputImage.fromBitmap(input, 0)).await() }.getOrDefault(emptyList())
                }
                    .map { val b = it.boundingBox; FaceRegion(b.left.toFloat() / input.width, b.top.toFloat() / input.height, b.right.toFloat() / input.width, b.bottom.toFloat() / input.height) }
                val pixels = IntArray(input.width * input.height)
                input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
                val stats = ImagingMath.stats(pixels, input.width, input.height, null, faces)
                main.execute { if (!disposed && token == generation) { latestScene = stats; onStats(stats) } }
            } finally { input.recycle(); analyzing.set(false) }
        }
    }

    override fun close() {
        disposed = true; generation++; orientation.disable(); owner.lifecycle.removeObserver(this)
        recording?.stop(); recording?.close(); recording = null
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        provider?.unbind(*useCases.toTypedArray()); effect?.close()
        useCases.filterIsInstance<ImageAnalysis>().forEach { it.clearAnalyzer() }
        analysisExecutor.shutdown()
        val children = analysisScope.coroutineContext[Job]?.children?.toList().orEmpty()
        analysisScope.cancel()
        CoroutineScope(Dispatchers.Default).launch { children.joinAll(); previewFace.close() }
    }
    private suspend fun <T> ListenableFuture<T>.awaitMain(): T = suspendCancellableCoroutine { c ->
        addListener({ if (c.isActive) runCatching { get() }.fold({ c.resume(it) }, { c.resumeWithException(it) }) }, main)
    }
    companion object {
        fun quality(height: Int): Quality = when (height) { 2160 -> Quality.UHD; 1080 -> Quality.FHD; 720 -> Quality.HD; else -> Quality.SD }
        fun size(q: Quality) = when (q) { Quality.UHD -> 2160; Quality.FHD -> 1080; Quality.HD -> 720; else -> 480 }
    }
}
