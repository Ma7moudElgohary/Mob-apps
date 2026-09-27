package com.ma7moud.reality3d.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import com.ma7moud.reality3d.mesh.DepthMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MeshSurfaceView(context: Context, mesh: DepthMesh, bitmap: Bitmap) : GLSurfaceView(context) {
    private val meshRenderer = MeshRenderer(mesh, bitmap)
    private var lastX = 0f
    private var lastY = 0f

    init {
        setEGLContextClientVersion(3)
        setRenderer(meshRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setPreserveEGLContextOnPause(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> {
                meshRenderer.yaw += (event.x - lastX) * 0.25f
                meshRenderer.pitch = (meshRenderer.pitch + (event.y - lastY) * 0.2f).coerceIn(-70f, 70f)
                lastX = event.x; lastY = event.y; return true
            }
        }
        return super.onTouchEvent(event)
    }
}

private class MeshRenderer(mesh: DepthMesh, private val bitmap: Bitmap) : GLSurfaceView.Renderer {
    private val vertices = floatBuffer(mesh.positions)
    private val texCoords = floatBuffer(mesh.texCoords)
    private val indices = intBuffer(mesh.indices)
    private val indexCount = mesh.indices.size
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private var program = 0
    private var texture = 0
    var yaw = -18f
    var pitch = 10f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.02f, 0.03f, 0.055f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        texture = createTexture(bitmap)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 46f, width.toFloat() / height.coerceAtLeast(1), 0.1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 2.7f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
        val uvLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glEnableVertexAttribArray(uvLoc)
        vertices.position(0); texCoords.position(0)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        indices.position(0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, indices)
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(uvLoc)
    }

    private fun createTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    private fun createProgram(v: String, f: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, v)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, f)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs); GLES30.glAttachShader(it, fs); GLES30.glLinkProgram(it)
            val status = IntArray(1); GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(it) }
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES30.glCreateShader(type).also {
        GLES30.glShaderSource(it, source); GLES30.glCompileShader(it)
        val status = IntArray(1); GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(it) }
    }

    companion object {
        private const val VERTEX_SHADER = "#version 300 es\nuniform mat4 uMvp; in vec3 aPosition; in vec2 aTexCoord; out vec2 vTexCoord; void main(){ vTexCoord=aTexCoord; gl_Position=uMvp*vec4(aPosition,1.0); }"
        private const val FRAGMENT_SHADER = "#version 300 es\nprecision mediump float; uniform sampler2D uTexture; in vec2 vTexCoord; out vec4 fragColor; void main(){ fragColor=vec4(texture(uTexture,vTexCoord).rgb,1.0); }"
        private fun floatBuffer(v: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        private fun intBuffer(v: IntArray): IntBuffer = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(v); position(0) }
    }
}
