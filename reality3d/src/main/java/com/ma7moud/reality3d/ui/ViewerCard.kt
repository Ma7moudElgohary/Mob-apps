package com.ma7moud.reality3d.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ma7moud.reality3d.mesh.Mesh3D
import kotlin.math.roundToInt

/** Lets buttons outside the 3D view act on it. */
internal class ViewerController {
    var view: MeshSurfaceView? = null

    fun reset() {
        view?.resetView()
    }

    fun show(preset: ViewPreset) {
        view?.showPreset(preset)
    }

    fun capture(onCaptured: (Bitmap) -> Unit) {
        view?.capture(onCaptured)
    }
}

private val ViewerOptionsSaver = listSaver<ViewerOptions, Any>(
    save = { listOf(it.shading.ordinal, it.autoRotate, it.lightAngle, it.environment) },
    restore = { ViewerOptions(Shading.entries[it[0] as Int], it[1] as Boolean, it[2] as Float, it[3] as Boolean) },
)

/**
 * The model in 3D with its viewing tools: looks, straight views, lighting, measuring, comparing with the
 * photo and screenshots.
 *
 * @param texture the model's texture, or null for scans, which carry their own colours
 * @param photo the original photo, for comparing; null for scans
 * @param metersPerUnit the real size of one model unit
 * @param sizeKnown the scale was measured (scans) or set by hand, rather than assumed
 * @param onSetRealLength sets the scale from a measured length (model units) and its real length in
 *   meters; null when the scale comes from the scan
 */
@Composable
internal fun ViewerCard(
    mesh: Mesh3D,
    texture: Bitmap?,
    photo: Bitmap?,
    useGlViewer: Boolean,
    metersPerUnit: Float,
    sizeKnown: Boolean,
    onSetRealLength: ((modelUnits: Float, meters: Float) -> Unit)?,
    onScreenshot: (Bitmap) -> Unit,
) {
    var options by rememberSaveable(stateSaver = ViewerOptionsSaver) { mutableStateOf(ViewerOptions()) }
    var measuring by rememberSaveable { mutableStateOf(false) }
    var compare by rememberSaveable { mutableStateOf(false) }
    var divider by remember { mutableFloatStateOf(0.5f) }
    var points by remember(mesh) { mutableStateOf(emptyList<FloatArray>()) }
    var missed by remember { mutableStateOf(false) }
    var askLength by remember { mutableStateOf(false) }
    val controller = remember { ViewerController() }
    val canCompare = photo != null && mesh.uvs != null

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.9f)) {
            if (useGlViewer) {
                MeshViewer(
                    mesh = mesh,
                    texture = texture,
                    options = options,
                    compare = compare && canCompare,
                    measuring = measuring,
                    markers = points,
                    controller = controller,
                    onPick = { hit ->
                        missed = hit == null
                        if (hit != null) points = if (points.size >= 2) listOf(hit) else points + listOf(hit)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF070B12)), contentAlignment = Alignment.Center) {
                    Text("3D preview")
                }
            }
            if (compare && canCompare) {
                CompareOverlay(photo, mesh, divider, onDivider = { divider = it }, modifier = Modifier.fillMaxSize())
            }
            Text(
                when {
                    compare && canCompare -> "Drag the line: photo on the left, 3D model on the right"
                    measuring -> "Tap two points on the model"
                    else -> "Drag to rotate · pinch to zoom · two fingers to move"
                },
                Modifier.align(Alignment.TopCenter).padding(10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB8F5FF),
            )
            val size = mesh.size.map { it * metersPerUnit }.toFloatArray()
            Text(
                "${mesh.triangleCount} triangles · ${if (mesh.solid) "closed" else "open"}\n" +
                    (if (sizeKnown) "" else "≈ ") + formatSize(size),
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Shading.entries.forEach { shading ->
                    FilterChip(
                        selected = options.shading == shading,
                        onClick = { options = options.copy(shading = shading) },
                        label = { Text(shadingLabel(shading, texture != null, mesh.colors != null)) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ViewPreset.entries.forEach { preset ->
                    AssistChip(
                        onClick = {
                            compare = false
                            controller.show(preset)
                        },
                        label = { Text(preset.label) },
                    )
                }
                FilterChip(
                    selected = options.autoRotate,
                    onClick = { options = options.copy(autoRotate = !options.autoRotate) },
                    label = { Text("Auto-rotate") },
                )
            }
            if (options.shading != Shading.NORMALS && options.shading != Shading.DEPTH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Light", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
                    Slider(
                        value = options.lightAngle,
                        onValueChange = { options = options.copy(lightAngle = it) },
                        valueRange = -150f..150f,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = options.environment,
                        onClick = { options = options.copy(environment = !options.environment) },
                        label = { Text("Environment") },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = measuring,
                    onClick = {
                        measuring = !measuring
                        points = emptyList()
                        missed = false
                        if (measuring) compare = false
                    },
                    label = { Text("Measure") },
                )
                if (canCompare) {
                    FilterChip(
                        selected = compare,
                        onClick = {
                            compare = !compare
                            if (compare) measuring = false
                        },
                        label = { Text("Compare with photo") },
                    )
                }
                AssistChip(onClick = { controller.capture(onScreenshot) }, label = { Text("Screenshot") })
                AssistChip(onClick = { controller.reset() }, label = { Text("Reset view") })
            }
            if (measuring) {
                MeasurePanel(
                    points = points,
                    missed = missed,
                    metersPerUnit = metersPerUnit,
                    sizeKnown = sizeKnown,
                    canSetLength = onSetRealLength != null,
                    onClear = {
                        points = emptyList()
                        missed = false
                    },
                    onSetLength = { askLength = true },
                )
            }
        }
    }
    if (askLength && points.size == 2 && onSetRealLength != null) {
        val modelUnits = MeshPicker.distance(points[0], points[1])
        RealLengthDialog(
            current = modelUnits * metersPerUnit,
            onDismiss = { askLength = false },
            onConfirm = { meters ->
                askLength = false
                onSetRealLength(modelUnits, meters)
            },
        )
    }
}

