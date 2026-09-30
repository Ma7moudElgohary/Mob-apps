package com.ma7moud.reality3d.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.remote.RemoteSettings
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.mesh.GameReadyPack
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.quality.QualityReport
import com.ma7moud.reality3d.scan.CoachTip
import com.ma7moud.reality3d.scan.CoverageTracker
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
import com.ma7moud.reality3d.scan.ScanCoach
import com.ma7moud.reality3d.scan.ScanGuide
import com.ma7moud.reality3d.scan.ScanPhase
import com.ma7moud.reality3d.scan.ScanStatus
import com.ma7moud.reality3d.scan.SurfaceCompleteness
import com.ma7moud.reality3d.scan.VoiceGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Panel = Color(0xE60B111C)
private val Covered = Color(0xFF5BE49B)
private val Warning = Color(0xFFFFC857)

/** The 360° scan: camera permission and ARCore checks, guided capture, then the finished model. */
@Composable
fun ScanScreen(viewModel: ScanViewModel, useGlViewer: Boolean, onClose: () -> Unit, onViewInAr: (PreviewModel) -> Unit = {}) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val ask by viewModel.needsComputer.collectAsStateWithLifecycle()
    val notice by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onPermissionResult)
    fun close() {
        viewModel.reset()
        onClose()
    }
    fun retry() {
        if (activity != null) viewModel.retry(activity)
    }
    BackHandler { close() }
    LaunchedEffect(Unit) {
        if (!context.hasCamera()) permission.launch(Manifest.permission.CAMERA)
    }
    // Checks ARCore when the screen opens and whenever it comes back: from the permission dialog, from
    // the app's settings, or from installing ARCore in the Play Store.
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null && context.hasCamera()) viewModel.check(activity)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val state = screen) {
            ScanScreenState.Scanning -> viewModel.engine?.let {
                CameraScan(
                    it,
                    onBuild = viewModel::build,
                    onComputer = viewModel::buildOnComputer,
                    onRetry = { retry() },
                    onClose = { close() },
                    report = viewModel::scanReport,
                    notice = notice?.takeIf { (_, isError) -> isError }?.first,
                )
                if (ask != null) {
                    ServerDialog(RemoteSettings(), ask, onDismiss = viewModel::dismissComputer, onSave = viewModel::saveComputer)
                }
            }
            is ScanScreenState.Remote -> Centered(onClose = null) {
                KeepScreenOn(true)
                Text("Building on your computer", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                val fraction = state.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(0.7f))
                else LinearProgressIndicator(Modifier.fillMaxWidth(0.7f))
                Text(state.label, textAlign = TextAlign.Center)
                Text(
                    "This takes a few minutes. Keep this screen open; it stays awake, and the model appears here when it is ready.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::cancelComputer) { Text("Cancel") }
            }
            is ScanScreenState.Building -> Centered(onClose = null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(state.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text("This takes a few seconds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ScanScreenState.Result -> ScanResult(
                state.capture,
                viewModel,
                useGlViewer,
                onAddViews = viewModel::addMoreViews,
                onViewInAr = { onViewInAr(PreviewModel(state.capture.mesh, state.capture.texture, state.capture.metersPerUnit, "Your scan")) },
                onScanAgain = { retry() },
                onDone = { close() },
            )
            ScanScreenState.NeedsPermission -> Centered(onClose = { close() }) {
                Text("The scan needs the camera", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ARCore follows the phone through the camera while you walk around the object.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                TextButton(onClick = { context.openAppSettings() }) { Text("Open app settings") }
            }
            ScanScreenState.Checking -> Centered(onClose = { close() }) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Checking ARCore…")
            }
            ScanScreenState.Installing -> Centered(onClose = { close() }) {
                Text("Installing Google Play Services for AR", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    "Finish the install in the Play Store, then come back here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is ScanScreenState.Unsupported -> Centered(onClose = { close() }) {
                Text(state.reason, textAlign = TextAlign.Center)
            }
            is ScanScreenState.Failed -> Centered(onClose = { close() }) {
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { retry() }) { Text("Scan again") }
                CopyReportButton(viewModel::scanReport)
            }
        }
    }
}

/** Copies the scan report to the clipboard, so it can be pasted into a message about a scan that went wrong. */
@Composable
private fun CopyReportButton(report: () -> String, color: Color = Color.Unspecified) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2500)
            copied = false
        }
    }
    TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Reality3D scan report", report()))
        copied = true
    }) { Text(if (copied) "Report copied" else "Copy report", color = color) }
}

