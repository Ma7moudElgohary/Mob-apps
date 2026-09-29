package com.ma7moud.reality3d.data

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
 * One model file, downloaded once: the download resumes after an interruption and is checked against a
 * SHA-256 before it is used. Models live in no-backup storage so they never fill the user's backup quota.
 */
class ModelDownload(
    directory: File,
    fileName: String,
    private val url: String,
    val bytes: Long,
    private val sha256: String,
    /** What the file is, for error messages ("depth model"). */
    private val label: String,
) {
    val file = File(directory, fileName)
    private val partFile = File(directory, "$fileName.part")

    fun isReady(): Boolean = file.length() == bytes

    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext file
        if (partFile.length() > bytes) partFile.delete()
        if (partFile.length() < bytes) {
            val resumeFrom = partFile.length()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("$label download failed (HTTP $code)")
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
                var done = if (append) resumeFrom else 0L
                var reported = done
                onProgress(done.toFloat() / bytes)
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
                                onProgress((done.toFloat() / bytes).coerceAtMost(1f))
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        if (partFile.length() != bytes) throw IOException("the download stopped early. Tap again to continue it")
        if (sha256(partFile) != sha256) {
            partFile.delete()
            throw IOException("the downloaded $label was damaged. Tap again to download it")
        }
        if (!partFile.renameTo(file)) throw IOException("couldn't save the $label")
        onProgress(1f)
        file
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

    private companion object {
        const val PROGRESS_STEP_BYTES = 256 * 1024
    }
}
