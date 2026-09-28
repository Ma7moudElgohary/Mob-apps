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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

enum class HomeMode(val title: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reality3DScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val analyzer = remember { AiCoreAnalyzer() }
    val masker = remember { SubjectMasker() }
    val modelManager = remember { DepthAnythingModelManager(context) }
    val backendSelector = remember { LiteRtBackendSelector(context) }
    val projectStore = remember { ProjectStore(context) }
    val ai3d = remember { Ai3dClient(context) }

    var homeMode by remember { mutableStateOf(HomeMode.QUICK) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskedTexture by remember { mutableStateOf<Bitmap?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var segmentation by remember { mutableStateOf<SegmentationBundle?>(null) }
    var activeMask by remember { mutableStateOf<SubjectMask?>(null) }
    var mesh by remember { mutableStateOf<DepthMesh?>(null) }
    var currentProject by remember { mutableStateOf<RealityProject?>(null) }
    var advice by remember { mutableStateOf<ReconstructionAdvice?>(null) }

    var aiStatus by remember { mutableStateOf("Checking AICore…") }
    var modelReady by remember { mutableStateOf(modelManager.isReady()) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Choose a mode and capture an object.") }
    var busy by remember { mutableStateOf(false) }
    var benchmarkText by remember { mutableStateOf("") }

    var editMode by remember { mutableStateOf<MaskOps.BrushMode?>(null) }
    var brushRadius by remember { mutableFloatStateOf(0.055f) }
    var viewerRef by remember { mutableStateOf<MeshSurfaceView?>(null) }
    var viewerMode by remember { mutableStateOf(ViewerMode.TEXTURE) }
    var autoRotate by remember { mutableStateOf(false) }
    var measurementMode by remember { mutableStateOf(false) }
    var measurementText by remember { mutableStateOf("Set scale, then tap two points") }
    var scaleText by remember { mutableStateOf("") }
    var showOriginal by remember { mutableStateOf(false) }
    var lightAngle by remember { mutableFloatStateOf(0.65f) }

    var aiBackendUrl by remember { mutableStateOf(ai3d.baseUrl) }
    var aiEngine by remember { mutableStateOf("sf3d") }
    var projectRefresh by remember { mutableIntStateOf(0) }

    fun preferredMask(bundle: SegmentationBundle): SubjectMask =
        bundle.subjects.maxByOrNull { it.width * it.height }?.mask ?: bundle.foreground

    fun loadImage(uri: Uri) {
        scope.launch {
            busy = true
            status = "Loading image…"
            runCatching { withContext(Dispatchers.IO) { ImageLoader.load(context, uri) } }
                .onSuccess { image ->
                    bitmap = image
                    mesh = null
                    maskedTexture = null
                    advice = null
                    currentProject = null
                    status = "Detecting subjects…"
                    runCatching { masker.segment(image) }
                        .onSuccess { bundle ->
                            segmentation = bundle
                            activeMask = preferredMask(bundle)
                            status = "Select a subject, refine its mask, or Generate 3D."
                        }
                        .onFailure { error ->
                            segmentation = null
                            activeMask = null
                            status = "Segmentation unavailable: ${error.message}"
                        }
                }
                .onFailure { error -> status = "Image error: ${error.message}" }
            busy = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let(::loadImage)
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(ScanActivity.EXTRA_PROJECT_ID)
            val project = projectStore.list().firstOrNull { it.id == id }
            val loaded = project?.let(projectStore::loadMesh)
            if (project != null && loaded != null) {
                currentProject = project
                mesh = loaded
                bitmap = project.thumbnailPath?.let { BitmapFactory.decodeFile(it) }
                maskedTexture = bitmap
                homeMode = HomeMode.QUICK
                status = "360 scan loaded: ${loaded.triangleCount} triangles. Metric scale is preserved."
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Reality3D",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text("Local reconstruction • ARCore scanning • AI full-3D", color = Color(0xFFAFBED2))
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
            HomeMode.QUICK -> QuickPanel(
                context = context,
                bitmap = bitmap,
                segmentation = segmentation,
                activeMask = activeMask,
                onMask = { activeMask = it },
                editMode = editMode,
                onEditMode = { editMode = it },
                brushRadius = brushRadius,
                onBrushRadius = { brushRadius = it },
                aiStatus = aiStatus,
                advice = advice,
                modelReady = modelReady,
                downloadProgress = downloadProgress,
                busy = busy,
                status = status,
                benchmarkText = benchmarkText,
                mesh = mesh,
                texture = maskedTexture,
                currentProject = currentProject,
                viewerRef = viewerRef,
                setViewer = { viewerRef = it },
                viewerMode = viewerMode,
                setViewerMode = {
                    viewerMode = it
                    viewerRef?.setMode(it)
                },
                autoRotate = autoRotate,
                setAutoRotate = {
                    autoRotate = it
                    viewerRef?.setAutoRotate(it)
                },
                measurementMode = measurementMode,
                setMeasurementMode = {
                    measurementMode = it
                    viewerRef?.measurementEnabled = it
                },
                measurementText = measurementText,
                scaleText = scaleText,
                setScaleText = { scaleText = it },
                showOriginal = showOriginal,
                setShowOriginal = { showOriginal = it },
                lightAngle = lightAngle,
                setLightAngle = {
                    lightAngle = it
                    val angle = it * Math.PI.toFloat() * 2f
                    viewerRef?.setLight(cos(angle), 0.75f, sin(angle))
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
                                status = "Advisor recommends ${it.recommendedMode}."
                            }
                            .onFailure { status = "AICore failed: ${it.message}" }
                        busy = false
                    }
                },
                onDownload = {
                    scope.launch {
                        busy = true
                        status = "Downloading Depth Anything V2…"
                        runCatching { modelManager.download { downloadProgress = it } }
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
                                "${result.backend}: ${result.milliseconds?.let { "${it}ms" } ?: "N/A"}"
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
                        }.onSuccess { (generated, texture, backend) ->
                            mesh = generated
                            maskedTexture = texture
                            val quality = QualityScorer.quick(mask, generated)
                            status = "3D ready: ${generated.triangleCount} triangles • $backend • quality ${quality.total}% (${quality.label})."
                        }.onFailure { status = "3D generation failed: ${it.message}" }
                        busy = false
                    }
                },
                onFeather = { activeMask?.let { activeMask = MaskOps.feather(it) } },
                onAutoRefine = { activeMask?.let { activeMask = MaskOps.autoRefine(it) } },
                onScale = { targetMeters ->
                    mesh?.let {
                        mesh = MeshMath.scaleToWidth(it, targetMeters)
                        status = "Metric scale applied. Measurements and AR Preview now use this scale."
                    }
                },
                onMeasurement = { measurementText = it },
                onSaveProject = { meshValue, textureValue ->
                    val project = currentProject ?: projectStore.create(
                        advice?.objectType?.replaceFirstChar { it.uppercase() } ?: "Quick Object",
                        "quick",
                    )
                    currentProject = projectStore.saveMesh(project, meshValue, textureValue)
                    projectRefresh++
                    status = "Saved to Projects."
                },
                onArPreview = currentProject?.let { project ->
                    {
                        context.startActivity(
                            Intent(context, ArPreviewActivity::class.java)
                                .putExtra(ArPreviewActivity.EXTRA_PROJECT_ID, project.id),
                        )
                    }
                },
            )

            HomeMode.SCAN -> ScanPanel(
                store = projectStore,
                refresh = projectRefresh,
                onStart = { resume ->
                    val project = resume ?: projectStore.create(
                        "360 Scan ${projectStore.list().size + 1}",
                        "scan360",
                    )
                    currentProject = project
                    scanLauncher.launch(
                        Intent(context, ScanActivity::class.java)
                            .putExtra(ScanActivity.EXTRA_PROJECT_ID, project.id),
                    )
                },
            )

            HomeMode.PROJECTS -> ProjectsPanel(
                context = context,
                store = projectStore,
                refresh = projectRefresh,
                onOpen = { project ->
                    projectStore.loadMesh(project)?.let { loaded ->
                        mesh = loaded
                        currentProject = project
                        bitmap = project.thumbnailPath?.let { BitmapFactory.decodeFile(it) }
                        maskedTexture = bitmap
                        homeMode = HomeMode.QUICK
                        status = "Project loaded."
                    }
                },
                onDelete = {
                    projectStore.delete(it)
                    if (currentProject?.id == it.id) currentProject = null
                    projectRefresh++
                },
            )

            HomeMode.AI3D -> Ai3dPanel(
                bitmap = bitmap,
                url = aiBackendUrl,
                setUrl = { aiBackendUrl = it },
                engine = aiEngine,
                setEngine = { aiEngine = it },
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
                                shareUris(
                                    context,
                                    listOf(
                                        FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.files",
                                            result.glb,
                                        ),
                                    ),
                                    "model/gltf-binary",
                                )
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
private fun QuickPanel(
    context: Context,
    bitmap: Bitmap?,
    segmentation: SegmentationBundle?,
    activeMask: SubjectMask?,
    onMask: (SubjectMask) -> Unit,
    editMode: MaskOps.BrushMode?,
    onEditMode: (MaskOps.BrushMode?) -> Unit,
    brushRadius: Float,
    onBrushRadius: (Float) -> Unit,
    aiStatus: String,
    advice: ReconstructionAdvice?,
    modelReady: Boolean,
    downloadProgress: Int,
    busy: Boolean,
    status: String,
    benchmarkText: String,
    mesh: DepthMesh?,
    texture: Bitmap?,
    currentProject: RealityProject?,
    viewerRef: MeshSurfaceView?,
    setViewer: (MeshSurfaceView) -> Unit,
    viewerMode: ViewerMode,
    setViewerMode: (ViewerMode) -> Unit,
    autoRotate: Boolean,
    setAutoRotate: (Boolean) -> Unit,
    measurementMode: Boolean,
    setMeasurementMode: (Boolean) -> Unit,
    measurementText: String,
    scaleText: String,
    setScaleText: (String) -> Unit,
    showOriginal: Boolean,
    setShowOriginal: (Boolean) -> Unit,
    lightAngle: Float,
    setLightAngle: (Float) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onAnalyze: (Bitmap) -> Unit,
    onDownload: () -> Unit,
    onBenchmark: (Bitmap) -> Unit,
    onGenerate: (Bitmap, SubjectMask) -> Unit,
    onFeather: () -> Unit,
    onAutoRefine: () -> Unit,
    onScale: (Float) -> Unit,
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
                Text("Tap a cyan box to select a subject. Use Add / Erase / Auto Refine for difficult edges.")
                MaskEditor(
                    bitmap = image,
                    mask = mask,
                    subjects = segmentation?.subjects.orEmpty(),
                    editMode = editMode,
                    brushRadius = brushRadius,
                    onMaskChanged = onMask,
                    onSubjectSelected = { onMask(it.mask) },
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = editMode == null,
                        onClick = { onEditMode(null) },
                        label = { Text("Select") },
                    )
                    FilterChip(
                        selected = editMode == MaskOps.BrushMode.ADD,
                        onClick = { onEditMode(MaskOps.BrushMode.ADD) },
                        label = { Text("Add") },
                    )
                    FilterChip(
                        selected = editMode == MaskOps.BrushMode.ERASE,
                        onClick = { onEditMode(MaskOps.BrushMode.ERASE) },
                        label = { Text("Erase") },
                    )
                    OutlinedButton(onClick = onAutoRefine) { Text("Auto Refine") }
                    OutlinedButton(onClick = onFeather) { Text("Feather") }
                }
                if (editMode != null) {
                    Text("Brush size")
                    Slider(
                        value = brushRadius,
                        onValueChange = onBrushRadius,
                        valueRange = 0.015f..0.15f,
                    )
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
                if (!modelReady) {
                    Button(
                        onClick = onDownload,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (downloadProgress > 0) "Model $downloadProgress%" else "Get Depth Model")
                    }
                } else {
                    Button(
                        onClick = { activeMask?.let { onGenerate(image, it) } },
                        enabled = !busy && activeMask != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Generate 3D") }
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
        advice?.let { AdviceCard(it) }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(status)
            }
        } else {
            Text(status, color = Color(0xFFB8C4D9))
        }

        mesh?.let { generated ->
            activeMask?.let { mask -> QualityCard(QualityScorer.quick(mask, generated)) }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showOriginal,
                    onClick = { setShowOriginal(!showOriginal) },
                    label = { Text(if (showOriginal) "Show 3D" else "Before / After") },
                )
                ViewerMode.entries.forEach { availableMode ->
                    FilterChip(
                        selected = viewerMode == availableMode,
                        onClick = { setViewerMode(availableMode) },
                        label = {
                            Text(availableMode.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }

            if (showOriginal && bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "Original photograph",
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            } else {
                key(generated) {
                    AndroidView(
                        factory = { ctx ->
                            MeshSurfaceView(ctx, generated, texture) { meters, _, vertexB ->
                                onMeasurement(
                                    if (vertexB < 0) {
                                        "Point 1 selected"
                                    } else {
                                        meters?.let { "Distance: ${"%.1f".format(it * 1000f)} mm" }
                                            ?: "Set scale first"
                                    },
                                )
                            }.also { view ->
                                view.setMode(viewerMode)
                                view.setAutoRotate(autoRotate)
                                view.measurementEnabled = measurementMode
                                val angle = lightAngle * Math.PI.toFloat() * 2f
                                view.setLight(cos(angle), 0.75f, sin(angle))
                                setViewer(view)
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
                AssistChip(onClick = { viewerRef?.resetCamera() }, label = { Text("Reset") })
                AssistChip(onClick = { viewerRef?.preset(ViewerPreset.FRONT) }, label = { Text("Front") })
                AssistChip(onClick = { viewerRef?.preset(ViewerPreset.RIGHT) }, label = { Text("Right") })
                AssistChip(onClick = { viewerRef?.preset(ViewerPreset.BACK) }, label = { Text("Back") })
                AssistChip(onClick = { viewerRef?.preset(ViewerPreset.LEFT) }, label = { Text("Left") })
                AssistChip(onClick = { viewerRef?.preset(ViewerPreset.TOP) }, label = { Text("Top") })
                AssistChip(onClick = { viewerRef?.preset(ViewerPreset.ISO) }, label = { Text("ISO") })
                AssistChip(
                    onClick = { setAutoRotate(!autoRotate) },
                    label = { Text(if (autoRotate) "Stop Rotate" else "Auto Rotate") },
                )
            }

            Text("Light direction")
            Slider(value = lightAngle, onValueChange = setLightAngle, valueRange = 0f..1f)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = scaleText,
                    onValueChange = setScaleText,
                    label = { Text("Known width (cm)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        scaleText.toFloatOrNull()
                            ?.takeIf { it > 0f }
                            ?.let { onScale(it / 100f) }
                    },
                ) { Text("Set Scale") }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = measurementMode,
                    onClick = { setMeasurementMode(!measurementMode) },
                    label = { Text("Measure") },
                    modifier = Modifier.weight(1f),
                )
                Text(measurementText, modifier = Modifier.weight(2f), color = Color(0xFFB8C4D9))
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        shareUris(
                            context,
                            listOf(GlbExporter.export(context, generated, texture)),
                            "model/gltf-binary",
                        )
                    },
                ) { Text("GLB") }
                OutlinedButton(
                    onClick = {
                        val original = bitmap
                        if (original != null) {
                            shareUris(
                                context,
                                ObjExporter.export(context, generated, original),
                                "application/octet-stream",
                            )
                        }
                    },
                ) { Text("OBJ") }
                OutlinedButton(
                    onClick = {
                        shareUris(
                            context,
                            listOf(StlExporter.export(context, generated)),
                            "model/stl",
                        )
                    },
                ) { Text("STL") }
                OutlinedButton(
                    onClick = {
                        shareUris(
                            context,
                            listOf(PlyExporter.export(context, generated)),
                            "application/octet-stream",
                        )
                    },
                ) { Text("PLY") }
                OutlinedButton(
                    onClick = {
                        val textureBitmap = texture
                        if (textureBitmap != null) {
                            shareUris(
                                context,
                                GameReadyExporter.exportUnrealPack(
                                    context,
                                    generated,
                                    textureBitmap,
                                    25_000,
                                ),
                                "model/gltf-binary",
                            )
                        }
                    },
                ) { Text("Unreal Pack") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewerRef?.capture { screenshot ->
                            val file = File(
                                context.cacheDir,
                                "reality3d_${System.currentTimeMillis()}.png",
                            )
                            file.outputStream().use {
                                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            shareUris(
                                context,
                                listOf(
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.files",
                                        file,
                                    ),
                                ),
                                "image/png",
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Screenshot") }
                Button(
                    onClick = { onSaveProject(generated, texture) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save Project") }
            }

            if (currentProject != null && onArPreview != null) {
                OutlinedButton(
                    onClick = onArPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("AR Preview in Real Space") }
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
    val resumableScans = remember(refresh) {
        store.list().filter { it.mode == "scan360" && it.status == "scanning" }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("True 3D Scan", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Walk around the object. Reality3D fuses ARCore Raw Depth, confidence, camera intrinsics and pose into a metric TSDF volume.",
        )
        Button(onClick = { onStart(null) }, modifier = Modifier.fillMaxWidth()) {
            Text("Start New 360° Scan")
        }
        resumableScans.forEach { project ->
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(project.name, fontWeight = FontWeight.Bold)
                    Text("${project.coverage}% coverage • resumable")
                    OutlinedButton(onClick = { onStart(project) }) { Text("Resume Scan") }
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
    val projects = remember(refresh) { store.list() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Projects", style = MaterialTheme.typography.headlineSmall)
        if (projects.isEmpty()) Text("No saved projects yet.")
        projects.forEach { project ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        project.thumbnailPath
                            ?.let { BitmapFactory.decodeFile(it) }
                            ?.let { thumb ->
                                Image(
                                    thumb.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp),
                                )
                            }
                        Column(Modifier.weight(1f)) {
                            Text(project.name, fontWeight = FontWeight.Bold)
                            Text("${project.mode} • ${project.triangleCount} triangles • ${project.coverage}%")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onOpen(project) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open") }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, ArPreviewActivity::class.java)
                                        .putExtra(ArPreviewActivity.EXTRA_PROJECT_ID, project.id),
                                )
                            },
                            enabled = project.meshPath != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("AR") }
                        TextButton(onClick = { onDelete(project) }) { Text("Delete") }
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
    setUrl: (String) -> Unit,
    engine: String,
    setEngine: (String) -> Unit,
    busy: Boolean,
    status: String,
    onGenerate: (Bitmap) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI Full 3D", style = MaterialTheme.typography.headlineSmall)
        Text(
            "For inferred hidden geometry. Connect a GPU backend running Stable Fast 3D or Hunyuan3D. Quick Local and Scan 360 remain on-device.",
        )
        OutlinedTextField(
            value = url,
            onValueChange = setUrl,
            label = { Text("GPU backend URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = engine == "sf3d",
                onClick = { setEngine("sf3d") },
                label = { Text("Stable Fast 3D") },
            )
            FilterChip(
                selected = engine == "hunyuan",
                onClick = { setEngine("hunyuan") },
                label = { Text("Hunyuan3D") },
            )
        }
        bitmap?.let { image ->
            Image(
                image.asImageBitmap(),
                contentDescription = "AI 3D input",
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
            Button(
                onClick = { onGenerate(image) },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate Full 3D") }
        } ?: Text("Choose a photo in Quick Local first.")
        Text(status, color = Color(0xFFB8C4D9))
    }
}

@Composable
private fun AdviceCard(advice: ReconstructionAdvice) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1820))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "AICore Reconstruction Advisor",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
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
