package com.ma7moud.reality3d.depth

import android.content.Context
import com.ma7moud.reality3d.data.ModelDownload
import java.io.File

/** Downloads the Depth Anything V2 model once (resumable, checked with SHA-256, in no-backup storage). */
class DepthModelManager(context: Context) {

    private val directory: File = context.noBackupFilesDir
    private val model = ModelDownload(directory, MODEL_FILE_NAME, MODEL_URL, MODEL_BYTES, MODEL_SHA256, "depth model")
    val modelFile: File = model.file

    init {
        // Earlier versions used MiDaS (34 MB), kept at first in filesDir and later in no-backup storage.
        for (dir in listOf(context.filesDir, directory)) {
            File(dir, LEGACY_FILE_NAME).delete()
            File(dir, "$LEGACY_FILE_NAME.part").delete()
        }
    }

    fun isReady(): Boolean = model.isReady()

    suspend fun download(onProgress: (Float) -> Unit): File = model.download(onProgress)

    companion object {
        const val MODEL_BYTES = 27_733_680L
        const val MODEL_SHA256 = "f74509422e4a9270a354b249a9193abdd4903354be63701262238a7f4b869611"
        private const val MODEL_FILE_NAME = "depth_anything_v2_small_wi8_afp32.tflite"
        private const val LEGACY_FILE_NAME = "midas_small_256_fp16.tflite"

        // Depth Anything V2 Small (Apache-2.0) with int8 weights, pinned to one revision of
        // litert-community/depth-anything-v2-small so the checksum always matches.
        private const val MODEL_URL = "https://huggingface.co/litert-community/depth-anything-v2-small/resolve/" +
            "178427e448dbf4da93b1e7b1b2abc103ad329bd6/tflite/depth_anything_v2_small_wi8_afp32.tflite"
    }
}
