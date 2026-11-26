package com.vineyard.editoraPRO.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import com.vineyard.Editpro.app.BuildConfig
import java.io.File

/**
 * Runtime integrity, anti-debugger, and obfuscated endpoint protection.
 * Protects against APK-to-ZIP inspection and live debugger attachment in release builds.
 */
object SecurityGuard {

    private val xorMask = byteArrayOf(0x5A, 0x33, 0x91.toByte(), 0xC7.toByte())

    // XOR-encoded endpoint fragments to prevent plain-text string extraction from classes.dex
    // "lunar-tag"
    private val encVertexProject = byteArrayOf(
        0x36, 0x46, 0xFF.toByte(), 0xA6.toByte(), 0x28, 0x1E, 0xE5.toByte(), 0xA6.toByte(), 0x3D
    )
    // "global"
    private val encVertexLocation = byteArrayOf(
        0x3D, 0x5F, 0xFE.toByte(), 0xA5.toByte(), 0x3B, 0x5F
    )
    // "https://aiplatform.googleapis.com/v1"
    private val encVertexHost = byteArrayOf(
        0x32, 0x47, 0xE5.toByte(), 0xB7.toByte(), 0x29, 0x09, 0xBE.toByte(), 0xE8.toByte(),
        0x3B, 0x5A, 0xE1.toByte(), 0xAB.toByte(), 0x3B, 0x47, 0xF7.toByte(), 0xA8.toByte(),
        0x28, 0x5E, 0xBF.toByte(), 0xA0.toByte(), 0x35, 0x5C, 0xF6.toByte(), 0xAB.toByte(),
        0x3F, 0x52, 0xE1.toByte(), 0xAE.toByte(), 0x29, 0x1D, 0xF2.toByte(), 0xA8.toByte(),
        0x37, 0x1C, 0xE7.toByte(), 0xF6.toByte()
    )
    // "https://generativelanguage.googleapis.com"
    private val encAiStudioHost = byteArrayOf(
        0x32, 0x47, 0xE5.toByte(), 0xB7.toByte(), 0x29, 0x09, 0xBE.toByte(), 0xE8.toByte(),
        0x3D, 0x56, 0xFF.toByte(), 0xA2.toByte(), 0x28, 0x52, 0xE5.toByte(), 0xAE.toByte(),
        0x2C, 0x56, 0xFD.toByte(), 0xA6.toByte(), 0x34, 0x54, 0xE4.toByte(), 0xA6.toByte(),
        0x3D, 0x56, 0xBF.toByte(), 0xA0.toByte(), 0x35, 0x5C, 0xF6.toByte(), 0xAB.toByte(),
        0x3F, 0x52, 0xE1.toByte(), 0xAE.toByte(), 0x29, 0x1D, 0xF2.toByte(), 0xA8.toByte(), 0x37
    )

    private fun decodeXor(encoded: ByteArray): String {
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            out[i] = (encoded[i].toInt() xor xorMask[i % xorMask.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    fun getVertexProject(): String = decodeXor(encVertexProject)
    fun getVertexLocation(): String = decodeXor(encVertexLocation)
    fun getVertexBaseUrl(): String =
        "${decodeXor(encVertexHost)}/projects/${getVertexProject()}/locations/${getVertexLocation()}/publishers/google/models"

    fun getAiStudioBaseUrl(): String = decodeXor(encAiStudioHost)

    fun isTamperedOrDebuggedInRelease(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        val flagDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (flagDebuggable) return true
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        return hasTracerPid()
    }

    private fun hasTracerPid(): Boolean {
        return try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.exists()) return false
            statusFile.useLines { lines ->
                val tracerLine = lines.firstOrNull { it.startsWith("TracerPid:") } ?: return false
                val pid = tracerLine.substringAfter("TracerPid:").trim().toIntOrNull() ?: 0
                pid != 0
            }
        } catch (_: Throwable) {
            false
        }
    }
}
