package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentIndigo
import com.vineyard.editoraPRO.app.ui.theme.EditoraBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraDanger
import com.vineyard.editoraPRO.app.ui.theme.EditoraDangerText
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccessDark
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccessGreen
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import com.vineyard.editoraPRO.app.ui.theme.EditoraWarn
import com.vineyard.editoraPRO.app.ui.theme.EditoraWarnText

@Composable
fun EditoraCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = EditoraSurface,
        border = BorderStroke(1.dp, EditoraBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun CardStepHeader(
    stepNumber: String?,
    title: String,
    pillText: String? = null,
    pillTone: PillTone = PillTone.NEUTRAL,
    pillTag: String = "card_header_pill"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            if (stepNumber != null) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(EditoraAccent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber,
                        color = EditoraAccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = EditoraTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (pillText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            StatusPill(
                text = pillText,
                tone = pillTone,
                modifier = Modifier.testTag(pillTag)
            )
        }
    }
}

@Composable
fun DashedUploadBox(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val dashColor = EditoraAccent.copy(alpha = 0.45f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(EditoraAccent.copy(alpha = 0.05f))
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                )
            }
            .testTag(testTag)
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = EditoraAccentCyan),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                color = EditoraTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                color = EditoraTextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun OrDivider(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = EditoraBorder)
        Text(
            text = label.uppercase(),
            color = EditoraTextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = EditoraBorder)
    }
}

enum class EditoraButtonTone {
    PRIMARY,
    SUCCESS,
    SECONDARY,
    WARN,
    DANGER
}

@Composable
fun EditoraButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    tone: EditoraButtonTone = EditoraButtonTone.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    when (tone) {
        EditoraButtonTone.PRIMARY, EditoraButtonTone.SUCCESS -> {
            val brush = if (tone == EditoraButtonTone.PRIMARY) {
                Brush.linearGradient(listOf(EditoraAccent, EditoraAccentIndigo))
            } else {
                Brush.linearGradient(listOf(EditoraSuccessGreen, EditoraSuccessDark))
            }
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                elevation = null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                modifier = modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(shape)
                    .background(brush = brush, alpha = if (enabled) 1f else 0.42f)
                    .testTag(testTag)
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        EditoraButtonTone.SECONDARY, EditoraButtonTone.WARN, EditoraButtonTone.DANGER -> {
            val (bg, borderCol, contentCol) = when (tone) {
                EditoraButtonTone.WARN -> Triple(
                    EditoraWarn.copy(alpha = 0.16f),
                    EditoraWarn.copy(alpha = 0.35f),
                    EditoraWarnText
                )
                EditoraButtonTone.DANGER -> Triple(
                    EditoraDanger.copy(alpha = 0.16f),
                    EditoraDanger.copy(alpha = 0.35f),
                    EditoraDangerText
                )
                else -> Triple(
                    EditoraSurface2,
                    EditoraBorder,
                    EditoraTextPrimary
                )
            }
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, borderCol.copy(alpha = if (enabled) borderCol.alpha else 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.45f),
                    contentColor = contentCol,
                    disabledContainerColor = bg.copy(alpha = 0.4f),
                    disabledContentColor = contentCol.copy(alpha = 0.45f)
                ),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
                modifier = modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag(testTag)
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EditoraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    testTag: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                color = EditoraTextMuted.copy(alpha = 0.72f),
                fontSize = 13.sp,
                maxLines = if (singleLine) 1 else maxLines,
                overflow = TextOverflow.Ellipsis
            )
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = EditoraBg,
            unfocusedContainerColor = EditoraBg,
            focusedBorderColor = EditoraAccent,
            unfocusedBorderColor = EditoraBorder,
            focusedTextColor = EditoraTextPrimary,
            unfocusedTextColor = EditoraTextPrimary,
            cursorColor = EditoraAccentCyan
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}