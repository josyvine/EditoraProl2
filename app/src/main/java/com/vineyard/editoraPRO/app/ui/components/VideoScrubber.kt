package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraMonoStyle
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import java.util.Locale

@Composable
fun VideoScrubber(
    currentTimeSec: Float,
    totalDurationSec: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMax = totalDurationSec.coerceAtLeast(0.1f)
    val safeValue = currentTimeSec.coerceIn(0f, safeMax)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = String.format(Locale.US, "%.1fs", safeValue),
            style = EditoraMonoStyle.copy(fontSize = 11.sp),
            color = EditoraTextMuted,
            modifier = Modifier.testTag("scrubber_time_now")
        )
        Slider(
            value = safeValue,
            onValueChange = { onSeek(it) },
            valueRange = 0f..safeMax,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = EditoraAccentCyan,
                activeTrackColor = EditoraAccent,
                inactiveTrackColor = EditoraSurface2,
                disabledThumbColor = EditoraTextMuted,
                disabledActiveTrackColor = EditoraTextMuted.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("timeline_scrubber")
        )
        Text(
            text = String.format(Locale.US, "%.1fs", safeMax),
            style = EditoraMonoStyle.copy(fontSize = 11.sp),
            color = EditoraTextMuted,
            modifier = Modifier.testTag("scrubber_time_total")
        )
    }
}
