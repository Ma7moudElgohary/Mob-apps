package com.ma7moud.reality3d.ar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.preview.ArPreview
import com.ma7moud.reality3d.preview.ArPreviewFactory
import com.ma7moud.reality3d.preview.ArPreviewStatus
import com.ma7moud.reality3d.preview.PreviewModel
import com.ma7moud.reality3d.scan.ScanSupport
import com.ma7moud.reality3d.ui.FRAGMENT_SHADER
import com.ma7moud.reality3d.ui.VERTEX_SHADER
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class ArCorePreviewFactory : ArPreviewFactory {
    override fun check(activity: Activity, userRequestedInstall: Boolean): ScanSupport =
        ArCoreSupport.check(activity, userRequestedInstall, "the AR view")

    override fun create(context: Context, model: PreviewModel): ArPreview = ArCorePreview(context, model)
}

/**
 * The model standing on a real table or floor through ARCore, at its real size: tap a surface to place it,
 * drag to turn it, pinch to resize it. A soft shadow grounds it, and its brightness follows the room's.
 */
class ArCorePreview(context: Context, private val model: PreviewModel) : ArPreview {

    private val _status = MutableStateFlow(ArPreviewStatus())
    override val status: StateFlow<ArPreviewStatus> = _status.asStateFlow()

    private val appContext = context.applicationContext
    private var session: Session? = null
    private var closed = false
    private val surfaceView = ArPreviewView(context, this)
    override val view: GLSurfaceView get() = surfaceView

    // Shared with the GL thread.
    @Volatile private var resumed = false
    @Volatile private var yaw = 0f
    @Volatile private var scale = 1f
    @Volatile private var textureBound = false
    @Volatile private var geometryPending = false
    private val actions = ConcurrentLinkedQueue<(Session) -> Unit>()

    // GL thread only.
    private var anchor: Anchor? = null
    private var cameraTexture = 0
    private var geometry = intArrayOf(Surface.ROTATION_0, 1, 1)
    private val drawable = MeshDrawable()
    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val anchorMatrix = FloatArray(16)
    private val inverseModelView = FloatArray(16)
    private val overlayVertices = FloatArray(7 * 128)

