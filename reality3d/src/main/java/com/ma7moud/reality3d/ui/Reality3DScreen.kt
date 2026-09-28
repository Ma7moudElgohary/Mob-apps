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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ma7moud.reality3d.data.ImageLoader
import com.ma7moud.reality3d.depth.*
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

private val RealityColors = darkColorScheme(
    primary = Color(0xFF50E3FF), secondary = Color(0xFF9A7CFF), background = Color(0xFF05070B),
    surface = Color(0xFF0D1320), onBackground = Color(0xFFF3F7FF), onSurface = Color(0xFFF3F7FF),
)

enum class HomeMode(val title: String) { QUICK("Quick Local"), SCAN("Scan 360"), PROJECTS("Projects"), AI3D("AI 3D") }

@Composable fun Reality3DApp() { MaterialTheme(colorScheme = RealityColors) { Reality3DScreen() } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Reality3DScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val analyzer = remember { AiCoreAnalyzer() }
    val masker = remember { SubjectMasker() }
    val modelManager = remember { DepthAnythingModelManager(context) }
    val backendSelector = remember { LiteRtBackendSelector(context) }
    val projects = remember { ProjectStore(context) }
    val ai3d = remember { Ai3dClient(context) }

    var mode by remember { mutableStateOf(HomeMode.QUICK) }
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
    var measurementText by remember { mutableStateOf("Tap two points after setting scale") }
    var scaleText by remember { mutableStateOf("") }
    var showOriginal by remember { mutableStateOf(false) }
    var aiBackendUrl by remember { mutableStateOf(ai3d.baseUrl) }
    var aiEngine by remember { mutableStateOf("sf3d") }
    var projectRefresh by remember { mutableIntStateOf(0) }

    fun chooseMask(bundle: SegmentationBundle): SubjectMask = bundle.subjects.maxByOrNull { it.width * it.height }?.mask ?: bundle.foreground

    fun load(uri: Uri) {
        scope.launch {
            busy = true; status = "Loading image…"
            runCatching { withContext(Dispatchers.IO) { ImageLoader.load(context, uri) } }
                .onSuccess { image ->
                    bitmap = image; mesh = null; maskedTexture = null; advice = null; currentProject = null
                    status = "Detecting subjects…"
                    runCatching { masker.segment(image) }.onSuccess { bundle -> segmentation = bundle; activeMask = chooseMask(bundle); status = "Select a subject, refine its mask, or Generate 3D." }
                        .onFailure { segmentation = null; activeMask = null; status = "Segmentation unavailable: ${it.message}" }
                }.onFailure { status = "Image error: ${it.message}" }
            busy = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::load) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::load) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getStringExtra(ScanActivity.EXTRA_PROJECT_ID)
            val project = projects.list().firstOrNull { it.id == id }
            val loaded = project?.let(projects::loadMesh)
            if (project != null && loaded != null) { currentProject = project; mesh = loaded; bitmap = project.thumbnailPath?.let { BitmapFactory.decodeFile(it) }; maskedTexture = bitmap; mode = HomeMode.QUICK; status = "360 scan loaded: ${loaded.triangleCount} triangles." }
            projectRefresh++
        }
    }

    LaunchedEffect(Unit) {
        aiStatus = runCatching { when(analyzer.status()) {
            AiCoreState.Available -> "AICore / Gemini Nano: ready on-device"
            AiCoreState.Downloadable -> "AICore: model download available"
            AiCoreState.Downloading -> "AICore: model downloading"
            AiCoreState.Unavailable -> "AICore: unavailable on this device/config"
        } }.getOrElse { "AICore check failed: ${it.message}" }
    }
    DisposableEffect(Unit) { onDispose { analyzer.close(); masker.close() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Reality3D", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Local reconstruction • ARCore scanning • AI full-3D", color = Color(0xFFAFBED2))
        }
        ScrollableTabRow(selectedTabIndex = mode.ordinal, edgePadding = 8.dp) {
            HomeMode.entries.forEach { item -> Tab(selected = mode == item, onClick = { mode = item }, text = { Text(item.title) }) }
        }
        when(mode) {
            HomeMode.QUICK -> QuickPanel(context, scope, bitmap, segmentation, activeMask, { activeMask = it }, editMode, { editMode = it }, brushRadius, { brushRadius = it },
                aiStatus, advice, modelReady, downloadProgress, busy, status, benchmarkText, mesh, maskedTexture, viewerRef, { viewerRef = it }, viewerMode, { viewerMode = it; viewerRef?.setMode(it) },
                autoRotate, { autoRotate = it; viewerRef?.setAutoRotate(it) }, measurementMode, { measurementMode = it; viewerRef?.measurementEnabled = it }, measurementText, scaleText, { scaleText = it }, showOriginal, { showOriginal = it },
                onCamera = { val uri=createCameraUri(context);cameraUri=uri;camera.launch(uri) }, onGallery={gallery.launch("image/*")},
                onAnalyze={ image -> scope.launch { busy=true;status="AICore is evaluating reconstruction risks…";runCatching{analyzer.analyze(image)}.onSuccess{advice=it;status="Advisor recommends ${it.recommendedMode}."}.onFailure{status="AICore failed: ${it.message}"};busy=false } },
                onDownload={ scope.launch { busy=true;status="Downloading Depth Anything V2…";runCatching{modelManager.download{downloadProgress=it}}.onSuccess{modelReady=true;status="Depth Anything V2 ready."}.onFailure{status="Model download failed: ${it.message}"};busy=false } },
                onBenchmark={ image -> scope.launch { busy=true;status="Benchmarking CPU/GPU/NPU…";runCatching{backendSelector.benchmark(modelManager.modelFile,DepthAnythingEstimator(modelManager.modelFile,backendSelector).prepareInput(image))}.onSuccess{results->benchmarkText=results.joinToString(" • "){r->"${r.backend}: ${r.milliseconds?.let{"${it}ms"}?:"N/A"}"};status="Fastest backend saved."}.onFailure{status="Benchmark failed: ${it.message}"};busy=false } },
                onGenerate={ image, mask -> scope.launch { busy=true;status="Depth Anything V2 → edge refinement → adaptive mesh…";runCatching{withContext(Dispatchers.Default){val result=DepthAnythingEstimator(modelManager.modelFile,backendSelector).estimate(image);val refined=DepthRefiner.edgeAware(result.depth,image,mask);val generated=AdaptiveMeshBuilder.fromDepth(refined,mask,image.width.toFloat()/image.height);Triple(generated,TextureBuilder.masked(image,mask),result.backend)}}.onSuccess{(generated,texture,backend)->mesh=generated;maskedTexture=texture;status="3D ready with ${generated.triangleCount} triangles on $backend."}.onFailure{status="3D generation failed: ${it.message}"};busy=false } },
                onFeather={ activeMask?.let { activeMask = MaskOps.feather(it) } },
                onScale={ targetMeters -> mesh?.let { mesh = MeshMath.scaleToWidth(it,targetMeters); status="Metric scale applied." } },
                onMeasurement={ text -> measurementText=text },
                onSaveProject={ meshValue, texture -> val p=currentProject?:projects.create(advice?.objectType?.replaceFirstChar{it.uppercase()}?:"Quick Object","quick");currentProject=projects.saveMesh(p,meshValue,texture);projectRefresh++;status="Saved to Projects." },
            )
            HomeMode.SCAN -> ScanPanel(projects, projectRefresh, onStart = { resume ->
                val project = resume ?: projects.create("360 Scan ${projects.list().size + 1}", "scan360")
                currentProject = project
                scanLauncher.launch(Intent(context, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_PROJECT_ID, project.id))
            })
            HomeMode.PROJECTS -> ProjectsPanel(projects, projectRefresh, onOpen = { project -> projects.loadMesh(project)?.let { mesh=it;currentProject=project;bitmap=project.thumbnailPath?.let(BitmapFactory::decodeFile);maskedTexture=bitmap;mode=HomeMode.QUICK;status="Project loaded." } }, onDelete={projects.delete(it);projectRefresh++})
            HomeMode.AI3D -> Ai3dPanel(bitmap, aiBackendUrl, {aiBackendUrl=it}, aiEngine, {aiEngine=it}, busy, status, onGenerate={ image -> scope.launch { busy=true;ai3d.baseUrl=aiBackendUrl;status="GPU backend is generating full 3D…";runCatching{ai3d.generate(image,aiEngine)}.onSuccess{result->status=result.message;shareUris(context,listOf(FileProvider.getUriForFile(context,"${context.packageName}.files",result.glb)),"model/gltf-binary")}.onFailure{status="AI 3D failed: ${it.message}"};busy=false } })
        }
    }
}

