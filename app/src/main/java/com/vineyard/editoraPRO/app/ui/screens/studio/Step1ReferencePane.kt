package com.vineyard.editoraPRO.app.ui.screens.studio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.data.model.ProcessStatus
import com.vineyard.editoraPRO.app.data.model.ReferenceSourceType
import com.vineyard.editoraPRO.app.ui.components.CardStepHeader
import com.vineyard.editoraPRO.app.ui.components.EditoraButton
import com.vineyard.editoraPRO.app.ui.components.EditoraButtonTone
import com.vineyard.editoraPRO.app.ui.components.EditoraCard
import com.vineyard.editoraPRO.app.ui.components.EditoraTextField
import com.vineyard.editoraPRO.app.ui.components.ExoVideoPreview
import com.vineyard.editoraPRO.app.ui.components.DashedUploadBox
import com.vineyard.editoraPRO.app.ui.components.LiveProcessMonitor
import com.vineyard.editoraPRO.app.ui.components.OrDivider
import com.vineyard.editoraPRO.app.ui.components.PillTone
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraCodeBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import java.util.Locale

@Composable
fun Step1ReferencePane(
    state: AppState,
    onLoadReferenceVideo: (Uri) -> Unit,
    onLoadYoutubeUrl: (String) -> Unit,
    onAnalyzeReference: () -> Unit,
    onClearReference: () -> Unit,
    onCancelStage1: () -> Unit,
    onNextStep: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var youtubeInput by remember { mutableStateOf(state.refYoutubeUrl) }

    LaunchedEffect(state.refYoutubeUrl) {
        if (state.refYoutubeUrl.isNotEmpty()) {
            youtubeInput = state.refYoutubeUrl
        } else if (state.refSourceType == ReferenceSourceType.NONE) {
            youtubeInput = ""
        }
    }

    val pickRefVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onLoadReferenceVideo(uri)
        }
    }

    val (badgeText, badgeTone) = when {
        state.step1Done && state.refAnalysis != null -> {
            val dur = state.refAnalysis.meta.durationSec.takeIf { it > 0 } ?: state.refDurationSec.toDouble()
            val label = if (dur > 0) "Analyzed • ${String.format(Locale.US, "%.1fs", dur)}" else "Analyzed"
            label to PillTone.READY
        }
        state.refSourceType == ReferenceSourceType.FILE -> {
            "${String.format(Locale.US, "%.1fs", state.refDurationSec)} (${state.refWidth}x${state.refHeight})" to PillTone.READY
        }
        state.refSourceType == ReferenceSourceType.YOUTUBE -> {
            "YouTube Ready" to PillTone.READY
        }
        else -> "Optional / Skipped" to PillTone.NEUTRAL
    }

    val isRunning = state.stage1Monitor.status == ProcessStatus.RUNNING

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EditoraCard {
            CardStepHeader(
                stepNumber = "1",
                title = "Reference Video (Optional)",
                pillText = badgeText,
                pillTone = badgeTone,
                pillTag = "ref_badge"
            )

            Text(
                text = "Upload a reference edit or paste a YouTube URL to reverse-engineer its mechanics — or skip straight to Step 2 to edit/animate purely by prompt.",
                color = EditoraTextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            DashedUploadBox(
                title = "Tap to Upload Reference Video",
                subtitle = "MP4 / MOV / WebM • Live Gemini Analysis",
                onClick = {
                    pickRefVideoLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                testTag = "ref_video_upload_box"
            )

            OrDivider(label = "Or YouTube Reference Engine")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditoraTextField(
                    value = youtubeInput,
                    onValueChange = { youtubeInput = it },
                    placeholder = "Paste YouTube / Shorts URL (youtube.com/...)",
                    testTag = "youtube_url_input",
                    modifier = Modifier.weight(1f)
                )
                EditoraButton(
                    text = "Load",
                    onClick = { onLoadYoutubeUrl(youtubeInput) },
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "load_youtube_button"
                )
            }

            AnimatedVisibility(visible = state.refSourceType != ReferenceSourceType.NONE) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.refSourceType == ReferenceSourceType.FILE && state.refUri != null) {
                        ExoVideoPreview(
                            uri = state.refUri,
                            testTag = "ref_video_preview",
                            height = 190.dp
                        )
                    } else if (state.refSourceType == ReferenceSourceType.YOUTUBE && state.refYoutubeId.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("yt_preview_card"),
                            shape = RoundedCornerShape(12.dp),
                            color = EditoraCodeBg,
                            border = BorderStroke(1.dp, EditoraBorder)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 195.dp)
                                        .aspectRatio(16f / 9f)
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = "https://img.youtube.com/vi/${state.refYoutubeId}/hqdefault.jpg",
                                        contentDescription = "YouTube Reference Thumbnail",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.25f))
                                    )
                                    IconButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(state.refYoutubeUrl))
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.7f))
                                            .testTag("yt_open_external_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "Open YouTube Reference",
                                            tint = Color.White,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = state.refYoutubeUrl,
                                        color = EditoraTextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Gemini Direct Video+Audio",
                                            color = EditoraAccentCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        IconButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(state.refYoutubeUrl))
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag("yt_open_link_icon")
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                                contentDescription = "Open in YouTube",
                                                tint = EditoraAccentCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EditoraButton(
                            text = "Analyze Reference & Synthesize AI Tools",
                            onClick = onAnalyzeReference,
                            enabled = !isRunning,
                            testTag = "analyze_ref_button",
                            modifier = Modifier.weight(1f)
                        )
                        EditoraButton(
                            text = "Clear",
                            onClick = onClearReference,
                            tone = EditoraButtonTone.SECONDARY,
                            enabled = !isRunning,
                            testTag = "clear_ref_button"
                        )
                        if (isRunning) {
                            EditoraButton(
                                text = "Cancel",
                                onClick = onCancelStage1,
                                tone = EditoraButtonTone.DANGER,
                                testTag = "cancel_stage1_button"
                            )
                        }
                    }
                }
            }

            LiveProcessMonitor(
                state = state.stage1Monitor,
                onSwitchModel = onNavigateToSettings,
                testTagPrefix = "stage1"
            )
        }

        EditoraButton(
            text = "Next: Media & AI Prompt →",
            onClick = onNextStep,
            tone = if (state.step1Done) EditoraButtonTone.PRIMARY else EditoraButtonTone.SECONDARY,
            testTag = "step1_next_button",
            modifier = Modifier.fillMaxWidth()
        )
    }
}