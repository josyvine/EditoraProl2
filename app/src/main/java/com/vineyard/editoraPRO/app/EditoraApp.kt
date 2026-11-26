package com.vineyard.editoraPRO.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.vineyard.editoraPRO.app.data.local.CrashReportManager
import com.vineyard.editoraPRO.app.data.local.PreferencesManager
import com.vineyard.editoraPRO.app.data.remote.GeminiClient
import com.vineyard.editoraPRO.app.domain.engine.GlShaderRunner
import com.vineyard.editoraPRO.app.domain.engine.QuickJsRunner
import com.vineyard.editoraPRO.app.domain.export.VideoExporter
import com.vineyard.editoraPRO.app.domain.qa.VisualQaManager
import com.vineyard.editoraPRO.app.domain.vision.SmartSegmenter
import com.vineyard.editoraPRO.app.security.SecurityGuard

class EditoraApp : Application(), ImageLoaderFactory {

    val isSecurityTampered: Boolean by lazy {
        SecurityGuard.isTamperedOrDebuggedInRelease(this)
    }

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(this)
    }

    val crashReportManager: CrashReportManager by lazy {
        CrashReportManager(this)
    }

    val geminiClient: GeminiClient by lazy {
        GeminiClient()
    }

    override fun onCreate() {
        super.onCreate()
        crashReportManager.install()
        // Evaluate integrity check cleanly at startup
        isSecurityTampered
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}

class StudioServiceContainer(
    private val app: EditoraApp,
    onLog: (String) -> Unit
) {
    val smartSegmenter = SmartSegmenter(app, onLog = onLog)
    val glShaderRunner = GlShaderRunner()
    val quickJsRunner = QuickJsRunner(smartSegmenter, glShaderRunner, onLog = onLog)
    val videoExporter = VideoExporter(app)
    val visualQaManager = VisualQaManager(app.geminiClient, quickJsRunner, videoExporter)

    fun release() {
        quickJsRunner.release()
        glShaderRunner.release()
        smartSegmenter.release()
    }
}