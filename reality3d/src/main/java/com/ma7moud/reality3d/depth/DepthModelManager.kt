package com.ma7moud.reality3d.depth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the Depth Anything V2 model once. The download resumes after an interruption and is checked
 * against a SHA-256, and the model lives in no-backup storage so it never fills the user's backup quota.
 */
class DepthModelManager(context: Context) {

    private val directory: File = context.noBackupFilesDir
    val modelFile = File(directory, MODEL_FILE_NAME)
    private val partFile = File(directory, "$MODEL_FILE_NAME.part")

    init {
        // Earlier versions used MiDaS (34 MB), kept at first in filesDir and later in no-backup storage.
        for (dir in listOf(context.filesDir, directory)) {
            File(dir, LEGACY_FILE_NAME).delete()
            File(dir, "$LEGACY_FILE_NAME.part").delete()
        }
    }

    fun isReady(): Boolean = modelFile.length() == MODEL_BYTES

    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext modelFile
        if (partFile.length() > MODEL_BYTES) partFile.delete()
        if (partFile.length() < MODEL_BYTES) {
            val resumeFrom = partFile.length()
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("depth model download failed (HTTP $code)")
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
                var done = if (append) resumeFrom else 0L
                var reported = done
                onProgress(done.toFloat() / MODEL_BYTES)
                connection.inputStream.use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - reported >= PROGRESS_STEP_BYTES) {
                                reported = done
                                onProgress((done.toFloat() / MODEL_BYTES).coerceAtMost(1f))
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        if (partFile.length() != MODEL_BYTES) throw IOException("the download stopped early. Tap again to continue it")
        if (sha256(partFile) != MODEL_SHA256) {
            partFile.delete()
            throw IOException("the downloaded model was damaged. Tap again to download it")
        }
        if (!partFile.renameTo(modelFile)) throw IOException("couldn't save the depth model")
        onProgress(1f)
        modelFile
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = "0123456789abcdef"
        return buildString {
            for (byte in digest.digest()) {
                append(hex[(byte.toInt() shr 4) and 0xF])
                append(hex[byte.toInt() and 0xF])
            }
        }
    }

    companion object {
        const val MODEL_BYTES = 27_733_680L
        const val MODEL_SHA256 = "f74509422e4a9270a354b249a9193abdd4903354be63701262238a7f4b869611"
        private const val MODEL_FILE_NAME = "depth_anything_v2_small_wi8_afp32.tflite"
        private const val LEGACY_FILE_NAME = "midas_small_256_fp16.tflite"

        // Depth Anything V2 Small (Apache-2.0) with int8 weights, pinned to one revision of
        // litert-community/depth-anything-v2-small so the checksum always matches.
        private const val MODEL_URL = "https://huggingface.co/litert-community/depth-anything-v2-small/resolve/" +
            "178427e448dbf4da93b1e7b1b2abc103ad329bd6/tflite/depth_anything_v2_small_wi8_afp32.tflite"
        private const val PROGRESS_STEP_BYTES = 256 * 1024
    }
}
