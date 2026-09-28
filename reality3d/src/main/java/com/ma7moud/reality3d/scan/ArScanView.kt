package com.ma7moud.reality3d.scan

import android.app.Activity
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArScanView(
    context: Context,
    private val activity: Activity,
    private val resumeFile: File?,
    private val callbacks: Callbacks,
) : GLSurfaceView(context), GLSurfaceView.Renderer {
    interface Callbacks {
        fun onCoverage(state: CoverageState)
        fun onStatus(message: String)
        fun onMeshReady(mesh: com.ma7moud.reality3d.mesh.DepthMesh, volume: SparseTsdfVolume, target: Vector3?, coverage: BooleanArray)
        fun onError(message: String)
    }

    private var session: Session? = null
    private var background: ArCameraBackgroundRenderer? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var frameCounter = 0
    private var finishing = false
    private val coach = CoverageCoach()
    private var volume = SparseTsdfVolume()
    private var target: Vector3? = null
    private var coverageState = CoverageState()

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        setPreserveEGLContextOnPause(true)
        resumeFile?.takeIf { it.exists() }?.let { file ->
            ScanSessionStore.load(file)?.let {
                volume = it.volume; target = it.target
                coverageState = CoverageState(it.coverage)
            }
        }
    }

    fun resumeAr() {
        queueEvent {
            try {
                if (session == null) {
                    val install = ArCoreApk.getInstance().requestInstall(activity, true)
                    if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                        activity.runOnUiThread { callbacks.onStatus("Install/update Google Play Services for AR, then reopen Scan 360.") }
                        return@queueEvent
                    }
                    val created = Session(activity)
                    val config = Config(created)
                    if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        created.close(); throw IllegalStateException("ARCore Depth API is not supported on this device.")
                    }
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    config.focusMode = Config.FocusMode.AUTO
                    created.configure(config)
                    session = created
                }
                session?.resume()
            } catch (e: Throwable) {
                activity.runOnUiThread { callbacks.onError(e.message ?: e.javaClass.simpleName) }
            }
        }
        super.onResume()
    }

    fun pauseAr() {
        queueEvent { runCatching { session?.pause() } }
        super.onPause()
    }

    fun finishScan() {
        if (finishing) return
        finishing = true
        callbacks.onStatus("Extracting watertight scan mesh…")
        Thread {
            runCatching { TsdfMeshExtractor.extract(volume) }
                .onSuccess { mesh -> activity.runOnUiThread { callbacks.onMeshReady(mesh, volume, target, coverageState.covered.copyOf()) } }
                .onFailure { error -> activity.runOnUiThread { finishing = false; callbacks.onError(error.message ?: "Mesh extraction failed") } }
        }.start()
    }

    fun saveSession(file: File) {
        val v = volume; val t = target; val c = coverageState.covered.copyOf()
        Thread { runCatching { ScanSessionStore.save(file, v, t, c) } }.start()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f,0f,0f,1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        background = ArCameraBackgroundRenderer()
        session?.setCameraTextureName(background!!.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        val rotation = if (android.os.Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: Surface.ROTATION_0 else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        val bg = background ?: return
        if (s.cameraConfig == null) return
        runCatching { s.setCameraTextureName(bg.textureId) }
        val frame = runCatching { s.update() }.getOrNull() ?: return
        bg.draw(frame)
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            val state = coverageState.copy(trackingGood = false)
            coverageState = state
            activity.runOnUiThread { callbacks.onCoverage(state) }
            return
        }
        frameCounter++
        if (frameCounter % 6 != 0 || finishing) return
        val integrated = RawDepthIntegrator.integrate(frame, volume, target)
        target = integrated.target
        val pose = camera.pose
        val cameraPos = Vector3(pose.tx(), pose.ty(), pose.tz())
        val t = target
        if (t != null) {
            coverageState = coach.update(cameraPos, t, frame.timestamp, integrated.meanConfidence, true)
            activity.runOnUiThread { callbacks.onCoverage(coverageState) }
        } else {
            activity.runOnUiThread { callbacks.onStatus("Point the center reticle at the object and move slightly sideways to initialize depth.") }
        }
    }
}
