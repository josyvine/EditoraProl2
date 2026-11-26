package com.vineyard.editoraPRO.app.data.local

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.vineyard.editoraPRO.app.data.model.PersistedCrashReport
import com.vineyard.editoraPRO.app.data.model.SessionDiagnosticSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global Crash & Pre-Crash Diagnostic Persistence Manager.
 * - Captures uncaught JVM exceptions across all threads and flushes synchronously (fsync) before exit.
 * - Detects native crashes (SIGSEGV/SIGABRT), ANRs, and low-memory process kills on startup via
 *   ApplicationExitInfo (API 30+) or logcat buffer dump + interrupted session checkpoints.
 * - Keeps persisted crash reports intact across restarts until explicitly cleared by the user.
 */
class CrashReportManager(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val diagDir = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
    private val crashArchiveDir = File(diagDir, "crashes").apply { mkdirs() }
    private val activeCrashFile = File(diagDir, "active_crash_report.json")
    private val snapshotFile = File(diagDir, "session_snapshot.json")
    private val lastExitTsFile = File(diagDir, "last_handled_exit_ts.txt")

    private val ioLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Editora-Diagnostics").apply { isDaemon = true }
    }
    private val installed = AtomicBoolean(false)

    @Volatile
    private var latestSnapshot: SessionDiagnosticSnapshot = loadSessionSnapshotInternal()

    @Volatile
    private var lastDiskFlushMs: Long = 0L

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                persistUncaughtCrashSynchronously(thread, throwable)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun updateSessionSnapshot(
        snapshot: SessionDiagnosticSnapshot,
        forceFlush: Boolean = false
    ) {
        val stamped = snapshot.copy(
            executionLogs = snapshot.executionLogs.takeLast(400),
            liveStreamText = snapshot.liveStreamText.takeLast(12_000),
            savedAtMs = System.currentTimeMillis()
        )
        latestSnapshot = stamped
        val now = stamped.savedAtMs
        if (!forceFlush && (now - lastDiskFlushMs) < 1200L) {
            return
        }
        lastDiskFlushMs = now
        writeExecutor.execute {
            synchronized(ioLock) {
                writeJsonAtomicSync(snapshotFile, json.encodeToString(stamped))
            }
        }
    }

    fun getLatestSnapshot(): SessionDiagnosticSnapshot = synchronized(ioLock) {
        val disk = loadSessionSnapshotInternal()
        if (disk.savedAtMs >= latestSnapshot.savedAtMs) disk else latestSnapshot
    }

    fun loadOrRecoverCrashReport(): PersistedCrashReport? = synchronized(ioLock) {
        val existing = readActiveCrashSync()
        val snap = loadSessionSnapshotInternal()
        val abnormalExit = detectAbnormalOsExit(snap)

        if (abnormalExit != null) {
            writeJsonAtomicSync(activeCrashFile, json.encodeToString(abnormalExit))
            archiveCrashSync(abnormalExit)
            if (snap.inProgress) {
                val cleared = snap.copy(inProgress = false, activeStage = "Idle (recovered after crash)")
                latestSnapshot = cleared
                writeJsonAtomicSync(snapshotFile, json.encodeToString(cleared))
            }
            return@synchronized abnormalExit
        }

        if (snap.inProgress) {
            val logcatDump = readSystemLogcatCrashDump()
            val fallbackKill = PersistedCrashReport(
                id = UUID.randomUUID().toString().take(8),
                timestampIso = formatIso(System.currentTimeMillis()),
                timestampMs = System.currentTimeMillis(),
                crashType = if (logcatDump.isNotBlank()) "NATIVE_CRASH_LOGCAT" else "UNEXPECTED_PROCESS_KILL",
                activeStage = snap.activeStage,
                exceptionClass = if (logcatDump.isNotBlank()) "NativeSignalFault" else "ProcessTerminated",
                exceptionMessage = "App process stopped unexpectedly during '${snap.activeStage}' (likely native SIGSEGV/SIGABRT or OS termination).",
                stackTrace = if (logcatDump.isNotBlank()) logcatDump else "No JVM exception or native logcat tombstone found; process was terminated by the OS.",
                deviceInfo = buildDeviceInfo(),
                memoryInfo = buildMemoryInfo(),
                appVersion = buildAppVersion(),
                providerAndModel = snap.providerAndModel,
                executionLogsTail = snap.executionLogs.takeLast(120),
                liveStreamTail = snap.liveStreamText.takeLast(6000),
                blueprintJson = snap.blueprintJsonText,
                toolsCode = snap.toolsCodeText,
                pipelineCode = snap.pipelineCodeText
            )
            writeJsonAtomicSync(activeCrashFile, json.encodeToString(fallbackKill))
            archiveCrashSync(fallbackKill)
            val cleared = snap.copy(inProgress = false, activeStage = "Idle (recovered after kill)")
            latestSnapshot = cleared
            writeJsonAtomicSync(snapshotFile, json.encodeToString(cleared))
            return@synchronized fallbackKill
        }

        existing
    }

    fun clearCrashReport() {
        synchronized(ioLock) {
            runCatching {
                if (activeCrashFile.exists()) activeCrashFile.delete()
            }
        }
    }

    private fun persistUncaughtCrashSynchronously(thread: Thread, throwable: Throwable) {
        synchronized(ioLock) {
            val nowMs = System.currentTimeMillis()
            val sw = StringWriter()
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
            val snap = latestSnapshot

            val report = PersistedCrashReport(
                id = UUID.randomUUID().toString().take(8),
                timestampIso = formatIso(nowMs),
                timestampMs = nowMs,
                crashType = "UNCAUGHT_EXCEPTION",
                threadName = thread.name,
                activeStage = snap.activeStage,
                exceptionClass = throwable::class.java.name,
                exceptionMessage = throwable.message ?: throwable.cause?.message ?: "No message",
                stackTrace = sw.toString(),
                deviceInfo = buildDeviceInfo(),
                memoryInfo = buildMemoryInfo(),
                appVersion = buildAppVersion(),
                providerAndModel = snap.providerAndModel,
                executionLogsTail = snap.executionLogs.takeLast(120),
                liveStreamTail = snap.liveStreamText.takeLast(6000),
                blueprintJson = snap.blueprintJsonText,
                toolsCode = snap.toolsCodeText,
                pipelineCode = snap.pipelineCodeText
            )

            writeJsonAtomicSync(activeCrashFile, json.encodeToString(report))
            archiveCrashSync(report)
            writeTextSync(lastExitTsFile, (nowMs + 5_000L).toString())

            val finalizedSnap = snap.copy(inProgress = false, savedAtMs = nowMs)
            latestSnapshot = finalizedSnap
            writeJsonAtomicSync(snapshotFile, json.encodeToString(finalizedSnap))
        }
    }

    private fun detectAbnormalOsExit(snap: SessionDiagnosticSnapshot): PersistedCrashReport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
            val exits = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
            val lastHandled = readLastHandledExitTs()

            val abnormal = exits.firstOrNull { info ->
                info.timestamp > lastHandled && info.reason in setOf(
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                )
            } ?: return@runCatching null

            writeTextSync(lastExitTsFile, abnormal.timestamp.toString())

            val existing = readActiveCrashSync()
            if (existing != null &&
                existing.crashType == "UNCAUGHT_EXCEPTION" &&
                kotlin.math.abs(existing.timestampMs - abnormal.timestamp) < 10_000L
            ) {
                return@runCatching null
            }

            val exitReasonLabel = when (abnormal.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH (SIGSEGV/SIGABRT)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY_KILL (OOM/LMK)"
                ApplicationExitInfo.REASON_ANR -> "ANR_TIMEOUT"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_KILL"
                ApplicationExitInfo.REASON_CRASH -> "PROCESS_CRASH"
                else -> "ABNORMAL_EXIT_${abnormal.reason}"
            }

            val traceText = runCatching {
                abnormal.traceInputStream?.bufferedReader()?.use { reader ->
                    reader.readText().take(16_000)
                }
            }.getOrNull().orEmpty()

            val logcatDump = readSystemLogcatCrashDump()
            val combinedTrace = buildString {
                appendLine("Exit Reason: $exitReasonLabel (status=${abnormal.status}, importance=${abnormal.importance})")
                appendLine("PSS=${abnormal.pss / 1024} MB • RSS=${abnormal.rss / 1024} MB")
                if (!abnormal.description.isNullOrBlank()) {
                    appendLine("Description: ${abnormal.description}")
                }
                if (traceText.isNotBlank()) {
                    appendLine()
                    appendLine("--- APP EXIT TRACE ---")
                    appendLine(traceText)
                }
                if (logcatDump.isNotBlank()) {
                    appendLine()
                    appendLine("--- SYSTEM LOGCAT CRASH DUMP ---")
                    appendLine(logcatDump)
                }
            }.trim()

            PersistedCrashReport(
                id = UUID.randomUUID().toString().take(8),
                timestampIso = formatIso(abnormal.timestamp),
                timestampMs = abnormal.timestamp,
                crashType = exitReasonLabel,
                threadName = abnormal.processName,
                activeStage = snap.activeStage,
                exceptionClass = exitReasonLabel,
                exceptionMessage = abnormal.description ?: "Process killed by OS during '${snap.activeStage}'.",
                stackTrace = combinedTrace,
                deviceInfo = buildDeviceInfo(),
                memoryInfo = buildMemoryInfo(),
                appVersion = buildAppVersion(),
                providerAndModel = snap.providerAndModel,
                executionLogsTail = snap.executionLogs.takeLast(120),
                liveStreamTail = snap.liveStreamText.takeLast(6000),
                blueprintJson = snap.blueprintJsonText,
                toolsCode = snap.toolsCodeText,
                pipelineCode = snap.pipelineCodeText
            )
        }.getOrNull()
    }

    private fun readSystemLogcatCrashDump(): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "crash", "-d", "-t", "100"))
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (output.isNotBlank()) {
                output.take(12_000)
            } else {
                val fallbackProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "150", "*:E"))
                fallbackProcess.inputStream.bufferedReader().use { it.readText() }.trim().take(12_000)
            }
        }.getOrDefault("")
    }

    private fun readActiveCrashSync(): PersistedCrashReport? {
        if (!activeCrashFile.exists()) return null
        return runCatching {
            json.decodeFromString<PersistedCrashReport>(activeCrashFile.readText())
        }.getOrNull()
    }

    private fun loadSessionSnapshotInternal(): SessionDiagnosticSnapshot {
        if (!snapshotFile.exists()) return SessionDiagnosticSnapshot()
        return runCatching {
            json.decodeFromString<SessionDiagnosticSnapshot>(snapshotFile.readText())
        }.getOrDefault(SessionDiagnosticSnapshot())
    }

    private fun readLastHandledExitTs(): Long {
        if (!lastExitTsFile.exists()) return 0L
        return runCatching { lastExitTsFile.readText().trim().toLong() }.getOrDefault(0L)
    }

    private fun archiveCrashSync(report: PersistedCrashReport) {
        runCatching {
            val txtFile = File(crashArchiveDir, "crash_${report.timestampMs}_${report.id}.txt")
            writeTextSync(txtFile, report.toFormattedText())
        }
    }

    private fun writeJsonAtomicSync(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                writeTextSync(target, content)
                tmp.delete()
            }
        }
    }

    private fun writeTextSync(target: File, content: String) {
        runCatching {
            FileOutputStream(target).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
        }
    }

    private fun buildDeviceInfo(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) • ABI=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"

    private fun buildMemoryInfo(): String {
        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxHeapMb = rt.maxMemory() / (1024 * 1024)
        val sysInfo = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val availMb = mi.availMem / (1024 * 1024)
            val totalMb = mi.totalMem / (1024 * 1024)
            " • RAM avail=${availMb}MB/${totalMb}MB (lowMem=${mi.lowMemory})"
        }.getOrDefault("")
        return "JVM Heap=${usedHeapMb}MB/${maxHeapMb}MB$sysInfo"
    }

    private fun buildAppVersion(): String = runCatching {
        val pm = appContext.packageManager
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(appContext.packageName, 0)
        }
        "${pInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pInfo)})"
    }.getOrDefault("unknown")

    private fun formatIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(ms))
}