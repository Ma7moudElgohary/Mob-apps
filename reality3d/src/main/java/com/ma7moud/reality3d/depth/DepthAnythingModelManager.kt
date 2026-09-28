package com.ma7moud.reality3d.depth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DepthAnythingModelManager(private val context: Context) {
    val modelFile: File = File(context.filesDir, MODEL_FILE_NAME)

    fun isReady(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES

    suspend fun download(onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext modelFile
        val temp = File(context.filesDir, "$MODEL_FILE_NAME.part")
        if (temp.exists()) temp.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Reality3D/0.5")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Depth Anything V2 download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0L) onProgress(((copied * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (temp.length() < MIN_MODEL_BYTES) {
                error("Downloaded Depth Anything V2 model is unexpectedly small (${temp.length()} bytes)")
            }
            if (modelFile.exists()) modelFile.delete()
            check(temp.renameTo(modelFile)) { "Unable to finalize Depth Anything V2 model" }
            onProgress(100)
            modelFile
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MODEL_FILE_NAME = "depth_anything_v2_small_wi8_afp32.tflite"
        private const val MIN_MODEL_BYTES = 25_000_000L
        private const val MODEL_URL = "https://huggingface.co/litert-community/depth-anything-v2-small/resolve/main/tflite/depth_anything_v2_small_wi8_afp32.tflite?download=true"
    }
}
