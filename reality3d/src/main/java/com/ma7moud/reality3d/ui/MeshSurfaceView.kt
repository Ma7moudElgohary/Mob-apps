package com.ma7moud.reality3d.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.ma7moud.reality3d.mesh.DepthMesh
import com.ma7moud.reality3d.mesh.MeshMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

enum class ViewerMode { TEXTURE, WIREFRAME, DEPTH, NORMALS, SHADED }
enum class ViewerPreset { FRONT, BACK, LEFT, RIGHT, TOP, ISO }

class MeshSurfaceView(
    context: Context,
    mesh: DepthMesh,
    bitmap: Bitmap?,
    private val onMeasurement: ((Float?, Int, Int) -> Unit)? = null,
) : GLSurfaceView(context) {
    private var screenshotCallback: ((Bitmap) -> Unit)? = null
    private val renderer = MeshRenderer(mesh, bitmap) { image ->
        post {
            screenshotCallback?.invoke(image)
            screenshotCallback = null
        }
    }
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.distance = (renderer.distance / detector.scaleFactor).coerceIn(0.65f, 8f)
                return true
            }
        },
    )
    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    var measurementEnabled: Boolean = false
    private val picked = ArrayList<Int>(2)

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setPreserveEGLContextOnPause(true)
    }

    fun setMode(mode: ViewerMode) {
        renderer.mode = mode
    }

    fun setAutoRotate(enabled: Boolean) {
        renderer.autoRotate = enabled
    }

    fun setLight(x: Float, y: Float, z: Float) {
        renderer.light = floatArrayOf(x, y, z)
    }

    fun resetCamera() {
        renderer.reset()
    }

    fun preset(preset: ViewerPreset) {
        renderer.preset(preset)
    }

    fun capture(callback: (Bitmap) -> Unit) {
        screenshotCallback = callback
        renderer.requestScreenshot = true
    }

    fun clearMeasurement() {
        picked.clear()
        onMeasurement?.invoke(null, -1, -1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount >= 2) {
            val centroidX = (event.getX(0) + event.getX(1)) * 0.5f
            val centroidY = (event.getY(0) + event.getY(1)) * 0.5f
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastCentroidX = centroidX
                lastCentroidY = centroidY
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress) {
                renderer.panX += (centroidX - lastCentroidX) / width.coerceAtLeast(1) * renderer.distance
                renderer.panY -= (centroidY - lastCentroidY) / height.coerceAtLeast(1) * renderer.distance
                lastCentroidX = centroidX
                lastCentroidY = centroidY
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!measurementEnabled) {
                    renderer.yaw += (event.x - lastX) * 0.25f
                    renderer.pitch = (renderer.pitch + (event.y - lastY) * 0.2f).coerceIn(-88f, 88f)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                val moved = sqrt(dx * dx + dy * dy)
                if (measurementEnabled && moved < 18f) {
                    renderer.pick(event.x, event.y, width, height)?.let { index ->
                        if (picked.size == 2) picked.clear()
                        picked += index
                        if (picked.size == 2) {
                            onMeasurement?.invoke(
                                MeshMath.distanceMeters(renderer.mesh, picked[0], picked[1]),
                                picked[0],
                                picked[1],
                            )
                        } else {
                            onMeasurement?.invoke(null, picked[0], -1)
                        }
                    }
                }
                return true
            }
        }
        return true
    }
}

