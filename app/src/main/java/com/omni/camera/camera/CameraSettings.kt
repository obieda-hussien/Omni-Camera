package com.omni.camera.camera

import android.hardware.camera2.CaptureRequest
import com.omni.camera.imaging.ProcessingOptions

enum class CaptureMode { PHOTO, VIDEO, NIGHT, PRO }
enum class VideoProfile { SDR, FLAT, HLG10 }
enum class WhiteBalance(val request: Int) {
    AUTO(CaptureRequest.CONTROL_AWB_MODE_AUTO), DAYLIGHT(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT), TUNGSTEN(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
}

data class CameraSettings(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val front: Boolean = false,
    val grid: Boolean = true,
    val histogram: Boolean = false,
    val torch: Boolean = false,
    val exposure: Float = 0f,
    val zoom: Float = 1f,
    val manualSensor: Boolean = false,
    val iso: Int = 200,
    val shutterNanos: Long = 16_666_667,
    val focus: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val profile: VideoProfile = VideoProfile.SDR,
    val resolution: Int = 1080,
    val fps: Int = 30,
    val bitrateMbps: Int = 16,
    val microphone: Boolean = true,
    val mirrorSelfie: Boolean = true,
    val processing: ProcessingOptions = ProcessingOptions(),
    val favoriteTools: List<String> = listOf("light", "portrait", "detail", "color"),
)

data class CameraCapabilities(
    val ready: Boolean = false,
    val manualSensor: Boolean = false,
    val manualFocus: Boolean = false,
    val raw: Boolean = false,
    val hlg10: Boolean = false,
    val flash: Boolean = false,
    val isoRange: IntRange = 100..1600,
    val shutterRange: LongRange = 1_000_000L..250_000_000L,
    val focusDiopters: Float = 0f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val exposureRange: ClosedFloatingPointRange<Float> = 0f..0f,
    val whiteBalances: List<WhiteBalance> = listOf(WhiteBalance.AUTO),
    val resolutions: List<Int> = listOf(1080),
    val frameRates: List<Int> = listOf(30),
    val actualProfile: VideoProfile = VideoProfile.SDR,
    val actualResolution: Int = 1080,
    val actualFps: Int = 30,
    val vendorAuto: Boolean = false,
    val vendorHdr: Boolean = false,
    val vendorNight: Boolean = false,
    val activeExtension: Int = 0,
)
