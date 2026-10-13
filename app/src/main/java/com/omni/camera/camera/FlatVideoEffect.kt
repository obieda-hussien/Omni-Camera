package com.omni.camera.camera

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** An SDR flat look applied to both preview and encoded frames. Never advertised as sensor Log. */
class FlatVideoEffect(private val onFailure: (Throwable) -> Unit) : SurfaceProcessor, AutoCloseable {
    private val thread = HandlerThread("OmniVideoGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    val effect: CameraEffect = object : CameraEffect(PREVIEW or VIDEO_CAPTURE, executor, this@FlatVideoEffect, { onFailure(it) }) {}
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var closing = false
    private var inputs = 0
    private val outputs = mutableMapOf<SurfaceOutput, EGLSurface>()
    private val original = FloatArray(16)
    private val transformed = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val coords = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    private fun initialize() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, 0x3142, 1, EGL14.EGL_NONE),
            0, configs, 0, 1, IntArray(1), 0))
        config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPosition; attribute vec4 aTex; uniform mat4 uMatrix; varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = (uMatrix * aTex).xy; }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES uTexture; varying vec2 vTex;
            void main() {
                vec3 c = texture2D(uTexture, vTex).rgb;
                float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(vec3(y), c, 0.92);
                gl_FragColor = vec4(clamp((c - 0.5) * 0.72 + 0.5, 0.0, 1.0), 1.0);
            }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (closing) { request.willNotProvideSurface(); return }
        try {
            initialize(); makeCurrent(pbuffer)
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
            val id = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val texture = SurfaceTexture(id).apply { setDefaultBufferSize(request.resolution.width, request.resolution.height) }
            val surface = Surface(texture)
            inputs++
            texture.setOnFrameAvailableListener({
                if (!closing) runCatching { draw(texture, id) }.onFailure { onFailure(it); close() }
            }, handler)
            request.provideSurface(surface, executor) {
                makeCurrent(pbuffer)
                texture.setOnFrameAvailableListener(null)
                surface.release(); texture.release(); GLES20.glDeleteTextures(1, ids, 0)
                inputs--; finishIfClosed()
            }
        } catch (e: Exception) { request.willNotProvideSurface(); onFailure(e); close() }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (closing) { output.close(); return }
        try {
            initialize()
            val surface = output.getSurface(executor) {
                outputs.remove(output)?.let { egl -> EGL14.eglDestroySurface(display, egl) }
                output.close()
            }
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(egl != EGL14.EGL_NO_SURFACE)
            outputs[output] = egl
        } catch (e: Exception) { output.close(); onFailure(e); close() }
    }

    private fun draw(texture: SurfaceTexture, id: Int) {
        makeCurrent(pbuffer); texture.updateTexImage(); texture.getTransformMatrix(original)
        for ((output, egl) in outputs) {
            makeCurrent(egl); output.updateTransformMatrix(transformed, original)
            GLES20.glViewport(0, 0, output.size.width, output.size.height)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, transformed, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, egl, texture.timestamp)
            check(EGL14.eglSwapBuffers(display, egl))
        }
    }
    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
    }
    override fun close() {
        handler.post {
            if (!closing) {
                closing = true
                outputs.forEach { (output, egl) -> EGL14.eglDestroySurface(display, egl); output.close() }
                outputs.clear(); finishIfClosed()
            }
        }
    }
    private fun finishIfClosed() {
        if (closing && inputs == 0) {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer); EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display); EGL14.eglReleaseThread()
            }
            thread.quitSafely()
        }
    }
}
