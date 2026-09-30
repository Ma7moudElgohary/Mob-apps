package com.ma7moud.reality3d.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.ma7moud.reality3d.Reality3DApplication
import com.ma7moud.reality3d.preview.ArPreview
import com.ma7moud.reality3d.preview.ArPreviewStatus
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.scan.ScanSupport
import kotlinx.coroutines.delay
import java.util.Locale

private val Panel = Color(0xE60B111C)

/** The model standing in the room at its real size, through the camera. */
@Composable
internal fun ArPreviewScreen(model: PreviewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context) { (context.applicationContext as Reality3DApplication).services.arPreview }
    val activity = remember(context) { context.findActivityOrNull() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var support by remember { mutableStateOf<ScanSupport>(ScanSupport.Checking) }
    var installAsked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    // Checks ARCore when the screen opens and whenever it comes back, e.g. from installing ARCore.
    DisposableEffect(lifecycleOwner, activity, granted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && granted && activity != null) {
                support = factory.check(activity, userRequestedInstall = !installAsked)
                if (support == ScanSupport.Installing) installAsked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // ARCore sometimes needs a moment to answer.
    LaunchedEffect(support, granted) {
        var attempts = 0
        while (granted && support == ScanSupport.Checking && activity != null && attempts++ < 20) {
            delay(300)
            support = factory.check(activity, userRequestedInstall = false)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = support
        when {
            !granted -> Message("Showing the model in your room needs the camera.", onClose) {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
            current is ScanSupport.Unsupported -> Message(current.reason, onClose)
            current == ScanSupport.Installing -> Message("Finish installing Google Play Services for AR, then come back here.", onClose)
            current == ScanSupport.Checking -> Message("Checking ARCore…", onClose) { CircularProgressIndicator() }
            else -> LivePreview(model, onClose)
        }
    }
}

@Composable
private fun LivePreview(model: PreviewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preview: ArPreview = remember(model) {
        (context.applicationContext as Reality3DApplication).services.arPreview.create(context, model)
    }
    val status by preview.status.collectAsStateWithLifecycle()
    DisposableEffect(lifecycle, preview) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> preview.resume()
                Lifecycle.Event.ON_PAUSE -> preview.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.pause()
            preview.close()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { preview.view }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(model.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
            }
            val instruction = when (status.phase) {
                ArPreviewStatus.Phase.STARTING, ArPreviewStatus.Phase.FINDING_SURFACE ->
                    "Move the phone slowly over the floor or a table until a ring appears."
                ArPreviewStatus.Phase.READY_TO_PLACE -> "Tap where the model should stand."
                ArPreviewStatus.Phase.PLACED -> "Drag to turn it, pinch to resize it, tap elsewhere to move it."
                ArPreviewStatus.Phase.FAILED -> status.message ?: "The AR view stopped."
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                status.trackingProblem?.let { Text(it, color = Color(0xFFFFC857), fontWeight = FontWeight.SemiBold) }
                Text(instruction, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                if (status.phase != ArPreviewStatus.Phase.FAILED) {
                    status.message?.let { Text(it, color = Color(0xFFB8C4D9), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (status.phase == ArPreviewStatus.Phase.PLACED) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val size = model.mesh.size.map { it * model.metersPerUnit * status.scale }.toFloatArray()
                    Text(
                        (if (status.scale in 0.995f..1.005f) "Real size" else String.format(Locale.US, "%.1f× real size", status.scale)) +
                            " · " + formatSize(size),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = preview::resetScale, modifier = Modifier.weight(1f)) { Text("Real size") }
                        FilledTonalButton(onClick = preview::placeAgain, modifier = Modifier.weight(1f)) { Text("Place again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, onClose: () -> Unit, content: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Close") }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, textAlign = TextAlign.Center)
            content()
        }
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivityOrNull()
    else -> null
}
