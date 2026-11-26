package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.Editpro.app.R
import com.vineyard.editoraPRO.app.data.model.ProviderMode
import com.vineyard.editoraPRO.app.ui.theme.EditoraBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary

@Composable
fun TopBar(
    apiProvider: ProviderMode,
    selectedModel: String,
    keyCount: Int,
    modifier: Modifier = Modifier
) {
    val cleanModel = selectedModel.removePrefix("models/")
    val (statusText, statusTone) = if (keyCount > 0 && cleanModel.isNotBlank()) {
        "${apiProvider.shortLabel}: $cleanModel • ${keyCount}k" to PillTone.READY
    } else {
        "${apiProvider.shortLabel}: Setup" to PillTone.WARN
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(EditoraBg.copy(alpha = 0.94f))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("top_bar_brand")
            ) {
                Image(
                    painter = painterResource(id = R.drawable.editorapro),
                    contentDescription = "AI Video Director Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI Video Director",
                    color = EditoraTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            StatusPill(
                text = statusText,
                tone = statusTone,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("header_status_pill")
            )
        }
        HorizontalDivider(thickness = 1.dp, color = EditoraBorder)
    }
}