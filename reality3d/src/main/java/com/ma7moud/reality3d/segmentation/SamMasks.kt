package com.ma7moud.reality3d.segmentation

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The host-side parts of Segment Anything 2.1: the photo as the encoder's input, a point as the decoder's
 * prompt, and the decoder's masks turned into the photo's objects.
 */
internal object Sam {

    /** The encoder's square input side; photos are stretched to it. */
    const val INPUT = 1024

    /** The decoder's mask side. */
    const val MASK = 256

    /** Points per side of the grid of prompts that looks for objects. */
    const val GRID = 4

    // Tuned on real photos: confident, stable masks of something between a speck and most of the photo.
    private const val MIN_IOU = 0.7f
    private const val MIN_STABILITY = 0.5f
    private const val MIN_AREA = 0.004f
    private const val MAX_AREA = 0.7f

    /** A mask covering more than this of two opposite edges spans the photo, like a wall or a table. */
    private const val EDGE = 0.05f

    /** A mask mostly inside an object already found is a part of it. */
    private const val OVERLAP = 0.5f

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** [argb] ([INPUT] × [INPUT]) as the encoder's [1, 3, 1024, 1024] input: RGB planes, ImageNet-normalised. */
    fun encoderInput(argb: IntArray): FloatArray {
        val n = INPUT * INPUT
        require(argb.size == n)
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val c = argb[i]
            out[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[n + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * n + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    /** The prompt encoder's constants: `posmat` [2, 128], the positive point's embedding and the padding point's. */
    class PromptConstants(val posmat: FloatArray, val positive: FloatArray, val padding: FloatArray) {
        init {
            require(posmat.size == 256 && positive.size == 256 && padding.size == 256)
        }

        companion object {
            /** From the model's prompt_encode_const.bin: 768 little-endian floats, in that order. */
            fun read(bytes: ByteArray): PromptConstants {
                require(bytes.size == 768 * 4) { "unexpected prompt constants" }
                val all = FloatArray(768)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(all)
                return PromptConstants(all.copyOfRange(0, 256), all.copyOfRange(256, 512), all.copyOfRange(512, 768))
            }
        }
    }

    /** The decoder's [1, 2, 256] prompt for one positive point at normalised photo point ([u], [v]), plus padding. */
    fun prompt(u: Float, v: Float, constants: PromptConstants): FloatArray {
        val cx = 2.0 * ((u * INPUT + 0.5) / INPUT) - 1.0
        val cy = 2.0 * ((v * INPUT + 0.5) / INPUT) - 1.0
        val out = FloatArray(512)
        for (j in 0 until 128) {
            val coord = 2.0 * PI * (cx * constants.posmat[j] + cy * constants.posmat[128 + j])
            out[j] = sin(coord).toFloat() + constants.positive[j]
            out[128 + j] = cos(coord).toFloat() + constants.positive[128 + j]
        }
        constants.padding.copyInto(out, 256)
        return out
    }

    /** One candidate mask for a prompt: [MASK] × [MASK] logits and the decoder's own guess of its quality. */
    class Candidate(val logits: FloatArray, val iou: Float) {
        val mask = BooleanArray(logits.size) { logits[it] > 0f }
        val area: Float = mask.count { it }.toFloat() / mask.size

        /** How little the mask changes with the threshold: high for crisp masks, low for guesses. */
        val stability: Float = logits.count { it > 1f }.toFloat() / max(1, logits.count { it > -1f })
    }

    /** The decoder's output for one prompt: masks [3, MASK, MASK] and their predicted IoU [3]. */
    fun candidates(masks: FloatArray, iou: FloatArray): List<Candidate> {
        val size = MASK * MASK
        require(masks.size == iou.size * size)
        return iou.indices.map { k -> Candidate(masks.copyOfRange(k * size, (k + 1) * size), iou[k]) }
    }

    /** Spans the photo from side to side or top to bottom, as backgrounds, walls and tables do. */
    fun isBackground(mask: BooleanArray): Boolean {
        var top = 0
        var bottom = 0
        var left = 0
        var right = 0
        for (i in 0 until MASK) {
            if (mask[i]) top++
            if (mask[(MASK - 1) * MASK + i]) bottom++
            if (mask[i * MASK]) left++
            if (mask[i * MASK + MASK - 1]) right++
        }
        val edge = EDGE * MASK
        return (left > edge && right > edge) || (top > edge && bottom > edge)
    }

    /** Good enough to be an object on its own: confident, stable, neither a speck nor the background. */
    fun plausible(candidate: Candidate): Boolean =
        candidate.iou >= MIN_IOU && candidate.stability >= MIN_STABILITY && candidate.area in MIN_AREA..MAX_AREA &&
            !isBackground(candidate.mask)

    /** The objects among plausible candidates: largest first, skipping any that is mostly inside one already kept. */
    fun objects(candidates: List<Candidate>): List<Candidate> {
        val kept = ArrayList<Candidate>()
        for (candidate in candidates.sortedByDescending { it.area }) {
            val area = candidate.mask.count { it }
            if (kept.none { overlap(candidate.mask, it.mask) > OVERLAP * area }) kept += candidate
        }
        return kept
    }

    private fun overlap(a: BooleanArray, b: BooleanArray): Int {
        var n = 0
        for (i in a.indices) if (a[i] && b[i]) n++
        return n
    }

    /** The object filling most of the photo's middle, the one the photo is most likely of. */
    fun mainObject(objects: List<Candidate>): Int = objects.indices.maxByOrNull { index ->
        val mask = objects[index].mask
        var n = 0
        for (y in MASK / 4 until MASK * 3 / 4) for (x in MASK / 4 until MASK * 3 / 4) if (mask[y * MASK + x]) n++
        n
    } ?: -1

    /**
     * For a tap: the whole object rather than a part of it, or null on the background. A single point is ambiguous
     * (a lace, a shoe, both shoes) and the decoder's own quality guess is unreliable there, so this takes the
     * largest of the masks that are stable, not the one it rates highest.
     */
    fun forTap(candidates: List<Candidate>): Candidate? = candidates
        .filter { it.stability >= MIN_STABILITY && it.area in MIN_AREA..MAX_AREA && !isBackground(it.mask) }
        .maxByOrNull { it.area }

    /**
     * The object as a [Subject] on a [width] × [height] photo: its logits upsampled over its box, with small holes
     * (the gaps between a shoe's laces) filled.
     */
    fun subject(candidate: Candidate, width: Int, height: Int): Subject {
        val logits = candidate.logits.copyOf()
        fillHoles(candidate.mask, logits)
        var minX = MASK
        var minY = MASK
        var maxX = -1
        var maxY = -1
        for (y in 0 until MASK) {
            for (x in 0 until MASK) {
                if (logits[y * MASK + x] > 0f) {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
        }
        if (maxX < 0) return Subject(0, 0, 1, 1, floatArrayOf(0f))
        // One mask cell of margin, so the soft edge fits inside the box.
        val left = floor((minX - 1).coerceAtLeast(0) * width / MASK.toFloat()).toInt().coerceIn(0, width - 1)
        val top = floor((minY - 1).coerceAtLeast(0) * height / MASK.toFloat()).toInt().coerceIn(0, height - 1)
        val right = ceil((maxX + 2).coerceAtMost(MASK) * width / MASK.toFloat()).toInt().coerceIn(left + 1, width)
        val bottom = ceil((maxY + 2).coerceAtMost(MASK) * height / MASK.toFloat()).toInt().coerceIn(top + 1, height)
        val boxWidth = right - left
        val boxHeight = bottom - top
        val confidence = FloatArray(boxWidth * boxHeight)
        for (y in 0 until boxHeight) {
            val my = ((top + y + 0.5f) * MASK / height - 0.5f).coerceIn(0f, MASK - 1f)
            val y0 = my.toInt()
            val y1 = min(y0 + 1, MASK - 1)
            val fy = my - y0
            for (x in 0 until boxWidth) {
                val mx = ((left + x + 0.5f) * MASK / width - 0.5f).coerceIn(0f, MASK - 1f)
                val x0 = mx.toInt()
                val x1 = min(x0 + 1, MASK - 1)
                val fx = mx - x0
                val upper = logits[y0 * MASK + x0] * (1 - fx) + logits[y0 * MASK + x1] * fx
                val lower = logits[y1 * MASK + x0] * (1 - fx) + logits[y1 * MASK + x1] * fx
                confidence[y * boxWidth + x] = 1f / (1f + exp(-(upper * (1 - fy) + lower * fy)))
            }
        }
        return Subject(left, top, boxWidth, boxHeight, confidence)
    }

    /** Marks as inside the holes in [mask] that don't reach the edge and are small next to the object. */
    internal fun fillHoles(mask: BooleanArray, logits: FloatArray) {
        val area = mask.count { it }
        val limit = max(16, (area * 0.03f).toInt())
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var touchesEdge = false
            while (head < tail) {
                val i = queue[head++]
                val x = i % MASK
                val y = i / MASK
                if (x == 0 || y == 0 || x == MASK - 1 || y == MASK - 1) touchesEdge = true
                if (x > 0) tail = visit(i - 1, mask, seen, queue, tail)
                if (x < MASK - 1) tail = visit(i + 1, mask, seen, queue, tail)
                if (y > 0) tail = visit(i - MASK, mask, seen, queue, tail)
                if (y < MASK - 1) tail = visit(i + MASK, mask, seen, queue, tail)
            }
            if (!touchesEdge && tail <= limit) {
                for (k in 0 until tail) logits[queue[k]] = max(logits[queue[k]], HOLE_LOGIT)
            }
        }
    }

    private fun visit(i: Int, mask: BooleanArray, seen: BooleanArray, queue: IntArray, tail: Int): Int {
        if (mask[i] || seen[i]) return tail
        seen[i] = true
        queue[tail] = i
        return tail + 1
    }

    /** A filled hole counts as clearly inside. */
    private const val HOLE_LOGIT = 4f
}
