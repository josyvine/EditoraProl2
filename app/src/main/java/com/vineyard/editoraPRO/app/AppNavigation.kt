package com.vineyard.editoraPRO.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vineyard.editoraPRO.app.data.model.MainTab
import com.vineyard.editoraPRO.app.ui.StudioViewModel
import com.vineyard.editoraPRO.app.ui.components.BottomNavBar
import com.vineyard.editoraPRO.app.ui.components.SideNavRail
import com.vineyard.editoraPRO.app.ui.components.TopBar
import com.vineyard.editoraPRO.app.ui.screens.logic.AiLogicScreen
import com.vineyard.editoraPRO.app.ui.screens.settings.SettingsScreen
import com.vineyard.editoraPRO.app.ui.screens.studio.StudioScreen
import com.vineyard.editoraPRO.app.ui.theme.EditoraBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import kotlinx.coroutines.delay

@Composable
fun AppNavigation(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isBackInterceptorEnabled = state.activeTab != MainTab.STUDIO || state.wizardStep > 1

    BackHandler(enabled = isBackInterceptorEnabled) {
        viewModel.handleBackPress()
    }

    LaunchedEffect(state.toastCounter) {
        if (state.toastMessage != null) {
            delay(2800L)
            viewModel.clearToast()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(EditoraBg)
    ) {
        val useRail = maxWidth >= 720.dp

        Scaffold(
            containerColor = EditoraBg,
            topBar = {
                TopBar(
                    apiProvider = state.apiProvider,
                    selectedModel = state.selectedModel,
                    keyCount = state.apiKeys.size
                )
            },
            bottomBar = {
                if (!useRail) {
                    BottomNavBar(
                        activeTab = state.activeTab,
                        onSelectTab = viewModel::setActiveTab
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    SideNavRail(
                        activeTab = state.activeTab,
                        onSelectTab = viewModel::setActiveTab,
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(
                        state.activeTab,
                        state.wizardStep,
                        state.step2SubStep,
                        state.settingsSubPage
                    ) {
                        scrollState.scrollTo(0)
                    }

                    Column(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        when (state.activeTab) {
                            MainTab.STUDIO -> StudioScreen(
                                state = state,
                                onSelectWizardStep = viewModel::setWizardStep,
                                onLoadReferenceVideo = viewModel::loadReferenceVideo,
                                onLoadYoutubeUrl = viewModel::loadYoutubeReference,
                                onAnalyzeReference = viewModel::analyzeReference,
                                onClearReference = { viewModel.clearReference(silent = false) },
                                onCancelStage1 = viewModel::cancelActiveProcess,
                                onAddUserClips = viewModel::addUserClips,
                                onRemoveUserClip = viewModel::removeUserClip,
                                onLoadCustomAudio = viewModel::loadCustomAudio,
                                onClearCustomAudio = viewModel::clearCustomAudio,
                                onToggleIncludeAudio = viewModel::setIncludeAudioTrack,
                                onToggleAutoVisualQa = viewModel::setAutoVisualQa,
                                onExtraNotesChanged = viewModel::setExtraNotes,
                                onBuildEdit = viewModel::buildEdit,
                                onCancelStage2 = viewModel::cancelActiveProcess,
                                onSeek = viewModel::seekTo,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onQaFeedbackChanged = viewModel::setQaFeedbackInput,
                                onRunManualAutoFix = viewModel::runManualAutoFix,
                                onCancelStage3 = viewModel::cancelActiveProcess,
                                onExportVideo = viewModel::exportVideo,
                                onSaveToGallery = viewModel::saveExportedToGallery,
                                onNavigateToSettings = { viewModel.setActiveTab(MainTab.SETTINGS) },
                                onSelectStep2SubStep = viewModel::setStep2SubStep
                            )
                            MainTab.AI_LOGIC -> AiLogicScreen(
                                state = state,
                                onBuildReportText = viewModel::buildFullReportText,
                                onWriteReportUri = viewModel::writeReportFileAndGetUri,
                                onClearExecutionLog = viewModel::clearExecutionLog,
                                onShowToast = viewModel::showToast,
                                onOpenPricingSettings = {
                                    viewModel.setSettingsSubPage(3)
                                    viewModel.setActiveTab(MainTab.SETTINGS)
                                },
                                onClearCrashReport = viewModel::clearCrashReport
                            )
                            MainTab.SETTINGS -> SettingsScreen(
                                state = state,
                                onSwitchProvider = viewModel::switchProvider,
                                onAddApiKey = viewModel::addApiKey,
                                onRemoveApiKey = viewModel::removeApiKey,
                                onFetchLiveModels = viewModel::fetchLiveModels,
                                onSelectModel = viewModel::selectModel,
                                onSelectThinkingLevel = viewModel::setThinkingLevel,
                                onSelectExportFps = viewModel::setExportFps,
                                onSelectMaxCanvasEdge = viewModel::setMaxCanvasEdge,
                                onSelectSettingsPage = viewModel::setSettingsSubPage,
                                onEnableApiKey = viewModel::enableApiKey,
                                onToggleEstimateCostInFreeTier = viewModel::setEstimateCostInFreeTier,
                                onSavePricingOverride = viewModel::saveModelPricingOverride,
                                onResetPricingOverride = viewModel::resetModelPricingOverride,
                                onClearEditUsage = viewModel::clearCurrentEditUsage,
                                onResetLifetimeUsage = viewModel::resetLifetimeUsage
                            )
                        }
                    }
                }
            }
        }

        // Floating toast notification matching HTML toast banner
        AnimatedVisibility(
            visible = state.toastMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EditoraSurface2,
                border = BorderStroke(1.dp, EditoraBorder),
                shadowElevation = 10.dp,
                modifier = Modifier.testTag("toast_banner")
            ) {
                Text(
                    text = state.toastMessage.orEmpty(),
                    color = EditoraTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }
    }
}