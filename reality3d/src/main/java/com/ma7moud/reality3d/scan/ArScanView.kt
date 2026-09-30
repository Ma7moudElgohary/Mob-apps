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
    private var lastDepthTimestamp: Long? = null
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
                volume = it.volume
                target = it.target
                coverageState = CoverageState(it.coverage)
                coach.seed(it.coverage)
            }
        }
    }

    fun resumeAr() {
        try {
            if (session == null) {
                val install = ArCoreApk.getInstance().requestInstall(activity, true)
                if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    callbacks.onStatus("Install/update Google Play Services for AR, then reopen Scan 360.")
                    return
                }
                val created = Session(activity)
                val config = Config(created)
                if (!created.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    created.close()
                    throw IllegalStateException("ARCore Raw Depth API is not supported on this device.")
                }
                config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                config.focusMode = Config.FocusMode.AUTO
                created.configure(config)
                val rotation = currentRotation()
                created.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight)
                session = created
            }
            queueEvent {
                try {
                    session?.resume()
                    background?.let { session?.setCameraTextureName(it.textureId) }
                } catch (error: Throwable) {
                    activity.runOnUiThread { callbacks.onError(error.message ?: error.javaClass.simpleName) }
                }
            }
            super.onResume()
        } catch (error: Throwable) {
            callbacks.onError(error.message ?: error.javaClass.simpleName)
        }
    }

    fun pauseAr() {
        queueEvent { runCatching { session?.pause() } }
        super.onPause()
    }

    fun finishScan() {
        if (finishing) return
        finishing = true
        callbacks.onStatus("Extracting 360 scan mesh…")
        Thread {
            runCatching { TsdfMeshExtractor.extract(volume) }
                .onSuccess { mesh ->
                    activity.runOnUiThread {
                        callbacks.onMeshReady(mesh, volume, target, coverageState.covered.copyOf())
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        finishing = false
                        callbacks.onError(error.message ?: "Mesh extraction failed")
                    }
                }
        }.start()
    }

    fun allowFinishRetry() {
        finishing = false
    }

    fun saveSession(file: File, onSaved: ((Boolean) -> Unit)? = null) {
        val v = volume
        val t = target
        val c = coverageState.covered.copyOf()
        Thread {
            val success = runCatching { ScanSessionStore.save(file, v, t, c) }.isSuccess
            if (onSaved != null) activity.runOnUiThread { onSaved(success) }
        }.start()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        background = ArCameraBackgroundRenderer()
        session?.setCameraTextureName(background!!.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        session?.setDisplayGeometry(currentRotation(), surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        val bg = background ?: return
        runCatching { s.setCameraTextureName(bg.textureId) }
        val frame = runCatching { s.update() }.getOrNull() ?: return
        bg.draw(frame)
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            val state = coverageState.copy(trackingGood = false, frameUsable = false)
            coverageState = state
            activity.runOnUiThread { callbacks.onCoverage(state) }
            return
        }
        frameCounter++
        if (frameCounter % 6 != 0 || finishing) return

        val pose = camera.pose
        val cameraPos = Vector3(pose.tx(), pose.ty(), pose.tz())
        // ARCore physical camera convention is OpenGL-like: local -Z points where the camera
        // looks. Transform a point one meter down -Z and subtract the camera translation to get
        // the world-space forward vector without depending on an extra Pose helper API.
        val forwardPoint = pose.transformPoint(floatArrayOf(0f, 0f, -1f))
        val cameraForward = Vector3(
            forwardPoint[0] - cameraPos.x,
            forwardPoint[1] - cameraPos.y,
            forwardPoint[2] - cameraPos.z,
        ).normalized()

        val targetBeforeIntegration = target
        val poseAllowsFusion = targetBeforeIntegration == null || coach.shouldFuse(
            camera = cameraPos,
            cameraForward = cameraForward,
            target = targetBeforeIntegration,
            nowNanos = frame.timestamp,
            trackingGood = true,
        )

        val previousDepthTimestamp = lastDepthTimestamp
        val integrated = RawDepthIntegrator.integrate(
            frame = frame,
            volume = volume,
            existingTarget = targetBeforeIntegration,
            lastDepthTimestamp = previousDepthTimestamp,
            fuseIntoVolume = poseAllowsFusion,
        )
        lastDepthTimestamp = integrated.depthTimestamp
        target = integrated.target

        // Raw depth may be reprojected for several render frames. Only evaluate capture quality
        // when ARCore produced a new raw-depth image, otherwise stale frames would look like
        // zero-confidence captures and distort motion estimates.
        val hasFreshDepth = integrated.depthTimestamp != null &&
            integrated.depthTimestamp != previousDepthTimestamp
        if (!hasFreshDepth) return

        val t = target
        if (t != null) {
            coverageState = coach.update(
                camera = cameraPos,
                cameraForward = cameraForward,
                target = t,
                nowNanos = frame.timestamp,
                depthConfidence = integrated.meanConfidence,
                depthPointCount = integrated.usablePoints,
                trackingGood = true,
            )
            activity.runOnUiThread { callbacks.onCoverage(coverageState) }
        } else {
            activity.runOnUiThread {
                callbacks.onStatus("Point the center reticle at the object and move slightly sideways to initialize depth.")
            }
        }
    }

    private fun currentRotation(): Int = if (android.os.Build.VERSION.SDK_INT >= 30) {
        activity.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.rotation
    }
}
