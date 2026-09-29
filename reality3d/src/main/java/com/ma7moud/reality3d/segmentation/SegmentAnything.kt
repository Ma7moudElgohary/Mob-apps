package com.ma7moud.reality3d.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.ma7moud.reality3d.data.ModelDownload
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.diagnostics.Fallback
import com.ma7moud.reality3d.diagnostics.Step
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/** The Segment Anything model files, fetched once when the user asks for them. */
interface SegmentAnythingModel {
    val isReady: Boolean
    val downloadBytes: Long
    suspend fun download(onProgress: (Float) -> Unit)
}

/**
 * Segment Anything 2.1 Hiera-Tiny (Meta, Apache-2.0) as converted by litert-community: the image encoder, the
 * mask decoder and the prompt constants, each pinned to one revision and checked with SHA-256.
 */
class SamModelFiles(context: Context) : SegmentAnythingModel {
    private val directory = context.noBackupFilesDir
    val encoder = ModelDownload(
        directory, "sam2_tiny_image_encoder_v2_fp16.tflite",
        "https://huggingface.co/litert-community/SAM2.1-Hiera-Tiny-Image-Encoder/resolve/" +
            "e46fe448ab8314f4afcb262f74f6d61f8e23ae59/sam2_tiny_image_encoder_v2_fp16.tflite",
        80_278_528L, "9f87693052c7c9bee2271f86d947420d7409ef0c2bec36c1885335afab90f6be", "Segment Anything encoder",
    )
    val decoder = ModelDownload(
        directory, "sam2_tiny_mask_decoder_v2_fp16.tflite",
        "https://huggingface.co/litert-community/SAM2.1-Hiera-Tiny-Mask-Decoder/resolve/" +
            "5eb2a03f1e3fdded966d8e9acd7fd387b6e26b6e/sam2_tiny_mask_decoder_v2_fp16.tflite",
        16_968_160L, "80d668b19156c548f31a8c8eb3cc1da2127de36e24e96ef923467a1e54c99e76", "Segment Anything decoder",
    )
    val prompts = ModelDownload(
        directory, "sam2_tiny_prompt_encode_const.bin",
        "https://huggingface.co/litert-community/SAM2.1-Hiera-Tiny-Mask-Decoder/resolve/" +
            "5eb2a03f1e3fdded966d8e9acd7fd387b6e26b6e/prompt_encode_const.bin",
        3_072L, "c1ac798f0cc0bd5e4b0dc94efe26f2f9dfe4d1d4cec141ab0350580ce7a48588", "Segment Anything prompt constants",
    )
    private val files = listOf(encoder, decoder, prompts)

    override val isReady: Boolean get() = files.all { it.isReady() }
    override val downloadBytes: Long = files.sumOf { it.bytes }

    override suspend fun download(onProgress: (Float) -> Unit) {
        var before = 0L
        for (file in files) {
            file.download { onProgress((before + it * file.bytes) / downloadBytes) }
            before += file.bytes
        }
    }
}

/**
 * Finds objects with Segment Anything through LiteRT, for phones where ML Kit's segmenter crashes the app. The
 * encoder looks at the photo once; the decoder then answers point prompts in milliseconds: a grid of them finds
 * the objects, and a tap finds the one under the finger. It runs on the GPU, or on the CPU when the GPU fails or
 * once crashed the app.
 */
class SamSegmenter(context: Context, private val files: SamModelFiles, private val diagnostics: Diagnostics) : SubjectSegmenterEngine {

    private val cacheDir = File(context.cacheDir, "litert_sam").apply { mkdirs() }
    private val lock = Mutex()

    // Every call into the models happens on this one thread: graphics drivers may tie the models to the thread that
    // made them, and the crash that brought this in was in a graphics thread.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "reality3d-sam").apply { isDaemon = true } }.asCoroutineDispatcher()
    private var models: Models? = null
    private var constants: Sam.PromptConstants? = null

    /** The photo the decoder's image inputs describe, for taps. */
    private var encoded: Bitmap? = null

    override val canPick: Boolean get() = true

