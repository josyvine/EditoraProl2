package com.vineyard.editoraPRO.app.domain.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Offscreen OpenGL ES 3.0 GPU Fragment Shader Runner.
 * Supports both GLSL ES 1.00 and `#version 300 es`, auto-provides `v_uv` (0..1) and `u_resolution`,
 * binds texture Bitmaps and typed uniforms, and returns an upright ARGB_8888 Bitmap matching WebGL2.
 */
class GlShaderRunner {

    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Editora-GLES30").apply { isDaemon = true }
    }
    private val glDispatcher = glExecutor.asCoroutineDispatcher()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var quadVbo: Int = 0
    private var fboId: Int = 0
    private var fboTexId: Int = 0
    private var fboW: Int = 0
    private var fboH: Int = 0
    private var fallbackTexId: Int = 0

    private var cachedReadBuf: ByteBuffer? = null
    private var cachedFlippedBuf: ByteBuffer? = null
    private val flipYMatrix = Matrix().apply { postScale(1f, -1f) }

    private data class ActiveUniform(
        val name: String,
        val baseName: String,
        val type: Int,
        val size: Int,
        val location: Int
    )

    private data class ProgramEntry(
        val programId: Int,
        val aPosLoc: Int,
        val uniforms: List<ActiveUniform>
    )

    private val programCache = object : LinkedHashMap<String, ProgramEntry>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProgramEntry>?): Boolean {
            if (size > 24 && eldest != null) {
                GLES30.glDeleteProgram(eldest.value.programId)
                return true
            }
            return false
        }
    }

    private val texturePool = mutableListOf<Int>()

    suspend fun runShader(
        key: String,
        outW: Int,
        outH: Int,
        fragmentGlsl: String,
        texturesMap: Map<String, Bitmap?>,
        uniformsMap: Map<String, Any?>
    ): Bitmap = withContext(glDispatcher) {
        val w = outW.coerceAtLeast(2)
        val h = outH.coerceAtLeast(2)
        ensureEglContext()
        ensureFbo(w, h)

        val compiled = getOrCompileProgram(fragmentGlsl)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(compiled.programId)

        val mergedUniforms = HashMap<String, Any?>(uniformsMap.size + 1).apply {
            put("u_resolution", listOf(w.toFloat(), h.toFloat()))
            putAll(uniformsMap)
        }

        val maxUnitsArr = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS, maxUnitsArr, 0)
        val maxUnits = maxUnitsArr[0].coerceAtLeast(8)
        var texUnit = 0

        for (u in compiled.uniforms) {
            if (u.location < 0) continue

            if (u.type == GLES30.GL_SAMPLER_2D) {
                val bmp = texturesMap[u.baseName]
                    ?: texturesMap[u.name]
                    ?: texturesMap[u.baseName.removePrefix("u_")]
                val unit = minOf(maxUnits - 1, texUnit++)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                    while (texturePool.size <= unit) {
                        val ids = IntArray(1)
                        GLES30.glGenTextures(1, ids, 0)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                        texturePool.add(ids[0])
                    }
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texturePool[unit])
                    val flippedBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, flipYMatrix, false)
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, flippedBmp, 0)
                    if (flippedBmp !== bmp) {
                        flippedBmp.recycle()
                    }
                } else {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fallbackTexId)
                }
                GLES30.glUniform1i(u.location, unit)
                continue
            }

            val value = mergedUniforms[u.baseName] ?: mergedUniforms[u.name] ?: continue
            bindUniformValue(u, value)
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (compiled.aPosLoc >= 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
            GLES30.glEnableVertexAttribArray(compiled.aPosLoc)
            GLES30.glVertexAttribPointer(compiled.aPosLoc, 2, GLES30.GL_FLOAT, false, 0, 0)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val bufferSize = w * h * 4
        val readBuf = cachedReadBuf?.takeIf { it.capacity() >= bufferSize }
            ?: ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder()).also { cachedReadBuf = it }
        readBuf.clear()

        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readBuf)

        val rowStride = w * 4
        val flippedBuf = cachedFlippedBuf?.takeIf { it.capacity() >= bufferSize }
            ?: ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder()).also { cachedFlippedBuf = it }
        flippedBuf.clear()

        for (row in 0 until h) {
            val srcOffset = (h - 1 - row) * rowStride
            readBuf.position(srcOffset)
            readBuf.limit(srcOffset + rowStride)
            flippedBuf.put(readBuf)
        }
        flippedBuf.rewind()

        val rawBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(flippedBuf)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        rawBitmap
    }

    private fun ensureEglContext() {
        if (eglContext !== EGL14.EGL_NO_CONTEXT) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            val es2Attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, es2Attribs, 0, configs, 0, 1, numConfigs, 0)
        }
        val config = configs[0] ?: throw IllegalStateException("No EGL config available for GLES 3.0")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val vboIds = IntArray(1)
        GLES30.glGenBuffers(1, vboIds, 0)
        quadVbo = vboIds[0]
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qBuf: FloatBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(quad)
            position(0)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qBuf, GLES30.GL_STATIC_DRAW)

        val fbTex = IntArray(1)
        GLES30.glGenTextures(1, fbTex, 0)
        fallbackTexId = fbTex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fallbackTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val zeroPx = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(0, 0, 0, 0)); position(0) }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, zeroPx)
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fboId != 0 && fboW == w && fboH == h) return
        if (fboId == 0) {
            val fbos = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            fboId = fbos[0]
            val texs = IntArray(1)
            GLES30.glGenTextures(1, texs, 0)
            fboTexId = texs[0]
        }
        fboW = w
        fboH = h
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexId, 0)
    }

    private fun getOrCompileProgram(rawFrag: String): ProgramEntry {
        val trimmed = rawFrag.trimStart()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Empty fragment shader passed to runtime.runShader().")

        val isEs300 = Regex("^#version\\s+300\\s+es\\b").containsMatchIn(trimmed)
        var fragSrc = trimmed
        val hasPrec = Regex("precision\\s+(lowp|mediump|highp)\\s+float\\s*;").containsMatchIn(fragSrc)
        if (!hasPrec) {
            fragSrc = if (isEs300) {
                fragSrc.replaceFirst(Regex("^(#version\\s+300\\s+es[^\\r\\n]*\\r?\\n)"), "$1precision highp float;\n")
            } else {
                "precision highp float;\n$fragSrc"
            }
        }

        val vertSrc = if (isEs300) {
            "#version 300 es\nin vec2 a_pos;\nout vec2 v_uv;\nvoid main(){\n  v_uv = a_pos * 0.5 + 0.5;\n  gl_Position = vec4(a_pos, 0.0, 1.0);\n}"
        } else {
            "attribute vec2 a_pos;\nvarying vec2 v_uv;\nvoid main(){\n  v_uv = a_pos * 0.5 + 0.5;\n  gl_Position = vec4(a_pos, 0.0, 1.0);\n}"
        }

        val cacheKey = "${if (isEs300) "300" else "100"}:$fragSrc"
        programCache[cacheKey]?.let { return it }

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = try {
            compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        } catch (e: Throwable) {
            GLES30.glDeleteShader(vs)
            throw e
        }

        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("GLSL link error: $info")
        }

        val aPosLoc = GLES30.glGetAttribLocation(prog, "a_pos")
        val countArr = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_ACTIVE_UNIFORMS, countArr, 0)
        val uniforms = mutableListOf<ActiveUniform>()
        val sizeArr = IntArray(1)
        val typeArr = IntArray(1)

        for (i in 0 until countArr[0]) {
            val name = GLES30.glGetActiveUniform(prog, i, sizeArr, 0, typeArr, 0) ?: continue
            val baseName = name.replace("[0]", "")
            val loc = GLES30.glGetUniformLocation(prog, name)
            uniforms.add(ActiveUniform(name, baseName, typeArr[0], sizeArr[0], loc))
        }

        return ProgramEntry(prog, aPosLoc, uniforms).also {
            programCache[cacheKey] = it
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            val kind = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw IllegalStateException("GLSL $kind compile error: $log")
        }
        return shader
    }

    private fun bindUniformValue(u: ActiveUniform, value: Any) {
        if (u.size > 1 && value is List<*>) {
            when (u.type) {
                GLES30.GL_FLOAT -> {
                    val arr = toFloatArray(value, value.size)
                    GLES30.glUniform1fv(u.location, arr.size, arr, 0)
                    return
                }
                GLES30.GL_FLOAT_VEC2 -> {
                    val arr = toFloatArray(value, value.size)
                    GLES30.glUniform2fv(u.location, arr.size / 2, arr, 0)
                    return
                }
                GLES30.GL_FLOAT_VEC3 -> {
                    val arr = toFloatArray(value, value.size)
                    GLES30.glUniform3fv(u.location, arr.size / 3, arr, 0)
                    return
                }
                GLES30.GL_FLOAT_VEC4 -> {
                    val arr = toFloatArray(value, value.size)
                    GLES30.glUniform4fv(u.location, arr.size / 4, arr, 0)
                    return
                }
                GLES30.GL_INT, GLES30.GL_BOOL -> {
                    val arr = toIntArray(value, value.size)
                    GLES30.glUniform1iv(u.location, arr.size, arr, 0)
                    return
                }
            }
        }

        when (u.type) {
            GLES30.GL_FLOAT -> GLES30.glUniform1f(u.location, toFloat(value))
            GLES30.GL_FLOAT_VEC2 -> {
                val a = toFloatArray(value, 2)
                GLES30.glUniform2f(u.location, a[0], a[1])
            }
            GLES30.GL_FLOAT_VEC3 -> {
                val a = toFloatArray(value, 3)
                GLES30.glUniform3f(u.location, a[0], a[1], a[2])
            }
            GLES30.GL_FLOAT_VEC4 -> {
                val a = toFloatArray(value, 4)
                GLES30.glUniform4f(u.location, a[0], a[1], a[2], a[3])
            }
            GLES30.GL_INT, GLES30.GL_BOOL -> GLES30.glUniform1i(u.location, toInt(value))
            GLES30.GL_INT_VEC2, GLES30.GL_BOOL_VEC2 -> {
                val a = toIntArray(value, 2)
                GLES30.glUniform2i(u.location, a[0], a[1])
            }
            GLES30.GL_INT_VEC3, GLES30.GL_BOOL_VEC3 -> {
                val a = toIntArray(value, 3)
                GLES30.glUniform3i(u.location, a[0], a[1], a[2])
            }
            GLES30.GL_INT_VEC4, GLES30.GL_BOOL_VEC4 -> {
                val a = toIntArray(value, 4)
                GLES30.glUniform4i(u.location, a[0], a[1], a[2], a[3])
            }
            GLES20.GL_FLOAT_MAT2 -> GLES30.glUniformMatrix2fv(u.location, 1, false, toFloatArray(value, 4), 0)
            GLES20.GL_FLOAT_MAT3 -> GLES30.glUniformMatrix3fv(u.location, 1, false, toFloatArray(value, 9), 0)
            GLES20.GL_FLOAT_MAT4 -> GLES30.glUniformMatrix4fv(u.location, 1, false, toFloatArray(value, 16), 0)
        }
    }

    private fun toFloat(v: Any?): Float = when (v) {
        is Number -> v.toFloat()
        is Boolean -> if (v) 1f else 0f
        is List<*> -> toFloat(v.firstOrNull())
        else -> 0f
    }

    private fun toInt(v: Any?): Int = when (v) {
        is Boolean -> if (v) 1 else 0
        is Number -> v.toInt()
        is List<*> -> toInt(v.firstOrNull())
        else -> 0
    }

    private fun toFloatArray(v: Any?, len: Int): FloatArray {
        val out = FloatArray(len)
        if (v is List<*>) {
            for (i in 0 until len) out[i] = toFloat(v.getOrNull(i))
        } else {
            val f = toFloat(v)
            for (i in 0 until len) out[i] = f
        }
        return out
    }

    private fun toIntArray(v: Any?, len: Int): IntArray {
        val out = IntArray(len)
        if (v is List<*>) {
            for (i in 0 until len) out[i] = toInt(v.getOrNull(i))
        } else {
            val iv = toInt(v)
            for (i in 0 until len) out[i] = iv
        }
        return out
    }

    fun release() {
        glExecutor.execute {
            programCache.values.forEach { GLES30.glDeleteProgram(it.programId) }
            programCache.clear()
            if (texturePool.isNotEmpty()) {
                GLES30.glDeleteTextures(texturePool.size, texturePool.toIntArray(), 0)
                texturePool.clear()
            }
            if (fboTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            if (fallbackTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(fallbackTexId), 0)
            if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            cachedReadBuf = null
            cachedFlippedBuf = null
        }
        glExecutor.shutdown()
    }
}