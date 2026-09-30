package com.ma7moud.reality3d.ar

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** The live camera picture with the scan box and coverage dome drawn over it. Taps place the box. */
@SuppressLint("ViewConstructor")
internal class ArScanView(context: Context, private val engine: ArCoreScanEngine) : GLSurfaceView(context) {

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            engine.onTap(e.x, e.y)
            performClick()
            return true
        }
    })

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(ArRenderer(engine) { display?.rotation ?: Surface.ROTATION_0 })
        renderMode = RENDERMODE_CONTINUOUSLY
        contentDescription = "Camera view. Tap the object to place the scan box."
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() runs from onSingleTapUp.
    override fun onTouchEvent(event: MotionEvent): Boolean = taps.onTouchEvent(event)

    override fun performClick(): Boolean = super.performClick()
}

private class ArRenderer(private val engine: ArCoreScanEngine, private val rotation: () -> Int) : GLSurfaceView.Renderer {
    private val background = CameraBackground()
    private val overlay = OverlayRenderer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        overlay.create()
        engine.onSurfaceCreated(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        engine.onSurfaceChanged(rotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        engine.onDrawFrame(background, overlay)
    }
}

/** Draws ARCore's camera texture across the whole view. */
internal class CameraBackground {
    var textureId = 0
        private set
    private var program = 0
    private val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val cornerBuffer = floatBuffer(corners)
    private val uvs = FloatArray(8)
    private val uvBuffer = floatBuffer(uvs)

    fun create() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        program = Gl.program(VERTEX, FRAGMENT)
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, corners, Coordinates2d.TEXTURE_NORMALIZED, uvs)
            uvBuffer.put(uvs)
            (uvBuffer as Buffer).position(0)
        }
        // No camera image yet.
        if (frame.timestamp == 0L) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        val position = GLES30.glGetAttribLocation(program, "aPosition")
        val uv = GLES30.glGetAttribLocation(program, "aUv")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, cornerBuffer)
        GLES30.glEnableVertexAttribArray(uv)
        GLES30.glVertexAttribPointer(uv, 2, GLES30.GL_FLOAT, false, 0, uvBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(position)
        GLES30.glDisableVertexAttribArray(uv)
        GLES30.glDepthMask(true)
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aPosition;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    gl_Position = aPosition;
    vUv = aUv;
}
"""
        const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vUv);
}
"""
    }
}

/** Lines and round points in world space, drawn over the camera picture. */
internal class OverlayRenderer {
    private var program = 0
    private var buffer: FloatBuffer = floatBuffer(FloatArray(7 * 64))

    fun create() {
        program = Gl.program(VERTEX, FRAGMENT)
    }

    /** [vertices] holds x, y, z, r, g, b, a per vertex. */
    fun draw(mode: Int, vertices: FloatArray, count: Int, viewProjection: FloatArray, pointSize: Float = 1f) {
        if (count == 0) return
        if (buffer.capacity() < count * 7) buffer = floatBuffer(FloatArray(count * 7))
        (buffer as Buffer).clear()
        buffer.put(vertices, 0, count * 7)
        (buffer as Buffer).position(0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uViewProjection"), 1, false, viewProjection, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPointSize"), pointSize)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uRound"), if (mode == GLES30.GL_POINTS) 1 else 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        (buffer as Buffer).position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 7 * 4, buffer)
        (buffer as Buffer).position(3)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 7 * 4, buffer)
        GLES30.glLineWidth(4f)
        GLES30.glDrawArrays(mode, 0, count)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private companion object {
        const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;
uniform mat4 uViewProjection;
uniform float uPointSize;
out vec4 vColor;
void main() {
    gl_Position = uViewProjection * vec4(aPosition, 1.0);
    gl_PointSize = uPointSize;
    vColor = aColor;
}
"""
        const val FRAGMENT = """#version 300 es
precision mediump float;
in vec4 vColor;
uniform int uRound;
out vec4 fragColor;
void main() {
    if (uRound == 1) {
        vec2 c = gl_PointCoord - vec2(0.5);
        if (dot(c, c) > 0.25) discard;
    }
    fragColor = vColor;
}
"""
    }
}

internal object Gl {
    fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(program) }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
        return shader
    }
}

internal fun floatBuffer(values: FloatArray): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buffer.put(values)
    (buffer as Buffer).position(0)
    return buffer
}