    private inner class Models(val accelerator: Accelerator, val encoder: CompiledModel, val decoder: CompiledModel) : Closeable {
        private val step = if (accelerator == Accelerator.GPU) Step.SAM_GPU else Step.SAM_CPU
        private val image = encoder.createInputBuffer(IN_0)
        private val embeddings = encoder.createOutputBuffer(OUT_0)
        private val featuresS1 = encoder.createOutputBuffer(OUT_1)
        private val featuresS0 = encoder.createOutputBuffer(OUT_2)
        private val decoderEmbeddings = decoder.createInputBuffer(IN_0)
        private val prompt = decoder.createInputBuffer(IN_1)
        private val decoderS1 = decoder.createInputBuffer(IN_2)
        private val decoderS0 = decoder.createInputBuffer(IN_3)
        private val masks = decoder.createOutputBuffer(OUT_0)
        private val iou = decoder.createOutputBuffer(OUT_1)
        private val buffers = listOf(image, embeddings, featuresS1, featuresS0, decoderEmbeddings, prompt, decoderS1, decoderS0, masks, iou)

        init {
            // The two models are wired by name; make a mix-up fail here, loudly, instead of giving wrong masks.
            expect("encoder image", encoder.getInputTensorType(IN_0), 1, 3, Sam.INPUT, Sam.INPUT)
            expect("encoder embeddings", encoder.getOutputTensorType(OUT_0), 1, 256, 64, 64)
            expect("encoder features s1", encoder.getOutputTensorType(OUT_1), 1, 64, 128, 128)
            expect("encoder features s0", encoder.getOutputTensorType(OUT_2), 1, 32, 256, 256)
            expect("decoder embeddings", decoder.getInputTensorType(IN_0), 1, 256, 64, 64)
            expect("decoder prompt", decoder.getInputTensorType(IN_1), 1, 2, 256)
            expect("decoder features s1", decoder.getInputTensorType(IN_2), 1, 64, 128, 128)
            expect("decoder features s0", decoder.getInputTensorType(IN_3), 1, 32, 256, 256)
            expect("decoder masks", decoder.getOutputTensorType(OUT_0), 1, 3, Sam.MASK, Sam.MASK)
            expect("decoder scores", decoder.getOutputTensorType(OUT_1), 1, 3)
        }

        private fun expect(what: String, type: TensorType, vararg dimensions: Int) {
            val actual = type.layout?.dimensions
            check(actual == dimensions.toList()) { "Segment Anything's $what is $actual, not ${dimensions.toList()}" }
        }

        /** Looks at the photo; the decoder keeps what it saw for the prompts that follow. */
        fun encode(input: FloatArray) = diagnostics.during(step) {
            image.writeFloat(input)
            encoder.run(mapOf(IN_0 to image), mapOf(OUT_0 to embeddings, OUT_1 to featuresS1, OUT_2 to featuresS0))
            copy(embeddings, decoderEmbeddings, 256 * 64 * 64)
            copy(featuresS1, decoderS1, 64 * 128 * 128)
            copy(featuresS0, decoderS0, 32 * 256 * 256)
        }

        fun decode(sparse: FloatArray): List<Sam.Candidate> = diagnostics.during(step) {
            prompt.writeFloat(sparse)
            decoder.run(mapOf(IN_0 to decoderEmbeddings, IN_1 to prompt, IN_2 to decoderS1, IN_3 to decoderS0), mapOf(OUT_0 to masks, OUT_1 to iou))
            Sam.candidates(masks.readFloat(), iou.readFloat())
        }

        private fun copy(from: TensorBuffer, to: TensorBuffer, size: Int) {
            val values = from.readFloat()
            check(values.size == size) { "Segment Anything's encoder gave ${values.size} values where $size were expected" }
            to.writeFloat(values)
        }

        override fun close() {
            buffers.forEach { it.close() }
            decoder.close()
            encoder.close()
        }
    }

    override suspend fun segment(photo: Bitmap, onProgress: (label: String, fraction: Float?) -> Unit): Segmentation? = lock.withLock {
        onProgress("Finding the objects with Segment Anything…", null)
        withContext(worker) {
            val input = encoderInput(photo)
            val grid = ArrayList<Sam.Candidate>()
            running { models, constants ->
                encoded = null
                models.encode(input)
                encoded = photo
                grid.clear()
                for (gy in 0 until Sam.GRID) {
                    for (gx in 0 until Sam.GRID) {
                        ensureActive()
                        grid += models.decode(Sam.prompt((gx + 0.5f) / Sam.GRID, (gy + 0.5f) / Sam.GRID, constants)).filter(Sam::plausible)
                    }
                }
            }
            val objects = Sam.objects(grid)
            if (objects.isEmpty()) return@withContext Segmentation(photo.width, photo.height, null, emptyList())
            val subjects = objects.map { Sam.subject(it, photo.width, photo.height) }
            // Several objects: start from the one the photo is most likely of; taps add the others.
            val suggested = if (subjects.size > 1) setOf(Sam.mainObject(objects)) else emptySet()
            Segmentation(photo.width, photo.height, null, subjects, suggested)
        }
    }

