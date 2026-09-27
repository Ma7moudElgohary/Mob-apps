package com.ma7moud.reality3d

import android.app.Application
import com.ma7moud.reality3d.ai.AiCoreAnalyzer
import com.ma7moud.reality3d.ai.ObjectAi
import com.ma7moud.reality3d.depth.DepthEngine
import com.ma7moud.reality3d.depth.MidasDepthEngine
import com.ma7moud.reality3d.segmentation.MlKitSubjectMasker
import com.ma7moud.reality3d.segmentation.SubjectSegmenterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** The on-device engines the app uses; tests swap in fakes. */
class Services(
    val depth: DepthEngine,
    val segmenter: SubjectSegmenterEngine,
    val ai: ObjectAi,
    /** False in JVM tests, which have no OpenGL. */
    val useGlViewer: Boolean = true,
)

open class Reality3DApplication : Application() {

    val appScope: CoroutineScope = MainScope()

    val services: Services by lazy { createServices() }

    protected open fun createServices(): Services =
        Services(MidasDepthEngine(this), MlKitSubjectMasker(this), AiCoreAnalyzer(appScope))
}
