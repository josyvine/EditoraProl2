package com.vineyard.editoraPRO.app.ui.screens.studio

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccess
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted

@Composable
fun StudioScreen(
    state: AppState,
    onSelectWizardStep: (Int) -> Unit,
    onLoadReferenceVideo: (Uri) -> Unit,
    onLoadYoutubeUrl: (String) -> Unit,
    onAnalyzeReference: () -> Unit,
    onClearReference: () -> Unit,
    onCancelStage1: () -> Unit,
    onAddUserClips: (List<Uri>) -> Unit,
    onRemoveUserClip: (Int) -> Unit,
    onLoadCustomAudio: (Uri) -> Unit,
    onClearCustomAudio: () -> Unit,
    onToggleIncludeAudio: (Boolean) -> Unit,
    onToggleAutoVisualQa: (Boolean) -> Unit,
    onExtraNotesChanged: (String) -> Unit,
    onBuildEdit: () -> Unit,
    onCancelStage2: () -> Unit,
    onSeek: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onQaFeedbackChanged: (String) -> Unit,
    onRunManualAutoFix: () -> Unit,
    onCancelStage3: () -> Unit,
    onExportVideo: () -> Unit,
    onSaveToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectStep2SubStep: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WizardBar(
            activeStep = state.wizardStep,
            step1Done = state.step1Done,
            step2Done = state.step2Done,
            step3Done = state.step3Done,
            onSelectStep = onSelectWizardStep
        )

        when (state.wizardStep) {
            1 -> Step1ReferencePane(
                state = state,
                onLoadReferenceVideo = onLoadReferenceVideo,
                onLoadYoutubeUrl = onLoadYoutubeUrl,
                onAnalyzeReference = onAnalyzeReference,
                onClearReference = onClearReference,
                onCancelStage1 = onCancelStage1,
                onNextStep = { onSelectWizardStep(2) },
                onNavigateToSettings = onNavigateToSettings
            )
            2 -> Step2MediaPromptPane(
                state = state,
                onAddUserClips = onAddUserClips,
                onRemoveUserClip = onRemoveUserClip,
                onLoadCustomAudio = onLoadCustomAudio,
                onClearCustomAudio = onClearCustomAudio,
                onToggleIncludeAudio = onToggleIncludeAudio,
                onToggleAutoVisualQa = onToggleAutoVisualQa,
                onExtraNotesChanged = onExtraNotesChanged,
                onBuildEdit = onBuildEdit,
                onCancelStage2 = onCancelStage2,
                onPreviousStep = { onSelectWizardStep(1) },
                onNextStep = { onSelectWizardStep(3) },
                onNavigateToSettings = onNavigateToSettings,
                onSelectStep2SubStep = onSelectStep2SubStep
            )
            else -> Step3ExportPane(
                state = state,
                onSeek = onSeek,
                onTogglePlayPause = onTogglePlayPause,
                onQaFeedbackChanged = onQaFeedbackChanged,
                onRunManualAutoFix = onRunManualAutoFix,
                onCancelStage3 = onCancelStage3,
                onExportVideo = onExportVideo,
                onSaveToGallery = onSaveToGallery,
                onPreviousStep = { onSelectWizardStep(2) },
                onNavigateToSettings = onNavigateToSettings
            )
        }
    }
}

@Composable
private fun WizardBar(
    activeStep: Int,
    step1Done: Boolean,
    step2Done: Boolean,
    step3Done: Boolean,
    onSelectStep: (Int) -> Unit
) {
    val steps = listOf(
        Triple(1, "1. Reference", step1Done),
        Triple(2, "2. Media / Prompt", step2Done),
        Triple(3, "3. Export", step3Done)
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard_bar"),
        shape = RoundedCornerShape(14.dp),
        color = EditoraSurface,
        border = BorderStroke(1.dp, EditoraBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            steps.forEach { (stepNum, label, done) ->
                val isActive = activeStep == stepNum
                val bg = if (isActive) EditoraAccent.copy(alpha = 0.18f) else Color.Transparent
                val textColor = when {
                    isActive -> EditoraAccentCyan
                    done -> EditoraSuccess
                    else -> EditoraTextMuted
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .testTag("wizard_step_tab_$stepNum")
                        .clickable(
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = EditoraAccentCyan)
                        ) { onSelectStep(stepNum) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (done) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "$label completed",
                                tint = textColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = label,
                            color = textColor,
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
}