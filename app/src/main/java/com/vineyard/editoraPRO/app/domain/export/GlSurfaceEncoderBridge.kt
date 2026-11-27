package com.vineyard.editoraPRO.app.domain.export

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Dedicated single-thread EGL/GLES20 Surface bridge for MediaCodec.createInputSurface().
 * Uploads rendered Bitmaps directly to GPU texture memory and swaps onto the hardware encoder's
 * Surface with exact presentation timestamps—eliminating per-frame CPU Bitmap->YUV loops.
 */
class GlSurfaceEncoderBridge(
    private val width: Int,
    private val height: Int
) {
    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Editora-GlEncoder").apply { isDaemon = true }
    }
    private val glDispatcher = glExecutor.asCoroutineDispatcher()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId: Int = 0
    private var textureId: Int = 0
    private var textureAllocated: Boolean = false
    private var posLoc: Int = -1
    private var uvLoc: Int = -1
    private var texLoc: Int = -1
    private var prepared: Boolean = false

    private val posBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_POS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_POS); position(0) }

    private val uvBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_UV.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_UV); position(0) }

    suspend fun prepare(inputSurface: Surface) = withContext(glDispatcher) {
        if (prepared) return@withContext

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL14.eglGetDisplay failed." }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "EGL14.eglInitialize failed."
        }

        val config = chooseRecordableConfig(eglDisplay)
            ?: throw IllegalStateException("No compatible EGLConfig found for MediaCodec input Surface.")

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) {
            "EGL14.eglCreateContext failed (err=0x${Integer.toHexString(EGL14.eglGetError())})."
        }

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, inputSurface, surfAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "EGL14.eglCreateWindowSurface failed (err=0x${Integer.toHexString(EGL14.eglGetError())})."
        }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "EGL14.eglMakeCurrent failed."
        }

        programId = createProgram(VERT_SHADER, FRAG_SHADER)
        posLoc = GLES20.glGetAttribLocation(programId, "a_pos")
        uvLoc = GLES20.glGetAttribLocation(programId, "a_uv")
        texLoc = GLES20.glGetUniformLocation(programId, "u_tex")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        check(textureId != 0) { "GLES20.glGenTextures failed." }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        textureAllocated = false
        prepared = true
    }

    suspend fun encodeFrame(bitmap: Bitmap, ptsUs: Long) = withContext(glDispatcher) {
        check(prepared) { "GlSurfaceEncoderBridge.prepare() must be called before encodeFrame()." }
        if (bitmap.isRecycled) return@withContext

        if (EGL14.eglGetCurrentContext() != eglContext || EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglSurface) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        var tempScaled: Bitmap? = null
        var tempCopy: Bitmap? = null
        val uploadBmp: Bitmap = try {
            val sized = if (bitmap.width != width || bitmap.height != height) {
                Bitmap.createScaledBitmap(bitmap, width, height, true).also { tempScaled = it }
            } else {
                bitmap
            }
            if (sized.config != Bitmap.Config.ARGB_8888) {
                sized.copy(Bitmap.Config.ARGB_8888, false).also { tempCopy = it }
            } else {
                sized
            }
        } catch (t: Throwable) {
            tempCopy?.let { if (!it.isRecycled) it.recycle() }
            tempScaled?.let { if (!it.isRecycled) it.recycle() }
            throw t
        }

        try {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)

            if (!textureAllocated) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBmp, 0)
                textureAllocated = true
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, uploadBmp)
            }
            GLES20.glUniform1i(texLoc, 0)

            posBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, posBuffer)

            uvBuffer.position(0)
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(uvLoc)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } finally {
            tempCopy?.let { if (!it.isRecycled) it.recycle() }
            tempScaled?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    suspend fun release() {
        runCatching {
            withContext(glDispatcher) {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    runCatching {
                        EGL14.eglMakeCurrent(
                            eglDisplay,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT
                        )
                    }
                    if (textureId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                        textureId = 0
                    }
                    if (programId != 0) {
                        GLES20.glDeleteProgram(programId)
                        programId = 0
                    }
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        eglSurface = EGL14.EGL_NO_SURFACE
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                        eglContext = EGL14.EGL_NO_CONTEXT
                    }
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }
                textureAllocated = false
                prepared = false
            }
        }
        glExecutor.shutdown()
    }

    private fun chooseRecordableConfig(display: EGLDisplay): EGLConfig? {
        val withRecordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val fallback = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (EGL14.eglChooseConfig(display, withRecordable, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            return configs[0]
        }
        if (EGL14.eglChooseConfig(display, fallback, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            return configs[0]
        }
        return null
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        check(prog != 0) { "GLES20.glCreateProgram failed." }
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        check(status[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            "GLES20 link failed: $log"
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        check(sh != 0) { "GLES20.glCreateShader failed." }
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            "GLES20 compile failed: $log"
        }
        return sh
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val QUAD_POS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        private val QUAD_UV = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        private const val VERT_SHADER = """
            attribute vec2 a_pos;
            attribute vec2 a_uv;
            varying vec2 v_uv;
            void main() {
                v_uv = a_uv;
                gl_Position = vec4(a_pos, 0.0, 1.0);
            }
        """

        private const val FRAG_SHADER = """
            precision mediump float;
            varying vec2 v_uv;
            uniform sampler2D u_tex;
            void main() {
                gl_FragColor = texture2D(u_tex, v_uv);
            }
        """
    }
}