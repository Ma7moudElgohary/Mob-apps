package com.ma7moud.reality3d.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ma7moud.reality3d.project.ProjectStore

class ArPreviewActivity : ComponentActivity() {
    private var arView: ArPreviewView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_PROJECT_ID)
        val store = ProjectStore(this)
        val project = store.list().firstOrNull { it.id == id }
        val mesh = project?.let(store::loadMesh)
        setContent {
            var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
            LaunchedEffect(Unit) { if (!permission) launcher.launch(Manifest.permission.CAMERA) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (permission && mesh != null) {
                    AndroidView(factory = { ArPreviewView(this@ArPreviewActivity, this@ArPreviewActivity, mesh).also { arView = it; it.resumeAr() } }, modifier = Modifier.fillMaxSize())
                    Card(Modifier.align(Alignment.TopCenter).padding(16.dp)) { Text("Tap a detected surface to place the model • drag to rotate • pinch to scale", Modifier.padding(12.dp)) }
                    OutlinedButton(onClick = { arView?.clearPlacement() }, modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp)) { Text("Place Again") }
                } else if (mesh == null) Text("This project has no mesh yet.", Modifier.align(Alignment.Center), color = Color.White)
            }
        }
    }
    override fun onResume() { super.onResume(); arView?.resumeAr() }
    override fun onPause() { arView?.pauseAr(); super.onPause() }
    companion object { const val EXTRA_PROJECT_ID = "project_id" }
}
