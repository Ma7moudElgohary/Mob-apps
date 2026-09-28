package com.ma7moud.reality3d.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 8f
private const val MAX_FEATHER = 12f

/** Paint the model's outline by hand: add what the segmenter missed, erase what it took by mistake. */
@Composable
internal fun MaskEditorScreen(session: MaskEditorSession, onCancel: () -> Unit, onDone: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onCancel)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // The photo always keeps at least half the screen; on small screens the controls scroll.
        val controlsHeight = maxHeight * 0.5f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text(
                    "Edit outline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onDone) { Text("Done") }
            }
            MaskCanvas(session, state.version, Modifier.weight(1f).fillMaxWidth())
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = controlsHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "One finger paints · two fingers zoom and move",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Choice(listOf("Add", "Erase"), state.brush.ordinal) { session.setBrush(Brush.entries[it]) }
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = session::undo, enabled = state.canUndo) { Text("Undo") }
                }
                LabeledSlider(
                    label = "Brush size",
                    value = state.brushSize,
                    valueText = "${state.brushSize.roundToInt()} dp",
                    range = 6f..60f,
                ) { session.setBrushSize(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Snap to edges", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Fits the outline to the photo and drops stray specks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Switch(checked = state.refine, onCheckedChange = session::setRefine)
                }
                LabeledSlider(
                    label = "Soften edge",
                    value = state.feather.toFloat(),
                    valueText = if (state.feather == 0) "off" else "${state.feather} px",
                    range = 0f..MAX_FEATHER,
                ) { session.setFeather(it.roundToInt()) }
                OutlinedButton(onClick = session::clearStrokes, modifier = Modifier.fillMaxWidth()) { Text("Clear strokes") }
            }
        }
    }
}

/** The photo under the mask preview; one finger paints, two fingers zoom and pan. */
@Composable
private fun MaskCanvas(session: MaskEditorSession, version: Int, modifier: Modifier) {
    val photo = remember(session) { session.photo.asImageBitmap() }
    // A new wrapper for each version, so the canvas redraws when the overlay's pixels change.
    val overlay = remember(session, version) { session.overlay.asImageBitmap() }
    var zoom by remember(session) { mutableFloatStateOf(1f) }
    var offset by remember(session) { mutableStateOf(Offset.Zero) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Outline editor" }
                .pointerInput(session) {
                    fun fit() = fitRect(Size(size.width.toFloat(), size.height.toFloat()), session.width, session.height)

                    /** Screen point to editing pixels. */
                    fun toEdit(point: Offset): Offset {
                        val fit = fit()
                        val content = (point - offset) / zoom
                        val scale = session.width / fit.width
                        return Offset((content.x - fit.left) * scale, (content.y - fit.top) * scale)
                    }

                    fun paint(from: Offset, to: Offset) {
                        val a = toEdit(from)
                        val b = toEdit(to)
                        val radius = session.state.value.brushSize.dp.toPx() / zoom * session.width / fit().width
                        session.stroke(a.x, a.y, b.x, b.y, radius)
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var last = down.position
                        var painting = false
                        var pinching = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (painting) {
                                    session.cancelStroke()
                                    painting = false
                                }
                                pinching = true
                                val centroid = event.calculateCentroid()
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val content = (centroid - offset) / zoom
                                val moved = centroid - content * newZoom + event.calculatePan()
                                zoom = newZoom
                                offset = Offset(
                                    moved.x.coerceIn(size.width * (1 - newZoom), 0f),
                                    moved.y.coerceIn(size.height * (1 - newZoom), 0f),
                                )
                                event.changes.forEach { it.consume() }
                            } else if (!pinching) {
                                val change = pressed.first()
                                if (change.positionChanged()) {
                                    if (!painting) {
                                        painting = true
                                        session.startStroke()
                                    }
                                    paint(last, change.position)
                                    last = change.position
                                    change.consume()
                                }
                            }
                        }
                        if (painting) {
                            session.endStroke()
                        } else if (!pinching) {
                            // A tap paints a dot.
                            session.startStroke()
                            paint(down.position, down.position)
                            session.endStroke()
                        }
                    }
                },
        ) {
            val fit = fitRect(size, session.width, session.height)
            val dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt())
            val dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt())
            withTransform({
                translate(offset.x, offset.y)
                scale(zoom, zoom, Offset.Zero)
            }) {
                drawImage(photo, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Medium)
                drawImage(overlay, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Low)
            }
        }
        if (zoom > 1.01f) {
            FilledTonalButton(
                onClick = {
                    zoom = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Text("Fit") }
        }
    }
}

/** Where an image of [imageWidth] × [imageHeight] sits when fitted, centred, into [area]. */
internal fun fitRect(area: Size, imageWidth: Int, imageHeight: Int): Rect {
    val scale = min(area.width / imageWidth, area.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (area.width - width) / 2
    val top = (area.height - height) / 2
    return Rect(left, top, left + width, top + height)
}
