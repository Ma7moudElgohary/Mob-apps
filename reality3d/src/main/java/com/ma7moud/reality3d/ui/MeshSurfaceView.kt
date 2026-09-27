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
import com.ma7moud.reality3d.mesh.Mesh3D
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** OpenGL ES 3 viewer: drag to orbit, pinch to zoom, double-tap to reset. Lit so the shape reads clearly. */
class MeshSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer = MeshRenderer(onSpinFinished = { post { renderMode = RENDERMODE_WHEN_DIRTY } })
    private var mesh: Mesh3D? = null
    private var photo: Bitmap? = null

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.zoomBy(detector.scaleFactor)
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

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "3D model viewer. Drag to rotate, pinch to zoom, double-tap to reset."
    }

    /** Shows a new mesh. A new photo also resets the view and gives the model a short turn. */
    fun setScene(mesh: Mesh3D, photo: Bitmap) {
        if (mesh === this.mesh && photo === this.photo) return
        val newPhoto = photo !== this.photo
        this.mesh = mesh
        this.photo = photo
        queueEvent { renderer.setScene(mesh, photo) }
        if (newPhoto) {
            renderer.resetView()
            renderer.startSpin()
            renderMode = RENDERMODE_CONTINUOUSLY
        } else {
            requestRender()
        }
    }

    fun setClay(clay: Boolean) {
        if (renderer.clay == clay) return
        renderer.clay = clay
        requestRender()
    }

    fun resetView() {
        renderer.resetView()
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() runs from onSingleTapConfirmed.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Stop the scrolling screen from taking vertical drags meant for the model.
                parent?.requestDisallowInterceptTouchEvent(true)
                if (renderer.stopSpin()) renderMode = RENDERMODE_WHEN_DIRTY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
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

private class MeshRenderer(private val onSpinFinished: () -> Unit) : GLSurfaceView.Renderer {

    @Volatile
    var clay = false

    // View state, shared between the UI thread (gestures) and the GL thread.
    private val lock = Any()
    private var yaw = DEFAULT_YAW
    private var pitch = DEFAULT_PITCH
    private var zoom = 1f
    private var spinUntil = 0L
    private var lastFrame = 0L

    // GL thread only.
    private var mesh: Mesh3D? = null
    private var photo: Bitmap? = null
    private var uploadedMesh: Mesh3D? = null
    private var uploadedPhoto: Bitmap? = null
    private var program = 0
    private val buffers = IntArray(2)
    private var texture = 0
    private var indexCount = 0
    private var radius = 1f
    private var aspect = 1f
    private var uMvp = -1
    private var uModelView = -1
    private var uTexture = -1
    private var uClay = -1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun rotateBy(deltaYaw: Float, deltaPitch: Float) = synchronized(lock) {
        yaw += deltaYaw
        pitch = (pitch + deltaPitch).coerceIn(-89f, 89f)
    }

    fun zoomBy(factor: Float) = synchronized(lock) { zoom = (zoom / factor).coerceIn(MIN_ZOOM, MAX_ZOOM) }

    fun resetView() = synchronized(lock) {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        zoom = 1f
    }

    fun startSpin() = synchronized(lock) {
        spinUntil = SystemClock.uptimeMillis() + SPIN_MS
        lastFrame = 0L
    }

    /** Stops the automatic turn; true when one was running. */
    fun stopSpin(): Boolean = synchronized(lock) {
        val spinning = spinUntil > 0
        spinUntil = 0
        spinning
    }

    /** Called on the GL thread. */
    fun setScene(mesh: Mesh3D, photo: Bitmap) {
        this.mesh = mesh
        this.photo = photo
        if (program != 0) upload()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context invalidates every GL object, so rebuild them all.
        program = createProgram()
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uModelView = GLES30.glGetUniformLocation(program, "uModelView")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        uClay = GLES30.glGetUniformLocation(program, "uClay")
        buffers.fill(0)
        texture = 0
        uploadedMesh = null
        uploadedPhoto = null
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.027f, 0.043f, 0.071f, 1f)
        upload()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(height, 1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (uploadedMesh == null || indexCount == 0) return
        val yaw: Float
        val pitch: Float
        val zoom: Float
        var spinFinished = false
        synchronized(lock) {
            if (spinUntil > 0) {
                val now = SystemClock.uptimeMillis()
                if (lastFrame > 0) this.yaw += (now - lastFrame) * SPIN_DEGREES_PER_MS
                lastFrame = now
                if (now >= spinUntil) {
                    spinUntil = 0
                    spinFinished = true
                }
            }
            yaw = this.yaw
            pitch = this.pitch
            zoom = this.zoom
        }
        if (spinFinished) onSpinFinished()

        // Fit the bounding sphere both ways: portrait views are narrower than they are tall.
        val halfFovY = Math.toRadians(FOV_Y / 2.0)
        val halfFovX = atan(tan(halfFovY) * aspect)
        val distance = (radius / sin(minOf(halfFovX, halfFovY)) * 1.05 * zoom).toFloat()
        Matrix.perspectiveM(projection, 0, FOV_Y.toFloat(), aspect, max(0.01f, distance - radius * 2f), distance + radius * 2f)
        Matrix.setLookAtM(view, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES30.glUniform1f(uClay, if (clay || texture == 0) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uTexture, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, STRIDE, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun upload() {
        val mesh = mesh ?: return
        val photo = photo ?: return
        if (mesh !== uploadedMesh) {
            if (buffers[0] == 0) GLES30.glGenBuffers(2, buffers, 0)
            val vertices = ByteBuffer.allocateDirect(mesh.vertexCount * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var farthest = 0f
            for (i in 0 until mesh.vertexCount) {
                val x = mesh.positions[i * 3]
                val y = mesh.positions[i * 3 + 1]
                val z = mesh.positions[i * 3 + 2]
                vertices.put(x).put(y).put(z)
                vertices.put(mesh.normals[i * 3]).put(mesh.normals[i * 3 + 1]).put(mesh.normals[i * 3 + 2])
                vertices.put(mesh.uvs[i * 2]).put(mesh.uvs[i * 2 + 1])
                farthest = max(farthest, x * x + y * y + z * z)
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
            radius = max(sqrt(farthest), 1e-3f)
            uploadedMesh = mesh
        }
        if (photo !== uploadedPhoto && !photo.isRecycled) {
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

    private fun createProgram(): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
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
        const val STRIDE = 8 * 4
        const val FOV_Y = 40.0
        const val DEFAULT_YAW = -25f
        const val DEFAULT_PITCH = 12f
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 3f
        const val SPIN_MS = 3_000L
        const val SPIN_DEGREES_PER_MS = 50f / 1_000f

        const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModelView;
out vec3 vNormal;
out vec3 vViewPosition;
out vec2 vUv;
void main() {
    vNormal = mat3(uModelView) * aNormal;
    vViewPosition = (uModelView * vec4(aPosition, 1.0)).xyz;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

        const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vViewPosition;
in vec2 vUv;
uniform sampler2D uTexture;
uniform float uClay;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    vec3 toEye = normalize(-vViewPosition);
    vec3 key = normalize(vec3(0.45, 0.6, 0.9));
    vec3 fill = normalize(vec3(-0.7, -0.2, 0.5));
    float light = 0.36 + 0.6 * max(dot(n, key), 0.0) + 0.2 * max(dot(n, fill), 0.0);
    vec3 base = mix(texture(uTexture, vUv).rgb, vec3(0.80, 0.78, 0.74), uClay);
    float specular = pow(max(dot(n, normalize(key + toEye)), 0.0), 40.0) * mix(0.08, 0.25, uClay);
    float rim = pow(1.0 - max(dot(n, toEye), 0.0), 3.0) * 0.12;
    fragColor = vec4(base * light + vec3(specular + rim), 1.0);
}
"""
    }
}

/** RGB888 with a depth buffer and 4× multisampling when the GPU offers it. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig =
        choose(egl, display, samples = 4) ?: choose(egl, display, samples = 0)
            ?: throw IllegalStateException("No OpenGL ES 3 configuration available")

    private fun choose(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val attributes = mutableListOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16,
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
