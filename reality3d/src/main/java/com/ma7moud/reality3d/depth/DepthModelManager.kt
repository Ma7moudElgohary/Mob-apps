package com.ma7moud.reality3d.depth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DepthModelManager(private val context: Context) {
    val modelFile: File = File(context.filesDir, MODEL_FILE_NAME)
    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > 20_000_000

    suspend fun download(): File = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext modelFile
        val temp = File(context.filesDir, "$MODEL_FILE_NAME.part")
        if (temp.exists()) temp.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("Model download failed: HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> temp.outputStream().buffered().use { output -> input.copyTo(output) } }
            if (temp.length() < 20_000_000) error("Downloaded depth model is unexpectedly small")
            if (modelFile.exists()) modelFile.delete()
            check(temp.renameTo(modelFile)) { "Unable to finalize depth model" }
            modelFile
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MODEL_FILE_NAME = "midas_small_256_fp16.tflite"
        private const val MODEL_URL = "https://huggingface.co/litert-community/MiDaS-small/resolve/main/midas_small_256_fp16.tflite?download=true"
    }
}