@Composable private fun QuickPanel(
    context: Context, scope: kotlinx.coroutines.CoroutineScope, bitmap: Bitmap?, segmentation: SegmentationBundle?, activeMask: SubjectMask?, onMask:(SubjectMask)->Unit,
    editMode:MaskOps.BrushMode?, onEditMode:(MaskOps.BrushMode?)->Unit, brushRadius:Float, onBrushRadius:(Float)->Unit,
    aiStatus:String, advice:ReconstructionAdvice?, modelReady:Boolean, downloadProgress:Int, busy:Boolean, status:String, benchmarkText:String,
    mesh:DepthMesh?, texture:Bitmap?, viewerRef:MeshSurfaceView?, setViewer:(MeshSurfaceView)->Unit, viewerMode:ViewerMode, setViewerMode:(ViewerMode)->Unit,
    autoRotate:Boolean, setAutoRotate:(Boolean)->Unit, measurementMode:Boolean, setMeasurementMode:(Boolean)->Unit, measurementText:String, scaleText:String, setScaleText:(String)->Unit,
    showOriginal:Boolean, setShowOriginal:(Boolean)->Unit, onCamera:()->Unit, onGallery:()->Unit, onAnalyze:(Bitmap)->Unit, onDownload:()->Unit, onBenchmark:(Bitmap)->Unit,
    onGenerate:(Bitmap,SubjectMask)->Unit, onFeather:()->Unit, onScale:(Float)->Unit, onMeasurement:(String)->Unit, onSaveProject:(DepthMesh,Bitmap?)->Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        StatusCard(aiStatus, if(modelReady)"Depth Anything V2: ready" else "Depth Anything V2: download required")
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) { Button(onClick=onCamera,modifier=Modifier.weight(1f)){Text("Camera")};OutlinedButton(onClick=onGallery,modifier=Modifier.weight(1f)){Text("Gallery")} }
        bitmap?.let { image ->
            activeMask?.let { mask ->
                Text("Tap a cyan box to select a subject. Use Add/Erase to fix the mask.")
                MaskEditor(image,mask,segmentation?.subjects.orEmpty(),editMode,brushRadius,onMask,{onMask(it.mask)})
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=editMode==null,onClick={onEditMode(null)},label={Text("Select")})
                    FilterChip(selected=editMode==MaskOps.BrushMode.ADD,onClick={onEditMode(MaskOps.BrushMode.ADD)},label={Text("Add")})
                    FilterChip(selected=editMode==MaskOps.BrushMode.ERASE,onClick={onEditMode(MaskOps.BrushMode.ERASE)},label={Text("Erase")})
                    OutlinedButton(onClick=onFeather){Text("Feather")}
                }
                if(editMode!=null){Text("Brush size");Slider(value=brushRadius,onValueChange=onBrushRadius,valueRange=0.015f..0.15f)}
            } ?: Image(image.asImageBitmap(),"Selected",Modifier.fillMaxWidth().height(260.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={onAnalyze(image)},enabled=!busy,modifier=Modifier.weight(1f)){Text("AICore Advisor")}
                if(!modelReady) Button(onClick=onDownload,enabled=!busy,modifier=Modifier.weight(1f)){Text(if(downloadProgress>0)"Model $downloadProgress%" else "Get Depth Model")}
                else Button(onClick={activeMask?.let{onGenerate(image,it)}},enabled=!busy&&activeMask!=null,modifier=Modifier.weight(1f)){Text("Generate 3D")}
            }
            if(modelReady) OutlinedButton(onClick={onBenchmark(image)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Benchmark CPU / GPU / NPU")}
        }
        if(benchmarkText.isNotBlank()) Text(benchmarkText,color=Color(0xFFC5B7FF))
        advice?.let { AdviceCard(it) }
        if(busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp);Text(status)} else Text(status,color=Color(0xFFB8C4D9))
        mesh?.let { generated ->
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(showOriginal,{setShowOriginal(!showOriginal)},{Text(if(showOriginal)"Show 3D" else "Before / After")})
                ViewerMode.entries.forEach { m -> FilterChip(viewerMode==m,{setViewerMode(m)},{Text(m.name.lowercase().replaceFirstChar{it.uppercase()})}) }
            }
            if(showOriginal&&bitmap!=null) Image(bitmap.asImageBitmap(),"Original",Modifier.fillMaxWidth().height(420.dp)) else key(generated) {
                AndroidView(factory={ctx->MeshSurfaceView(ctx,generated,texture){meters,a,b->onMeasurement(if(b<0)"Point 1 selected" else meters?.let{"Distance: ${"%.1f".format(it*1000)} mm"}?:"Set scale first")}.also{it.setMode(viewerMode);it.setAutoRotate(autoRotate);it.measurementEnabled=measurementMode;setViewer(it)}},modifier=Modifier.fillMaxWidth().height(420.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick={viewerRef?.resetCamera()},label={Text("Reset")});AssistChip(onClick={viewerRef?.preset(ViewerPreset.FRONT)},label={Text("Front")});AssistChip(onClick={viewerRef?.preset(ViewerPreset.RIGHT)},label={Text("Right")});AssistChip(onClick={viewerRef?.preset(ViewerPreset.BACK)},label={Text("Back")});AssistChip(onClick={setAutoRotate(!autoRotate)},label={Text(if(autoRotate)"Stop Rotate" else "Auto Rotate")})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(scaleText,setScaleText,label={Text("Known width (cm)")},modifier=Modifier.weight(1f),singleLine=true)
                Button(onClick={scaleText.toFloatOrNull()?.takeIf{it>0}?.let{onScale(it/100f)}}){Text("Set Scale")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(measurementMode,{setMeasurementMode(!measurementMode)},{Text("Measure")},modifier=Modifier.weight(1f));Text(measurementText,modifier=Modifier.weight(2f),color=Color(0xFFB8C4D9))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={shareUris(context,listOf(GlbExporter.export(context,generated,texture)),"model/gltf-binary")}){Text("GLB")}
                OutlinedButton(onClick={bitmap?.let{shareUris(context,ObjExporter.export(context,generated,it),"application/octet-stream")}}){Text("OBJ")}
                OutlinedButton(onClick={shareUris(context,listOf(StlExporter.export(context,generated)),"model/stl")}){Text("STL")}
                OutlinedButton(onClick={shareUris(context,listOf(PlyExporter.export(context,generated)),"application/octet-stream")}){Text("PLY")}
                OutlinedButton(onClick={if(texture!=null){shareUris(context,GameReadyExporter.exportUnrealPack(context,generated,texture,25_000),"model/gltf-binary")}}){Text("Unreal Pack")}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={viewerRef?.capture{shot->val f=File(context.cacheDir,"reality3d_${System.currentTimeMillis()}.png");f.outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shareUris(context,listOf(FileProvider.getUriForFile(context,"${context.packageName}.files",f)),"image/png")}},modifier=Modifier.weight(1f)){Text("Screenshot")}
                Button(onClick={onSaveProject(generated,texture)},modifier=Modifier.weight(1f)){Text("Save Project")}
            }
        }
    }
}

