package com.ma7moud.reality3d.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.graphics.createBitmap
import com.ma7moud.reality3d.mesh.Mesh3D
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * OpenGL ES 3 viewer: drag to orbit, pinch to zoom, two fingers to move, double-tap to reset. Shows the
 * model textured, as clay, as a wireframe or as normals or depth, and can pick points on it for measuring.
 */
class MeshSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer = MeshRenderer(
        density = resources.displayMetrics.density,
        onAnimationsFinished = { post { updateRenderMode() } },
    )
    private var mesh: Mesh3D? = null
    private var photo: Bitmap? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /** Taps pick points on the model instead of clicking the view. */
    var measuring = false

    /** Receives the picked point in model coordinates, or null when the tap missed the model. */
    var onPick: ((FloatArray?) -> Unit)? = null

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.zoomBy(detector.scaleFactor)
            renderer.panBy(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            requestRender()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaler.isInProgress || e2.pointerCount > 1) return false
            renderer.rotateBy(-distanceX * DEGREES_PER_PIXEL, -distanceY * DEGREES_PER_PIXEL)
            requestRender()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!measuring) return performClick()
            pick(e.x, e.y)
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "3D model viewer. Drag to rotate, pinch to zoom, two fingers to move, double-tap to reset."
    }

    /**
     * Shows a mesh, textured with [photo] or coloured per vertex. A new subject also resets the view and
     * gives the model a short turn.
     */
    fun setScene(mesh: Mesh3D, photo: Bitmap?) {
        if (mesh === this.mesh && photo === this.photo) return
        val newSubject = this.mesh == null || photo !== this.photo
        this.mesh = mesh
        this.photo = photo
        queueEvent { renderer.setScene(mesh, photo) }
        if (newSubject) {
            renderer.resetView()
            if (!renderer.compare) renderer.startSpin()
        }
        updateRenderMode()
    }

    fun setOptions(options: ViewerOptions) {
        if (renderer.options == options) return
        renderer.options = options
        updateRenderMode()
    }

    /** Straight-on orthographic front view, fixed, for comparing with the photo. */
    fun setCompare(compare: Boolean) {
        if (renderer.compare == compare) return
        renderer.compare = compare
        if (compare) renderer.stopSpin()
        updateRenderMode()
    }

    /** Points drawn on the model (model coordinates); two are joined by a line. */
    fun setMarkers(points: List<FloatArray>) {
        renderer.markers = points.map { it.copyOf() }
        requestRender()
    }

    fun showPreset(preset: ViewPreset) {
        renderer.animateTo(preset.yaw, preset.pitch)
        updateRenderMode()
    }

    fun resetView() {
        renderer.resetView()
        requestRender()
    }

    /** Renders a frame and hands it to [onCaptured] on the main thread. */
    fun capture(onCaptured: (Bitmap) -> Unit) {
        renderer.requestCapture { bitmap -> post { onCaptured(bitmap) } }
        requestRender()
    }

    private fun pick(x: Float, y: Float) {
        val mesh = mesh ?: return
        val inverse = renderer.inverseMvp() ?: return
        val hit = MeshPicker.pick(mesh, inverse, 2f * x / width - 1f, 1f - 2f * y / height)
        onPick?.invoke(hit)
    }

    private fun updateRenderMode() {
        renderMode = if (renderer.isAnimating) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() runs from onSingleTapConfirmed.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (renderer.compare) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Stop the scrolling screen from taking vertical drags meant for the model.
                parent?.requestDisallowInterceptTouchEvent(true)
                renderer.stopSpin()
                renderer.touching = true
                updateRenderMode()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                renderer.touching = false
            }
        }
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val DEGREES_PER_PIXEL = 0.3f
    }
}

private class MeshRenderer(private val density: Float, private val onAnimationsFinished: () -> Unit) : GLSurfaceView.Renderer {

    @Volatile
    var options = ViewerOptions()

    @Volatile
    var compare = false

    @Volatile
    var markers: List<FloatArray> = emptyList()

    /** A finger is on the view: auto-rotation waits. */
    @Volatile
    var touching = false

    // View state, shared between the UI thread (gestures) and the GL thread.
    private val lock = Any()
    private var yaw = DEFAULT_YAW
    private var pitch = DEFAULT_PITCH
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var spinStart = 0L
    private var spinBaseYaw = 0f
    private var spinning = false
    private var targetYaw: Float? = null
    private var targetPitch = 0f
    private var lastFrame = 0L
    private var unitsPerPixel = 0f
    private var radius = 1f
    private val lastMvp = FloatArray(16)
    private var hasFrame = false
    private var capture: ((Bitmap) -> Unit)? = null

