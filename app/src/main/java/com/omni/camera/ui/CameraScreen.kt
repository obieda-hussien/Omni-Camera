package com.omni.camera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.camera.R
import com.omni.camera.camera.*
import com.omni.camera.imaging.*
import kotlinx.coroutines.delay
import kotlin.math.*

private val Ink = Color(0xFF08090C)
private val Accent = Color(0xFFB8AEFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun cameraAllowed() = allowed(Manifest.permission.CAMERA) && (Build.VERSION.SDK_INT >= 29 || allowed(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    var permitted by remember { mutableStateOf(cameraAllowed()) }
    var denied by remember { mutableStateOf(false) }
    var customize by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var activeTool by rememberSaveable { mutableStateOf<String?>(null) }
    var controller by remember { mutableStateOf<CameraController?>(null) }
    val latestSettings by rememberUpdatedState(state.settings)
    val cameraPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = cameraAllowed(); denied = !permitted
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller?.let { c ->
            if (granted) vm.startVideo(c, true) else {
                vm.startVideo(c, false)
                vm.error(context.getString(R.string.audio_denied))
            }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permitted = cameraAllowed() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val preview = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        keepScreenOn = true
    } }
    DisposableEffect(permitted, owner, preview) {
        if (permitted) {
            val c = CameraController(context, owner, preview, vm.media, vm::capabilities, vm::scene, vm::error, vm::video)
            controller = c
            onDispose { c.close(); if (controller === c) controller = null }
        } else onDispose { }
    }
    val s = state.settings
    val bindingKey = listOf(s.front, s.mode, s.profile, s.resolution, s.fps, s.bitrateMbps, s.processing.hdr, s.processing.quality, retry)
    LaunchedEffect(controller, bindingKey) {
        controller?.let { c ->
            vm.capabilities(CameraCapabilities())
            try { c.bind(latestSettings) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { vm.error(e.message ?: context.getString(R.string.camera_unavailable)) }
        }
    }
    LaunchedEffect(controller, s) { controller?.update(s) }
    BackHandler(state.recording || activeTool != null) {
        if (state.recording) controller?.let(vm::stopVideo) else activeTool = null
    }
    val capture: () -> Unit = remember(haptic, vm, context, audioPermission) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val current = vm.state.value
            controller?.let { c ->
                if (current.settings.mode == CaptureMode.VIDEO) {
                    if (current.recording) vm.stopVideo(c)
                    else if (current.settings.microphone && !allowed(Manifest.permission.RECORD_AUDIO)) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    else vm.startVideo(c, current.settings.microphone)
                } else vm.capture(c)
            }
            Unit
        }
    }
    // Metering changes only invalidate the viewfinder, not all of the controls.
    val chrome = remember(s, state.capabilities, state.busy, state.processing, state.stage, state.thumbnail,
        state.shutterCount, state.recording, state.stopping, state.seconds, state.lastMedia, state.lastPhoto, state.lastIsVideo) {
        state.copy(scene = SceneStats())
    }
    Surface(color = Ink, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        if (!permitted) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.CameraAlt, null, tint = Accent, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.camera_permission), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.permission_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                Button(onClick = {
                    cameraPermissions.launch(if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else arrayOf(Manifest.permission.CAMERA))
                }) { Text(stringResource(R.string.allow_camera)) }
                if (denied) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.open_settings)) }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth > maxHeight
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        CameraPreviewPane(state, preview, controller, vm, activeTool, { activeTool = null }, Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(230.dp).fillMaxHeight().padding(horizontal = 8.dp)) {
                            TopBar(chrome, { customize = true }, { vm.change { it.copy(torch = !it.torch) } })
                            Controls(chrome, activeTool, { activeTool = if (activeTool == it) null else it }, vm,
                                capture, { vm.change { it.copy(front = !it.front, zoom = 1f, torch = false, focus = null) } },
                                Modifier.weight(1f), true)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(chrome, { customize = true }, { vm.change { it.copy(torch = !it.torch) } })
                        CameraPreviewPane(state, preview, controller, vm, activeTool, { activeTool = null }, Modifier.weight(1f).fillMaxWidth())
                        Controls(chrome, activeTool, { activeTool = if (activeTool == it) null else it }, vm,
                            capture, { vm.change { it.copy(front = !it.front, zoom = 1f, torch = false, focus = null) } }, Modifier.fillMaxWidth(), false)
                    }
                }
            }
        }
        state.error?.let { error ->
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        if (!state.capabilities.ready && permitted) TextButton(onClick = { vm.clearError(); retry++ }) { Text(stringResource(R.string.retry)) }
                        IconButton(onClick = vm::clearError) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                    }
                }
            }
        }
    }
    if (customize) {
        ModalBottomSheet(onDismissRequest = { customize = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Customize(state, vm, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp))
        }
    }
    state.review?.let { review -> ReviewSheet(review, vm::dismissReview) }
}

