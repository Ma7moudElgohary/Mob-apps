package com.ma7moud.reality3d.depth

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.segmentation.SubjectMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Monocular depth for a photo. */
interface DepthEngine {
    val isModelReady: Boolean
    val downloadBytes: Long

    /** Which hardware runs the model and how fast, once known (for example "GPU · 180 ms (CPU 900 ms)"). */
    val backendSummary: String? get() = null

    suspend fun downloadModel(onProgress: (Float) -> Unit)

    /**
     * Relative depth for [photo]. With a [subject] mask the model looks at the subject and its
     * surroundings at full resolution, and the result is cleaned up without mixing subject and background.
     */
    suspend fun estimate(photo: Bitmap, subject: SubjectMask? = null): DepthMap
}

/** Depth Anything V2 Small through LiteRT, on the fastest backend that gives the same result as the CPU. */
class DepthAnythingEngine(context: Context, private val diagnostics: Diagnostics) : DepthEngine {

    private val appContext = context.applicationContext
    private val models = DepthModelManager(context)
    private val runner by lazy { DepthAnythingRunner(appContext, models.modelFile, DepthModelManager.MODEL_SHA256, diagnostics) }

    override val isModelReady: Boolean get() = models.isReady()
    override val downloadBytes: Long get() = DepthModelManager.MODEL_BYTES
    override val backendSummary: String? get() = if (models.isReady()) runner.benchmark?.summary else null

    override suspend fun downloadModel(onProgress: (Float) -> Unit) {
        models.download(onProgress)
    }

    override suspend fun estimate(photo: Bitmap, subject: SubjectMask?): DepthMap = withContext(Dispatchers.Default) {
        val focus = subject?.takeIf { it.coverage > MIN_SUBJECT_COVERAGE }?.bounds()
        val plan = DepthInputPlan.create(photo.width, photo.height, focus, INPUT_WIDTH, INPUT_HEIGHT)
        val content = resize(photo, plan)
        val pixels = IntArray(plan.contentWidth * plan.contentHeight)
        content.getPixels(pixels, 0, plan.contentWidth, 0, 0, plan.contentWidth, plan.contentHeight)
        if (content !== photo) content.recycle()
        val output = runner.run(DepthTensors.pack(pixels, plan, INPUT_WIDTH, INPUT_HEIGHT))
        val raw = DepthTensors.unpack(output, plan, INPUT_WIDTH, photo.width, photo.height)
        // The resized photo lines up with the depth map pixel for pixel, so it guides the clean-up directly.
        DepthRefiner.refine(raw, pixels, subject?.let { maskOnGrid(it, raw) })
    }

    /** The subject mask sampled where [depth] samples the photo. */
    private fun maskOnGrid(mask: SubjectMask, depth: DepthMap): FloatArray {
        val w = depth.width
        val h = depth.height
        val spanX = (depth.right - depth.left) / max(1, w - 1)
        val spanY = (depth.bottom - depth.top) / max(1, h - 1)
        return FloatArray(w * h) { i -> mask.sample(depth.left + (i % w) * spanX, depth.top + (i / w) * spanY) }
    }

    /** The cropped photo at the plan's content size, halving first so large reductions don't alias. */
    private fun resize(photo: Bitmap, plan: DepthInputPlan): Bitmap {
        var current = if (plan.cropLeft == 0 && plan.cropTop == 0 && plan.cropWidth == photo.width && plan.cropHeight == photo.height) {
            photo
        } else {
            Bitmap.createBitmap(photo, plan.cropLeft, plan.cropTop, plan.cropWidth, plan.cropHeight)
        }
        while (max(current.width.toFloat() / plan.contentWidth, current.height.toFloat() / plan.contentHeight) > 2f) {
            val half = current.scale(max(1, current.width / 2), max(1, current.height / 2))
            if (current !== photo) current.recycle()
            current = half
        }
        if (current.width == plan.contentWidth && current.height == plan.contentHeight) return current
        val sized = current.scale(plan.contentWidth, plan.contentHeight)
        if (current !== photo && current !== sized) current.recycle()
        return sized
    }

    /** Forgets the chosen backend; the next estimate benchmarks CPU, GPU and NPU again. */
    fun rebenchmark() = runner.rebenchmark()

    private companion object {
        const val MIN_SUBJECT_COVERAGE = 0.001f

        // The model's fixed input: 686 × 518 RGB.
        const val INPUT_WIDTH = 686
        const val INPUT_HEIGHT = 518
    }
}