@Composable
private fun Centered(onClose: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        if (onClose != null) TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Close") }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun CameraScan(
    engine: ScanEngine,
    onBuild: () -> Unit,
    onComputer: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    report: () -> String,
    notice: String?,
) {
    val status by engine.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> engine.resume()
                Lifecycle.Event.ON_PAUSE -> engine.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            engine.pause()
        }
    }
    val context = LocalContext.current
    val preferences = remember { ScanPreferences(context) }
    var voiceOn by remember { mutableStateOf(preferences.voice) }
    var showTips by remember { mutableStateOf(!preferences.tipsSeen) }

    // Leaving or starting over drops the scan, so once there is something worth keeping the person is asked first.
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val worthKeeping = status.phase == ScanPhase.SCANNING && status.coverageFraction >= WORTH_KEEPING
    fun leave() {
        if (worthKeeping) confirm = Confirm.LEAVE else onClose()
    }
    fun restart() {
        if (worthKeeping) confirm = Confirm.RESTART else engine.restart()
    }
    BackHandler {
        if (showTips) {
            showTips = false
            preferences.tipsSeen = true
        } else {
            leave()
        }
    }

    // The guidance is said out loud too, so the person can keep their eyes on the object while they walk.
    val guidance = ScanGuide.guidance(status)
    val voice = remember { CoachVoice(context) }
    DisposableEffect(voice) { onDispose { voice.close() } }
    val voiceGuide = remember(voice) { VoiceGuide(say = voice::say) }
    val latestGuidance by rememberUpdatedState(guidance)
    LaunchedEffect(voiceOn, showTips, voiceGuide) {
        // The how-to card is read, not listened to.
        if (voiceOn && !showTips) {
            while (true) {
                voiceGuide.onGuidance(latestGuidance)
                delay(300)
            }
        } else {
            voice.stop()
        }
    }

    // A tap of the phone for every new side covered, and a longer one when views plus measured geometry are enough.
    val haptics = LocalHapticFeedback.current
    val coveredSides = status.coverage.count { it }
    val enough = ScanCoach.isEnough(status.coverage, status.surfaceCompleteness)
    var previousSides by remember { mutableIntStateOf(coveredSides) }
    var wasEnough by remember { mutableStateOf(enough) }
    LaunchedEffect(coveredSides, enough) {
        if (enough && !wasEnough) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        else if (coveredSides > previousSides) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        previousSides = coveredSides
        wasEnough = enough
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { engine.createView(it) }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leave() }) { Text("Close", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showTips = true }) { Text("Tips", color = Color.White) }
                TextButton(onClick = {
                    voiceOn = !voiceOn
                    preferences.voice = voiceOn
                }) { Text(if (voiceOn) "Voice: on" else "Voice: off", color = Color.White) }
                CopyReportButton(report, color = Color.White)
            }
            GuidanceCard(status, rememberStableTip(guidance))
            Spacer(Modifier.weight(1f))
            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp),
                )
            }
            ScanPanel(status, engine, onBuild, onComputer, onRetry, onRestart = { restart() })
        }
        if (showTips) {
            TipsCard(onDone = {
                showTips = false
                preferences.tipsSeen = true
            })
        }
    }
    confirm?.let { what ->
        val leaving = what == Confirm.LEAVE
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (leaving) "Leave the scan?" else "Start over?") },
            text = { Text("What you have scanned so far will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (leaving) onClose() else engine.restart()
                }) { Text(if (leaving) "Leave" else "Start over") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep scanning") } },
        )
    }
}

private enum class Confirm { LEAVE, RESTART }

/** Coverage from which the scan is worth asking about before it is thrown away. */
private const val WORTH_KEEPING = 0.15f

/**
 * The tip to show: a new one appears once it has lasted a moment, so advice that flickers as the phone moves
 * doesn't flicker on the screen.
 */