@Composable
private fun TopBar(state: CameraUiState, customize: () -> Unit, torch: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("OMNI", fontSize = 14.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (state.capabilities.flash) IconToggleButton(checked = state.settings.torch, onCheckedChange = { torch() }, enabled = !state.busy) {
            Icon(if (state.settings.torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                stringResource(R.string.torch), Modifier.size(21.dp), tint = if (state.settings.torch) Accent else Color.White)
        }
        IconButton(onClick = customize, enabled = !state.busy && !state.recording) {
            Icon(Icons.Outlined.Tune, stringResource(R.string.settings), Modifier.size(21.dp))
        }
    }
}

@Composable
private fun CameraPreviewPane(state: CameraUiState, view: PreviewView, controller: CameraController?, vm: CameraViewModel,
    tool: String?, close: () -> Unit, modifier: Modifier) {
    Box(modifier.background(Color.Black)) {
        Viewfinder(state, view, controller, vm, Modifier.fillMaxSize())
        androidx.compose.animation.AnimatedVisibility(tool != null, enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(140)) { it / 3 }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = Color(0xF217181E), shape = RoundedCornerShape(22.dp),
                modifier = Modifier.padding(12.dp).widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 215.dp)
                    .border(.5.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(22.dp))) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(toolLabel(tool ?: "light")), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = close, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, stringResource(R.string.close), Modifier.size(18.dp)) }
                    }
                    ToolPanel(tool, state, vm)
                }
            }
        }
    }
}

