package com.omni.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.test.filters.SdkSuppress
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.omni.camera.ui.CameraViewModel
import com.omni.camera.imaging.NativeImaging
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CameraExperienceTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun previewStreaming(): Boolean {
        var streaming = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun find(view: View): PreviewView? {
                if (view is PreviewView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            streaming = find(compose.activity.window.decorView)?.previewStreamState?.value == PreviewView.StreamState.STREAMING
        }
        return streaming
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // App-private external files disappear when UTP uninstalls the target. Public MediaStore
        // screenshots survive teardown so the CI runner can inspect and archive them.
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Omni-Camera-UI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) { resolver.delete(uri, null, null); throw e }
        finally { image.recycle() }
    }
    @Test fun nativeBurstPreservesOrderAndLifetime() {
        val width = 96; val height = 80
        fun bitmap(delta: Int): Bitmap {
            val pixels = IntArray(width * height) { n ->
                val value = 85 + ((n % width) / 9 + (n / width) / 11) % 2 * 60 + delta
                -0x1000000 or (value shl 16) or (value shl 8) or value
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        val forward = bitmap(0); val reverse = bitmap(0); val a = bitmap(12); val b = bitmap(-10)
        try {
            requireNotNull(NativeImaging.burst(forward)).use {
                assertTrue(it.add(a, 0f)); assertTrue(it.add(b, 0f)); it.finish(forward)
            }
            requireNotNull(NativeImaging.burst(reverse)).use {
                assertTrue(it.add(b, 0f)); assertTrue(it.add(a, 0f)); it.finish(reverse)
            }
            val first = IntArray(width * height); val second = IntArray(width * height)
            forward.getPixels(first, 0, width, 0, 0, width, height)
            reverse.getPixels(second, 0, width, 0, 0, width, height)
            assertArrayEquals("JNI burst must not favor the last frame", first, second)
            val closed = requireNotNull(NativeImaging.burst(forward))
            closed.close(); closed.close()
            assertFalse("Closed burst handles must not reach JNI", runCatching { closed.add(a, 0f) }.isSuccess)
        } finally { listOf(forward, reverse, a, b).forEach { it.recycle() } }
    }
    @Test fun captureDevelopReviewAndLandscape() {
        val vm = ViewModelProvider(compose.activity)[CameraViewModel::class.java]
        compose.waitUntil(45_000) { vm.state.value.capabilities.ready && previewStreaming() }
        compose.waitForIdle()
        assertTrue("The device must execute the native engine", NativeImaging.available)
        screenshot("01-camera-portrait")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture)).performClick()
        compose.waitUntil(45_000) { vm.state.value.processing == 0 && vm.state.value.lastMedia != null }
        compose.waitForIdle()
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.lastPhoto)
        assertNull("Photo development must not interrupt shooting with a modal", vm.state.value.review)
        screenshot("02-camera-developed")
        compose.runOnIdle { vm.openReview() }
        compose.waitUntil { vm.state.value.review != null }
        compose.waitForIdle(); screenshot("03-photo-review")
        compose.runOnIdle { vm.dismissReview(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(30_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && vm.state.value.capabilities.ready && previewStreaming() }
        compose.waitForIdle(); screenshot("04-camera-landscape")
    }
}
