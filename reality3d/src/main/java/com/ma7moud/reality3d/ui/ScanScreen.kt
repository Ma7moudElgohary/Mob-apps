package com.ma7moud.reality3d.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.scan.CoverageTracker
import com.ma7moud.reality3d.scan.ScanCapture
import com.ma7moud.reality3d.scan.ScanEngine
import com.ma7moud.reality3d.scan.ScanPhase
import com.ma7moud.reality3d.scan.ScanStatus
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Panel = Color(0xE60B111C)
private val Covered = Color(0xFF5BE49B)
private val Warning = Color(0xFFFFC857)

/** The 360° scan: camera permission and ARCore checks, guided capture, then the finished model. */
@Composable
fun ScanScreen(viewModel: ScanViewModel, useGlViewer: Boolean, onClose: () -> Unit) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
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
            ScanScreenState.Scanning -> viewModel.engine?.let { CameraScan(it, onBuild = viewModel::build, onRetry = { retry() }, onClose = { close() }) }
            is ScanScreenState.Building -> Centered(onClose = null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(state.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text("This takes a few seconds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ScanScreenState.Result -> ScanResult(state.capture, viewModel, useGlViewer, onScanAgain = { retry() }, onDone = { close() })
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
            }
        }
    }
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
private fun CameraScan(engine: ScanEngine, onBuild: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
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
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { engine.createView(it) }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("360° scan", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
            }
            Instructions(status)
            Spacer(Modifier.weight(1f))
            ScanPanel(status, engine, onBuild, onRetry)
        }
    }
}

@Composable
private fun Instructions(status: ScanStatus) {
    val text = when (status.phase) {
        ScanPhase.STARTING, ScanPhase.FIND_SURFACE ->
            "Point the camera at the table around the object and move the phone slowly so it can find the surface."
        ScanPhase.PLACE_BOX -> "Tap the object."
        ScanPhase.READY -> "Make the box a little bigger than the object, then start. Tap the object again to move the box."
        ScanPhase.SCANNING -> when (status.nextStep) {
            CoverageTracker.Step.LOW_RING -> "Walk slowly around the object, holding the phone at about its height."
            CoverageTracker.Step.MIDDLE_RING -> "Now hold the phone higher and go around again, looking down at 45°."
            CoverageTracker.Step.HIGH_RING -> "Hold the phone high and go around once more."
            CoverageTracker.Step.TOP -> "Finish with a view from straight above the object."
            CoverageTracker.Step.DONE -> "Every side is covered. Tap Build model."
        }
        ScanPhase.BUILDING -> "Building…"
        ScanPhase.FAILED -> status.message ?: "Scanning stopped."
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        status.trackingProblem?.let { Text(it, color = Warning, fontWeight = FontWeight.SemiBold) }
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        if (status.phase != ScanPhase.FAILED) status.message?.let { Text(it, color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ScanPanel(status: ScanStatus, engine: ScanEngine, onBuild: () -> Unit, onRetry: () -> Unit) {
    when (status.phase) {
        ScanPhase.READY -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                Text("Box size", color = Color.White, modifier = Modifier.weight(1f))
                Text("${(status.boxSize * 100).roundToInt()} cm", color = Color(0xFFB8C4D9))
            }
            Slider(
                value = status.boxSize,
                onValueChange = engine::setBoxSize,
                valueRange = ScanStatus.MIN_BOX_SIZE..ScanStatus.MAX_BOX_SIZE,
            )
            Button(onClick = engine::startScanning, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start scan") }
        }
        ScanPhase.SCANNING -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CoverageRadar(status, Modifier.size(112.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Covered ${(status.coverageFraction * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("${status.photos} photos · ${status.depthFrames} depth maps", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
                    Text("Outer ring: low views. Centre: from above. The white dot is you.", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
                }
            }
            val canBuild = status.coverageFraction >= MIN_COVERAGE && status.depthFrames > 0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = engine::restart, modifier = Modifier.weight(1f)) { Text("Restart") }
                Button(onClick = onBuild, enabled = canBuild, modifier = Modifier.weight(1f)) { Text("Build model") }
            }
            if (!canBuild) Text("Go around at least once before building.", color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall)
        }
        ScanPhase.FAILED -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
        else -> Unit
    }
}

private const val MIN_COVERAGE = 0.3f

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
private fun ScanResult(capture: ScanCapture, viewModel: ScanViewModel, useGlViewer: Boolean, onScanAgain: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var clay by rememberSaveable { mutableStateOf(false) }
    var resetRequests by remember { mutableIntStateOf(0) }
    val saveGlb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GLB.mimeType), viewModel::savePending)
    val saveStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.STL.mimeType), viewModel::savePending)
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.OBJ.mimeType), viewModel::savePending)

    fun share(format: ExportFormat) {
        scope.launch {
            try {
                val file = viewModel.export(format) ?: return@launch
                context.startActivity(Exporter.shareIntent(context, file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't share: ${e.message ?: e.javaClass.simpleName}.", isError = true)
            }
        }
    }

    fun save(format: ExportFormat) {
        scope.launch {
            try {
                val file = viewModel.export(format) ?: return@launch
                viewModel.holdForSaving(file)
                when (format) {
                    ExportFormat.GLB -> saveGlb.launch(file.fileName)
                    ExportFormat.STL -> saveStl.launch(file.fileName)
                    ExportFormat.OBJ, ExportFormat.PHOTOS -> saveZip.launch(file.fileName)
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
            Text("Your scan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        ViewerCard(mesh, photo = null, useGlViewer, clay, resetRequests, onToggleClay = { clay = !clay }, onReset = { resetRequests++ })
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Size: ${oneDecimal(width)} × ${oneDecimal(depth)} × ${oneDecimal(height)} cm",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Width × depth × height, measured by ARCore. From ${capture.keyframes.size} photos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { (text, isError) ->
            Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExportCard(
            formats = ExportFormat.entries,
            solid = true,
            exporting = exporting,
            onShare = { share(it) },
            onSave = { save(it) },
        )
        OutlinedButton(onClick = onScanAgain, modifier = Modifier.fillMaxWidth()) { Text("Scan something else") }
        Spacer(Modifier.height(8.dp))
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