@Composable
private fun Viewfinder(state: CameraUiState, view: PreviewView, controller: CameraController?, vm: CameraViewModel, modifier: Modifier) {
    var focus by remember { mutableStateOf<Offset?>(null) }
    var locked by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(focus) { if (focus != null) { delay(2200); focus = null } }
    BoxWithConstraints(modifier.background(Color.Black)) {
        val aspect = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4f / 3f else 3f / 4f
        val frameWidth = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.size(frameWidth, frameWidth / aspect).align(Alignment.Center).clip(RoundedCornerShape(8.dp))) {
        Box(Modifier.fillMaxSize().pointerInput(controller) {
            detectTransformGestures { _, _, zoom, _ ->
                if (!latest.busy && latest.capabilities.ready)
                    vm.change { it.copy(zoom = (it.zoom * zoom).coerceIn(latest.capabilities.minZoom, latest.capabilities.maxZoom)) }
            }
        }.pointerInput(controller) {
            detectTapGestures(onTap = { if (!latest.busy && latest.settings.focus == null) { focus = it; locked = false; controller?.focus(it.x, it.y) } },
                onLongPress = { if (!latest.busy && latest.settings.focus == null) { focus = it; locked = true; controller?.focus(it.x, it.y, true) } })
        }) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            if (state.settings.grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.16f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                }
            }
            focus?.let { point -> Canvas(Modifier.fillMaxSize()) {
                val radius = 24.dp.toPx()
                drawRoundRect(if (locked) Accent else Color.White, point - Offset(radius, radius),
                    androidx.compose.ui.geometry.Size(radius * 2, radius * 2), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            } }
        }
        Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = when {
                state.recording -> stringResource(R.string.recording_time, "%02d:%02d".format(state.seconds / 60, state.seconds % 60))
                !state.capabilities.ready -> stringResource(R.string.warming_up)
                state.settings.mode == CaptureMode.VIDEO -> "${state.capabilities.actualResolution}p · ${state.capabilities.actualFps.takeIf { it > 0 } ?: "…"} fps · ${state.capabilities.actualProfile.name}"
                state.capabilities.activeExtension != 0 -> stringResource(R.string.device_processing)
                state.settings.mode == CaptureMode.NIGHT -> stringResource(R.string.night)
                state.settings.processing.hdr && state.settings.mode == CaptureMode.PHOTO -> stringResource(R.string.hold_steady)
                state.scene.background - state.scene.subject > 0.15f && state.scene.faces > 0 -> stringResource(R.string.backlit)
                state.scene.average < 0.25f -> stringResource(R.string.low_light)
                state.scene.faces > 0 -> stringResource(R.string.portrait_detected)
                else -> stringResource(R.string.ready)
            }
            Surface(color = if (state.recording) Color(0xFF852A38) else Color.Black.copy(alpha = 0.5f), shape = CircleShape) {
                Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (state.settings.histogram && state.capabilities.actualProfile != VideoProfile.HLG10) {
            Canvas(Modifier.align(Alignment.BottomStart).padding(16.dp).size(110.dp, 45.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(6.dp)) {
                val bins = state.scene.histogram
                val w = size.width / bins.size
                bins.forEachIndexed { i, value -> drawLine(Accent.copy(alpha = 0.8f), Offset(i * w, size.height), Offset(i * w, size.height * (1 - value)), w, StrokeCap.Butt) }
            }
        }
        if (state.capabilities.maxZoom > 1f) {
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.5f, 1f, 2f, 3f).filter { it in state.capabilities.minZoom..state.capabilities.maxZoom }.forEach { ratio ->
                    Surface(onClick = { vm.change { it.copy(zoom = ratio) } }, color = if (abs(state.settings.zoom - ratio) < 0.1f) Accent else Color.Black.copy(alpha = 0.6f),
                        contentColor = if (abs(state.settings.zoom - ratio) < 0.1f) Ink else Color.White, shape = CircleShape,
                        enabled = !state.busy) { Text(if (ratio < 1f) "0.5×" else "${ratio.toInt()}×", Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (state.processing > 0) {
            Surface(color = Color.Black.copy(alpha = .65f), shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Accent, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(7.dp)); Text(state.processing.toString(), fontSize = 10.sp)
                }
            }
        }
        val flash = remember { Animatable(0f) }
        LaunchedEffect(state.shutterCount) {
            if (state.shutterCount > 0) { flash.snapTo(.22f); flash.animateTo(0f, tween(160)) }
        }
        if (flash.value > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }
    }
}

@Composable
private fun Controls(state: CameraUiState, tool: String?, selectTool: (String) -> Unit, vm: CameraViewModel,
    capture: () -> Unit, flip: () -> Unit, modifier: Modifier, wide: Boolean) {
    val context = LocalContext.current
    val controlsEnabled = !state.busy && !state.recording
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (wide) Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
            CaptureMode.entries.forEach { mode ->
                val selected = state.settings.mode == mode
                TextButton(onClick = { vm.change { it.copy(mode = mode, manualSensor = false, focus = null, torch = false) } },
                    enabled = controlsEnabled, contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (selected) Accent else Color.White.copy(alpha = .55f))) {
                    Text(stringResource(modeLabel(mode)), fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        val quickTools = if (state.settings.mode == CaptureMode.VIDEO) listOf("video", "light", "focus", "color")
            else state.settings.favoriteTools + if (state.settings.mode == CaptureMode.PRO) listOf("sensor") else emptyList()
        Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            quickTools.forEach { id ->
                IconToggleButton(checked = tool == id, onCheckedChange = { selectTool(id) },
                    enabled = !state.busy && (!state.recording || id != "video"), modifier = Modifier.size(44.dp)) {
                    Icon(toolIcon(id), stringResource(toolLabel(id)), Modifier.size(21.dp),
                        tint = if (tool == id) Accent else Color.White.copy(alpha = .7f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).border(.5.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(11.dp))
                .clickable(enabled = state.lastMedia != null, onClickLabel = stringResource(R.string.gallery)) {
                    if (state.lastPhoto != null) vm.openReview() else state.lastMedia?.let { openMedia(context, it, state.lastIsVideo) }
                }, contentAlignment = Alignment.Center) {
                val thumbnail = state.thumbnail
                if (thumbnail != null && !state.lastIsVideo) Image(thumbnail.asImageBitmap(), stringResource(R.string.gallery), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(if (state.lastIsVideo) Icons.Outlined.PlayArrow else Icons.Outlined.PhotoLibrary, stringResource(R.string.gallery), Modifier.size(21.dp), tint = Color.White.copy(alpha = .65f))
                if (state.processing > 0) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            }
            Shutter(state, capture)
            IconButton(onClick = flip, enabled = controlsEnabled && state.capabilities.ready,
                modifier = Modifier.size(46.dp).background(Color.White.copy(alpha = .06f), CircleShape)) {
                Icon(Icons.Outlined.Cameraswitch, stringResource(R.string.switch_camera), Modifier.size(24.dp))
            }
        }
        Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(state.processing > 0, enter = fadeIn(), exit = fadeOut()) {
                Text(stringResource(stageLabel(state.stage)), fontSize = 10.sp, color = Color.White.copy(alpha = .5f))
            }
        }
        if (wide) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Shutter(state: CameraUiState, click: () -> Unit) {
    val recording = state.recording
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(state.shutterCount) { if (state.shutterCount > 0) { pulse.snapTo(.86f); pulse.animateTo(1f, spring(dampingRatio = .72f, stiffness = 550f)) } }
    val size by animateDpAsState(if (recording) 28.dp else 61.dp, tween(180), label = "shutter")
    val color by animateColorAsState(if (state.settings.mode == CaptureMode.VIDEO) Color(0xFFFD556B) else Color.White, tween(160), label = "shutterColor")
    val label = stringResource(if (recording) R.string.stop_recording else if (state.settings.mode == CaptureMode.VIDEO) R.string.start_recording else R.string.capture)
    val available = state.capabilities.ready && !state.busy && !state.stopping && (state.recording || state.settings.mode == CaptureMode.VIDEO || state.processing < 2)
    Box(Modifier.size(79.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = if (available) .95f else .35f), CircleShape)
        .clickable(enabled = available, onClickLabel = label, onClick = click)
        .semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).scale(pulse.value).clip(if (recording) RoundedCornerShape(7.dp) else CircleShape).background(color.copy(alpha = if (available) 1f else .45f)))
    }
}

@Composable
private fun ToolPanel(tool: String?, state: CameraUiState, vm: CameraViewModel) {
    val s = state.settings; val p = s.processing; val c = state.capabilities
    when (tool) {
        "light" -> {
            ValueControl(R.string.exposure, s.exposure, c.exposureRange, "%.1f EV".format(s.exposure)) { v -> vm.change { it.copy(exposure = v) } }
            if (s.mode != CaptureMode.VIDEO) ValueControl(R.string.strength, p.strength) { v -> vm.change { it.copy(processing = p.copy(strength = v)) } }
        }
        "portrait" -> {
            ValueControl(R.string.face_light, p.faceLight) { v -> vm.change { it.copy(processing = p.copy(faceLight = v)) } }
            ValueControl(R.string.background_light, p.backgroundLight, -1f..1f) { v -> vm.change { it.copy(processing = p.copy(backgroundLight = v)) } }
        }
        "detail" -> {
            ValueControl(R.string.denoise, p.denoise) { v -> vm.change { it.copy(processing = p.copy(denoise = v)) } }
            ValueControl(R.string.sharpness, p.detail) { v -> vm.change { it.copy(processing = p.copy(detail = v)) } }
        }
        "color" -> {
            Choices(stringResource(R.string.white_balance), c.whiteBalances, s.whiteBalance, { stringResource(wbLabel(it)) }) { v -> vm.change { it.copy(whiteBalance = v) } }
            if (s.mode != CaptureMode.VIDEO) ValueControl(R.string.warmth, p.warmth, -1f..1f) { v -> vm.change { it.copy(processing = p.copy(warmth = v)) } }
        }
        "focus" -> {
            if (c.manualFocus) {
                Toggle(R.string.auto_focus, s.focus == null) { auto -> vm.change { it.copy(focus = if (auto) null else 0f) } }
                if (s.focus != null) ValueControl(R.string.focus, s.focus) { v -> vm.change { it.copy(focus = v) } }
            } else Text(stringResource(R.string.unavailable), fontSize = 12.sp)
        }
        "sensor" -> {
            if (c.manualSensor) {
                Toggle(R.string.manual_exposure, s.manualSensor) { v -> vm.change { it.copy(manualSensor = v) } }
                if (s.manualSensor) {
                    ValueControl(R.string.iso, s.iso.toFloat(), c.isoRange.first.toFloat()..c.isoRange.last.toFloat(), s.iso.toString()) { v -> vm.change { it.copy(iso = v.roundToInt()) } }
                    val minLog = ln(c.shutterRange.first.toDouble()).toFloat(); val maxLog = ln(c.shutterRange.last.toDouble()).toFloat()
                    ValueControl(R.string.shutter, ln(s.shutterNanos.coerceIn(c.shutterRange).toDouble()).toFloat(), minLog..maxLog,
                        "1/${(1_000_000_000.0 / s.shutterNanos).roundToInt().coerceAtLeast(1)}s") { v -> vm.change { it.copy(shutterNanos = exp(v.toDouble()).toLong()) } }
                }
            } else Text(stringResource(R.string.unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.manualFocus) {
                Toggle(R.string.auto_focus, s.focus == null) { auto -> vm.change { it.copy(focus = if (auto) null else 0f) } }
                if (s.focus != null) ValueControl(R.string.focus, s.focus) { v -> vm.change { it.copy(focus = v) } }
            }
        }
        "video" -> VideoSettings(state, vm)
    }
    if (tool in listOf("portrait", "detail") || (tool in listOf("light", "color") && s.mode != CaptureMode.VIDEO)) {
        Text(stringResource(R.string.preview_note), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValueControl(label: Int, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f,
    display: String = "${(value * 100).roundToInt()}%", changed: (Float) -> Unit) {
    val description = stringResource(label)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(display, fontSize = 11.sp, color = Accent)
    }
    val valid = range.endInclusive > range.start
    val sliderRange = if (valid) range else 0f..1f
    Slider(value = value.coerceIn(sliderRange), onValueChange = changed, valueRange = sliderRange,
        enabled = valid, modifier = Modifier.height(36.dp).semantics { contentDescription = description })
}

@Composable
private fun Toggle(label: Int, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
private fun <T> Choices(title: String, values: List<T>, selected: T, label: @Composable (T) -> String, change: (T) -> Unit) {
    Text(title, Modifier.padding(top = 12.dp, bottom = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v -> FilterChip(selected = v == selected, onClick = { change(v) }, label = { Text(label(v), fontSize = 12.sp) }) }
    }
}

@Composable
private fun VideoSettings(state: CameraUiState, vm: CameraViewModel) {
    val s = state.settings; val c = state.capabilities
    val profiles = listOf(VideoProfile.SDR, VideoProfile.FLAT) + if (c.hlg10) listOf(VideoProfile.HLG10) else emptyList()
    Choices(stringResource(R.string.video_profile), profiles, s.profile, { stringResource(when (it) { VideoProfile.SDR -> R.string.sdr; VideoProfile.FLAT -> R.string.flat; VideoProfile.HLG10 -> R.string.hlg }) }) {
        v -> vm.change { it.copy(profile = v) }
    }
    Text(stringResource(R.string.log_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Choices(stringResource(R.string.resolution), c.resolutions, s.resolution, { "${it}p" }) { v -> vm.change { it.copy(resolution = v) } }
    Choices(stringResource(R.string.frame_rate), c.frameRates, s.fps, { "$it fps" }) { v -> vm.change { it.copy(fps = v) } }
    ValueControl(R.string.bitrate, s.bitrateMbps.toFloat(), 4f..100f, "${s.bitrateMbps} Mbps") { v -> vm.change { it.copy(bitrateMbps = v.roundToInt()) } }
    Toggle(R.string.microphone, s.microphone) { v -> vm.change { it.copy(microphone = v) } }
}

@Composable
private fun Customize(state: CameraUiState, vm: CameraViewModel, modifier: Modifier) {
    val s = state.settings; val p = s.processing
    Column(modifier) {
        Text(stringResource(R.string.settings), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.offline_models), Modifier.padding(vertical = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Toggle(R.string.smart_processing, p.enabled) { v -> vm.change { it.copy(processing = p.copy(enabled = v)) } }
        Toggle(R.string.hdr_fusion, p.hdr, state.capabilities.vendorHdr || state.capabilities.exposureRange.endInclusive - state.capabilities.exposureRange.start >= 1f) { v -> vm.change { it.copy(processing = p.copy(hdr = v)) } }
        Text(stringResource(R.string.hdr_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Choices(stringResource(R.string.quality), ProcessingQuality.entries, p.quality, { stringResource(when (it) { ProcessingQuality.FAST -> R.string.quality_fast; ProcessingQuality.BALANCED -> R.string.quality_balanced; ProcessingQuality.HIGH -> R.string.quality_high }) }) {
            v -> vm.change { it.copy(processing = p.copy(quality = v)) }
        }
        Text(stringResource(R.string.quality_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Toggle(R.string.keep_original, p.keepOriginal) { v -> vm.change { it.copy(processing = p.copy(keepOriginal = v)) } }
        Toggle(R.string.mirror_selfie, s.mirrorSelfie) { v -> vm.change { it.copy(mirrorSelfie = v) } }
        Toggle(R.string.grid, s.grid) { v -> vm.change { it.copy(grid = v) } }
        Toggle(R.string.histogram, s.histogram) { v -> vm.change { it.copy(histogram = v) } }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.favorite_tools), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.favorite_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ordered = s.favoriteTools + SettingsStore.TOOLS.filterNot { it in s.favoriteTools }
        ordered.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in s.favoriteTools, onCheckedChange = { checked -> vm.change {
                    it.copy(favoriteTools = if (checked) it.favoriteTools + id else it.favoriteTools.filterNot { t -> t == id })
                } })
                Icon(toolIcon(id), null, Modifier.size(18.dp)); Spacer(Modifier.width(12.dp))
                Text(stringResource(toolLabel(id)), Modifier.weight(1f), fontSize = 13.sp)
                IconButton(onClick = { vm.change {
                    val list = it.favoriteTools.toMutableList(); val index = list.indexOf(id)
                    if (index > 0) { list.removeAt(index); list.add(index - 1, id) }
                    it.copy(favoriteTools = list)
                } }, enabled = s.favoriteTools.indexOf(id) > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_earlier), Modifier.size(18.dp)) }
            }
        }
        if (s.mode == CaptureMode.VIDEO) { HorizontalDivider(Modifier.padding(vertical = 16.dp)); VideoSettings(state, vm) }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.device_support), fontWeight = FontWeight.SemiBold)
        listOf(R.string.manual_sensor_support to state.capabilities.manualSensor,
            R.string.raw_support to state.capabilities.raw, R.string.hlg to state.capabilities.hlg10).forEach { (label, available) ->
            Row(Modifier.padding(vertical = 8.dp)) {
                Text(stringResource(label), Modifier.weight(1f), fontSize = 11.sp)
                Text(stringResource(if (available) R.string.available else R.string.unavailable), fontSize = 11.sp, color = if (available) Accent else Color.Gray)
            }
        }
        TextButton(onClick = vm::reset) { Text(stringResource(R.string.reset)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(review: PhotoReview, dismiss: () -> Unit) {
    val context = LocalContext.current
    var before by remember(review.uri) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.photo_saved), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.capture_summary, review.fusedFrames, review.milliseconds / 1000f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
            }
            Spacer(Modifier.height(16.dp))
            Image((if (before) review.before else review.image).asImageBitmap(), stringResource(if (before) R.string.before else R.string.after),
                Modifier.fillMaxWidth().heightIn(max = 450.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Fit)
            Choices(stringResource(R.string.compare), listOf(true, false), before,
                { stringResource(if (it) R.string.before else R.string.after) }) { before = it }
            if (!review.modelsAvailable) Text(stringResource(R.string.model_fallback), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { openMedia(context, review.uri, false) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.gallery))
                }
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, review.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.share))) }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share)) }
            }
        }
    }
}

