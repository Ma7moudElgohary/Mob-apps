@file:OptIn(ExperimentalMaterial3Api::class)

package com.ma7moud.reality3d.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ma7moud.reality3d.ai.AiState
import com.ma7moud.reality3d.ai.ObjectInsight
import com.ma7moud.reality3d.export.ExportFormat
import com.ma7moud.reality3d.export.Exporter
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.mesh.MeshDetail
import com.ma7moud.reality3d.mesh.MeshSettings
import com.ma7moud.reality3d.mesh.ShapeProfile
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

private val RealityColors = darkColorScheme(
    primary = Color(0xFF50E3FF),
    onPrimary = Color(0xFF00222C),
    primaryContainer = Color(0xFF0E3A4A),
    onPrimaryContainer = Color(0xFFBDF3FF),
    secondary = Color(0xFF9A7CFF),
    onSecondary = Color(0xFF1B0F3D),
    secondaryContainer = Color(0xFF2B2152),
    onSecondaryContainer = Color(0xFFE3DAFF),
    background = Color(0xFF05070B),
    onBackground = Color(0xFFF3F7FF),
    surface = Color(0xFF0D1320),
    onSurface = Color(0xFFF3F7FF),
    surfaceVariant = Color(0xFF172134),
    onSurfaceVariant = Color(0xFFB8C4D9),
    surfaceContainerLowest = Color(0xFF070B12),
    surfaceContainerLow = Color(0xFF0B111C),
    surfaceContainer = Color(0xFF0F1622),
    surfaceContainerHigh = Color(0xFF121A28),
    surfaceContainerHighest = Color(0xFF16202F),
    outline = Color(0xFF3A4A66),
    outlineVariant = Color(0xFF243048),
    error = Color(0xFFFF8A95),
)

private val Good = Color(0xFF5BE49B)
private val Waiting = Color(0xFFFFC857)
private val Muted = Color(0xFF6B7A94)

@Composable
fun Reality3DApp(viewModel: Reality3DViewModel, useGlViewer: Boolean) {
    MaterialTheme(colorScheme = RealityColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Reality3DScreen(viewModel, useGlViewer)
        }
    }
}