    override suspend fun objectAt(photo: Bitmap, u: Float, v: Float): Subject? = lock.withLock {
        withContext(worker) {
            val models = models ?: return@withContext null
            val constants = constants ?: return@withContext null
            if (encoded !== photo) return@withContext null
            Sam.forTap(models.decode(Sam.prompt(u, v, constants)))?.let { Sam.subject(it, photo.width, photo.height) }
        }
    }

    /** The photo stretched to the encoder's square input. */
    private fun encoderInput(photo: Bitmap): FloatArray {
        val square = photo.scale(Sam.INPUT, Sam.INPUT)
        val argb = IntArray(Sam.INPUT * Sam.INPUT)
        square.getPixels(argb, 0, Sam.INPUT, 0, 0, Sam.INPUT, Sam.INPUT)
        if (square !== photo) square.recycle()
        return Sam.encoderInput(argb)
    }

    /** Runs [work] with the models, moving from the GPU to the CPU for good if the GPU fails. */
    private inline fun running(work: (Models, Sam.PromptConstants) -> Unit) {
        val constants = constants ?: Sam.PromptConstants.read(files.prompts.file.readBytes()).also { constants = it }
        val current = models ?: open().also { models = it }
        try {
            work(current, constants)
        } catch (e: Exception) {
            if (current.accelerator != Accelerator.GPU) throw e
            Log.w(TAG, "Segment Anything stopped working on the GPU; using the CPU", e)
            current.close()
            models = null
            val cpu = load(Accelerator.CPU).also { models = it }
            work(cpu, constants)
        }
    }

    private fun open(): Models {
        if (!diagnostics.isOff(Fallback.NO_SAM_GPU)) {
            try {
                return load(Accelerator.GPU)
            } catch (e: Exception) {
                Log.i(TAG, "Segment Anything can't use the GPU", e)
            } catch (e: LinkageError) {
                Log.i(TAG, "Segment Anything can't use the GPU", e)
            }
        }
        return load(Accelerator.CPU)
    }

    private fun load(accelerator: Accelerator): Models {
        val step = if (accelerator == Accelerator.GPU) Step.SAM_GPU else Step.SAM_CPU
        return diagnostics.during(step) {
            val encoder = CompiledModel.create(files.encoder.file.absolutePath, options(accelerator, "sam2_tiny_encoder_v2"))
            try {
                val decoder = CompiledModel.create(files.decoder.file.absolutePath, options(accelerator, "sam2_tiny_decoder_v2"))
                try {
                    Models(accelerator, encoder, decoder)
                } catch (e: Exception) {
                    decoder.close()
                    throw e
                }
            } catch (e: Exception) {
                encoder.close()
                throw e
            }
        }
    }

    private fun options(accelerator: Accelerator, cacheKey: String) = when (accelerator) {
        Accelerator.GPU -> CompiledModel.Options(Accelerator.GPU).apply {
            gpuOptions = CompiledModel.GpuOptions(serializationDir = cacheDir.absolutePath, modelCacheKey = cacheKey, serializeProgramCache = true)
        }
        else -> CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS) }
    }

    private companion object {
        const val TAG = "Reality3DSam"
        const val CPU_THREADS = 4

        // Tensor names in both models' signatures: encoder image -> embeddings, s1 and s0 features; decoder
        // embeddings, prompt, s1, s0 -> masks and their predicted IoU.
        const val IN_0 = "args_0"
        const val IN_1 = "args_1"
        const val IN_2 = "args_2"
        const val IN_3 = "args_3"
        const val OUT_0 = "output_0"
        const val OUT_1 = "output_1"
        const val OUT_2 = "output_2"
    }
}

/**
 * Chooses how photos are cut out: ML Kit where it works (nothing to download), and Segment Anything on phones
 * where ML Kit crashed the app, once the user has fetched it.
 */
class CutOut(
    private val mlKit: SubjectSegmenterEngine,
    private val sam: SubjectSegmenterEngine,
    private val samModel: SegmentAnythingModel,
    private val diagnostics: Diagnostics,
) : SubjectSegmenterEngine {

    private val useSam: Boolean
        get() = diagnostics.isOff(Fallback.ONE_OBJECT) && !diagnostics.isOff(Fallback.NO_SAM) && samModel.isReady

    override val canPick: Boolean get() = useSam && sam.canPick

    override suspend fun segment(photo: Bitmap, onProgress: (label: String, fraction: Float?) -> Unit): Segmentation? =
        if (useSam) sam.segment(photo, onProgress) else mlKit.segment(photo, onProgress)

    override suspend fun objectAt(photo: Bitmap, u: Float, v: Float): Subject? = if (useSam) sam.objectAt(photo, u, v) else null
}
