package com.vineyard.editoraPRO.app.ui.screens.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.ProcessStatus
import com.vineyard.editoraPRO.app.ui.components.CardStepHeader
import com.vineyard.editoraPRO.app.ui.components.DashedUploadBox
import com.vineyard.editoraPRO.app.ui.components.EditoraButton
import com.vineyard.editoraPRO.app.ui.components.EditoraButtonTone
import com.vineyard.editoraPRO.app.ui.components.EditoraCard
import com.vineyard.editoraPRO.app.ui.components.EditoraTextField
import com.vineyard.editoraPRO.app.ui.components.LiveProcessMonitor
import com.vineyard.editoraPRO.app.ui.components.PillTone
import com.vineyard.editoraPRO.app.ui.components.StatusPill
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccessGreen
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import java.util.Locale

@Composable
fun Step2MediaPromptPane(
    state: AppState,
    onAddUserClips: (List<Uri>) -> Unit,
    onRemoveUserClip: (Int) -> Unit,
    onLoadCustomAudio: (Uri) -> Unit,
    onClearCustomAudio: () -> Unit,
    onToggleIncludeAudio: (Boolean) -> Unit,
    onToggleAutoVisualQa: (Boolean) -> Unit,
    onExtraNotesChanged: (String) -> Unit,
    onBuildEdit: () -> Unit,
    onCancelStage2: () -> Unit,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectStep2SubStep: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddUserClips(uris)
        }
    }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onLoadCustomAudio(uri)
        }
    }

    val hasRef = state.refAnalysis != null
    val isRunning = state.stage2Monitor.status == ProcessStatus.RUNNING
    val canBuild = !isRunning && (state.userClips.isNotEmpty() || state.extraNotes.trim().isNotEmpty())
    val subStep = state.step2SubStep.coerceIn(1, 2)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Step2SubStepBar(
            currentSubStep = subStep,
            hasMedia = state.userClips.isNotEmpty() || state.customAudioName.isNotBlank(),
            hasPromptOrBuild = state.extraNotes.trim().isNotEmpty() || state.isRenderReady,
            onSelectSubStep = onSelectStep2SubStep
        )

        if (subStep == 1) {
            // Sub-Page 1: Media Upload + Clips Grid + Custom Audio
            EditoraCard {
                CardStepHeader(
                    stepNumber = "2",
                    title = "Your Media & Audio",
                    pillText = "${state.userClips.size} item${if (state.userClips.size == 1) "" else "s"}",
                    pillTone = if (state.userClips.isNotEmpty()) PillTone.READY else PillTone.NEUTRAL,
                    pillTag = "clips_badge"
                )

                Text(
                    text = if (hasRef) {
                        "Upload 1 or many videos/images (including exact background/tile plates) and optionally attach music. Tap Next to enter your AI prompt."
                    } else {
                        "Prompt/Media Mode (No Reference): Upload media or skip straight to Next to animate purely by prompt."
                    },
                    color = EditoraTextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                DashedUploadBox(
                    title = "Tap to Add Videos or Images (Optional for Pure Prompt)",
                    subtitle = "Single or multiple videos / photos / exact tile templates",
                    onClick = {
                        pickMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    testTag = "user_media_upload_box"
                )

                if (state.userClips.isNotEmpty()) {
                    val rows = state.userClips.withIndex().chunked(3)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("user_clips_grid"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowItems.forEach { indexed ->
                                    ClipChipCard(
                                        index = indexed.index,
                                        clip = indexed.value,
                                        onRemove = { onRemoveUserClip(indexed.index) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // Custom audio bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = EditoraSurface2,
                    border = BorderStroke(1.dp, EditoraBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("attach_audio_button")
                                .clickable(role = Role.Button) {
                                    pickAudioLauncher.launch(arrayOf("audio/*", "video/*"))
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MusicNote,
                                contentDescription = "Attach custom music or audio",
                                tint = EditoraAccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "+ Attach Music / Audio (Optional)",
                                color = EditoraAccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        StatusPill(
                            text = if (state.customAudioName.isNotBlank()) {
                                "${state.customAudioName} (${String.format(Locale.US, "%.1fs", state.customAudioDurationSec)})"
                            } else {
                                "None"
                            },
                            tone = if (state.customAudioName.isNotBlank()) PillTone.READY else PillTone.NEUTRAL,
                            modifier = Modifier.testTag("custom_audio_badge")
                        )

                        if (state.customAudioName.isNotBlank()) {
                            IconButton(
                                onClick = onClearCustomAudio,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("clear_audio_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear attached audio",
                                    tint = EditoraTextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditoraButton(
                    text = "← Previous",
                    onClick = onPreviousStep,
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "step2_prev_button",
                    modifier = Modifier.weight(1f)
                )
                EditoraButton(
                    text = "Next: AI Prompt →",
                    onClick = { onSelectStep2SubStep(2) },
                    tone = if (state.step2Done) EditoraButtonTone.PRIMARY else EditoraButtonTone.SECONDARY,
                    testTag = "step2_next_button",
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // Sub-Page 2: Audio / QA Toggles + AI Prompt + Build Edit + Live Monitor
            EditoraCard {
                CardStepHeader(
                    stepNumber = "2",
                    title = "Options & AI Prompt",
                    pillText = "${state.userClips.size} item${if (state.userClips.size == 1) "" else "s"}",
                    pillTone = if (state.userClips.isNotEmpty() || state.extraNotes.trim().isNotEmpty()) PillTone.READY else PillTone.NEUTRAL,
                    pillTag = "clips_badge"
                )

                ToggleSettingRow(
                    label = "Include Audio Track (Ref / Custom / AI)",
                    checked = state.includeAudioTrack,
                    onCheckedChange = onToggleIncludeAudio,
                    testTag = "use_ref_audio_toggle"
                )

                ToggleSettingRow(
                    label = "Auto Visual QA & Self-Fix (Style Check)",
                    checked = state.autoVisualQa,
                    onCheckedChange = onToggleAutoVisualQa,
                    testTag = "auto_visual_qa_toggle"
                )

                EditoraTextField(
                    value = state.extraNotes,
                    onValueChange = onExtraNotesChanged,
                    placeholder = "AI Prompt / Guidance (e.g. 'Place photos inside exact tile grid size, make tiles mirror-like, add an animated piano on screen' OR without reference: 'Create an anime style piano animation for video')",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    testTag = "extra_notes_input"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditoraButton(
                        text = "AI Plan & Generate Live Edit",
                        onClick = onBuildEdit,
                        enabled = canBuild,
                        testTag = "build_edit_button",
                        modifier = Modifier.weight(1f)
                    )
                    if (isRunning) {
                        EditoraButton(
                            text = "Cancel",
                            onClick = onCancelStage2,
                            tone = EditoraButtonTone.DANGER,
                            testTag = "cancel_stage2_button"
                        )
                    }
                }

                LiveProcessMonitor(
                    state = state.stage2Monitor,
                    onSwitchModel = onNavigateToSettings,
                    testTagPrefix = "stage2"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditoraButton(
                    text = "← Previous",
                    onClick = { onSelectStep2SubStep(1) },
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "step2_prev_button",
                    modifier = Modifier.weight(1f)
                )
                EditoraButton(
                    text = "Next: Render →",
                    onClick = onNextStep,
                    tone = if (state.step2Done) EditoraButtonTone.PRIMARY else EditoraButtonTone.SECONDARY,
                    testTag = "step2_next_button",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun Step2SubStepBar(
    currentSubStep: Int,
    hasMedia: Boolean,
    hasPromptOrBuild: Boolean,
    onSelectSubStep: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = EditoraSurface,
        border = BorderStroke(1.dp, EditoraBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val pages = listOf(
                Triple(1, "2A. Media & Audio", hasMedia),
                Triple(2, "2B. Options & Prompt", hasPromptOrBuild)
            )
            pages.forEach { (page, label, done) ->
                val active = currentSubStep == page
                val bg = if (active) EditoraAccent.copy(alpha = 0.18f) else Color.Transparent
                val fg = when {
                    active -> EditoraAccentCyan
                    done -> EditoraSuccessGreen
                    else -> EditoraTextMuted
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .testTag("step2_subtab_$page")
                        .clickable(role = Role.Tab) { onSelectSubStep(page) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = fg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipChipCard(
    index: Int,
    clip: ClipAsset,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.testTag("clip_chip_$index"),
        shape = RoundedCornerShape(10.dp),
        color = EditoraSurface2,
        border = BorderStroke(1.dp, EditoraBorder)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (clip.thumbnail != null) {
                        Image(
                            bitmap = clip.thumbnail.asImageBitmap(),
                            contentDescription = clip.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "#$index ${if (clip.isImage) "IMG" else "VID"}",
                        color = EditoraTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (clip.isImage) "${clip.width}x${clip.height}" else String.format(Locale.US, "%.1fs", clip.durationSec),
                        color = EditoraTextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .testTag("remove_clip_$index")
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove ${clip.name}",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = EditoraTextPrimary,
            fontSize = 12.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = EditoraAccent,
                uncheckedThumbColor = EditoraTextMuted,
                uncheckedTrackColor = EditoraSurface2
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}