    // GL thread only.
    private var mesh: Mesh3D? = null
    private var photo: Bitmap? = null
    private var uploadedMesh: Mesh3D? = null
    private var uploadedPhoto: Bitmap? = null
    private var hasColors = false
    private val center = FloatArray(3)
    private var program = 0
    private var overlayProgram = 0
    private val buffers = IntArray(4) // vertices, triangles, edges, markers
    private var texture = 0
    private var indexCount = 0
    private var edgeCount = -1
    private var width = 1
    private var height = 1
    private val uniforms = HashMap<String, Int>()
    private val overlayUniforms = HashMap<String, Int>()
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val inverseModelView = FloatArray(16)

    val isAnimating: Boolean
        get() = synchronized(lock) { spinning || targetYaw != null } || (options.autoRotate && !compare)

    fun rotateBy(deltaYaw: Float, deltaPitch: Float) = synchronized(lock) {
        targetYaw = null
        yaw += deltaYaw
        pitch = (pitch + deltaPitch).coerceIn(-89f, 89f)
    }

    fun zoomBy(factor: Float) = synchronized(lock) { zoom = (zoom / factor).coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** Moves the model with the fingers, by screen pixels. */
    fun panBy(dx: Float, dy: Float) = synchronized(lock) {
        panX = (panX + dx * unitsPerPixel).coerceIn(-radius * 2, radius * 2)
        panY = (panY - dy * unitsPerPixel).coerceIn(-radius * 2, radius * 2)
    }

    fun resetView() = synchronized(lock) {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        zoom = 1f
        panX = 0f
        panY = 0f
        targetYaw = null
    }

    /** Turns the model smoothly to [yaw], [pitch], the shorter way round. */
    fun animateTo(yaw: Float, pitch: Float) = synchronized(lock) {
        spinning = false
        var delta = (yaw - this.yaw) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        targetYaw = this.yaw + delta
        targetPitch = pitch
        panX = 0f
        panY = 0f
        zoom = 1f
    }

    /** Swings the model left and right once, ending where it started, to show it is 3D. */
    fun startSpin() = synchronized(lock) {
        spinStart = SystemClock.uptimeMillis()
        spinBaseYaw = yaw
        spinning = true
    }

    fun stopSpin() = synchronized(lock) {
        spinning = false
        targetYaw = null
    }

    fun requestCapture(onCaptured: (Bitmap) -> Unit) = synchronized(lock) { capture = onCaptured }

    /** The inverse of the last frame's model-view-projection matrix, for picking; null before a frame. */
    fun inverseMvp(): FloatArray? = synchronized(lock) {
        if (!hasFrame) return null
        FloatArray(16).takeIf { Matrix.invertM(it, 0, lastMvp, 0) }
    }

    /** Called on the GL thread. */
    fun setScene(mesh: Mesh3D, photo: Bitmap?) {
        this.mesh = mesh
        this.photo = photo
        if (program != 0) upload()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context invalidates every GL object, so rebuild them all.
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        uniforms.clear()
        for (name in listOf("uMvp", "uModelView", "uTexture", "uMode", "uCutout", "uEnvironment", "uLightDir", "uCamera", "uDepthRange")) {
            uniforms[name] = GLES30.glGetUniformLocation(program, name)
        }
        overlayUniforms.clear()
        for (name in listOf("uMvp", "uColor", "uPointSize", "uRound")) {
            overlayUniforms[name] = GLES30.glGetUniformLocation(overlayProgram, name)
        }
        buffers.fill(0)
        texture = 0
        edgeCount = -1
        uploadedMesh = null
        uploadedPhoto = null
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.027f, 0.043f, 0.071f, 1f)
        upload()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        this.width = max(width, 1)
        this.height = max(height, 1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val options = options
        val compare = compare
        val mesh = uploadedMesh
        if (mesh == null || indexCount == 0) {
            deliverCapture()
            return
        }
        val yaw: Float
        val pitch: Float
        val zoom: Float
        val panX: Float
        val panY: Float
        val radius: Float
        var finished = false
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.1f)
            lastFrame = now
            if (spinning) {
                val progress = (now - spinStart).toFloat() / SPIN_MS
                if (progress >= 1f) {
                    this.yaw = spinBaseYaw
                    spinning = false
                    finished = true
                } else {
                    this.yaw = spinBaseYaw + SWING_DEGREES * sin(2 * PI * progress).toFloat()
                }
            }
            targetYaw?.let { target ->
                val step = min(1f, dt * PRESET_SPEED)
                this.yaw += (target - this.yaw) * step
                this.pitch += (targetPitch - this.pitch) * step
                if (abs(target - this.yaw) < 0.1f && abs(targetPitch - this.pitch) < 0.1f) {
                    this.yaw = target
                    this.pitch = targetPitch
                    targetYaw = null
                    finished = true
                }
            }
            if (options.autoRotate && !compare && !touching && !spinning && targetYaw == null) {
                this.yaw += AUTO_ROTATE_DEGREES_PER_SECOND * dt
            }
            yaw = this.yaw
            pitch = this.pitch
            zoom = this.zoom
            panX = this.panX
            panY = this.panY
            radius = this.radius
        }
        if (finished) onAnimationsFinished()

