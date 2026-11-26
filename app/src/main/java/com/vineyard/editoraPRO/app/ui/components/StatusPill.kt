package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccess
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraWarn

enum class PillTone {
    NEUTRAL,
    READY,
    WARN,
    NEXT
}

@Composable
fun StatusPill(
    text: String,
    tone: PillTone = PillTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val (textColor, borderColor, bgColor) = when (tone) {
        PillTone.READY -> Triple(EditoraSuccess, EditoraSuccess.copy(alpha = 0.35f), EditoraSurface2)
        PillTone.WARN -> Triple(EditoraWarn, EditoraWarn.copy(alpha = 0.35f), EditoraSurface2)
        PillTone.NEXT -> Triple(EditoraAccentCyan, EditoraAccentCyan.copy(alpha = 0.4f), EditoraAccentCyan.copy(alpha = 0.16f))
        PillTone.NEUTRAL -> Triple(EditoraTextMuted, EditoraBorder, EditoraSurface2)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
