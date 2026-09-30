package com.ma7moud.reality3d.segmentation

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The mask editor's state, at editing resolution: the selected objects' mask plus brush strokes that add to
 * it or erase from it, then optional edge refinement against the photo, and feathering.
 *
 * [guide] is the photo's brightness (0..1) at the same resolution, for snapping edges to the photo.
 */
class MaskEdit(val width: Int, val height: Int, private val guide: FloatArray) {

    private val count = width * height
    private var base = FloatArray(count)
    private var added = ByteArray(count)
    private var erased = ByteArray(count)
    private val history = ArrayDeque<Pair<ByteArray, ByteArray>>()

    /** Snap the outline to edges in the photo, drop specks and fill pinholes. */
    var refineEdges = false

    /** Softens the outline over this many pixels (editing resolution). */
    var feather = 0

    init {
        require(width > 0 && height > 0 && guide.size == count)
    }

    val canUndo: Boolean get() = history.isNotEmpty()

    val hasStrokes: Boolean get() = added.any { it != 0.toByte() } || erased.any { it != 0.toByte() }

    /** Starts from [mask] (the selected objects), resampled to the editing resolution; strokes are kept. */
    fun setBase(mask: SubjectMask?) {
        base = if (mask == null) FloatArray(count) else FloatArray(count) { i ->
            mask.sample((i % width).toFloat() / max(1, width - 1), (i / width).toFloat() / max(1, height - 1))
        }
    }

    /** Call when a stroke starts, so [undo] can take it back. */
    fun beginStroke() {
        history.addLast(added.copyOf() to erased.copyOf())
        while (history.size > MAX_UNDO) history.removeFirst()
    }

    fun undo(): Boolean {
        val last = history.removeLastOrNull() ?: return false
        added = last.first
        erased = last.second
        return true
    }

    fun clearStrokes() {
        if (!hasStrokes) return
        beginStroke()
        added = ByteArray(count)
        erased = ByteArray(count)
    }