        val aspect = width.toFloat() / height
        val pixelSize: Float
        if (compare) {
            // Straight at the front, orthographic, framed like FrontView so the photo lines up.
            val b = mesh.bounds
            val pixels = FrontView.pixelsPerUnit(b, width, height)
            val halfW = width / 2f / pixels
            val halfH = height / 2f / pixels
            val distance = radius * 3f
            Matrix.orthoM(projection, 0, -halfW, halfW, -halfH, halfH, distance - radius * 1.5f, distance + radius * 1.5f)
            Matrix.setLookAtM(view, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, -(b[0] + b[3]) / 2, -(b[1] + b[4]) / 2, -center[2])
            pixelSize = 1f / pixels
        } else {
            // Fit the bounding sphere both ways: portrait views are narrower than they are tall.
            val halfFovY = Math.toRadians(FOV_Y / 2.0)
            val halfFovX = atan(tan(halfFovY) * aspect)
            val distance = (radius / sin(minOf(halfFovX, halfFovY)) * 1.05 * zoom).toFloat()
            // The model lies within `radius` of the origin, which a pan moves by up to 2 radii; a tight
            // near plane keeps depth precision high.
            val near = max(distance * 0.05f, distance - radius * 3.2f)
            Matrix.perspectiveM(projection, 0, FOV_Y.toFloat(), aspect, near, distance + radius * 3.2f)
            Matrix.setLookAtM(view, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.translateM(view, 0, panX, panY, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
            Matrix.translateM(model, 0, -center[0], -center[1], -center[2])
            pixelSize = (2 * distance * tan(halfFovY) / height).toFloat()
        }
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        synchronized(lock) {
            System.arraycopy(mvp, 0, lastMvp, 0, 16)
            hasFrame = true
            unitsPerPixel = pixelSize
        }
        // The camera in model coordinates, for reflections.
        Matrix.invertM(inverseModelView, 0, modelView, 0)

        drawSurface(options, mesh)
        if (options.shading == Shading.WIREFRAME) drawEdges(mesh)
        drawMarkers()
        deliverCapture()
    }

    private fun drawSurface(options: ViewerOptions, mesh: Mesh3D) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uniforms.getValue("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uniforms.getValue("uModelView"), 1, false, modelView, 0)
        val textured = uploadedPhoto != null && texture != 0
        val mode = when (options.shading) {
            Shading.SURFACE -> if (textured) MODE_TEXTURE else if (hasColors) MODE_COLORS else MODE_CLAY
            Shading.CLAY, Shading.WIREFRAME -> MODE_CLAY
            Shading.NORMALS -> MODE_NORMALS
            Shading.DEPTH -> MODE_DEPTH
        }
        GLES30.glUniform1i(uniforms.getValue("uMode"), mode)
        // Open reliefs are cut out along the subject mask in every mode, so the outline matches the photo.
        GLES30.glUniform1i(uniforms.getValue("uCutout"), if (textured && uploadedMesh?.solid == false) 1 else 0)
        GLES30.glUniform1i(uniforms.getValue("uEnvironment"), if (options.environment) 1 else 0)
        val angle = Math.toRadians(options.lightAngle.toDouble())
        val elevation = Math.toRadians(LIGHT_ELEVATION)
        GLES30.glUniform3f(
            uniforms.getValue("uLightDir"),
            (sin(angle) * cos(elevation)).toFloat(),
            sin(elevation).toFloat(),
            (cos(angle) * cos(elevation)).toFloat(),
        )
        GLES30.glUniform3f(uniforms.getValue("uCamera"), inverseModelView[12], inverseModelView[13], inverseModelView[14])
        if (options.shading == Shading.DEPTH) {
            // The colours span the model's nearest to farthest point from this view.
            var depthNear = Float.POSITIVE_INFINITY
            var depthFar = Float.NEGATIVE_INFINITY
            val p = mesh.positions
            for (i in p.indices step 3) {
                val depth = -(modelView[2] * p[i] + modelView[6] * p[i + 1] + modelView[10] * p[i + 2] + modelView[14])
                if (depth < depthNear) depthNear = depth
                if (depth > depthFar) depthFar = depth
            }
            GLES30.glUniform2f(uniforms.getValue("uDepthRange"), depthNear, depthFar)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uniforms.getValue("uTexture"), 0)

        if (options.shading == Shading.WIREFRAME) {
            // Push the surface back a little so the edges drawn on it win the depth test.
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(1f, 1f)
        }
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
        for (attribute in 1..3) GLES30.glDisableVertexAttribArray(attribute)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    /** Every triangle edge once, as lines over the clay. */
    private fun drawEdges(mesh: Mesh3D) {
        if (edgeCount < 0) uploadEdges(mesh)
        if (edgeCount == 0) return
        GLES30.glUseProgram(overlayProgram)
        GLES30.glUniformMatrix4fv(overlayUniforms.getValue("uMvp"), 1, false, mvp, 0)
        // See-through lines, so a dense mesh still shows its shading instead of turning black.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUniform4f(overlayUniforms.getValue("uColor"), 0.04f, 0.08f, 0.16f, 0.45f)
        GLES30.glUniform1f(overlayUniforms.getValue("uPointSize"), 1f)
        GLES30.glUniform1i(overlayUniforms.getValue("uRound"), 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDrawElements(GLES30.GL_LINES, edgeCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Measuring points, always on top: a line between them and a dot on each. */
    private fun drawMarkers() {
        val points = markers
        if (points.isEmpty()) return
        val data = ByteBuffer.allocateDirect(points.size * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (p in points) data.put(p[0]).put(p[1]).put(p[2])
        (data as Buffer).position(0)
        if (buffers[3] == 0) GLES30.glGenBuffers(1, buffers, 3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[3])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, points.size * 12, data, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glUseProgram(overlayProgram)
        GLES30.glUniformMatrix4fv(overlayUniforms.getValue("uMvp"), 1, false, mvp, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUniform1i(overlayUniforms.getValue("uRound"), 0)
        GLES30.glUniform1f(overlayUniforms.getValue("uPointSize"), 1f)
        GLES30.glUniform4f(overlayUniforms.getValue("uColor"), 0.31f, 0.89f, 1f, 1f)
        if (points.size >= 2) GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        GLES30.glUniform1i(overlayUniforms.getValue("uRound"), 1)
        GLES30.glUniform1f(overlayUniforms.getValue("uPointSize"), MARKER_DP * density)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, points.size)
        GLES30.glUniform4f(overlayUniforms.getValue("uColor"), 1f, 1f, 1f, 1f)
        GLES30.glUniform1f(overlayUniforms.getValue("uPointSize"), MARKER_DP * density * 0.45f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, points.size)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun deliverCapture() {
        val callback = synchronized(lock) { capture.also { capture = null } } ?: return
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        val upsideDown = createBitmap(width, height)
        upsideDown.copyPixelsFromBuffer(pixels)
        // OpenGL's rows start at the bottom.
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val bitmap = Bitmap.createBitmap(upsideDown, 0, 0, width, height, flip, false)
        if (bitmap !== upsideDown) upsideDown.recycle()
        callback(bitmap)
    }

    private fun upload() {
        val mesh = mesh ?: return
        val photo = photo
        if (mesh !== uploadedMesh) {
            if (buffers[0] == 0) GLES30.glGenBuffers(3, buffers, 0)
            val vertices = ByteBuffer.allocateDirect(mesh.vertexCount * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
            // Orbit around the middle of the bounding box: scans stand on their base at y = 0.
            val b = mesh.bounds
            for (axis in 0 until 3) center[axis] = (b[axis] + b[axis + 3]) / 2
            val uvs = mesh.uvs
            val colors = mesh.colors
            var farthest = 0f
            for (i in 0 until mesh.vertexCount) {
                val x = mesh.positions[i * 3]
                val y = mesh.positions[i * 3 + 1]
                val z = mesh.positions[i * 3 + 2]
                vertices.put(x).put(y).put(z)
                vertices.put(mesh.normals[i * 3]).put(mesh.normals[i * 3 + 1]).put(mesh.normals[i * 3 + 2])
                if (uvs != null) vertices.put(uvs[i * 2]).put(uvs[i * 2 + 1]) else vertices.put(0f).put(0f)
                if (colors != null) vertices.put(colors[i * 3]).put(colors[i * 3 + 1]).put(colors[i * 3 + 2]) else vertices.put(1f).put(1f).put(1f)
                val dx = x - center[0]
                val dy = y - center[1]
                val dz = z - center[2]
                farthest = max(farthest, dx * dx + dy * dy + dz * dz)
            }
            hasColors = colors != null
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
            edgeCount = -1 // Built the first time the wireframe is shown.
            synchronized(lock) { radius = max(sqrt(farthest), 1e-3f) }
            uploadedMesh = mesh
        }
        if (photo == null) {
            uploadedPhoto = null
        } else if (photo !== uploadedPhoto && !photo.isRecycled) {
            if (texture == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                texture = ids[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            // GLUtils puts the bitmap's top row at t = 0, which matches the mesh's glTF-style UVs.
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, photo, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            uploadedPhoto = photo
        }
    }

    private fun uploadEdges(mesh: Mesh3D) {
        val edges = uniqueEdges(mesh.indices)
        val data = ByteBuffer.allocateDirect(max(edges.size, 1) * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        data.put(edges)
        (data as Buffer).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, edges.size * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        edgeCount = edges.size
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
            val status = IntArray(1)
            GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(it) }
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES30.glCreateShader(type).also {
        GLES30.glShaderSource(it, source)
        GLES30.glCompileShader(it)
        val status = IntArray(1)
        GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(it) }
    }

    private companion object {
        const val STRIDE = 11 * 4
        const val MODE_TEXTURE = 0
        const val MODE_COLORS = 1
        const val MODE_CLAY = 2
        const val MODE_NORMALS = 3
        const val MODE_DEPTH = 4
        const val FOV_Y = 40.0
        const val DEFAULT_YAW = -25f
        const val DEFAULT_PITCH = 12f
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 3f
        const val SPIN_MS = 3_500f
        const val SWING_DEGREES = 40f
        const val PRESET_SPEED = 9f
        const val AUTO_ROTATE_DEGREES_PER_SECOND = 24f
        const val LIGHT_ELEVATION = 31.0
        const val MARKER_DP = 14f
    }
}

/** Each edge of [indices]' triangles once, as pairs of vertex indices. */
internal fun uniqueEdges(indices: IntArray): IntArray {
    val keys = LongArray(indices.size)
    for (t in indices.indices step 3) {
        for (k in 0 until 3) {
            val a = indices[t + k]
            val b = indices[t + (k + 1) % 3]
            keys[t + k] = (min(a, b).toLong() shl 32) or max(a, b).toLong()
        }
    }
    keys.sort()
    var count = 0
    for (i in keys.indices) if (i == 0 || keys[i] != keys[i - 1]) count++
    val edges = IntArray(count * 2)
    var n = 0
    for (i in keys.indices) {
        if (i > 0 && keys[i] == keys[i - 1]) continue
        edges[n++] = (keys[i] ushr 32).toInt()
        edges[n++] = (keys[i] and 0xFFFFFFFFL).toInt()
    }
    return edges
}

internal const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec3 aColor;
uniform mat4 uMvp;
uniform mat4 uModelView;
out vec3 vNormal;
out vec3 vViewPosition;
out vec3 vModelNormal;
out vec3 vModelPosition;
out vec2 vUv;
out vec3 vColor;
void main() {
    vNormal = mat3(uModelView) * aNormal;
    vViewPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
    vModelNormal = aNormal;
    vModelPosition = aPosition;
    vUv = aUv;
    vColor = aColor;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

internal const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vViewPosition;
in vec3 vModelNormal;
in vec3 vModelPosition;
in vec2 vUv;
in vec3 vColor;
uniform sampler2D uTexture;
uniform int uMode;
uniform int uCutout;
uniform int uEnvironment;
uniform vec3 uLightDir;
uniform vec3 uCamera;
uniform vec2 uDepthRange;
out vec4 fragColor;

// A studio sky: warm ground, grey horizon, blue zenith. The model's +y is up.
vec3 sky(vec3 d) {
    float up = clamp(d.y, -1.0, 1.0);
    vec3 ground = vec3(0.24, 0.21, 0.19);
    vec3 horizon = vec3(0.66, 0.68, 0.72);
    vec3 zenith = vec3(0.45, 0.58, 0.80);
    return up < 0.0 ? mix(horizon, ground, smoothstep(0.0, 0.35, -up)) : mix(horizon, zenith, smoothstep(0.0, 0.8, up));
}

// Polynomial fit of Google's Turbo colour map.
vec3 turbo(float x) {
    const vec4 r4 = vec4(0.13572138, 4.61539260, -42.66032258, 132.13108234);
    const vec4 g4 = vec4(0.09140261, 2.19418839, 4.84296658, -14.18503333);
    const vec4 b4 = vec4(0.10667330, 12.64194608, -60.58204836, 110.36276771);
    const vec2 r2 = vec2(-152.94239396, 59.28637943);
    const vec2 g2 = vec2(4.27729857, 2.82956604);
    const vec2 b2 = vec2(-89.90310912, 27.34824973);
    x = clamp(x, 0.0, 1.0);
    vec4 v4 = vec4(1.0, x, x * x, x * x * x);
    vec2 v2 = v4.zw * v4.z;
    return vec3(dot(v4, r4) + dot(v2, r2), dot(v4, g4) + dot(v2, g2), dot(v4, b4) + dot(v2, b2));
}

void main() {
    vec4 texel = texture(uTexture, vUv);
    // Open reliefs are cut out along the subject mask kept in the texture's alpha.
    if (uCutout == 1 && texel.a < 0.5) discard;
    vec3 modelNormal = normalize(vModelNormal);
    if (!gl_FrontFacing) modelNormal = -modelNormal;
    if (uMode == 3) {
        fragColor = vec4(modelNormal * 0.5 + 0.5, 1.0);
        return;
    }
    if (uMode == 4) {
        float t = (-vViewPosition.z - uDepthRange.x) / max(uDepthRange.y - uDepthRange.x, 1e-6);
        fragColor = vec4(turbo(1.0 - t), 1.0);
        return;
    }
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    vec3 toEye = normalize(-vViewPosition);
    vec3 key = normalize(uLightDir);
    vec3 base = uMode == 0 ? texel.rgb : (uMode == 1 ? vColor : vec3(0.80, 0.78, 0.74));
    float keyLight = max(dot(n, key), 0.0);
    float specular = pow(max(dot(n, normalize(key + toEye)), 0.0), 40.0) * (uMode == 2 ? 0.25 : 0.08);
    vec3 color;
    if (uEnvironment == 1) {
        vec3 look = normalize(vModelPosition - uCamera);
        vec3 reflected = reflect(look, modelNormal);
        float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(modelNormal, -look), 0.0), 5.0);
        vec3 ambient = sky(modelNormal) * 0.72;
        color = base * (ambient + 0.5 * keyLight) + sky(reflected) * fresnel * (uMode == 2 ? 0.35 : 0.18) + vec3(specular);
    } else {
        vec3 fill = normalize(vec3(-0.7, -0.2, 0.5));
        float light = 0.36 + 0.6 * keyLight + 0.2 * max(dot(n, fill), 0.0);
        float rim = pow(1.0 - max(dot(n, toEye), 0.0), 3.0) * 0.12;
        color = base * light + vec3(specular + rim);
    }
    fragColor = vec4(color, 1.0);
}
"""

internal const val OVERLAY_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPosition;
uniform mat4 uMvp;
uniform float uPointSize;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
    gl_PointSize = uPointSize;
}
"""

internal const val OVERLAY_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec4 uColor;
uniform int uRound;
out vec4 fragColor;
void main() {
    if (uRound == 1 && length(gl_PointCoord - vec2(0.5)) > 0.5) discard;
    fragColor = uColor;
}
"""

/** RGB888 with a 24-bit (else 16-bit) depth buffer and 4× multisampling when the GPU offers it. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig =
        choose(egl, display, samples = 4, depth = 24) ?: choose(egl, display, samples = 4, depth = 16)
            ?: choose(egl, display, samples = 0, depth = 24) ?: choose(egl, display, samples = 0, depth = 16)
            ?: throw IllegalStateException("No OpenGL ES 3 configuration available")

    private fun choose(egl: EGL10, display: EGLDisplay, samples: Int, depth: Int): EGLConfig? {
        val attributes = mutableListOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, depth,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        )
        if (samples > 0) attributes += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
        attributes += EGL10.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        return if (egl.eglChooseConfig(display, attributes.toIntArray(), configs, 1, count) && count[0] > 0) configs[0] else null
    }

    private companion object {
        const val EGL_RENDERABLE_TYPE = 0x3040
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
