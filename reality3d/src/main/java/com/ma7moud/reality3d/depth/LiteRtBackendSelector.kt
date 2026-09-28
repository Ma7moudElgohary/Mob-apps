package com.ma7moud.reality3d.depth

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class InferenceBackend(val accelerator: Accelerator) {
    GPU(Accelerator.GPU),
    NPU(Accelerator.NPU),
    CPU(Accelerator.CPU),
}

data class BackendBenchmark(
    val backend: InferenceBackend,
    val milliseconds: Long?,
    val error: String? = null,
) {
    val succeeded: Boolean get() = milliseconds != null
}

class LiteRtBackendSelector(context: Context) {
    private val prefs = context.getSharedPreferences("reality3d_litert", Context.MODE_PRIVATE)

    fun preferred(): InferenceBackend? = prefs.getString(KEY_BACKEND, null)
        ?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() }

    fun savePreferred(backend: InferenceBackend) {
        prefs.edit().putString(KEY_BACKEND, backend.name).apply()
    }

    suspend fun benchmark(modelFile: File, input: FloatArray): List<BackendBenchmark> =
        withContext(Dispatchers.Default) {
            val results = listOf(
                InferenceBackend.GPU,
                InferenceBackend.NPU,
                InferenceBackend.CPU,
            ).map { backend ->
                runCatching {
                    CompiledModel.create(modelFile.absolutePath, optionsFor(backend)).use { model ->
                        val warmInputs = model.createInputBuffers()
                        warmInputs[0].writeFloat(input)
                        model.run(warmInputs)

                        val timedInputs = model.createInputBuffers()
                        timedInputs[0].writeFloat(input)
                        val start = SystemClock.elapsedRealtimeNanos()
                        val outputs = model.run(timedInputs)
                        outputs[0].readFloat()
                        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
                        BackendBenchmark(backend, elapsedMs)
                    }
                }.getOrElse {
                    BackendBenchmark(backend, null, it.message ?: it.javaClass.simpleName)
                }
            }
            results.filter { it.succeeded }
                .minByOrNull { it.milliseconds ?: Long.MAX_VALUE }
                ?.let { savePreferred(it.backend) }
            results
        }

    fun infer(
        modelFile: File,
        input: FloatArray,
        requested: InferenceBackend? = null,
    ): Pair<FloatArray, InferenceBackend> {
        val order = buildList {
            requested?.let(::add)
            preferred()?.takeIf { it !in this }?.let(::add)
            listOf(InferenceBackend.GPU, InferenceBackend.NPU, InferenceBackend.CPU).forEach {
                if (it !in this) add(it)
            }
        }
        var lastError: Throwable? = null
        for (backend in order) {
            try {
                CompiledModel.create(modelFile.absolutePath, optionsFor(backend)).use { model ->
                    val inputs = model.createInputBuffers()
                    inputs[0].writeFloat(input)
                    val outputs = model.run(inputs)
                    val values = outputs[0].readFloat()
                    savePreferred(backend)
                    return values to backend
                }
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "No LiteRT accelerator could execute Depth Anything V2",
            lastError,
        )
    }

    private fun optionsFor(backend: InferenceBackend): CompiledModel.Options =
        CompiledModel.Options(backend.accelerator).apply {
            if (backend == InferenceBackend.GPU) {
                gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                )
            }
        }

    companion object {
        private const val KEY_BACKEND = "preferred_backend"
    }
}