private class MeshRenderer(
    var mesh: DepthMesh,
    private val bitmap: Bitmap?,
    private val screenshotReady: (Bitmap) -> Unit,
) : GLSurfaceView.Renderer {
    private val vertices = floatBuffer(mesh.positions)
    private val normals = floatBuffer(normalizedNormals(mesh))
    private val texCoords = floatBuffer(normalizedUvs(mesh))
    private val colors = floatBuffer(normalizedColors(mesh))
    private val indices = intBuffer(mesh.indices)
    private val wireIndicesArray = lineIndices(mesh.indices)
    private val wireIndices = intBuffer(wireIndicesArray)

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val lastMvp = FloatArray(16)

    private var program = 0
    private var texture = 0
    private var width = 1
    private var height = 1

    var yaw = -18f
    var pitch = 10f
    var distance = 2.7f
    var panX = 0f
    var panY = 0f

    @Volatile var mode = ViewerMode.TEXTURE
    @Volatile var autoRotate = false
    @Volatile var light = floatArrayOf(-0.4f, 0.7f, 1f)
    @Volatile var requestScreenshot = false

    fun reset() {
        yaw = -18f
        pitch = 10f
        distance = 2.7f
        panX = 0f
        panY = 0f
    }

    fun preset(preset: ViewerPreset) {
        when (preset) {
            ViewerPreset.FRONT -> { yaw = 0f; pitch = 0f }
            ViewerPreset.BACK -> { yaw = 180f; pitch = 0f }
            ViewerPreset.LEFT -> { yaw = -90f; pitch = 0f }
            ViewerPreset.RIGHT -> { yaw = 90f; pitch = 0f }
            ViewerPreset.TOP -> { yaw = 0f; pitch = -88f }
            ViewerPreset.ISO -> { yaw = -35f; pitch = 20f }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.02f, 0.03f, 0.055f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (bitmap != null) texture = createTexture(bitmap)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(
            projection,
            0,
            46f,
            this.width.toFloat() / this.height,
            0.05f,
            30f,
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        if (autoRotate) yaw += 0.22f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.setLookAtM(
            view,
            0,
            -panX,
            -panY,
            distance,
            -panX,
            -panY,
            0f,
            0f,
            1f,
            0f,
        )
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        System.arraycopy(mvp, 0, lastMvp, 0, 16)

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uLight"),
            light[0],
            light[1],
            light[2],
        )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMode"), mode.ordinal)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(program, "uHasTexture"),
            if (bitmap != null) 1 else 0,
        )

        bind("aPosition", 3, vertices)
        bind("aNormal", 3, normals)
        bind("aTexCoord", 2, texCoords)
        bind("aColor", 4, colors)

        if (bitmap != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        }

        if (mode == ViewerMode.WIREFRAME) {
            wireIndices.position(0)
            GLES30.glDrawElements(
                GLES30.GL_LINES,
                wireIndicesArray.size,
                GLES30.GL_UNSIGNED_INT,
                wireIndices,
            )
        } else {
            indices.position(0)
            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES,
                mesh.indices.size,
                GLES30.GL_UNSIGNED_INT,
                indices,
            )
        }

        if (requestScreenshot) {
            requestScreenshot = false
            screenshotReady(readPixels())
        }
    }

    private fun bind(name: String, size: Int, buffer: FloatBuffer) {
        val location = GLES30.glGetAttribLocation(program, name)
        if (location < 0) return
        GLES30.glEnableVertexAttribArray(location)
        buffer.position(0)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 0, buffer)
    }

    fun pick(x: Float, y: Float, width: Int, height: Int): Int? {
        var best = -1
        var bestDistanceSquared = 42f * 42f
        val input = FloatArray(4)
        val output = FloatArray(4)
        for (vertex in 0 until mesh.vertexCount) {
            val p = vertex * 3
            input[0] = mesh.positions[p]
            input[1] = mesh.positions[p + 1]
            input[2] = mesh.positions[p + 2]
            input[3] = 1f
            Matrix.multiplyMV(output, 0, lastMvp, 0, input, 0)
            if (output[3] <= 0f) continue
            val screenX = (output[0] / output[3] * 0.5f + 0.5f) * width
            val screenY = (1f - (output[1] / output[3] * 0.5f + 0.5f)) * height
            val dx = screenX - x
            val dy = screenY - y
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                best = vertex
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun readPixels(): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer,
        )
        val pixels = IntArray(width * height)
        buffer.rewind()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                pixels[(height - 1 - y) * width + x] =
                    (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun createTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR_MIPMAP_LINEAR,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return ids[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
        }

        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also { createdProgram ->
            GLES30.glAttachShader(createdProgram, vertexShader)
            GLES30.glAttachShader(createdProgram, fragmentShader)
            GLES30.glLinkProgram(createdProgram)
            val status = IntArray(1)
            GLES30.glGetProgramiv(createdProgram, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(createdProgram) }
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uModel;
in vec3 aPosition;
in vec3 aNormal;
in vec2 aTexCoord;
in vec4 aColor;
out vec3 vNormal;
out vec2 vTexCoord;
out vec4 vColor;
out float vDepth;
void main(){
    vNormal=normalize(mat3(uModel)*aNormal);
    vTexCoord=aTexCoord;
    vColor=aColor;
    vDepth=aPosition.z;
    gl_Position=uMvp*vec4(aPosition,1.0);
}
"""
        private const val FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform int uHasTexture;
uniform int uMode;
uniform vec3 uLight;
in vec3 vNormal;
in vec2 vTexCoord;
in vec4 vColor;
in float vDepth;
out vec4 fragColor;
void main(){
    vec3 n=normalize(vNormal);
    vec4 base=uHasTexture==1?texture(uTexture,vTexCoord):vColor;
    if(uMode==2){
        float d=clamp(0.5+vDepth*1.5,0.0,1.0);
        fragColor=vec4(d,1.0-d,0.7,1.0);
        return;
    }
    if(uMode==3){
        fragColor=vec4(n*0.5+0.5,1.0);
        return;
    }
    if(uMode==1){
        fragColor=vec4(0.25,0.95,1.0,1.0);
        return;
    }
    float diffuse=max(dot(n,normalize(uLight)),0.0);
    float ambient=0.32;
    vec3 shaded=base.rgb*(ambient+0.68*diffuse);
    if(uMode==4) shaded=vec3(0.72)*(ambient+0.68*diffuse);
    fragColor=vec4(shaded,base.a);
}
"""

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        private fun intBuffer(values: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()
                .apply { put(values); position(0) }

        private fun normalizedNormals(mesh: DepthMesh): FloatArray =
            if (mesh.normals.size == mesh.positions.size) mesh.normals
            else MeshMath.recalculateNormals(mesh).normals

        private fun normalizedUvs(mesh: DepthMesh): FloatArray =
            if (mesh.texCoords.size == mesh.vertexCount * 2) mesh.texCoords
            else FloatArray(mesh.vertexCount * 2)

        private fun normalizedColors(mesh: DepthMesh): FloatArray =
            mesh.colors?.takeIf { it.size == mesh.vertexCount * 4 }
                ?: FloatArray(mesh.vertexCount * 4) { index -> if (index % 4 == 3) 1f else 0.78f }

        private fun lineIndices(triangleIndices: IntArray): IntArray {
            val output = IntArray(triangleIndices.size * 2)
            var out = 0
            var i = 0
            while (i + 2 < triangleIndices.size) {
                val a = triangleIndices[i]
                val b = triangleIndices[i + 1]
                val c = triangleIndices[i + 2]
                output[out++] = a
                output[out++] = b
                output[out++] = b
                output[out++] = c
                output[out++] = c
                output[out++] = a
                i += 3
            }
            return output
        }
    }
}
