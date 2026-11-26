package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraCodeBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraCodeText
import com.vineyard.editoraPRO.app.ui.theme.EditoraMonoStyle

@Composable
fun CodeViewer(
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
    textColor: Color = EditoraCodeText,
    maxHeight: Dp = 220.dp
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp, max = maxHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(EditoraCodeBg)
            .border(1.dp, EditoraBorder, RoundedCornerShape(12.dp))
            .verticalScroll(vScroll)
            .horizontalScroll(hScroll)
            .padding(10.dp)
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = EditoraMonoStyle.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = textColor,
                modifier = Modifier.testTag(testTag)
            )
        }
    }
}
