package com.omni.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
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
    @Test fun captureDevelopReviewAndLandscape() {
        val vm = ViewModelProvider(compose.activity)[CameraViewModel::class.java]
        compose.waitUntil(45_000) { vm.state.value.capabilities.ready }
        assertTrue("The device must execute the native engine", NativeImaging.available)
        screenshot("01-camera-portrait")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture)).performClick()
        compose.waitUntil(45_000) { vm.state.value.processing == 0 && vm.state.value.lastMedia != null }
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.lastPhoto)
        assertNull("Photo development must not interrupt shooting with a modal", vm.state.value.review)
        screenshot("02-camera-developed")
        compose.runOnIdle { vm.openReview() }
        compose.waitUntil { vm.state.value.review != null }
        compose.waitForIdle(); screenshot("03-photo-review")
        compose.runOnIdle { vm.dismissReview(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(30_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && vm.state.value.capabilities.ready }
        compose.waitForIdle(); screenshot("04-camera-landscape")
    }
}
