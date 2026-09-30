package com.ma7moud.reality3d

import android.app.Application
import com.ma7moud.reality3d.ai.AiCoreAnalyzer
import com.ma7moud.reality3d.ar.ArCorePreviewFactory
import com.ma7moud.reality3d.ar.ArCoreScanFactory
import com.ma7moud.reality3d.ai.ObjectAi
import com.ma7moud.reality3d.depth.DepthEngine
import com.ma7moud.reality3d.depth.DepthAnythingEngine
import com.ma7moud.reality3d.diagnostics.Diagnostics
import com.ma7moud.reality3d.preview.ArPreviewFactory
import com.ma7moud.reality3d.project.ProjectStore
import com.ma7moud.reality3d.remote.HttpRemoteServer
import com.ma7moud.reality3d.remote.RemoteServer
import com.ma7moud.reality3d.scan.ScanEngineFactory
import com.ma7moud.reality3d.segmentation.CutOut
import com.ma7moud.reality3d.segmentation.MlKitSubjectMasker
import com.ma7moud.reality3d.segmentation.SamModelFiles
import com.ma7moud.reality3d.segmentation.SamSegmenter
import com.ma7moud.reality3d.segmentation.SegmentAnythingModel
import com.ma7moud.reality3d.segmentation.SubjectSegmenterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import java.io.File

/** The on-device engines the app uses; tests swap in fakes. */
class Services(
    val depth: DepthEngine,
    val segmenter: SubjectSegmenterEngine,
    val ai: ObjectAi,
    /** 360° scanning with ARCore. */
    val scanner: ScanEngineFactory,
    /** Saved models, for the gallery. */
    val projects: ProjectStore,
    /** Models standing in the room, through ARCore. */
    val arPreview: ArPreviewFactory,
    /** Image-to-3D AIs on the user's own computer. */
    val remote: RemoteServer,
    /** Segment Anything's model files, for phones where ML Kit's segmenter crashes. */
    val segmentAnything: SegmentAnythingModel,
    /** False in JVM tests, which have no OpenGL. */
    val useGlViewer: Boolean = true,
)

open class Reality3DApplication : Application() {

    val appScope: CoroutineScope = MainScope()

    /** Why the app last closed unexpectedly, and the features turned off after native crashes. */
    val diagnostics: Diagnostics by lazy { Diagnostics(this) }

    val services: Services by lazy { createServices() }

    override fun onCreate() {
        super.onCreate()
        diagnostics.watchJavaCrashes()
    }

    protected open fun createServices(): Services {
        val sam = SamModelFiles(this)
        return Services(
            DepthAnythingEngine(this, diagnostics),
            CutOut(MlKitSubjectMasker(this, diagnostics), SamSegmenter(this, sam, diagnostics), sam, diagnostics),
            AiCoreAnalyzer(appScope),
            ArCoreScanFactory(),
            projectStore(),
            ArCorePreviewFactory(),
            HttpRemoteServer(),
            sam,
        )
    }

    protected fun projectStore() = ProjectStore(File(filesDir, "projects"))
}