@Composable
private fun rememberStableTip(tip: CoachTip): CoachTip {
    var shown by remember { mutableStateOf(tip) }
    LaunchedEffect(tip.text, tip.kind) {
        if (tip.text != shown.text) delay(TIP_HOLD_MS)
        shown = tip
    }
    return shown
}

private const val TIP_HOLD_MS = 700L

/** The one thing to do now, big, with the step the scan is at. */
@Composable
private fun GuidanceCard(status: ScanStatus, tip: CoachTip) {
    val step = ScanGuide.step(status.phase)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (step > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (number in 1..ScanGuide.STEPS) {
                    Box(
                        Modifier
                            .size(if (number == step) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (number <= step) Covered else Color.White.copy(alpha = 0.25f)),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("Step $step of ${ScanGuide.STEPS} · ${ScanGuide.STEP_TITLES[step - 1]}", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            tip.text,
            color = when (tip.kind) {
                CoachTip.Kind.WARNING -> Warning
                CoachTip.Kind.DONE -> Covered
                CoachTip.Kind.INFO -> Color.White
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (status.phase != ScanPhase.FAILED) status.message?.let { Text(it, color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodyMedium) }
    }
}

/** How to scan, in a few plain lines; shown the first time and from the Tips button. */
@Composable
private fun TipsCard(onDone: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            // Taps on the dimmed camera don't reach the buttons underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF101827)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("How to scan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            for ((number, text) in TIPS.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${number + 1}", color = Covered, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                "The phone talks you through it out loud and taps when a new side is covered. Voice can be turned off at the top.",
                color = Color(0xFFB8C4D9),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Got it") }
        }
    }
}

private val TIPS = listOf(
    "Put the object on a table, on a newspaper or a patterned cloth, in good light.",
    "Point the phone at it and tap the object. Pick a size so the green box is a little bigger than the object.",
    "Walk slowly around it, keeping it in view, about an arm's length away. Do it twice: first with the phone at the object's height, then a bit higher, looking down.",
    "When the phone says that's enough, tap Build model. Going around once more, higher, cleans up the top.",
)

/** Object sizes to start from: what the person can picture is easier than centimetres. */
private val SIZE_PRESETS = listOf("Small · in a hand" to 0.2f, "Medium · shoebox" to 0.4f, "Large · a chair" to 0.8f)

@Composable
private fun ScanPanel(status: ScanStatus, engine: ScanEngine, onBuild: () -> Unit, onComputer: () -> Unit, onRetry: () -> Unit, onRestart: () -> Unit) {
    when (status.phase) {
        ScanPhase.READY -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                Text("Object size", color = Color.White, modifier = Modifier.weight(1f))
                Text("${(status.boxSize * 100).roundToInt()} cm", color = Color(0xFFB8C4D9))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, size) in SIZE_PRESETS) {
                    FilterChip(
                        selected = abs(status.boxSize - size) < 0.03f,
                        onClick = { engine.setBoxSize(size) },
                        label = { Text(label) },
                    )
                }
            }
            Slider(
                value = status.boxSize,
                onValueChange = engine::setBoxSize,
                valueRange = ScanStatus.MIN_BOX_SIZE..ScanStatus.MAX_BOX_SIZE,
            )
            Text("Tap the object again to move the box.", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
            Button(onClick = engine::startScanning, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start scan") }
        }
        ScanPhase.SCANNING -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val enough = ScanCoach.isEnough(status.coverage, status.surfaceCompleteness)
            val lap = ScanCoach.currentLap(status.coverage)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverageRadar(status, Modifier.size(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val (done, sides) = ScanCoach.sidesCovered(status.coverage, lap)
                    val complete = status.coverageFraction >= ScanCoach.COMPLETE &&
                        (status.surfaceCompleteness ?: 0f) >= SurfaceCompleteness.GOOD
                    Text(
                        when {
                            complete -> "Everything is covered"
                            enough -> "Enough to build"
                            else -> "Lap $lap of ${ScanCoach.MAIN_LAPS} · $done of $sides sides"
                        },
                        color = if (enough) Covered else Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = { ScanCoach.progress(status.coverage) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (enough) Covered else MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.18f),
                    )
                    Text("Covered ${(status.coverageFraction * 100).roundToInt()}%", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
                    status.surfaceCompleteness?.let {
                        Text("Measured shape ${(it * 100).roundToInt()}%", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${status.photos} photos · ${status.depthFrames} depth maps", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
                }
            }
            val surface = status.surfaceCompleteness
            val canBuild = status.coverageFraction >= MIN_COVERAGE &&
                status.depthFrames > 0 &&
                surface != null &&
                surface >= SurfaceCompleteness.MIN_BUILD
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRestart, modifier = Modifier.weight(0.4f).height(52.dp)) { Text("Restart") }
                Button(
                    onClick = onBuild,
                    enabled = canBuild,
                    modifier = Modifier.weight(0.6f).height(52.dp),
                    colors = if (enough) ButtonDefaults.buttonColors(containerColor = Covered, contentColor = Color(0xFF07130D)) else ButtonDefaults.buttonColors(),
                ) { Text("Build model", style = MaterialTheme.typography.titleMedium) }
            }
            if (!canBuild) {
                Text(
                    when {
                        status.coverageFraction < MIN_COVERAGE -> "Go around at least once before building."
                        status.depthFrames == 0 || surface == null -> "Keep scanning until enough of the object's shape has been measured."
                        else -> "Only ${(surface * 100).roundToInt()}% of the shape is measured. Add a few more views before building."
                    },
                    color = Color(0xFFB8C4D9),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(
                onClick = onComputer,
                enabled = status.photos >= MIN_PHOTOS_FOR_COMPUTER,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Build on my computer · best quality") }
            when {
                status.photos < MIN_PHOTOS_FOR_COMPUTER ->
                    Text("Needs at least $MIN_PHOTOS_FOR_COMPUTER photos: keep going around.", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
                status.photos < GOOD_PHOTOS_FOR_COMPUTER ->
                    Text("$GOOD_PHOTOS_FOR_COMPUTER or more photos give a cleaner model.", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
            }
        }
        ScanPhase.FAILED -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
        else -> Unit
    }
}

private const val MIN_COVERAGE = 0.3f
private const val MIN_PHOTOS_FOR_COMPUTER = 12
private const val GOOD_PHOTOS_FOR_COMPUTER = 40

/** Top-down map of the covered directions: outer ring low views, inner rings higher, the centre from above. */
@Composable
private fun CoverageRadar(status: ScanStatus, modifier: Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        val ringWidth = radius * 0.2f
        val sweep = 360f / CoverageTracker.SEGMENTS
        for (ring in 0 until CoverageTracker.RINGS) {
            val r = radius - ringWidth / 2 - ring * (ringWidth + radius * 0.06f)
            for (segment in 0 until CoverageTracker.SEGMENTS) {
                val covered = status.coverage[ring * CoverageTracker.SEGMENTS + segment]
                drawArc(
                    color = if (covered) Covered else Color.White.copy(alpha = 0.18f),
                    startAngle = segment * sweep - 90f + 2f,
                    sweepAngle = sweep - 4f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = ringWidth),
                )
            }
        }
        val topCovered = status.coverage[CoverageTracker.TOP_CELL]
        drawCircle(if (topCovered) Covered else Color.White.copy(alpha = 0.18f), radius = radius * 0.16f, center = center)
        val azimuth = status.phoneAzimuth
        val ring = status.phoneRing
        if (azimuth != null && ring != null) {
            val r = if (ring >= CoverageTracker.RINGS) 0f else radius - ringWidth / 2 - ring * (ringWidth + radius * 0.06f)
            val angle = Math.toRadians((azimuth - 90f).toDouble())
            val dot = Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat())
            drawCircle(Color.White, radius = ringWidth * 0.55f, center = dot)
        }
    }
}

@Composable
private fun ScanResult(
    capture: ScanCapture,
    viewModel: ScanViewModel,
    useGlViewer: Boolean,
    onAddViews: () -> Unit,
    onViewInAr: () -> Unit,
    onScanAgain: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saveGlb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GLB.mimeType), viewModel::savePending)
    val saveStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.STL.mimeType), viewModel::savePending)
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.OBJ.mimeType), viewModel::savePending)
    val savePly = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PLY.mimeType), viewModel::savePending)

    fun shareScreenshot(bitmap: Bitmap) {
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { Exporter.png(bitmap) }
                context.startActivity(Exporter.shareIntent(context, Exporter.screenshotName(), "image/png", bytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't share the picture: ${e.message ?: e.javaClass.simpleName}.", isError = true)
            }
        }
    }

    fun share(format: ExportFormat, budget: GameReadyPack.Budget) {
        scope.launch {
            try {
                val file = viewModel.export(format, budget) ?: return@launch
                context.startActivity(Exporter.shareIntent(context, file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't share: ${e.message ?: e.javaClass.simpleName}.", isError = true)
            }
        }
    }

    fun save(format: ExportFormat, budget: GameReadyPack.Budget) {
        scope.launch {
            try {
                val file = viewModel.export(format, budget) ?: return@launch
                viewModel.holdForSaving(file)
                when (format) {
                    ExportFormat.GLB -> saveGlb.launch(file.fileName)
                    ExportFormat.STL -> saveStl.launch(file.fileName)
                    ExportFormat.OBJ, ExportFormat.UNREAL, ExportFormat.PHOTOS -> saveZip.launch(file.fileName)
                    ExportFormat.PLY -> savePly.launch(file.fileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't save: ${e.message ?: e.javaClass.simpleName}.", isError = true)
            }
        }
    }

    val mesh = capture.mesh
    val (width, height, depth) = mesh.size.map { it * 100 }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (capture.fromPhotos) "Model from your photos" else "Your scan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        ViewerCard(
            mesh = mesh,
            texture = capture.texture,
            photo = null,
            useGlViewer = useGlViewer,
            metersPerUnit = capture.metersPerUnit,
            sizeKnown = capture.sizeKnown,
            onSetRealLength = null,
            onScreenshot = { shareScreenshot(it) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Size: ${if (capture.sizeKnown) "" else "≈ "}${oneDecimal(width)} × ${oneDecimal(depth)} × ${oneDecimal(height)} cm",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    !capture.fromPhotos -> "Width × depth × height, measured by ARCore. From ${capture.keyframes.size} photos."
                    capture.sizeKnown -> "Width × depth × height, in real size from ARCore's positions. Built from ${capture.keyframes.size} photos on your computer."
                    else -> "Built from ${capture.keyframes.size} photos on your computer. The real size isn't known: open it in My models and set a real length."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val saved by viewModel.saved.collectAsStateWithLifecycle()
        QualityCard(capture.quality, onAddViews)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = viewModel::saveProject, enabled = saved !== capture, modifier = Modifier.weight(1f)) {
                Text(if (saved === capture) "Saved to My models" else "Save to My models")
            }
            FilledTonalButton(onClick = onViewInAr) { Text("View in AR") }
        }
        message?.let { (text, isError) ->
            Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExportCard(
            formats = ExportFormat.entries,
            solid = true,
            exporting = exporting,
            onShare = { format, budget -> share(format, budget) },
            onSave = { format, budget -> save(format, budget) },
        )
        OutlinedButton(onClick = onScanAgain, modifier = Modifier.fillMaxWidth()) { Text("Scan something else") }
        CopyReportButton(viewModel::scanReport)
        Spacer(Modifier.height(8.dp))
    }
}

/** The scan's score, what held it back, and the way to fix it: more views of the same scan. */
@Composable
private fun QualityCard(quality: QualityReport, onAddViews: () -> Unit) {
    QualityCard("Scan quality", quality, goodText = "Every side was covered with good depth.") {
        val label = @Composable { Text("Add more views to this scan") }
        if (quality.score < 70) {
            Button(onClick = onAddViews, modifier = Modifier.fillMaxWidth()) { label() }
        } else {
            OutlinedButton(onClick = onAddViews, modifier = Modifier.fillMaxWidth()) { label() }
        }
    }
}

private fun oneDecimal(value: Float) = ((value * 10).roundToInt() / 10f).toString()

private fun Context.hasCamera() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** For when the camera was refused for good and Android no longer shows the permission dialog. */
private fun Context.openAppSettings() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}