@Composable
private fun Reality3DScreen(viewModel: Reality3DViewModel, useGlViewer: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ai by viewModel.aiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var clay by rememberSaveable { mutableStateOf(false) }
    var resetRequests by remember { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.loadPhoto(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) viewModel.loadPhoto(uri)
    }
    val saveGlb = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GLB.mimeType), viewModel::savePending)
    val saveStl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.STL.mimeType), viewModel::savePending)
    val saveObj = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.OBJ.mimeType), viewModel::savePending)

    fun takePhoto() {
        val uri = createCameraUri(context)
        cameraUri = uri
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            viewModel.showMessage("No camera app was found on this phone.", isError = true)
        }
    }

    fun pickPhoto() {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            viewModel.showMessage("No gallery app was found on this phone.", isError = true)
        }
    }

    fun share(format: ExportFormat) {
        scope.launch {
            try {
                val file = viewModel.export(format) ?: return@launch
                context.startActivity(Exporter.shareIntent(context, file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't share the model: ${e.message ?: e.javaClass.simpleName}.", isError = true)
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
                    ExportFormat.OBJ -> saveObj.launch(file.fileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't save the model: ${e.message ?: e.javaClass.simpleName}.", isError = true)
            }
        }
    }

    val photo = state.photo
    val mesh = state.mesh
    val busy = state.progress != null
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        StatusCard(ai, state.depthModelReady, state.depthDownloadBytes, onGetAi = viewModel::downloadAi)
        PhotoCard(photo, enabled = !busy, onCamera = { takePhoto() }, onGallery = { pickPhoto() })
        if (photo != null && mesh == null) {
            Button(
                onClick = viewModel::generate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Make 3D model", style = MaterialTheme.typography.titleMedium)
            }
        }
        state.progress?.let { ProgressPanel(it) }
        state.message?.let {
            Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (photo != null && mesh != null) {
            ViewerCard(
                mesh = mesh,
                photo = photo,
                useGlViewer = useGlViewer,
                clay = clay,
                resetRequests = resetRequests,
                onToggleClay = { clay = !clay },
                onReset = { resetRequests++ },
            )
            ShapeCard(state.settings, state.rebuilding, viewModel::updateSettings)
        }
        if (photo != null) {
            AiCard(ai, state.insight, state.analyzing, onAnalyze = viewModel::analyze, onDownload = viewModel::downloadAi, onRetry = viewModel::retryAi)
        }
        if (mesh != null) {
            ExportCard(mesh.solid, state.exporting, onShare = { share(it) }, onSave = { save(it) })
        }
        AboutCard()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            "Reality3D",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Turn one photo into a 3D model, right on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(ai: AiState, depthReady: Boolean, depthBytes: Long, onGetAi: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val (aiText, aiColor) = when (ai) {
                AiState.Checking -> "checking…" to Muted
                AiState.Ready -> "ready on this phone" to Good
                AiState.NeedsDownload -> "one-time download needed" to Waiting
                is AiState.Downloading -> "downloading…" to Waiting
                AiState.NotSupported -> "not available on this phone" to Muted
                is AiState.Failed -> "unavailable right now" to Muted
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLine("Gemini Nano", aiText, aiColor, Modifier.weight(1f))
                if (ai == AiState.NeedsDownload) TextButton(onClick = onGetAi) { Text("Get") }
            }
            StatusLine(
                "Depth model",
                if (depthReady) "ready" else "downloads on first use (${Reality3DViewModel.formatBytes(depthBytes)})",
                if (depthReady) Good else Waiting,
            )
        }
    }
}

@Composable
private fun StatusLine(name: String, value: String, dot: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text("$name: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotoCard(photo: Bitmap?, enabled: Boolean, onCamera: () -> Unit, onGallery: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (photo == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF12203A), Color(0xFF0A0F1A)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("One object, plain background", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Keep the whole object in the frame and fill most of it. Soft, even light works best.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                val image = remember(photo) { photo.asImageBitmap() }
                Image(
                    image,
                    contentDescription = "Selected photo",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCamera, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(if (photo == null) "Take photo" else "New photo")
                }
                OutlinedButton(onClick = onGallery, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("Gallery")
                }
            }
        }
    }
}

@Composable
private fun ProgressPanel(progress: Progress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(progress.label, style = MaterialTheme.typography.bodyMedium)
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ViewerCard(
    mesh: Mesh3D,
    photo: Bitmap,
    useGlViewer: Boolean,
    clay: Boolean,
    resetRequests: Int,
    onToggleClay: () -> Unit,
    onReset: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.9f)) {
            if (useGlViewer) {
                MeshViewer(mesh, photo, clay, resetRequests, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF070B12)), contentAlignment = Alignment.Center) {
                    Text("3D preview")
                }
            }
            Text(
                "Drag to rotate · pinch to zoom · double-tap to reset",
                Modifier.align(Alignment.TopCenter).padding(10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB8F5FF),
            )
            Text(
                "${mesh.triangleCount} triangles · " + if (mesh.solid) "closed" else "open",
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggleClay) { Text(if (clay) "Photo" else "Clay") }
                FilledTonalButton(onClick = onReset) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun MeshViewer(mesh: Mesh3D, photo: Bitmap, clay: Boolean, resetRequests: Int, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MeshSurfaceView(context) }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resetRequests) { if (resetRequests > 0) view.resetView() }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {
            it.setScene(mesh, photo)
            it.setClay(clay)
        },
    )
}