@Composable private fun ScanPanel(store:ProjectStore,refresh:Int,onStart:(RealityProject?)->Unit){val scans=remember(refresh){store.list().filter{it.mode=="scan360"&&it.status=="scanning"}};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("True 3D Scan",style=MaterialTheme.typography.headlineSmall);Text("Walk around the object. Reality3D fuses ARCore Raw Depth + confidence + camera pose into a metric TSDF volume.");Button(onClick={onStart(null)},modifier=Modifier.fillMaxWidth()){Text("Start New 360° Scan")};scans.forEach{p->Card{Column(Modifier.padding(14.dp)){Text(p.name,fontWeight=FontWeight.Bold);Text("${p.coverage}% coverage • resumable");OutlinedButton(onClick={onStart(p)}){Text("Resume Scan")}}}}}}

@Composable private fun ProjectsPanel(store:ProjectStore,refresh:Int,onOpen:(RealityProject)->Unit,onDelete:(RealityProject)->Unit){val list=remember(refresh){store.list()};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Projects",style=MaterialTheme.typography.headlineSmall);if(list.isEmpty())Text("No saved projects yet.");list.forEach{p->Card{Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){p.thumbnailPath?.let{BitmapFactory.decodeFile(it)}?.let{Image(it.asImageBitmap(),null,Modifier.size(72.dp))};Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold);Text("${p.mode} • ${p.triangleCount} triangles • ${p.coverage}%")};OutlinedButton(onClick={onOpen(p)}){Text("Open")};TextButton(onClick={onDelete(p)}){Text("Delete")}}}}}}

@Composable private fun Ai3dPanel(bitmap:Bitmap?,url:String,setUrl:(String)->Unit,engine:String,setEngine:(String)->Unit,busy:Boolean,status:String,onGenerate:(Bitmap)->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("AI Full 3D",style=MaterialTheme.typography.headlineSmall);Text("For inferred hidden geometry. Configure a GPU backend running Stable Fast 3D or Hunyuan3D. Quick Local and Scan 360 remain fully local.");OutlinedTextField(url,setUrl,label={Text("GPU backend URL")},modifier=Modifier.fillMaxWidth(),singleLine=true);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(engine=="sf3d",{setEngine("sf3d")},{Text("Stable Fast 3D")});FilterChip(engine=="hunyuan",{setEngine("hunyuan")},{Text("Hunyuan3D")})};bitmap?.let{Image(it.asImageBitmap(),"Input",Modifier.fillMaxWidth().height(260.dp));Button(onClick={onGenerate(it)},enabled=!busy&&url.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Generate Full 3D")}}?:Text("Choose a photo in Quick Local first.");Text(status,color=Color(0xFFB8C4D9))}}

@Composable private fun AdviceCard(a:ReconstructionAdvice){Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF0A1820))){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text("AICore Reconstruction Advisor",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);Text("${a.objectType} • ${a.surfaceType} • confidence ${a.confidence}%");Text("Recommended: ${a.recommendedMode}");if(a.reflective)Text("⚠ Reflective surface");if(a.transparent)Text("⚠ Transparent surface");if(a.thinStructures)Text("⚠ Thin structures");if(a.holes)Text("⚠ Holes / occlusion");a.captureAdvice.forEach{Text("• $it")}}}}
@Composable private fun StatusCard(ai:String,depth:String){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(ai,color=Color(0xFF93F1FF));Text(depth,color=Color(0xFFC6B8FF))}}}
private fun createCameraUri(context:Context):Uri{val dir=File(context.cacheDir,"camera").apply{mkdirs()};return FileProvider.getUriForFile(context,"${context.packageName}.files",File(dir,"capture_${System.currentTimeMillis()}.jpg"))}
private fun shareUris(context:Context,uris:List<Uri>,type:String){if(uris.isEmpty())return;val intent=if(uris.size==1)Intent(Intent.ACTION_SEND).apply{this.type=type;putExtra(Intent.EXTRA_STREAM,uris.first())}else Intent(Intent.ACTION_SEND_MULTIPLE).apply{this.type=type;putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))};intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);context.startActivity(Intent.createChooser(intent,"Share Reality3D output"))}
