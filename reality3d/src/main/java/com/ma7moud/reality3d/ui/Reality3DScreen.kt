package com.ma7moud.reality3d.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.ai.AiCoreAnalyzer
import com.ma7moud.reality3d.ai.AiCoreState
import com.ma7moud.reality3d.ai.ReconstructionAdvice
import com.ma7moud.reality3d.ai3d.Ai3dClient
import com.ma7moud.reality3d.ar.ArPreviewActivity
import com.ma7moud.reality3d.data.ImageLoader
import com.ma7moud.reality3d.depth.DepthAnythingEstimator
import com.ma7moud.reality3d.depth.DepthAnythingModelManager
import com.ma7moud.reality3d.depth.DepthRefiner
import com.ma7moud.reality3d.depth.LiteRtBackendSelector
import com.ma7moud.reality3d.export.GameReadyExporter
import com.ma7moud.reality3d.export.GlbExporter
import com.ma7moud.reality3d.export.PlyExporter
import com.ma7moud.reality3d.export.StlExporter
import com.ma7moud.reality3d.mesh.AdaptiveMeshBuilder
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath
import com.ma7moud.reality3d.mesh.ObjExporter
import com.ma7moud.reality3d.project.ProjectStore
import com.ma7moud.reality3d.project.RealityProject
import com.ma7moud.reality3d.quality.QualityScorer
import com.ma7moud.reality3d.scan.ScanActivity
import com.ma7moud.reality3d.segmentation.MaskOps
import com.ma7moud.reality3d.segmentation.SegmentationBundle
import com.ma7moud.reality3d.segmentation.SubjectMask
import com.ma7moud.reality3d.segmentation.SubjectMasker
import com.ma7moud.reality3d.texture.TextureBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

private val RealityColors = darkColorScheme(
    primary = Color(0xFF50E3FF),
    secondary = Color(0xFF9A7CFF),
    background = Color(0xFF05070B),
    surface = Color(0xFF0D1320),
    onBackground = Color(0xFFF3F7FF),
    onSurface = Color(0xFFF3F7FF),
)

private enum class HomeMode(val title: String) {
    QUICK("Quick Local"),
    SCAN("Scan 360"),
    PROJECTS("Projects"),
    AI3D("AI 3D"),
}

@Composable
fun Reality3DApp() {
    MaterialTheme(colorScheme = RealityColors) {
        Reality3DScreen()
    }
}

