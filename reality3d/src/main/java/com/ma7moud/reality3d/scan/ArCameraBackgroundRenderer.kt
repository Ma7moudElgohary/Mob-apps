package com.ma7moud.reality3d.scan

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class ArCameraBackgroundRenderer {
    val textureId: Int
    private val program: Int
    private val vertices: FloatBuffer
    private val texCoords: FloatBuffer
    private var transformed = false

    init {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        vertices = floatBuffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
        texCoords = floatBuffer(floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f))
        program = createProgram(VERTEX, FRAGMENT)
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged() || !transformed) {
            val input = floatBuffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
            texCoords.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, input, Coordinates2d.TEXTURE_NORMALIZED, texCoords)
            texCoords.position(0)
            transformed = true
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        val pos = GLES30.glGetAttribLocation(program, "aPosition")
        val uv = GLES30.glGetAttribLocation(program, "aTexCoord")
        GLES30.glEnableVertexAttribArray(pos); GLES30.glEnableVertexAttribArray(uv)
        vertices.position(0); texCoords.position(0)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glVertexAttribPointer(uv, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos); GLES30.glDisableVertexAttribArray(uv)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun createProgram(v: String, f: String): Int {
        fun shader(type: Int, source: String): Int = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, source); GLES30.glCompileShader(it)
            val ok = IntArray(1); GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(it) }
        }
        val vs = shader(GLES30.GL_VERTEX_SHADER, v); val fs = shader(GLES30.GL_FRAGMENT_SHADER, f)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs); GLES30.glAttachShader(it, fs); GLES30.glLinkProgram(it)
            val ok = IntArray(1); GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(it) }
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        }
    }

    companion object {
        private const val VERTEX = "#version 300 es\nin vec2 aPosition; in vec2 aTexCoord; out vec2 vTexCoord; void main(){vTexCoord=aTexCoord;gl_Position=vec4(aPosition,0.0,1.0);}"
        private const val FRAGMENT = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float; uniform samplerExternalOES uTexture; in vec2 vTexCoord; out vec4 fragColor; void main(){fragColor=texture(uTexture,vTexCoord);}"
        private fun floatBuffer(v: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }
}