private fun shadingLabel(shading: Shading, textured: Boolean, coloured: Boolean): String = when (shading) {
    Shading.SURFACE -> if (textured) "Photo" else if (coloured) "Colours" else "Surface"
    Shading.CLAY -> "Clay"
    Shading.WIREFRAME -> "Wireframe"
    Shading.NORMALS -> "Normals"
    Shading.DEPTH -> "Depth"
}

@Composable
private fun MeasurePanel(
    points: List<FloatArray>,
    missed: Boolean,
    metersPerUnit: Float,
    sizeKnown: Boolean,
    canSetLength: Boolean,
    onClear: () -> Unit,
    onSetLength: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1820), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val distance = if (points.size == 2) MeshPicker.distance(points[0], points[1]) * metersPerUnit else null
        Text(
            when {
                distance != null -> "Distance: " + (if (sizeKnown) "" else "≈ ") + formatLength(distance)
                missed -> "That tap missed the model. Tap on the model itself."
                points.size == 1 -> "Now tap the second point."
                else -> "Tap the first point on the model."
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (distance != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            when {
                !canSetLength -> "The scan measured the object, so lengths are real."
                sizeKnown -> "Lengths use the real length you set."
                else -> "One photo can't show the real size, so lengths are estimates. Measure something you know and set its real length to fix every size, exports included."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canSetLength && distance != null) OutlinedButton(onClick = onSetLength) { Text("Set real length") }
            if (points.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear points") }
        }
    }
}

@Composable
private fun RealLengthDialog(current: Float, onDismiss: () -> Unit, onConfirm: (meters: Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    val centimetres = text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it < 100_000f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Real length") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How long is the line you measured? It reads ${formatLength(current)} now.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Length in centimetres") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { centimetres?.let { onConfirm(it / 100f) } }, enabled = centimetres != null) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The photo over the left of the view, lined up with the model's straight front view. */
@Composable
private fun CompareOverlay(photo: Bitmap, mesh: Mesh3D, divider: Float, onDivider: (Float) -> Unit, modifier: Modifier) {
    val image = remember(photo) { photo.asImageBitmap() }
    val uv = remember(mesh) { uvBounds(mesh) } ?: return
    Canvas(
        modifier
            .semantics { contentDescription = "Photo and model compared" }
            .pointerInput(Unit) {
                detectTapGestures { onDivider((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onDivider((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val rect = FrontView.rect(mesh.bounds, size.width.roundToInt(), size.height.roundToInt())
        val split = size.width * divider
        clipRect(right = split) {
            drawRect(Color(0xFF070B12))
            val left = (uv[0] * photo.width).roundToInt().coerceIn(0, photo.width - 1)
            val top = (uv[1] * photo.height).roundToInt().coerceIn(0, photo.height - 1)
            val right = (uv[2] * photo.width).roundToInt().coerceIn(left + 1, photo.width)
            val bottom = (uv[3] * photo.height).roundToInt().coerceIn(top + 1, photo.height)
            drawImage(
                image,
                srcOffset = IntOffset(left, top),
                srcSize = IntSize(right - left, bottom - top),
                dstOffset = IntOffset(rect[0].roundToInt(), rect[1].roundToInt()),
                dstSize = IntSize((rect[2] - rect[0]).roundToInt(), (rect[3] - rect[1]).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
        val line = 2.dp.toPx()
        drawLine(Color.White, Offset(split, 0f), Offset(split, size.height), strokeWidth = line)
        drawCircle(Color.White, radius = 12.dp.toPx(), center = Offset(split, size.height / 2))
        drawCircle(Color(0xFF0E3A4A), radius = 12.dp.toPx() - line, center = Offset(split, size.height / 2))
    }
}

@Composable
private fun MeshViewer(
    mesh: Mesh3D,
    texture: Bitmap?,
    options: ViewerOptions,
    compare: Boolean,
    measuring: Boolean,
    markers: List<FloatArray>,
    controller: ViewerController,
    onPick: (FloatArray?) -> Unit,
    modifier: Modifier,
) {
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
        controller.view = view
        onDispose {
            lifecycle.removeObserver(observer)
            if (controller.view === view) controller.view = null
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {
            it.setScene(mesh, texture)
            it.setOptions(options)
            it.setCompare(compare)
            it.measuring = measuring
            it.onPick = onPick
            it.setMarkers(markers)
        },
    )
}