@Composable
private fun Reality3DScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val analyzer = remember { AiCoreAnalyzer() }
    val masker = remember { SubjectMasker() }
    val modelManager = remember { DepthAnythingModelManager(context) }
    val backendSelector = remember { LiteRtBackendSelector(context) }
    val projects = remember { ProjectStore(context) }
    val ai3d = remember { Ai3dClient(context) }

    var homeMode by remember { mutableStateOf(HomeMode.QUICK) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskedTexture by remember { mutableStateOf<Bitmap?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var segmentation by remember { mutableStateOf<SegmentationBundle?>(null) }
    var activeMask by remember { mutableStateOf<SubjectMask?>(null) }
    var mesh by remember { mutableStateOf<DepthMesh?>(null) }
    var project by remember { mutableStateOf<RealityProject?>(null) }
    var advice by remember { mutableStateOf<ReconstructionAdvice?>(null) }
    var aiStatus by remember { mutableStateOf("Checking AICore…") }
    var modelReady by remember { mutableStateOf(modelManager.isReady()) }
    var modelProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Choose a mode and capture an object.") }
    var busy by remember { mutableStateOf(false) }
    var benchmarkText by remember { mutableStateOf("") }
    var projectRefresh by remember { mutableIntStateOf(0) }

    var editMode by remember { mutableStateOf<MaskOps.BrushMode?>(null) }
    var brushRadius by remember { mutableFloatStateOf(0.055f) }
    var viewer by remember { mutableStateOf<MeshSurfaceView?>(null) }
    var viewerMode by remember { mutableStateOf(ViewerMode.TEXTURE) }
    var autoRotate by remember { mutableStateOf(false) }
    var measureMode by remember { mutableStateOf(false) }
    var measureText by remember { mutableStateOf("Set scale, then tap two points") }
    var scaleText by remember { mutableStateOf("") }
    var showOriginal by remember { mutableStateOf(false) }
    var lightAngle by remember { mutableFloatStateOf(0.65f) }

    var aiBackendUrl by remember { mutableStateOf(ai3d.baseUrl) }
    var aiEngine by remember { mutableStateOf("sf3d") }

    fun selectDefaultMask(bundle: SegmentationBundle): SubjectMask =
        bundle.subjects.maxByOrNull { it.width * it.height }?.mask ?: bundle.foreground

    fun loadImage(uri: Uri) {
        scope.launch {
            busy = true
            status = "Loading image…"
            runCatching { withContext(Dispatchers.IO) { ImageLoader.load(context, uri) } }
                .onSuccess { image ->
                    bitmap = image
                    maskedTexture = null
                    mesh = null
                    project = null
                    advice = null
                    status = "Detecting subjects…"
                    runCatching { masker.segment(image) }
                        .onSuccess { bundle ->
                            segmentation = bundle
                            activeMask = selectDefaultMask(bundle)
                            status = "Select/refine the object, then Generate 3D."
                        }
                        .onFailure { error ->
                            segmentation = null
                            activeMask = null
                            status = "Segmentation failed: ${error.message}"
                        }
                }
                .onFailure { status = "Image error: ${it.message}" }
            busy = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) loadImage(it)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let(::loadImage)
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(ScanActivity.EXTRA_PROJECT_ID)
            val loadedProject = projects.list().firstOrNull { it.id == id }
            val loadedMesh = loadedProject?.let(projects::loadMesh)
            if (loadedProject != null && loadedMesh != null) {
                project = loadedProject
                mesh = loadedMesh
                bitmap = loadedProject.thumbnailPath?.let(BitmapFactory::decodeFile)
                maskedTexture = bitmap
                homeMode = HomeMode.QUICK
                status = "360 scan loaded: ${loadedMesh.triangleCount} triangles."
            }
            projectRefresh++
        }
    }

    LaunchedEffect(Unit) {
        aiStatus = runCatching {
            when (analyzer.status()) {
                AiCoreState.Available -> "AICore / Gemini Nano: ready on-device"
                AiCoreState.Downloadable -> "AICore: model download available"
                AiCoreState.Downloading -> "AICore: model downloading"
                AiCoreState.Unavailable -> "AICore: unavailable on this device/config"
            }
        }.getOrElse { "AICore check failed: ${it.message}" }
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
            masker.close()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Reality3D",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text("Local AI depth • true AR scan • game-ready export", color = Color(0xFFAFBED2))
        }
        ScrollableTabRow(selectedTabIndex = homeMode.ordinal, edgePadding = 8.dp) {
            HomeMode.entries.forEach { item ->
                Tab(
                    selected = homeMode == item,
                    onClick = { homeMode = item },
                    text = { Text(item.title) },
                )
            }
        }

        when (homeMode) {
            HomeMode.QUICK -> QuickLocalPanel(
                context = context,
                bitmap = bitmap,
                segmentation = segmentation,
                activeMask = activeMask,
                onMaskChanged = { activeMask = it },
                editMode = editMode,
                onEditMode = { editMode = it },
                brushRadius = brushRadius,
                onBrushRadius = { brushRadius = it },
                aiStatus = aiStatus,
                advice = advice,
                modelReady = modelReady,
                modelProgress = modelProgress,
                busy = busy,
                status = status,
                benchmarkText = benchmarkText,
                mesh = mesh,
                texture = maskedTexture,
                project = project,
                viewer = viewer,
                onViewerCreated = { viewer = it },
                viewerMode = viewerMode,
                onViewerMode = {
                    viewerMode = it
                    viewer?.setMode(it)
                },
                autoRotate = autoRotate,
                onAutoRotate = {
                    autoRotate = it
                    viewer?.setAutoRotate(it)
                },
                measureMode = measureMode,
                onMeasureMode = {
                    measureMode = it
                    viewer?.measurementEnabled = it
                },
                measureText = measureText,
                scaleText = scaleText,
                onScaleText = { scaleText = it },
                showOriginal = showOriginal,
                onShowOriginal = { showOriginal = it },
                lightAngle = lightAngle,
                onLightAngle = {
                    lightAngle = it
                    val angle = it * Math.PI.toFloat() * 2f
                    viewer?.setLight(cos(angle), 0.75f, sin(angle))
                },
                onCamera = {
                    val uri = createCameraUri(context)
                    cameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onGallery = { galleryLauncher.launch("image/*") },
                onAnalyze = { image ->
                    scope.launch {
                        busy = true
                        status = "AICore is evaluating reconstruction risks…"
                        runCatching { analyzer.analyze(image) }
                            .onSuccess {
                                advice = it
                                status = "AICore recommends ${it.recommendedMode}."
                            }
                            .onFailure { status = "AICore failed: ${it.message}" }
                        busy = false
                    }
                },
                onDownloadModel = {
                    scope.launch {
                        busy = true
                        status = "Downloading Depth Anything V2…"
                        runCatching { modelManager.download { modelProgress = it } }
                            .onSuccess {
                                modelReady = true
                                status = "Depth Anything V2 ready."
                            }
                            .onFailure { status = "Model download failed: ${it.message}" }
                        busy = false
                    }
                },
                onBenchmark = { image ->
                    scope.launch {
                        busy = true
                        status = "Benchmarking CPU / GPU / NPU…"
                        runCatching {
                            val estimator = DepthAnythingEstimator(modelManager.modelFile, backendSelector)
                            backendSelector.benchmark(modelManager.modelFile, estimator.prepareInput(image))
                        }.onSuccess { results ->
                            benchmarkText = results.joinToString(" • ") { result ->
                                "${result.backend}: ${result.milliseconds?.let { ms -> "${ms}ms" } ?: "N/A"}"
                            }
                            status = "Fastest working backend saved."
                        }.onFailure { status = "Benchmark failed: ${it.message}" }
                        busy = false
                    }
                },
                onGenerate = { image, mask ->
                    scope.launch {
                        busy = true
                        status = "Depth Anything V2 → edge refinement → adaptive mesh → cleanup…"
                        runCatching {
                            withContext(Dispatchers.Default) {
                                val result = DepthAnythingEstimator(modelManager.modelFile, backendSelector).estimate(image)
                                val refined = DepthRefiner.edgeAware(result.depth, image, mask)
                                val generated = AdaptiveMeshBuilder.fromDepth(
                                    refined,
                                    mask,
                                    image.width.toFloat() / image.height,
                                )
                                Triple(generated, TextureBuilder.masked(image, mask), result.backend)
                            }
                        }.onSuccess { (generated, textureResult, backend) ->
                            mesh = generated
                            maskedTexture = textureResult
                            val quality = QualityScorer.quick(mask, generated)
                            status = "3D ready: ${generated.triangleCount} triangles • $backend • quality ${quality.total}% (${quality.label})."
                        }.onFailure { status = "3D generation failed: ${it.message}" }
                        busy = false
                    }
                },
                onFeather = { activeMask?.let { activeMask = MaskOps.feather(it) } },
                onAutoRefine = { activeMask?.let { activeMask = MaskOps.autoRefine(it) } },
                onSetScale = { meters ->
                    mesh?.let {
                        mesh = MeshMath.scaleToWidth(it, meters)
                        status = "Metric scale applied."
                    }
                },
                onMeasurement = { measureText = it },
                onSaveProject = { meshValue, textureValue ->
                    val base = project ?: projects.create(
                        advice?.objectType?.replaceFirstChar { char -> char.uppercase() } ?: "Quick Object",
                        "quick",
                    )
                    project = projects.saveMesh(base, meshValue, textureValue)
                    projectRefresh++
                    status = "Saved to Projects."
                },
                onArPreview = project?.let { current ->
                    {
                        context.startActivity(
                            Intent(context, ArPreviewActivity::class.java)
                                .putExtra(ArPreviewActivity.EXTRA_PROJECT_ID, current.id),
                        )
                    }
                },
            )

            HomeMode.SCAN -> ScanPanel(
                store = projects,
                refresh = projectRefresh,
                onStart = { resumeProject ->
                    val scanProject = resumeProject ?: projects.create(
                        "360 Scan ${projects.list().size + 1}",
                        "scan360",
                    )
                    project = scanProject
                    scanLauncher.launch(
                        Intent(context, ScanActivity::class.java)
                            .putExtra(ScanActivity.EXTRA_PROJECT_ID, scanProject.id),
                    )
                },
            )

            HomeMode.PROJECTS -> ProjectsPanel(
                context = context,
                store = projects,
                refresh = projectRefresh,
                onOpen = { selected ->
                    projects.loadMesh(selected)?.let { loaded ->
                        project = selected
                        mesh = loaded
                        bitmap = selected.thumbnailPath?.let(BitmapFactory::decodeFile)
                        maskedTexture = bitmap
                        homeMode = HomeMode.QUICK
                        status = "Project loaded."
                    }
                },
                onDelete = {
                    projects.delete(it)
                    if (project?.id == it.id) project = null
                    projectRefresh++
                },
            )

            HomeMode.AI3D -> Ai3dPanel(
                bitmap = bitmap,
                url = aiBackendUrl,
                onUrl = { aiBackendUrl = it },
                engine = aiEngine,
                onEngine = { aiEngine = it },
                busy = busy,
                status = status,
                onGenerate = { image ->
                    scope.launch {
                        busy = true
                        ai3d.baseUrl = aiBackendUrl
                        status = "GPU backend is generating full 3D…"
                        runCatching { ai3d.generate(image, aiEngine) }
                            .onSuccess { result ->
                                status = result.message
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.files",
                                    result.glb,
                                )
                                shareUris(context, listOf(uri), "model/gltf-binary")
                            }
                            .onFailure { status = "AI 3D failed: ${it.message}" }
                        busy = false
                    }
                },
            )
        }
    }
}

