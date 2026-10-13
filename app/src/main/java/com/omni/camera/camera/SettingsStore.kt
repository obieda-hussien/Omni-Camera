package com.omni.camera.camera

import android.content.Context
import com.omni.camera.imaging.ProcessingOptions
import com.omni.camera.imaging.ProcessingQuality

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("camera_preferences", Context.MODE_PRIVATE)
    fun load(): CameraSettings = CameraSettings(
        grid = prefs.getBoolean("grid", true), histogram = prefs.getBoolean("histogram", false),
        microphone = prefs.getBoolean("microphone", true), mirrorSelfie = prefs.getBoolean("mirror", true),
        resolution = prefs.getInt("resolution", 1080), fps = prefs.getInt("fps", 30), bitrateMbps = prefs.getInt("bitrate", 16),
        favoriteTools = prefs.getString("favorites", null)?.split(",")?.filter { it in TOOLS } ?: TOOLS,
        processing = ProcessingOptions(
            enabled = prefs.getBoolean("processing", true), strength = prefs.getFloat("strength", 0.7f),
            faceLight = prefs.getFloat("faceLight", 0.65f), backgroundLight = prefs.getFloat("backgroundLight", 0f),
            denoise = prefs.getFloat("denoise", 0.35f), detail = prefs.getFloat("detail", 0.25f),
            warmth = prefs.getFloat("warmth", 0f), keepOriginal = prefs.getBoolean("original", false),
            hdr = prefs.getBoolean("hdr", false),
            quality = runCatching { ProcessingQuality.valueOf(prefs.getString("quality", "BALANCED")!!) }.getOrDefault(ProcessingQuality.BALANCED)
        )
    )
    fun save(s: CameraSettings) {
        prefs.edit().putBoolean("grid", s.grid).putBoolean("histogram", s.histogram)
            .putBoolean("microphone", s.microphone).putBoolean("mirror", s.mirrorSelfie)
            .putInt("resolution", s.resolution).putInt("fps", s.fps).putInt("bitrate", s.bitrateMbps)
            .putString("favorites", s.favoriteTools.joinToString(","))
            .putBoolean("processing", s.processing.enabled).putFloat("strength", s.processing.strength)
            .putFloat("faceLight", s.processing.faceLight).putFloat("backgroundLight", s.processing.backgroundLight)
            .putFloat("denoise", s.processing.denoise).putFloat("detail", s.processing.detail)
            .putFloat("warmth", s.processing.warmth).putBoolean("original", s.processing.keepOriginal)
            .putBoolean("hdr", s.processing.hdr).putString("quality", s.processing.quality.name).apply()
    }
    companion object { val TOOLS = listOf("light", "portrait", "detail", "color") }
}