private fun openMedia(context: android.content.Context, uri: Uri, video: Boolean) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (video) "video/mp4" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}
private fun modeLabel(mode: CaptureMode) = when (mode) { CaptureMode.PHOTO -> R.string.photo; CaptureMode.VIDEO -> R.string.video; CaptureMode.NIGHT -> R.string.night; CaptureMode.PRO -> R.string.pro }
private fun toolLabel(id: String) = when (id) { "portrait" -> R.string.portrait; "detail" -> R.string.detail; "color" -> R.string.color; "sensor" -> R.string.sensor; "focus" -> R.string.focus; "video" -> R.string.video; else -> R.string.light }
private fun toolIcon(id: String): ImageVector = when (id) { "portrait" -> Icons.Outlined.Face; "detail" -> Icons.Outlined.Details; "color" -> Icons.Outlined.Palette; "sensor" -> Icons.Outlined.Tune; "focus" -> Icons.Outlined.CenterFocusStrong; "video" -> Icons.Outlined.Videocam; else -> Icons.Outlined.WbSunny }
private fun wbLabel(wb: WhiteBalance) = when (wb) { WhiteBalance.AUTO -> R.string.wb_auto; WhiteBalance.DAYLIGHT -> R.string.wb_daylight; WhiteBalance.CLOUDY -> R.string.wb_cloudy; WhiteBalance.TUNGSTEN -> R.string.wb_tungsten }

private fun stageLabel(stage: DevelopStage?) = when (stage) {
    DevelopStage.DECODING -> R.string.processing_decode
    DevelopStage.ALIGNING -> R.string.processing_align
    DevelopStage.PORTRAIT -> R.string.processing_portrait
    DevelopStage.DEVELOPING -> R.string.processing_develop
    DevelopStage.SAVING -> R.string.processing_save
    null -> R.string.processing
}