@Composable
private fun QuickLocalPanel(
    context: Context,
    bitmap: Bitmap?,
    segmentation: SegmentationBundle?,
    activeMask: SubjectMask?,
    onMaskChanged: (SubjectMask) -> Unit,
    editMode: MaskOps.BrushMode?,
    onEditMode: (MaskOps.BrushMode?) -> Unit,
    brushRadius: Float,
    onBrushRadius: (Float) -> Unit,
    aiStatus: String,
    advice: ReconstructionAdvice?,
    modelReady: Boolean,
    modelProgress: Int,
    busy: Boolean,
    status: String,
    benchmarkText: String,
    mesh: DepthMesh?,
    texture: Bitmap?,
    project: RealityProject?,
    viewer: MeshSurfaceView?,
    onViewerCreated: (MeshSurfaceView) -> Unit,
    viewerMode: ViewerMode,
    onViewerMode: (ViewerMode) -> Unit,
    autoRotate: Boolean,
    onAutoRotate: (Boolean) -> Unit,
    measureMode: Boolean,
    onMeasureMode: (Boolean) -> Unit,
    measureText: String,
    scaleText: String,
    onScaleText: (String) -> Unit,
    showOriginal: Boolean,
    onShowOriginal: (Boolean) -> Unit,
    lightAngle: Float,
    onLightAngle: (Float) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onAnalyze: (Bitmap) -> Unit,
    onDownloadModel: () -> Unit,
    onBenchmark: (Bitmap) -> Unit,
    onGenerate: (Bitmap, SubjectMask) -> Unit,
    onFeather: () -> Unit,
    onAutoRefine: () -> Unit,
    onSetScale: (Float) -> Unit,
    onMeasurement: (String) -> Unit,
    onSaveProject: (DepthMesh, Bitmap?) -> Unit,
    onArPreview: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            aiStatus,
            if (modelReady) "Depth Anything V2: ready" else "Depth Anything V2: download required",
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onCamera, modifier = Modifier.weight(1f)) { Text("Camera") }
            OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) { Text("Gallery") }
        }

        bitmap?.let { image ->
            activeMask?.let { mask ->
                Text("Tap a cyan subject box, then refine edges if needed.")
                MaskEditor(
                    bitmap = image,
                    mask = mask,
                    subjects = segmentation?.subjects.orEmpty(),
                    editMode = editMode,
                    brushRadius = brushRadius,
                    onMaskChanged = onMaskChanged,
                    onSubjectSelected = { onMaskChanged(it.mask) },
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(editMode == null, { onEditMode(null) }, { Text("Select") })
                    FilterChip(editMode == MaskOps.BrushMode.ADD, { onEditMode(MaskOps.BrushMode.ADD) }, { Text("Add") })
                    FilterChip(editMode == MaskOps.BrushMode.ERASE, { onEditMode(MaskOps.BrushMode.ERASE) }, { Text("Erase") })
                    OutlinedButton(onClick = onAutoRefine) { Text("Auto Refine") }
                    OutlinedButton(onClick = onFeather) { Text("Feather") }
                }
                if (editMode != null) {
                    Text("Brush size")
                    Slider(brushRadius, onBrushRadius, valueRange = 0.015f..0.15f)
                }
            } ?: Image(
                image.asImageBitmap(),
                contentDescription = "Selected object",
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAnalyze(image) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("AICore Advisor") }
                if (modelReady) {
                    Button(
                        onClick = { activeMask?.let { onGenerate(image, it) } },
                        enabled = !busy && activeMask != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Generate 3D") }
                } else {
                    Button(
                        onClick = onDownloadModel,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (modelProgress > 0) "Model $modelProgress%" else "Get Depth Model") }
                }
            }
            if (modelReady) {
                OutlinedButton(
                    onClick = { onBenchmark(image) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Benchmark CPU / GPU / NPU") }
            }
        }

        if (benchmarkText.isNotBlank()) Text(benchmarkText, color = Color(0xFFC5B7FF))
        advice?.let(::AdviceCard)

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(status)
            }
        } else {
            Text(status, color = Color(0xFFB8C4D9))
        }

        mesh?.let { generated ->
            activeMask?.let { QualityCard(QualityScorer.quick(it, generated)) }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(showOriginal, { onShowOriginal(!showOriginal) }) {
                    Text(if (showOriginal) "Show 3D" else "Before / After")
                }
                ViewerMode.entries.forEach { available ->
                    FilterChip(viewerMode == available, { onViewerMode(available) }) {
                        Text(available.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }

            if (showOriginal && bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "Original",
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            } else {
                key(generated) {
                    AndroidView(
                        factory = { ctx ->
                            MeshSurfaceView(ctx, generated, texture) { meters, _, vertexB ->
                                onMeasurement(
                                    when {
                                        vertexB < 0 -> "Point 1 selected"
                                        meters != null -> "Distance: ${"%.1f".format(meters * 1000f)} mm"
                                        else -> "Set scale first"
                                    },
                                )
                            }.also { view ->
                                view.setMode(viewerMode)
                                view.setAutoRotate(autoRotate)
                                view.measurementEnabled = measureMode
                                val angle = lightAngle * Math.PI.toFloat() * 2f
                                view.setLight(cos(angle), 0.75f, sin(angle))
                                onViewerCreated(view)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                    )
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip({ viewer?.resetCamera() }, { Text("Reset") })
                AssistChip({ viewer?.preset(ViewerPreset.FRONT) }, { Text("Front") })
                AssistChip({ viewer?.preset(ViewerPreset.RIGHT) }, { Text("Right") })
                AssistChip({ viewer?.preset(ViewerPreset.BACK) }, { Text("Back") })
                AssistChip({ viewer?.preset(ViewerPreset.LEFT) }, { Text("Left") })
                AssistChip({ viewer?.preset(ViewerPreset.TOP) }, { Text("Top") })
                AssistChip({ viewer?.preset(ViewerPreset.ISO) }, { Text("ISO") })
                AssistChip({ onAutoRotate(!autoRotate) }) {
                    Text(if (autoRotate) "Stop Rotate" else "Auto Rotate")
                }
            }

            Text("Light direction")
            Slider(lightAngle, onLightAngle, valueRange = 0f..1f)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    scaleText,
                    onScaleText,
                    modifier = Modifier.weight(1f),
                    label = { Text("Known width (cm)") },
                    singleLine = true,
                )
                Button(onClick = {
                    scaleText.toFloatOrNull()?.takeIf { it > 0f }?.let { onSetScale(it / 100f) }
                }) { Text("Set Scale") }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = measureMode,
                    onClick = { onMeasureMode(!measureMode) },
                    label = { Text("Measure") },
                    modifier = Modifier.weight(1f),
                )
                Text(measureText, modifier = Modifier.weight(2f), color = Color(0xFFB8C4D9))
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    shareUris(
                        context,
                        listOf(GlbExporter.export(context, generated, texture)),
                        "model/gltf-binary",
                    )
                }) { Text("GLB") }
                OutlinedButton(onClick = {
                    if (bitmap != null) {
                        shareUris(
                            context,
                            ObjExporter.export(context, generated, bitmap),
                            "application/octet-stream",
                        )
                    }
                }) { Text("OBJ") }
                OutlinedButton(onClick = {
                    shareUris(context, listOf(StlExporter.export(context, generated)), "model/stl")
                }) { Text("STL") }
                OutlinedButton(onClick = {
                    shareUris(context, listOf(PlyExporter.export(context, generated)), "application/octet-stream")
                }) { Text("PLY") }
                OutlinedButton(onClick = {
                    if (texture != null) {
                        shareUris(
                            context,
                            GameReadyExporter.exportUnrealPack(context, generated, texture, 25_000),
                            "model/gltf-binary",
                        )
                    }
                }) { Text("Unreal Pack") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewer?.capture { screenshot ->
                            val file = File(context.cacheDir, "reality3d_${System.currentTimeMillis()}.png")
                            file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file,
                            )
                            shareUris(context, listOf(uri), "image/png")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Screenshot") }
                Button(
                    onClick = { onSaveProject(generated, texture) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save Project") }
            }

            if (project != null && onArPreview != null) {
                OutlinedButton(onClick = onArPreview, modifier = Modifier.fillMaxWidth()) {
                    Text("AR Preview in Real Space")
                }
            }
        }
    }
}

@Composable
private fun ScanPanel(
    store: ProjectStore,
    refresh: Int,
    onStart: (RealityProject?) -> Unit,
) {
    val resumable = remember(refresh) {
        store.list().filter { it.mode == "scan360" && it.status == "scanning" }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("True 3D Scan", style = MaterialTheme.typography.headlineSmall)
        Text("Walk around the object. Raw Depth + confidence + camera pose are fused into a metric TSDF volume.")
        Button(onClick = { onStart(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Start New 360° Scan")
        }
        resumable.forEach { scan ->
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(scan.name, fontWeight = FontWeight.Bold)
                    Text("${scan.coverage}% coverage • resumable")
                    OutlinedButton(onClick = { onStart(scan) }) { Text("Resume Scan") }
                }
            }
        }
    }
}

@Composable
private fun ProjectsPanel(
    context: Context,
    store: ProjectStore,
    refresh: Int,
    onOpen: (RealityProject) -> Unit,
    onDelete: (RealityProject) -> Unit,
) {
    val list = remember(refresh) { store.list() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Projects", style = MaterialTheme.typography.headlineSmall)
        if (list.isEmpty()) Text("No saved projects yet.")
        list.forEach { item ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.thumbnailPath
                            ?.let(BitmapFactory::decodeFile)
                            ?.let { thumbnail ->
                                Image(thumbnail.asImageBitmap(), null, Modifier.size(72.dp))
                            }
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text("${item.mode} • ${item.triangleCount} triangles • ${item.coverage}%")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { onOpen(item) }, modifier = Modifier.weight(1f)) {
                            Text("Open")
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, ArPreviewActivity::class.java)
                                        .putExtra(ArPreviewActivity.EXTRA_PROJECT_ID, item.id),
                                )
                            },
                            enabled = item.meshPath != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("AR") }
                        TextButton(onClick = { onDelete(item) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Ai3dPanel(
    bitmap: Bitmap?,
    url: String,
    onUrl: (String) -> Unit,
    engine: String,
    onEngine: (String) -> Unit,
    busy: Boolean,
    status: String,
    onGenerate: (Bitmap) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI Full 3D", style = MaterialTheme.typography.headlineSmall)
        Text("Connect a GPU host running Stable Fast 3D or Hunyuan3D. Quick Local and Scan 360 stay on-device.")
        OutlinedTextField(
            url,
            onUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("GPU backend URL") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(engine == "sf3d", { onEngine("sf3d") }, { Text("Stable Fast 3D") })
            FilterChip(engine == "hunyuan", { onEngine("hunyuan") }, { Text("Hunyuan3D") })
        }
        if (bitmap == null) {
            Text("Choose a photo in Quick Local first.")
        } else {
            Image(bitmap.asImageBitmap(), "AI 3D input", Modifier.fillMaxWidth().height(260.dp))
            Button(
                onClick = { onGenerate(bitmap) },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate Full 3D") }
        }
        Text(status, color = Color(0xFFB8C4D9))
    }
}

@Composable
private fun AdviceCard(advice: ReconstructionAdvice) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1820))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AICore Reconstruction Advisor", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("${advice.objectType} • ${advice.surfaceType} • confidence ${advice.confidence}%")
            Text("Recommended: ${advice.recommendedMode}")
            if (advice.reflective) Text("⚠ Reflective surface")
            if (advice.transparent) Text("⚠ Transparent surface")
            if (advice.thinStructures) Text("⚠ Thin structures")
            if (advice.holes) Text("⚠ Holes / occlusion")
            advice.captureAdvice.forEach { Text("• $it") }
        }
    }
}

@Composable
private fun QualityCard(quality: com.ma7moud.reality3d.quality.ReconstructionQuality) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1720))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Quality ${quality.total}% • ${quality.label}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text("Segmentation ${quality.segmentation}% • topology ${quality.topology}%")
            quality.notes.forEach { Text("• $it", color = Color(0xFFB8C4D9)) }
        }
    }
}

@Composable
private fun StatusCard(ai: String, depth: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(ai, color = Color(0xFF93F1FF))
            Text(depth, color = Color(0xFFC6B8FF))
        }
    }
}

private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        File(dir, "capture_${System.currentTimeMillis()}.jpg"),
    )
}

private fun shareUris(context: Context, uris: List<Uri>, type: String) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share Reality3D output"))
}
