package com.ma7moud.reality3d.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import com.google.ar.core.*
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath
import com.ma7moud.reality3d.scan.ArCameraBackgroundRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArPreviewView(
    context: Context,
    private val activity: Activity,
    private val mesh: DepthMesh,
) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private var session: Session? = null
    private var background: ArCameraBackgroundRenderer? = null
    private var anchor: Anchor? = null
    private var tapX = -1f
    private var tapY = -1f
    private var modelRenderer: ArMeshRenderer? = null
    private var modelYaw = 0f
    private var modelScale = if (mesh.isMetric) 1f else 0.28f
    private var lastX = 0f
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                modelScale = (modelScale * detector.scaleFactor).coerceIn(0.03f, 4f)
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        setPreserveEGLContextOnPause(true)
    }

    fun resumeAr() {
        try {
            if (session == null) {
                if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return
                val created = Session(activity)
                val config = Config(created)
                if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) config.depthMode = Config.DepthMode.AUTOMATIC
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                created.configure(config)
                created.setDisplayGeometry(currentRotation(), surfaceWidth, surfaceHeight)
                session = created
            }
            queueEvent {
                runCatching {
                    session?.resume()
                    background?.let { session?.setCameraTextureName(it.textureId) }
                }
            }
            super.onResume()
        } catch (_: Throwable) {
            // The activity keeps the camera UI visible; the next resume can retry ARCore setup.
        }
    }

    fun pauseAr() {
        queueEvent { runCatching { session?.pause() } }
        super.onPause()
    }

    fun clearPlacement() {
        queueEvent {
            anchor?.detach()
            anchor = null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (anchor != null && !scaleDetector.isInProgress) {
                    modelYaw += (event.x - lastX) * 0.3f
                    lastX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (anchor == null) {
                    tapX = event.x
                    tapY = event.y
                }
                return true
            }
        }
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        background = ArCameraBackgroundRenderer()
        modelRenderer = ArMeshRenderer(mesh)
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
        if (camera.trackingState != TrackingState.TRACKING) return
        if (anchor == null && tapX >= 0f) {
            val hits = frame.hitTest(tapX, tapY)
            tapX = -1f
            tapY = -1f
            val hit = hits.firstOrNull { result ->
                when (val trackable = result.trackable) {
                    is Plane -> trackable.isPoseInPolygon(result.hitPose)
                    is Point -> true
                    is DepthPoint -> true
                    else -> false
                }
            }
            anchor = hit?.createAnchor()
        }
        val placed = anchor ?: return
        if (placed.trackingState != TrackingState.TRACKING) return
        val view = FloatArray(16)
        val projection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 50f)
        modelRenderer?.draw(placed.pose, view, projection, modelYaw, modelScale)
    }

    private fun currentRotation(): Int = if (android.os.Build.VERSION.SDK_INT >= 30) {
        activity.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.rotation
    }
}

private class ArMeshRenderer(source: DepthMesh) {
    private val mesh = if (source.normals.size == source.positions.size) source else MeshMath.recalculateNormals(source)
    private val vertices = floatBuffer(mesh.positions)
    private val normals = floatBuffer(mesh.normals)
    private val colors = floatBuffer(
        mesh.colors?.takeIf { it.size == mesh.vertexCount * 4 }
            ?: FloatArray(mesh.vertexCount * 4) { i -> if (i % 4 == 3) 1f else 0.65f },
    )
    private val indices = intBuffer(mesh.indices)
    private val program = createProgram()
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    fun draw(pose: Pose, view: FloatArray, projection: FloatArray, yaw: Float, scale: Float) {
        pose.toMatrix(model, 0)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, scale, scale, scale)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
        bind("aPosition", 3, vertices)
        bind("aNormal", 3, normals)
        bind("aColor", 4, colors)
        indices.position(0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indices.size, GLES30.GL_UNSIGNED_INT, indices)
    }

    private fun bind(name: String, size: Int, buffer: FloatBuffer) {
        val location = GLES30.glGetAttribLocation(program, name)
        GLES30.glEnableVertexAttribArray(location)
        buffer.position(0)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 0, buffer)
    }

    private fun createProgram(): Int {
        fun shader(type: Int, source: String): Int = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, source)
            GLES30.glCompileShader(it)
        }
        val vertex = shader(
            GLES30.GL_VERTEX_SHADER,
            "#version 300 es\nuniform mat4 uMvp;uniform mat4 uModel;in vec3 aPosition;in vec3 aNormal;in vec4 aColor;out vec3 n;out vec4 c;void main(){n=normalize(mat3(uModel)*aNormal);c=aColor;gl_Position=uMvp*vec4(aPosition,1.0);}",
        )
        val fragment = shader(
            GLES30.GL_FRAGMENT_SHADER,
            "#version 300 es\nprecision mediump float;in vec3 n;in vec4 c;out vec4 o;void main(){float d=0.25+0.75*max(dot(normalize(n),normalize(vec3(-0.3,0.8,1.0))),0.0);o=vec4(c.rgb*d,1.0);}",
        )
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertex)
            GLES30.glAttachShader(it, fragment)
            GLES30.glLinkProgram(it)
        }
    }

    companion object {
        private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private fun intBuffer(values: IntArray): IntBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(values); position(0) }
    }
}
