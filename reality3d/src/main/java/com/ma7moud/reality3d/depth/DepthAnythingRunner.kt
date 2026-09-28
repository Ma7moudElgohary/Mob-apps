package com.ma7moud.reality3d.depth

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File

/** The hardware the depth model runs on. */
enum class DepthBackend { GPU, NPU, CPU }

/** What the first-run benchmark found: milliseconds per run for each backend that ran, and the one in use. */
class DepthBenchmark(val chosen: DepthBackend, val timings: Map<DepthBackend, Long>, val rejected: Map<DepthBackend, String>) {
    val summary: String
        get() = buildString {
            append(chosen.name)
            timings[chosen]?.let { append(" · ").append(it).append(" ms") }
            val others = timings.filterKeys { it != chosen }.entries.joinToString { "${it.key.name} ${it.value} ms" }
            if (others.isNotEmpty()) append(" (").append(others).append(")")
        }
}

/**
 * Runs Depth Anything V2 with LiteRT. The first estimate times the CPU, the GPU (forced to fp32, as the
 * model's validation requires) and the NPU when the phone has one, keeps the fastest backend whose output
 * matches the CPU's, and remembers the choice. A backend that fails later falls back to the CPU for good.
 */
internal class DepthAnythingRunner(context: Context, private val modelFile: File, private val modelKey: String) : Closeable {

    private val cacheDir = File(context.cacheDir, "litert").apply { mkdirs() }
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class Loaded(val backend: DepthBackend, val model: CompiledModel, val environment: Environment?) : Closeable {
        val inputs: List<TensorBuffer> = model.createInputBuffers()
        val outputs: List<TensorBuffer> = model.createOutputBuffers()

        fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        override fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
            environment?.close()
        }
    }

    private var loaded: Loaded? = null

    /** The benchmark result once the first estimate has run (or a remembered one). */
    @Volatile
    var benchmark: DepthBenchmark? = readBenchmark()
        private set

    @Synchronized
    fun run(input: FloatArray): FloatArray {
        loaded?.let { current ->
            return try {
                current.run(input)
            } catch (e: Exception) {
                if (current.backend == DepthBackend.CPU) throw e
                Log.w(TAG, "${current.backend} stopped working; using the CPU from now on", e)
                current.close()
                loaded = null
                remember(DepthBenchmark(DepthBackend.CPU, benchmark?.timings.orEmpty(), benchmark?.rejected.orEmpty() + (current.backend to (e.message ?: "failed"))))
                open(DepthBackend.CPU).also { loaded = it }.run(input)
            }
        }
        val remembered = benchmark
        if (remembered != null) {
            val opened = try {
                open(remembered.chosen)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open ${remembered.chosen}; benchmarking again", e)
                null
            }
            if (opened != null) {
                loaded = opened
                return run(input)
            }
        }
        return benchmark(input)
    }

    /** Times every backend on [input], keeps the best one open and returns the CPU's (reference) output. */
    private fun benchmark(input: FloatArray): FloatArray {
        val timings = LinkedHashMap<DepthBackend, Long>()
        val rejected = LinkedHashMap<DepthBackend, String>()
        val cpu = open(DepthBackend.CPU)
        cpu.run(input)
        var start = SystemClock.elapsedRealtime()
        val reference = cpu.run(input)
        timings[DepthBackend.CPU] = SystemClock.elapsedRealtime() - start
        var best = cpu
        for (backend in listOf(DepthBackend.GPU, DepthBackend.NPU)) {
            if (backend == DepthBackend.NPU && !npuAvailable()) {
                rejected[backend] = "not on this phone"
                continue
            }
            var candidate: Loaded? = null
            try {
                candidate = open(backend)
                val first = candidate.run(input)
                start = SystemClock.elapsedRealtime()
                candidate.run(input)
                val ms = SystemClock.elapsedRealtime() - start
                if (!DepthTensors.agree(reference, first)) {
                    rejected[backend] = "results differ from the CPU"
                    candidate.close()
                    continue
                }
                timings[backend] = ms
                if (ms < timings.getValue(best.backend)) {
                    if (best !== cpu) best.close()
                    best = candidate
                } else {
                    candidate.close()
                }
            } catch (e: Exception) {
                rejected[backend] = e.message ?: e.javaClass.simpleName
                Log.i(TAG, "$backend unavailable for depth", e)
                candidate?.close()
            }
        }
        if (best !== cpu) cpu.close()
        loaded = best
        remember(DepthBenchmark(best.backend, timings, rejected))
        return reference
    }

    private fun npuAvailable(): Boolean = try {
        Environment.create(appContext).use { Accelerator.NPU in it.getAvailableAccelerators() }
    } catch (e: Exception) {
        false
    }

    private fun open(backend: DepthBackend): Loaded {
        val options = when (backend) {
            DepthBackend.CPU -> CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS)
            }
            DepthBackend.GPU -> CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                    serializationDir = cacheDir.absolutePath,
                    modelCacheKey = modelKey.take(16),
                    serializeProgramCache = true,
                )
            }
            DepthBackend.NPU -> CompiledModel.Options(Accelerator.NPU)
        }
        val environment = if (backend == DepthBackend.NPU) Environment.create(appContext) else null
        try {
            val model = if (environment != null) {
                CompiledModel.create(modelFile.absolutePath, options, environment)
            } else {
                CompiledModel.create(modelFile.absolutePath, options)
            }
            return Loaded(backend, model, environment)
        } catch (e: Exception) {
            environment?.close()
            throw e
        }
    }

    private fun remember(result: DepthBenchmark) {
        benchmark = result
        prefs.edit {
            putString(KEY_MODEL, modelKey)
            putString(KEY_CHOSEN, result.chosen.name)
            putString(KEY_TIMINGS, result.timings.entries.joinToString(",") { "${it.key.name}=${it.value}" })
        }
    }

    private fun readBenchmark(): DepthBenchmark? {
        if (prefs.getString(KEY_MODEL, null) != modelKey) return null
        val chosen = prefs.getString(KEY_CHOSEN, null)?.let { name -> DepthBackend.entries.firstOrNull { it.name == name } } ?: return null
        val timings = prefs.getString(KEY_TIMINGS, "").orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('=')
            val backend = DepthBackend.entries.firstOrNull { it.name == parts.getOrNull(0) }
            val ms = parts.getOrNull(1)?.toLongOrNull()
            if (backend != null && ms != null) backend to ms else null
        }.toMap()
        return DepthBenchmark(chosen, timings, emptyMap())
    }

    /** Forgets the choice so the next estimate benchmarks again. */
    @Synchronized
    fun rebenchmark() {
        loaded?.close()
        loaded = null
        benchmark = null
        prefs.edit { clear() }
    }

    @Synchronized
    override fun close() {
        loaded?.close()
        loaded = null
    }

    private companion object {
        const val TAG = "Reality3DDepth"
        const val PREFS = "depth_backend"
        const val KEY_MODEL = "model"
        const val KEY_CHOSEN = "chosen"
        const val KEY_TIMINGS = "timings"
        const val CPU_THREADS = 4
    }
}
