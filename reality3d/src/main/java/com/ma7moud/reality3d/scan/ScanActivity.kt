package com.ma7moud.reality3d.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ma7moud.reality3d.project.ProjectStore
import java.io.File

class ScanActivity : ComponentActivity(), ArScanView.Callbacks {
    private var scanView: ArScanView? = null
    private var coverage by mutableStateOf(CoverageState())
    private var status by mutableStateOf("Starting ARCore…")
    private var error by mutableStateOf<String?>(null)
    private var savingResult by mutableStateOf(false)
    private var leavingForPause = false
    private lateinit var store: ProjectStore
    private var projectId: String? = null
    private val tempScan by lazy { File(cacheDir, "scan_temp.r3ds") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        setContent { ScanUi() }
    }

    @Composable
    private fun ScanUi() {
        var permissionGranted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted = it }
        LaunchedEffect(Unit) { if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA) }
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4DEBFF), background = Color.Black)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (permissionGranted) {
                    AndroidView(
                        factory = {
                            ArScanView(this@ScanActivity, this@ScanActivity, projectResumeFile(), this@ScanActivity).also { view ->
                                scanView = view
                                view.resumeAr()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("Camera permission is required for Scan 360", Modifier.align(Alignment.Center), color = Color.White)
                }
                val reticleColor = if (coverage.frameUsable) Color(0xAA45F57A) else Color(0x663FFFFF)
                Box(Modifier.size(24.dp).align(Alignment.Center).background(reticleColor, CircleShape))
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(progress = { coverage.coveragePercent / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${coverage.coveragePercent}% • ${coverage.message()}", color = Color.White)
                    Text(
                        "Depth ${(coverage.depthConfidence * 100).toInt()}% • ${coverage.depthPointCount} pts • ${"%.2f".format(coverage.distanceMeters)} m",
                        color = Color(0xFFB9C8D8),
                    )
                    Text(
                        "Aim ${"%.0f".format(coverage.aimErrorDegrees)}° • Motion ${"%.2f".format(coverage.speedMetersPerSecond)} m/s • ${"%.0f".format(coverage.angularSpeedDegreesPerSecond)}°/s",
                        color = Color(0xFFB9C8D8),
                    )
                    if (status != coverage.message()) Text(status, color = Color(0xFFB9C8D8))
                }
                error?.let {
                    Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                        Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (savingResult) {
                    Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                        Row(
                            Modifier.padding(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Saving reconstructed model…")
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            leavingForPause = true
                            persistResume { finish() }
                        },
                        enabled = !savingResult,
                        modifier = Modifier.weight(1f),
                    ) { Text("Pause") }
                    Button(
                        onClick = { scanView?.finishScan() },
                        enabled = !savingResult && coverage.coveragePercent >= 30,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (coverage.coveragePercent >= 85) "Finish Scan" else "Build Preview") }
                }
            }
        }
    }

    private fun projectResumeFile(): File? = projectId
        ?.let { id -> store.list().firstOrNull { it.id == id }?.scanPath?.let(::File) }
        ?: tempScan.takeIf { it.exists() }

    private fun persistResume(onComplete: (() -> Unit)? = null) {
        val project = projectId?.let { id -> store.list().firstOrNull { it.id == id } }
        val file = File(cacheDir, "scan_${project?.id ?: "temp"}.r3ds")
        val view = scanView
        if (view == null) {
            onComplete?.invoke()
            return
        }
        view.saveSession(file) { success ->
            if (success && project != null) {
                runCatching { store.attachScan(project, file, coverage.coveragePercent) }
            }
            onComplete?.invoke()
        }
    }

    override fun onResume() {
        super.onResume()
        leavingForPause = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanView?.resumeAr()
        }
    }

    override fun onPause() {
        if (!leavingForPause && !savingResult) persistResume()
        scanView?.pauseAr()
        super.onPause()
    }

    override fun onCoverage(state: CoverageState) {
        coverage = state
        status = state.message()
    }

    override fun onStatus(message: String) {
        status = message
    }

    override fun onError(message: String) {
        error = message
    }

    override fun onMeshReady(
        mesh: com.ma7moud.reality3d.mesh.DepthMesh,
        volume: SparseTsdfVolume,
        target: Vector3?,
        coverageBins: BooleanArray,
    ) {
        if (mesh.triangleCount == 0) {
            scanView?.allowFinishRetry()
            error = "Not enough overlapping depth data yet. Keep moving around the object."
            return
        }

        savingResult = true
        error = null
        status = "Saving reconstructed model…"
        leavingForPause = true
        val finalCoverage = coverage.coveragePercent
        val existingProjectId = projectId

        Thread {
            val result = runCatching {
                val project = existingProjectId
                    ?.let { id -> store.list().firstOrNull { it.id == id } }
                    ?: store.create("360 Scan", "scan360")
                val saved = store.saveMesh(project, mesh, null, finalCoverage)
                val sessionFile = File(cacheDir, "scan_${saved.id}.r3ds")
                ScanSessionStore.save(sessionFile, volume, target, coverageBins)
                store.attachScan(saved, sessionFile, finalCoverage)
            }

            runOnUiThread {
                result.onSuccess { savedProject ->
                    savingResult = false
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PROJECT_ID, savedProject.id))
                    finish()
                }.onFailure { failure ->
                    savingResult = false
                    leavingForPause = false
                    scanView?.allowFinishRetry()
                    error = "Could not save scan: ${failure.message ?: failure.javaClass.simpleName}"
                    status = "Scan is still in memory — try Finish again."
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