    override fun resume() {
        if (closed) return
        if (session == null) {
            session = try {
                Session(appContext).also { created ->
                    created.configure(
                        Config(created)
                            .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
                            .setLightEstimationMode(Config.LightEstimationMode.AMBIENT_INTENSITY)
                            .setFocusMode(Config.FocusMode.AUTO)
                            .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE),
                    )
                }
            } catch (e: Exception) {
                fail("ARCore couldn't start: ${e.message ?: e.javaClass.simpleName}.")
                return
            }
            textureBound = false
            geometryPending = true
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            fail("The camera is being used by another app. Close it and try again.")
            return
        }
        surfaceView.onResume()
        resumed = true
        _status.update { if (it.phase == ArPreviewStatus.Phase.STARTING) it.copy(phase = ArPreviewStatus.Phase.FINDING_SURFACE) else it }
    }

    override fun pause() {
        resumed = false
        // The GL thread stops before the session pauses, so update() never runs on a paused session.
        surfaceView.onPause()
        session?.pause()
    }

    override fun resetScale() {
        scale = 1f
        _status.update { it.copy(scale = 1f) }
    }

    override fun placeAgain() {
        actions.offer { _ ->
            anchor?.detach()
            anchor = null
        }
        _status.update { it.copy(phase = ArPreviewStatus.Phase.FINDING_SURFACE) }
    }

    override fun close() {
        if (closed) return
        closed = true
        resumed = false
        surfaceView.onPause()
        session?.close()
        session = null
    }

    internal fun onTap(x: Float, y: Float) {
        actions.offer { current ->
            val frame = lastFrame ?: return@offer
            val hit = frame.hitTest(x, y).firstOrNull { result ->
                val plane = result.trackable as? Plane
                plane != null && plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(result.hitPose)
            }
            if (hit == null) {
                _status.update { it.copy(message = "No surface there yet. Point at the floor or a table and try again.") }
                return@offer
            }
            anchor?.detach()
            anchor = try {
                current.createAnchor(hit.hitPose)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't place the model", e)
                null
            }
            _status.update { it.copy(phase = if (anchor != null) ArPreviewStatus.Phase.PLACED else it.phase, message = null) }
        }
    }

    internal fun onTurn(degrees: Float) {
        yaw += degrees
    }

    internal fun onPinch(factor: Float) {
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        _status.update { it.copy(scale = scale) }
    }

    // ---- GL thread ----

    private var lastFrame: com.google.ar.core.Frame? = null

    internal fun onSurfaceCreated(background: CameraBackground) {
        cameraTexture = background.textureId
        textureBound = false
        drawable.create()
        drawable.upload(model.mesh, model.texture)
    }

    internal fun onSurfaceChanged(rotation: Int, width: Int, height: Int) {
        geometry = intArrayOf(rotation, width, height)
        geometryPending = true
    }

    internal fun onDrawFrame(background: CameraBackground, overlay: OverlayRenderer) {
        val current = session ?: return
        if (!resumed) return
        if (!textureBound && cameraTexture != 0) {
            current.setCameraTextureName(cameraTexture)
            textureBound = true
        }
        if (geometryPending) {
            current.setDisplayGeometry(geometry[0], geometry[1], geometry[2])
            geometryPending = false
        }
        val frame = try {
            current.update()
        } catch (e: Exception) {
            Log.w(TAG, "ARCore update failed", e)
            return
        }
        lastFrame = frame
        background.draw(frame)
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            _status.update { it.copy(trackingProblem = trackingMessage(camera.trackingFailureReason)) }
            return
        }
        while (true) actions.poll()?.invoke(current) ?: break
        camera.getProjectionMatrix(projection, 0, 0.02f, 50f)
        camera.getViewMatrix(viewMatrix, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewMatrix, 0)

        val placed = anchor?.takeIf { it.trackingState == TrackingState.TRACKING }
        if (placed == null) {
            // An aiming ring where the model would stand, at the middle of the screen.
            val hit = frame.hitTest(geometry[1] / 2f, geometry[2] / 2f).firstOrNull { result ->
                val plane = result.trackable as? Plane
                plane != null && plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(result.hitPose)
            }
            if (hit != null) {
                hit.hitPose.toMatrix(anchorMatrix, 0)
                drawRing(overlay, anchorMatrix, footprint() * 0.5f)
            }
            val phase = when {
                anchor != null -> ArPreviewStatus.Phase.PLACED
                hit != null -> ArPreviewStatus.Phase.READY_TO_PLACE
                else -> ArPreviewStatus.Phase.FINDING_SURFACE
            }
            _status.update { if (it.phase != phase || it.trackingProblem != null) it.copy(phase = phase, trackingProblem = null) else it }
            return
        }
        _status.update { if (it.trackingProblem != null) it.copy(trackingProblem = null) else it }
        placed.pose.toMatrix(anchorMatrix, 0)
        drawShadow(overlay, anchorMatrix)
        // Anchor × turn × size × (base centre to the origin).
        val b = model.mesh.bounds
        val size = model.metersPerUnit * scale
        System.arraycopy(anchorMatrix, 0, modelMatrix, 0, 16)
        Matrix.rotateM(modelMatrix, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, size, size, size)
        Matrix.translateM(modelMatrix, 0, -(b[0] + b[3]) / 2, -b[1], -(b[2] + b[5]) / 2)
        Matrix.multiplyMM(modelView, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        Matrix.invertM(inverseModelView, 0, modelView, 0)
        drawable.draw(mvp, modelView, inverseModelView, shadeFrom(frame.lightEstimate), cutout = model.texture != null && !model.mesh.solid)
    }

    /** How much darker than the studio lights the room is, from ARCore's ambient light estimate. */
    private fun shadeFrom(estimate: LightEstimate): Float {
        if (estimate.state != LightEstimate.State.VALID) return 0f
        // A well lit room reads about 0.5 or more.
        return (1f - estimate.pixelIntensity / 0.5f).coerceIn(0f, 0.6f)
    }

    /** The model's footprint across, in meters at its current size. */
    private fun footprint(): Float {
        val size = model.mesh.size
        return max(size[0], size[2]) * model.metersPerUnit * scale
    }

    private fun drawRing(overlay: OverlayRenderer, pose: FloatArray, radius: Float) {
        var count = 0
        val point = FloatArray(4)
        val world = FloatArray(4)
        for (k in 0 until RING_POINTS) {
            val angle = 2 * PI * k / RING_POINTS
            point[0] = (radius * cos(angle)).toFloat()
            point[1] = 0.002f
            point[2] = (radius * sin(angle)).toFloat()
            point[3] = 1f
            Matrix.multiplyMV(world, 0, pose, 0, point, 0)
            count = putVertex(count, world, RING)
        }
        overlay.draw(GLES30.GL_POINTS, overlayVertices, count, viewProjection, pointSize = 12f)
    }

    /** A soft dark disc under the model, so it looks like it stands on the surface. */
    private fun drawShadow(overlay: OverlayRenderer, pose: FloatArray) {
        val radius = footprint() * 0.6f
        val point = FloatArray(4)
        val world = FloatArray(4)
        var count = 0
        point[0] = 0f
        point[1] = 0.001f
        point[2] = 0f
        point[3] = 1f
        Matrix.multiplyMV(world, 0, pose, 0, point, 0)
        count = putVertex(count, world, SHADOW_CENTRE)
        for (k in 0..SHADOW_SEGMENTS) {
            val angle = 2 * PI * k / SHADOW_SEGMENTS
            point[0] = (radius * cos(angle)).toFloat()
            point[2] = (radius * sin(angle)).toFloat()
            Matrix.multiplyMV(world, 0, pose, 0, point, 0)
            count = putVertex(count, world, SHADOW_RIM)
        }
        overlay.draw(GLES30.GL_TRIANGLE_FAN, overlayVertices, count, viewProjection)
    }

    private fun putVertex(index: Int, position: FloatArray, color: FloatArray): Int {
        val base = index * 7
        overlayVertices[base] = position[0]
        overlayVertices[base + 1] = position[1]
        overlayVertices[base + 2] = position[2]
        overlayVertices[base + 3] = color[0]
        overlayVertices[base + 4] = color[1]
        overlayVertices[base + 5] = color[2]
        overlayVertices[base + 6] = color[3]
        return index + 1
    }

    private fun fail(reason: String) {
        _status.value = ArPreviewStatus(phase = ArPreviewStatus.Phase.FAILED, message = reason)
    }

    private fun trackingMessage(reason: com.google.ar.core.TrackingFailureReason): String = when (reason) {
        com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on more light."
        com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION -> "Move the phone more slowly."
        com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a surface with more detail."
        else -> "Move the phone slowly so it can find its position."
    }

    private companion object {
        const val TAG = "Reality3DAr"
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 10f
        const val RING_POINTS = 36
        const val SHADOW_SEGMENTS = 32
        val RING = floatArrayOf(0.31f, 0.89f, 1f, 0.9f)
        val SHADOW_CENTRE = floatArrayOf(0f, 0f, 0f, 0.45f)
        val SHADOW_RIM = floatArrayOf(0f, 0f, 0f, 0f)
    }
}

