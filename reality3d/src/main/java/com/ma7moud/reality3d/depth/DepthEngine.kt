package com.ma7moud.reality3d.depth

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Monocular depth for a photo. */
interface DepthEngine {
    val isModelReady: Boolean
    val downloadBytes: Long

    suspend fun downloadModel(onProgress: (Float) -> Unit)

    suspend fun estimate(photo: Bitmap): DepthMap
}

class MidasDepthEngine(context: Context) : DepthEngine {

    private val models = DepthModelManager(context)
    private val estimator by lazy { MidasDepthEstimator(models.modelFile) }

    override val isModelReady: Boolean get() = models.isReady()
    override val downloadBytes: Long get() = DepthModelManager.MODEL_BYTES

    override suspend fun downloadModel(onProgress: (Float) -> Unit) {
        models.download(onProgress)
    }

    override suspend fun estimate(photo: Bitmap): DepthMap = withContext(Dispatchers.Default) { estimator.estimate(photo) }
}
