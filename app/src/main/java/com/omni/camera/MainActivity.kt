package com.omni.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.camera.ui.CameraScreen
import com.omni.camera.ui.CameraViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB6AFFA), onPrimary = Color(0xFF211D41),
                secondary = Color(0xFFCBC5FF), background = Color(0xFF0D0E12),
                surface = Color(0xFF17181F), surfaceVariant = Color(0xFF272832),
                onSurface = Color(0xFFF5F3FF), onSurfaceVariant = Color(0xFFB6B4C4)
            )) {
                val vm: CameraViewModel = viewModel()
                CameraScreen(vm)
            }
        }
    }
}
