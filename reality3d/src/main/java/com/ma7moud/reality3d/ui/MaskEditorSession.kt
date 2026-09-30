package com.ma7moud.reality3d.ui

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.ma7moud.reality3d.segmentation.MaskEdit
import com.ma7moud.reality3d.segmentation.Segmentation
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Colours for showing a mask over the photo: what is left out is dimmed, what is modelled is lightly tinted. */
internal object MaskOverlay {

    // Straight ARGB: left out = black at 67 %, modelled = cyan at 16 %, another object = violet at 43 %.
    private const val OUT_ALPHA = 170f
    private const val IN_ALPHA = 40f
    private const val IN_R = 80f // 0x50E3FF, the app's cyan
    private const val IN_G = 227f
    private const val IN_B = 255f
    private const val OTHER_ALPHA = 110f
    private const val OTHER_R = 154f // 0x9A7CFF, the app's violet
    private const val OTHER_G = 124f
    private const val OTHER_B = 255f

    /** The overlay colour where the mask is [selected] and another, unchosen object is [other] (both 0..1). */
    fun color(selected: Float, other: Float = 0f): Int {
        val s = selected.coerceIn(0f, 1f)
        val o = (1 - s) * other.coerceIn(0f, 1f)
        val b = 1 - s - o
        val a = (b * OUT_ALPHA + s * IN_ALPHA + o * OTHER_ALPHA).roundToInt()
        val r = (s * IN_R + o * OTHER_R).roundToInt()
        val g = (s * IN_G + o * OTHER_G).roundToInt()
        val bl = (s * IN_B + o * OTHER_B).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /**
     * The overlay at [width] × [height] for [mask], tinting the objects outside [selection] so they can be
     * seen and tapped.
     */
    fun render(mask: SubjectMask?, segmentation: Segmentation?, selection: Set<Int>, width: Int, height: Int): IntArray {
        val others = segmentation?.subjects?.indices?.filter { selection.isNotEmpty() && it !in selection }.orEmpty()
        val sx = 1f / max(1, width - 1)
        val sy = 1f / max(1, height - 1)
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val u = x * sx
            val v = y * sy
            val selected = mask?.sample(u, v) ?: 0f
            var other = 0f
            if (others.isNotEmpty()) {
                val px = (u * segmentation!!.photoWidth).toInt().coerceIn(0, segmentation.photoWidth - 1)
                val py = (v * segmentation.photoHeight).toInt().coerceIn(0, segmentation.photoHeight - 1)
                for (index in others) other = max(other, segmentation.subjects[index].at(px, py))
            }
            color(selected, other)
        }
    }

    fun toBitmap(pixels: IntArray, width: Int, height: Int): Bitmap =
        createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
}

enum class Brush { ADD, ERASE }

data class MaskEditorState(
    /** Changes whenever the overlay's pixels do, so the screen redraws. */
    val version: Int = 0,
    val brush: Brush = Brush.ADD,
    /** Brush radius on screen, in dp. */
    val brushSize: Float = 24f,
    val refine: Boolean = false,
    /** Edge softening in editing pixels. */
    val feather: Int = 0,
    val canUndo: Boolean = false,
    /** The refined preview is being worked out. */
    val working: Boolean = false,
)

/**
 * One visit to the mask editor: the outline being edited, its preview over the photo, and the brush. Use
 * it from the main thread; refinement runs on a copy in the background.
 */
class MaskEditorSession internal constructor(
    val photo: Bitmap,
    internal val edit: MaskEdit,
    private val scope: CoroutineScope,
) {
    val width: Int get() = edit.width
    val height: Int get() = edit.height

    /** The mask drawn over the photo, at editing resolution. */
    val overlay: Bitmap = createBitmap(edit.width, edit.height)

    private val _state = MutableStateFlow(MaskEditorState(refine = edit.refineEdges, feather = edit.feather))
    val state: StateFlow<MaskEditorState> = _state.asStateFlow()

    /** Something was painted, undone or switched since the editor opened. */
    var changed = false
        private set

    private var preview: Job? = null

    init {
        refreshPreview()
    }

    fun startStroke() {
        preview?.cancel()
        edit.beginStroke()
        changed = true
        _state.update { it.copy(canUndo = true, working = false) }
    }

    /** Paints from ([x0], [y0]) to ([x1], [y1]) in editing pixels with a brush of [radius] editing pixels. */
    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        val dirty = edit.paint(x0, y0, x1, y1, radius, erase = _state.value.brush == Brush.ERASE) ?: return
        drawRaw(dirty)
    }

    fun endStroke() {
        if (edit.refineEdges || edit.feather > 0) refreshPreview()
    }

    /** A second finger came down, so the stroke was the start of a pinch: take it back. */
    fun cancelStroke() {
        edit.undo()
        refreshPreview()
    }

    fun undo() {
        if (edit.undo()) {
            changed = true
            refreshPreview()
        }
    }

    fun clearStrokes() {
        if (!edit.hasStrokes) return
        edit.clearStrokes()
        changed = true
        refreshPreview()
    }

    fun setBrush(brush: Brush) = _state.update { it.copy(brush = brush) }

    fun setBrushSize(dp: Float) = _state.update { it.copy(brushSize = dp) }

    fun setRefine(on: Boolean) {
        if (edit.refineEdges == on) return
        edit.refineEdges = on
        changed = true
        _state.update { it.copy(refine = on) }
        refreshPreview()
    }

    fun setFeather(pixels: Int) {
        if (edit.feather == pixels) return
        edit.feather = pixels
        changed = true
        _state.update { it.copy(feather = pixels) }
        refreshPreview()
    }

    internal fun close() {
        preview?.cancel()
    }

    /** Shows the unrefined mask in the painted area straight away. */
    private fun drawRaw(dirty: IntArray) {
        val left = dirty[0]
        val top = dirty[1]
        val w = dirty[2] - left
        val h = dirty[3] - top
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = (top + y) * edit.width + left
            for (x in 0 until w) pixels[y * w + x] = MaskOverlay.color(edit.raw(row + x))
        }
        overlay.setPixels(pixels, 0, w, left, top, w, h)
        _state.update { it.copy(version = it.version + 1) }
    }

    /** Redraws the whole overlay with the finished mask: refined and feathered as set. */
    private fun refreshPreview() {
        preview?.cancel()
        val snapshot = edit.copy()
        _state.update { it.copy(canUndo = edit.canUndo, working = snapshot.refineEdges || snapshot.feather > 0) }
        preview = scope.launch {
            val pixels = withContext(Dispatchers.Default) {
                val mask = snapshot.result()
                IntArray(mask.size) { MaskOverlay.color(mask[it]) }
            }
            overlay.setPixels(pixels, 0, width, 0, 0, width, height)
            _state.update { it.copy(version = it.version + 1, working = false) }
        }
    }
}