    /**
     * Paints a soft round brush of [radius] pixels from ([x0], [y0]) to ([x1], [y1]), adding to or erasing
     * from the mask. Later strokes override earlier ones. Returns the changed area (left, top, right,
     * bottom, exclusive) or null.
     */
    fun paint(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, erase: Boolean): IntArray? {
        val r = max(radius, 0.5f)
        val length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val steps = max(1, ceil(length / max(1f, r / 3f)).toInt())
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val cx = x0 + (x1 - x0) * t
            val cy = y0 + (y1 - y0) * t
            val xa = max(0, floor(cx - r).toInt())
            val xb = min(width - 1, ceil(cx + r).toInt())
            val ya = max(0, floor(cy - r).toInt())
            val yb = min(height - 1, ceil(cy + r).toInt())
            if (xa > xb || ya > yb) continue
            for (y in ya..yb) {
                for (x in xa..xb) {
                    val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                    if (d > r) continue
                    // Full strength in the inner 60 %, fading to nothing at the rim.
                    val strength = if (d <= r * HARDNESS) 1f else (r - d) / (r * (1 - HARDNESS))
                    val value = (strength * 255f).roundToInt()
                    val keep = 255 - value
                    val i = y * width + x
                    if (erase) {
                        if (value > (erased[i].toInt() and 0xFF)) erased[i] = value.toByte()
                        if ((added[i].toInt() and 0xFF) > keep) added[i] = keep.toByte()
                    } else {
                        if (value > (added[i].toInt() and 0xFF)) added[i] = value.toByte()
                        if ((erased[i].toInt() and 0xFF) > keep) erased[i] = keep.toByte()
                    }
                }
            }
            left = min(left, xa)
            top = min(top, ya)
            right = max(right, xb + 1)
            bottom = max(bottom, yb + 1)
        }
        return if (left == Int.MAX_VALUE) null else intArrayOf(left, top, right, bottom)
    }

    /** The mask before refinement at pixel [i]: the selection, plus added, minus erased strokes. */
    fun raw(i: Int): Float {
        val add = (added[i].toInt() and 0xFF) / 255f
        val erase = (erased[i].toInt() and 0xFF) / 255f
        return max(base[i], add) * (1 - erase)
    }

    /** The finished mask: refined against the photo when asked, then feathered. */
    fun result(): FloatArray {
        var mask = FloatArray(count) { raw(it) }
        if (refineEdges) {
            mask = guidedFilter(mask, guide, max(3, (min(width, height) / 100f).roundToInt()), REFINE_EPSILON)
            for (i in mask.indices) mask[i] = mask[i].coerceIn(0f, 1f)
            removeSpecksAndPinholes(mask)
        }
        if (feather > 0) {
            mask = boxMean(boxMean(mask, feather), feather)
        }
        return mask
    }

    fun toSubjectMask(): SubjectMask = SubjectMask(width, height, result())

    /** The same mask, strokes and settings without the undo history, e.g. to finish in the background. */
    fun copy(): MaskEdit = MaskEdit(width, height, guide).also {
        // The base is replaced, never changed in place, so it can be shared.
        it.base = base
        it.added = added.copyOf()
        it.erased = erased.copyOf()
        it.refineEdges = refineEdges
        it.feather = feather
    }

    /**
     * Guided filter (He, Sun and Tang): locally a linear function of the guide, so soft mask edges move
     * onto the edges of the photo.
     */
    private fun guidedFilter(p: FloatArray, image: FloatArray, radius: Int, epsilon: Float): FloatArray {
        val meanI = boxMean(image, radius)
        val meanP = boxMean(p, radius)
        val corrIp = boxMean(FloatArray(count) { image[it] * p[it] }, radius)
        val corrII = boxMean(FloatArray(count) { image[it] * image[it] }, radius)
        val a = FloatArray(count)
        val b = FloatArray(count)
        for (i in 0 until count) {
            val variance = corrII[i] - meanI[i] * meanI[i]
            val covariance = corrIp[i] - meanI[i] * meanP[i]
            a[i] = covariance / (variance + epsilon)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, radius)
        val meanB = boxMean(b, radius)
        return FloatArray(count) { meanA[it] * image[it] + meanB[it] }
    }

    /** Mean over a (2r+1)² window, shrinking at the borders; separable running sums, so O(pixels). */
    private fun boxMean(values: FloatArray, radius: Int): FloatArray {
        val horizontal = FloatArray(count)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            var n = 0
            for (x in 0..min(radius, width - 1)) {
                sum += values[row + x]
                n++
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / n
                val add = x + radius + 1
                val drop = x - radius
                if (add < width) {
                    sum += values[row + add]
                    n++
                }
                if (drop >= 0) {
                    sum -= values[row + drop]
                    n--
                }
            }
        }
        val out = FloatArray(count)
        for (x in 0 until width) {
            var sum = 0f
            var n = 0
            for (y in 0..min(radius, height - 1)) {
                sum += horizontal[y * width + x]
                n++
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / n
                val add = y + radius + 1
                val drop = y - radius
                if (add < height) {
                    sum += horizontal[add * width + x]
                    n++
                }
                if (drop >= 0) {
                    sum -= horizontal[drop * width + x]
                    n--
                }
            }
        }
        return out
    }

    /** Drops pieces much smaller than the main object and fills small holes that don't reach the border. */
    private fun removeSpecksAndPinholes(mask: FloatArray) {
        val label = IntArray(count) { -1 }
        val queue = IntArray(count)
        val sizes = ArrayList<Int>()
        val touchesBorder = ArrayList<Boolean>()
        val inside = ArrayList<Boolean>()
        for (start in 0 until count) {
            if (label[start] >= 0) continue
            val isInside = mask[start] >= 0.5f
            val id = sizes.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = id
            var border = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border = true
                fun visit(q: Int) {
                    if (label[q] < 0 && (mask[q] >= 0.5f) == isInside) {
                        label[q] = id
                        queue[tail++] = q
                    }
                }
                if (x > 0) visit(p - 1)
                if (x < width - 1) visit(p + 1)
                if (y > 0) visit(p - width)
                if (y < height - 1) visit(p + width)
            }
            sizes += tail
            touchesBorder += border
            inside += isInside
        }
        val largest = sizes.indices.filter { inside[it] }.maxOfOrNull { sizes[it] } ?: return
        for (i in 0 until count) {
            val id = label[i]
            if (inside[id] && sizes[id] < largest * SPECK_FRACTION) {
                mask[i] = min(mask[i], 0.49f)
            } else if (!inside[id] && !touchesBorder[id] && sizes[id] < count * PINHOLE_FRACTION) {
                mask[i] = max(mask[i], 0.51f)
            }
        }
    }

    companion object {
        private const val HARDNESS = 0.6f
        private const val MAX_UNDO = 20
        private const val REFINE_EPSILON = 1e-3f
        private const val SPECK_FRACTION = 0.01f
        private const val PINHOLE_FRACTION = 0.005f

        /** The editing resolution for a photo: its own size, at most [longest] pixels on the long side. */
        fun sizeFor(photoWidth: Int, photoHeight: Int, longest: Int = 1024): Pair<Int, Int> {
            val scale = min(1f, longest.toFloat() / max(photoWidth, photoHeight))
            return max(1, (photoWidth * scale).roundToInt()) to max(1, (photoHeight * scale).roundToInt())
        }

        /** Brightness (0..1) of ARGB pixels, the guide for edge refinement. */
        fun brightness(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
            val c = argb[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        }
    }
}