/** The camera picture with the model over it. Tap places, one finger turns, two fingers resize. */
@SuppressLint("ViewConstructor")
internal class ArPreviewView(context: Context, private val preview: ArCorePreview) : GLSurfaceView(context) {

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            preview.onPinch(detector.scaleFactor)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            preview.onTap(e.x, e.y)
            performClick()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaler.isInProgress || e2.pointerCount > 1) return false
            preview.onTurn(-distanceX * 0.4f)
            return true
        }
    })

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(object : Renderer {
            private val background = CameraBackground()
            private val overlay = OverlayRenderer()

            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                background.create()
                overlay.create()
                preview.onSurfaceCreated(background)
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES30.glViewport(0, 0, width, height)
                preview.onSurfaceChanged(display?.rotation ?: Surface.ROTATION_0, width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
                preview.onDrawFrame(background, overlay)
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
        contentDescription = "Camera view. Tap a surface to place the model, drag to turn it, pinch to resize it."
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() runs from onSingleTapUp.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * A mesh in OpenGL with the viewer's shaders, drawn with matrices given from outside (here ARCore's). The
 * vertex layout matches the 3D viewer's: position, normal, UV, colour.
 */
internal class MeshDrawable {
    private var program = 0
    private val buffers = IntArray(2)
    private var texture = 0
    private var indexCount = 0
    private var hasColors = false
    private var textured = false
    private val uniforms = HashMap<String, Int>()

    fun create() {
        program = Gl.program(VERTEX_SHADER, FRAGMENT_SHADER)
        uniforms.clear()
        for (name in listOf("uMvp", "uModelView", "uTexture", "uMode", "uCutout", "uEnvironment", "uLightDir", "uCamera", "uDepthRange", "uShade")) {
            uniforms[name] = GLES30.glGetUniformLocation(program, name)
        }
        buffers.fill(0)
        texture = 0
    }

    fun upload(mesh: Mesh3D, photo: Bitmap?) {
        if (buffers[0] == 0) GLES30.glGenBuffers(2, buffers, 0)
        val vertices = ByteBuffer.allocateDirect(mesh.vertexCount * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uvs = mesh.uvs
        val colors = mesh.colors
        for (i in 0 until mesh.vertexCount) {
            vertices.put(mesh.positions[i * 3]).put(mesh.positions[i * 3 + 1]).put(mesh.positions[i * 3 + 2])
            vertices.put(mesh.normals[i * 3]).put(mesh.normals[i * 3 + 1]).put(mesh.normals[i * 3 + 2])
            if (uvs != null) vertices.put(uvs[i * 2]).put(uvs[i * 2 + 1]) else vertices.put(0f).put(0f)
            if (colors != null) vertices.put(colors[i * 3]).put(colors[i * 3 + 1]).put(colors[i * 3 + 2]) else vertices.put(1f).put(1f).put(1f)
        }
        (vertices as Buffer).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertexCount * STRIDE, vertices, GLES30.GL_STATIC_DRAW)
        val indices = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        indices.put(mesh.indices)
        (indices as Buffer).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        indexCount = mesh.indices.size
        hasColors = colors != null
        textured = photo != null && !photo.isRecycled
        if (textured && photo != null) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, photo, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
    }

    fun draw(mvp: FloatArray, modelView: FloatArray, inverseModelView: FloatArray, shade: Float, cutout: Boolean) {
        if (indexCount == 0) return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uniforms.getValue("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uniforms.getValue("uModelView"), 1, false, modelView, 0)
        GLES30.glUniform1i(uniforms.getValue("uMode"), if (textured) 0 else if (hasColors) 1 else 2)
        GLES30.glUniform1i(uniforms.getValue("uCutout"), if (cutout && textured) 1 else 0)
        GLES30.glUniform1i(uniforms.getValue("uEnvironment"), 1)
        // Light from above and a little in front, in the camera's frame.
        GLES30.glUniform3f(uniforms.getValue("uLightDir"), 0.3f, 0.8f, 0.5f)
        GLES30.glUniform3f(uniforms.getValue("uCamera"), inverseModelView[12], inverseModelView[13], inverseModelView[14])
        GLES30.glUniform2f(uniforms.getValue("uDepthRange"), 0f, 1f)
        GLES30.glUniform1f(uniforms.getValue("uShade"), shade)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniforms.getValue("uTexture"), 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, STRIDE, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, STRIDE, 32)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        for (attribute in 0..3) GLES30.glDisableVertexAttribArray(attribute)
    }

    private companion object {
        const val STRIDE = 11 * 4
    }
}
