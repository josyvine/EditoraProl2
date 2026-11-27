package com.vineyard.editoraPRO.app.ui.screens.studio

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.data.model.ProcessStatus
import com.vineyard.editoraPRO.app.ui.components.CardStepHeader
import com.vineyard.editoraPRO.app.ui.components.EditoraButton
import com.vineyard.editoraPRO.app.ui.components.EditoraButtonTone
import com.vineyard.editoraPRO.app.ui.components.EditoraCard
import com.vineyard.editoraPRO.app.ui.components.EditoraTextField
import com.vineyard.editoraPRO.app.ui.components.ExoVideoPreview
import com.vineyard.editoraPRO.app.ui.components.LiveProcessMonitor
import com.vineyard.editoraPRO.app.ui.components.PillTone
import com.vineyard.editoraPRO.app.ui.components.ShimmerProgressTrack
import com.vineyard.editoraPRO.app.ui.components.VideoScrubber
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted

@Composable
fun Step3ExportPane(
    state: AppState,
    onSeek: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onQaFeedbackChanged: (String) -> Unit,
    onRunManualAutoFix: () -> Unit,
    onCancelStage3: () -> Unit,
    onExportVideo: () -> Unit,
    onSaveToGallery: () -> Unit,
    onPreviousStep: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBuildPreviewProxy: () -> Unit = {},
    onCancelPreviewProxy: () -> Unit = {},
    onCancelExport: () -> Unit = {},
    onToggleUseProxyPlayer: (Boolean) -> Unit = {},
    onExoPositionChanged: (Float) -> Unit = {},
    onExoPlayingChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val isQaRunning = state.stage3Monitor.status == ProcessStatus.RUNNING
    val isHeavyJobBusy = state.isExporting || state.isBuildingPreviewProxy

    val ratio = (state.canvasWidth.toFloat() / state.canvasHeight.coerceAtLeast(1).toFloat())
        .coerceIn(0.45f, 2.2f)
    val stageHeight = when {
        ratio < 0.85f -> 290.dp
        ratio > 1.25f -> 195.dp
        else -> 240.dp
    }

    val playableFile = if (state.useVideoProxyPlayer) {
        (state.exportedVideoFile ?: state.cachedRenderedMp4File ?: state.previewProxyFile)
            ?.takeIf { it.exists() && it.length() > 1024L }
    } else {
        null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EditoraCard {
            CardStepHeader(
                stepNumber = "3",
                title = "AI Render Output",
                pillText = when {
                    !state.isRenderReady -> "Waiting"
                    playableFile != null -> "${state.canvasWidth}x${state.canvasHeight} • Smooth MP4"
                    else -> "${state.canvasWidth}x${state.canvasHeight} Ready"
                },
                pillTone = if (state.isRenderReady) PillTone.READY else PillTone.NEUTRAL,
                pillTag = "render_badge"
            )

            // Composite Frame Canvas Stage (switches automatically to ExoPlayer when MP4 is cached!)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (playableFile != null) {
                    ExoVideoPreview(
                        uri = Uri.fromFile(playableFile),
                        testTag = "render_preview_stage",
                        height = stageHeight,
                        modifier = Modifier.aspectRatio(ratio),
                        externalIsPlaying = state.isPlaying,
                        externalSeekSec = state.currentTimeSec,
                        loopPlayback = false,
                        useBuiltInController = false,
                        onPositionChanged = onExoPositionChanged,
                        onPlayingChanged = onExoPlayingChanged
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 160.dp, max = 300.dp)
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .border(1.dp, EditoraBorder, RoundedCornerShape(12.dp))
                            .testTag("render_preview_stage"),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = state.previewFrame
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "AI Rendered Output Frame",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Movie,
                                    contentDescription = "Preview Placeholder",
                                    tint = EditoraTextMuted,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = "Generate an edit in Step 2 to preview",
                                    color = EditoraTextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                VideoScrubber(
                    currentTimeSec = state.currentTimeSec,
                    totalDurationSec = state.totalDurationSec,
                    enabled = state.isRenderReady && (playableFile != null || !isHeavyJobBusy),
                    onSeek = onSeek
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditoraButton(
                    text = if (state.isPlaying) "Pause Preview" else "Play Preview",
                    onClick = onTogglePlayPause,
                    tone = EditoraButtonTone.SECONDARY,
                    enabled = state.isRenderReady && (playableFile != null || !isHeavyJobBusy),
                    testTag = "play_pause_button",
                    modifier = Modifier.weight(1f)
                )
                EditoraButton(
                    text = if (state.isExporting) "Exporting..." else "Export Video",
                    onClick = onExportVideo,
                    tone = EditoraButtonTone.SUCCESS,
                    enabled = state.isRenderReady && !isHeavyJobBusy,
                    testTag = "export_video_button",
                    modifier = Modifier.weight(1f)
                )
                if (state.isExporting) {
                    EditoraButton(
                        text = "Stop",
                        onClick = onCancelExport,
                        tone = EditoraButtonTone.DANGER,
                        testTag = "cancel_export_button"
                    )
                }
            }

            // Smooth Playable Preview Proxy Builder / Player Switcher
            AnimatedVisibility(visible = state.isRenderReady) {
                val hasAnyCachedMp4 = (state.exportedVideoFile ?: state.cachedRenderedMp4File ?: state.previewProxyFile)
                    ?.let { it.exists() && it.length() > 1024L } == true
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (state.isBuildingPreviewProxy) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.previewProxyStatusText.ifBlank { "Building smooth preview..." },
                                color = EditoraTextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            EditoraButton(
                                text = "Stop",
                                onClick = onCancelPreviewProxy,
                                tone = EditoraButtonTone.DANGER,
                                testTag = "cancel_proxy_button"
                            )
                        }
                        if (state.exportFrameText.isNotBlank() || state.exportEtaText.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = state.exportFrameText,
                                    color = EditoraTextMuted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = state.exportEtaText,
                                    color = EditoraTextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        ShimmerProgressTrack(
                            progress = state.previewProxyProgress,
                            isActive = true
                        )
                    } else if (!hasAnyCachedMp4 && !state.isExporting) {
                        EditoraButton(
                            text = "Build Smooth Preview (Fast 15fps MP4)",
                            onClick = onBuildPreviewProxy,
                            tone = EditoraButtonTone.SECONDARY,
                            testTag = "build_preview_proxy_button",
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (hasAnyCachedMp4 && !state.isExporting) {
                        EditoraButton(
                            text = if (state.useVideoProxyPlayer) {
                                "Player Mode: Smooth MP4 (Tap for Frame Scrub)"
                            } else {
                                "Player Mode: Frame Scrub (Tap for Smooth MP4)"
                            },
                            onClick = { onToggleUseProxyPlayer(!state.useVideoProxyPlayer) },
                            tone = EditoraButtonTone.SECONDARY,
                            testTag = "toggle_player_mode_button",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // On-Demand Visual QA / Auto-Fix Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = EditoraAccent.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, EditoraAccent.copy(alpha = 0.24f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EditoraTextField(
                            value = state.qaFeedbackInput,
                            onValueChange = onQaFeedbackChanged,
                            placeholder = "Describe what to fix or add (e.g. 'Resize image per tile grid size, add a piano')",
                            testTag = "qa_feedback_input",
                            modifier = Modifier.weight(1f)
                        )
                        EditoraButton(
                            text = "AI Auto-Fix",
                            onClick = onRunManualAutoFix,
                            tone = EditoraButtonTone.WARN,
                            enabled = state.isRenderReady && !isQaRunning && !isHeavyJobBusy,
                            testTag = "fix_render_button"
                        )
                        if (isQaRunning) {
                            EditoraButton(
                                text = "Stop",
                                onClick = onCancelStage3,
                                tone = EditoraButtonTone.DANGER,
                                testTag = "cancel_stage3_button"
                            )
                        }
                    }

                    LiveProcessMonitor(
                        state = state.stage3Monitor,
                        onSwitchModel = onNavigateToSettings,
                        testTagPrefix = "stage3"
                    )
                }
            }

            AnimatedVisibility(visible = state.isExporting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = state.exportStatusText.ifBlank { "Rendering export..." },
                        color = EditoraTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("export_status_text")
                    )
                    if (state.exportFrameText.isNotBlank() || state.exportEtaText.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = state.exportFrameText,
                                color = EditoraTextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.testTag("export_frame_text")
                            )
                            Text(
                                text = state.exportEtaText,
                                color = EditoraTextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.testTag("export_eta_text")
                            )
                        }
                    }
                    ShimmerProgressTrack(
                        progress = state.exportProgress,
                        isActive = true
                    )
                }
            }

            val cachedExportFile = state.exportedVideoFile ?: state.cachedRenderedMp4File
            val outUri = state.exportedVideoUri ?: cachedExportFile?.let { Uri.fromFile(it) }
            AnimatedVisibility(visible = outUri != null) {
                if (outUri != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("download_wrap"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExoVideoPreview(
                            uri = outUri,
                            testTag = "exported_video_preview",
                            height = 195.dp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EditoraButton(
                                text = if (state.isFastDownloadSaving) "Saving..." else "Download / Save MP4",
                                onClick = onSaveToGallery,
                                tone = EditoraButtonTone.SUCCESS,
                                enabled = !state.isFastDownloadSaving,
                                testTag = "save_movies_button",
                                modifier = Modifier.weight(1f)
                            )
                            EditoraButton(
                                text = "Share Video",
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, outUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share Rendered Video")
                                    )
                                },
                                tone = EditoraButtonTone.SECONDARY,
                                testTag = "share_video_button",
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        EditoraButton(
            text = "← Previous: Edit Media",
            onClick = onPreviousStep,
            tone = EditoraButtonTone.SECONDARY,
            testTag = "step3_prev_button",
            modifier = Modifier.fillMaxWidth()
        )
    }
}