@Composable
private fun ShapeCard(settings: MeshSettings, rebuilding: Boolean, onChange: ((MeshSettings) -> MeshSettings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shape", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (rebuilding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Choice(listOf("Solid", "Relief"), if (settings.solid) 0 else 1) { index -> onChange { it.copy(solid = index == 0) } }
            Text(
                if (settings.solid) "Closed model with a mirrored back: looks right from every side and can be 3D printed."
                else "Front surface only, like a relief.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Choice(listOf("Round", "Boxy"), if (settings.profile == ShapeProfile.ROUND) 0 else 1) { index ->
                onChange { it.copy(profile = if (index == 0) ShapeProfile.ROUND else ShapeProfile.BOXY) }
            }
            LabeledSlider(
                label = "Thickness",
                value = settings.thickness,
                valueText = "${(settings.thickness * 100).roundToInt()}% of width",
                range = MeshSettings.MIN_THICKNESS..MeshSettings.MAX_THICKNESS,
            ) { value -> onChange { it.copy(thickness = value) } }
            LabeledSlider(
                label = "Depth from photo",
                value = settings.depthStrength,
                valueText = "${(settings.depthStrength * 10).roundToInt() / 10f}×",
                range = 0f..MeshSettings.MAX_DEPTH_STRENGTH,
            ) { value -> onChange { it.copy(depthStrength = value) } }
            Choice(listOf("Standard detail", "High detail"), if (settings.detail == MeshDetail.STANDARD) 0 else 1) { index ->
                onChange { it.copy(detail = if (index == 0) MeshDetail.STANDARD else MeshDetail.HIGH) }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AiCard(
    ai: AiState,
    insight: ObjectInsight?,
    analyzing: Boolean,
    onAnalyze: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gemini Nano", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recognises the object on your phone and sets the shape and thickness to match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (ai) {
                AiState.Ready -> Button(onClick = onAnalyze, enabled = !analyzing, modifier = Modifier.fillMaxWidth()) {
                    if (analyzing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking at the photo…")
                    } else {
                        Text("Recognise object")
                    }
                }
                AiState.NeedsDownload -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Download Gemini Nano (one time)")
                }
                is AiState.Downloading -> {
                    Text("Downloading Gemini Nano…", style = MaterialTheme.typography.bodyMedium)
                    val fraction = ai.progress
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                AiState.NotSupported -> Text(
                    "Not available on this phone. Everything else works without it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is AiState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ai.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                AiState.Checking -> Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            insight?.let { InsightView(it) }
        }
    }
}

@Composable
private fun InsightView(insight: ObjectInsight) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A1820))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        insight.name?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        val details = listOfNotNull(
            insight.shape?.let { "Shape: " + it.name.lowercase().replaceFirstChar(Char::uppercase) },
            insight.thicknessPercent?.let { "thickness about $it% of its width" },
        )
        if (details.isNotEmpty()) {
            Text(details.joinToString(", ") + " (applied to the model)", style = MaterialTheme.typography.bodyMedium)
        }
        insight.tip?.let {
            Text("Tip: $it", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExportCard(solid: Boolean, exporting: Boolean, onShare: (ExportFormat) -> Unit, onSave: (ExportFormat) -> Unit) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.GLB) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Choice(ExportFormat.entries.map { it.label }, format.ordinal) { format = ExportFormat.entries[it] }
            Text(format.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (format == ExportFormat.STL && !solid) {
                Text("Switch Shape to Solid for a closed model a printer can use.", style = MaterialTheme.typography.bodySmall, color = Waiting)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onShare(format) }, enabled = !exporting, modifier = Modifier.weight(1f)) { Text("Share") }
                OutlinedButton(onClick = { onSave(format) }, enabled = !exporting, modifier = Modifier.weight(1f)) { Text("Save to phone") }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F17)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            "How it works: ML Kit cuts the subject out, MiDaS estimates its depth, and the outline is inflated into a " +
                "rounded shape. One photo cannot show the back, so Solid mode mirrors the front. Everything runs on the phone.",
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun createCameraUri(context: Context): Uri {
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    directory.listFiles()?.forEach { it.delete() }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, "capture_${System.currentTimeMillis()}.jpg"))
}
