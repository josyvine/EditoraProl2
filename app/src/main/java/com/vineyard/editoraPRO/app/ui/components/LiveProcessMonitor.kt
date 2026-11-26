package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.ProcessMonitorState
import com.vineyard.editoraPRO.app.data.model.ProcessStatus
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraCodeBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraCyanStreamText
import com.vineyard.editoraPRO.app.ui.theme.EditoraDanger
import com.vineyard.editoraPRO.app.ui.theme.EditoraMonoStyle
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccess
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTerminalBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted

@Composable
fun LiveProcessMonitor(
    state: ProcessMonitorState,
    onSwitchModel: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = state.visible, modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("${testTagPrefix}_process_monitor"),
            shape = RoundedCornerShape(12.dp),
            color = EditoraTerminalBg,
            border = BorderStroke(1.dp, EditoraAccentCyan.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (state.status) {
                            ProcessStatus.RUNNING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = EditoraAccentCyan,
                                    trackColor = EditoraAccentCyan.copy(alpha = 0.25f)
                                )
                            }
                            ProcessStatus.SUCCESS -> {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(EditoraSuccess)
                                )
                            }
                            ProcessStatus.ERROR -> {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(EditoraDanger)
                                )
                            }
                            ProcessStatus.IDLE -> {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(EditoraTextMuted)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = state.statusText,
                            color = EditoraAccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("${testTagPrefix}_status_text")
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val totalSec = (state.elapsedMs / 1000L).coerceAtLeast(0L)
                    val mm = (totalSec / 60L).toString().padStart(2, '0')
                    val ss = (totalSec % 60L).toString().padStart(2, '0')
                    val charsLabel = if (state.charCount >= 1000) {
                        "${"%.1f".format(state.charCount / 1000f)}k"
                    } else {
                        state.charCount.toString()
                    }
                    Text(
                        text = "$mm:$ss • $charsLabel chars",
                        style = EditoraMonoStyle,
                        color = EditoraTextMuted,
                        maxLines = 1
                    )
                }

                // Animated shimmer progress track
                ShimmerProgressTrack(
                    progress = state.progress,
                    isActive = state.status == ProcessStatus.RUNNING
                )

                // Live feed mini terminal
                val feedScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp, max = 90.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EditoraCodeBg)
                        .border(1.dp, EditoraBorder, RoundedCornerShape(8.dp))
                        .verticalScroll(feedScroll)
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = state.liveFeedLines.joinToString("\n").ifBlank { "Waiting for stream..." },
                        style = EditoraMonoStyle.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                        color = EditoraCyanStreamText,
                        modifier = Modifier.testTag("${testTagPrefix}_live_feed")
                    )
                }

                if (state.showSwitchModelButton) {
                    EditoraButton(
                        text = "Change Model in Settings",
                        onClick = onSwitchModel,
                        tone = EditoraButtonTone.WARN,
                        testTag = "${testTagPrefix}_switch_model_button"
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerProgressTrack(
    progress: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 250),
        label = "progress_fill"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(EditoraSurface2)
    ) {
        val brush = if (isActive) {
            Brush.linearGradient(
                colors = listOf(EditoraAccent, EditoraAccentCyan, EditoraAccent),
                start = Offset(shimmerOffset, 0f),
                end = Offset(shimmerOffset + 260f, 0f)
            )
        } else {
            Brush.horizontalGradient(listOf(EditoraAccent, EditoraAccentCyan))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(brush)
        )
    }
}
