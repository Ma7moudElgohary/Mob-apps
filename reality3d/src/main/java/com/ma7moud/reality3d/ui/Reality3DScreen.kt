package com.ma7moud.reality3d.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.ma7moud.reality3d.ai.AiCoreAnalyzer
import com.ma7moud.reality3d.ai.AiCoreState
import com.ma7moud.reality3d.data.ImageLoader
import com.ma7moud.reality3d.depth.DepthModelManager
import com.ma7moud.reality3d.depth.MidasDepthEstimator
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshBuilder
import com.ma7moud.reality3d.mesh.ObjExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val RealityColors = darkColorScheme(primary = Color(0xFF50E3FF), secondary = Color(0xFF9A7CFF), background = Color(0xFF05070B), surface = Color(0xFF0D1320), onBackground = Color(0xFFF3F7FF), onSurface = Color(0xFFF3F7FF))

@Composable fun Reality3DApp() { MaterialTheme(colorScheme = RealityColors) { Reality3DScreen() } }

@Composable private fun Reality3DScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember { AiCoreAnalyzer() }
    val modelManager = remember { DepthModelManager(context) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var mesh by remember { mutableStateOf<DepthMesh?>(null) }
    var aiStatus by remember { mutableStateOf("Checking AICore…") }
    var aiAnalysis by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(modelManager.isReady()) }
    var status by remember { mutableStateOf("Choose or photograph one object.") }
    var busy by remember { mutableStateOf(false) }

    fun load(uri: Uri) {
        scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { ImageLoader.load(context, uri) } }
                .onSuccess { bitmap = it; mesh = null; aiAnalysis = ""; status = "Photo ready. Analyze it or generate a depth mesh." }
                .onFailure { status = "Image error: ${it.message}" }
            busy = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) load(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::load) }

    LaunchedEffect(Unit) {
        aiStatus = runCatching { when (analyzer.status()) {
            AiCoreState.Available -> "AICore / Gemini Nano: ready on-device"
            AiCoreState.Downloadable -> "AICore: model available, download required"
            AiCoreState.Downloading -> "AICore: model downloading"
            AiCoreState.Unavailable -> "AICore: unavailable on this device/config"
        } }.getOrElse { "AICore check failed: ${it.message}" }
    }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Reality3D", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("Photo → on-device depth → textured 3D mesh", style = MaterialTheme.typography.titleMedium)
        StatusCard(aiStatus, if (modelReady) "MiDaS depth model: ready" else "MiDaS depth model: not downloaded")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { val uri = createCameraUri(context); cameraUri = uri; camera.launch(uri) }, modifier = Modifier.weight(1f)) { Text("Camera") }
            OutlinedButton(onClick = { gallery.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Gallery") }
        }
        bitmap?.let { image ->
            Card { Image(image.asImageBitmap(), "Selected object", Modifier.fillMaxWidth().height(280.dp), contentScale = ContentScale.Fit) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    scope.launch { busy = true; status = "AICore is inspecting the object locally…"; runCatching { analyzer.analyze(image) }.onSuccess { aiAnalysis = it; status = "AICore analysis complete." }.onFailure { status = "AICore analysis failed: ${it.message}" }; busy = false }
                }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("AICore Analyze") }
                if (!modelReady) {
                    Button(onClick = { scope.launch { busy = true; status = "Downloading MiDaS depth model…"; runCatching { modelManager.download() }.onSuccess { modelReady = true; status = "Depth model ready." }.onFailure { status = "Model download failed: ${it.message}" }; busy = false } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Get 3D Model") }
                } else {
                    Button(onClick = { scope.launch { busy = true; status = "Estimating depth and constructing mesh on-device…"; runCatching { withContext(Dispatchers.Default) { MeshBuilder.fromDepth(MidasDepthEstimator(modelManager.modelFile).estimate(image)) } }.onSuccess { mesh = it; status = "3D depth mesh ready. Drag to rotate." }.onFailure { status = "3D generation failed: ${it.message}" }; busy = false } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Generate 3D") }
                }
            }
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text(status) } else Text(status, color = Color(0xFFB8C4D9))
        if (aiAnalysis.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1820))) { Column(Modifier.padding(14.dp)) { Text("AICore object report", color = MaterialTheme.colorScheme.primary); Text(aiAnalysis) } }
        mesh?.let { generated ->
            Card { Box(Modifier.fillMaxWidth().height(420.dp)) { AndroidView(factory = { MeshSurfaceView(it, generated, requireNotNull(bitmap)) }, modifier = Modifier.fillMaxSize()); Text("DRAG TO ORBIT", Modifier.align(Alignment.TopCenter).padding(10.dp), color = Color(0xFFB8F5FF)) } }
            Button(onClick = { shareFiles(context, ObjExporter.export(context, generated, requireNotNull(bitmap))) }, modifier = Modifier.fillMaxWidth()) { Text("Export OBJ + Texture") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF11131A)), shape = RoundedCornerShape(16.dp)) { Text("This MVP creates a real textured 2.5D mesh from monocular depth. It cannot know the hidden back of an object from one photo. Full 360 reconstruction is the next engine upgrade.", Modifier.padding(14.dp)) }
    }
}

@Composable private fun StatusCard(ai: String, depth: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(ai, color = Color(0xFF93F1FF)); Text(depth, color = Color(0xFFC6B8FF)) } } }
private fun createCameraUri(context: Context): Uri { val dir = File(context.cacheDir, "camera").apply { mkdirs() }; return FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "capture_${System.currentTimeMillis()}.jpg")) }
private fun shareFiles(context: Context, uris: List<Uri>) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "application/octet-stream"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Export Reality3D mesh")) }
