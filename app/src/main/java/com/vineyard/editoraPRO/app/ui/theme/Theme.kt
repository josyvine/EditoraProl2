package com.vineyard.editoraPRO.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val EditoraDarkScheme = darkColorScheme(
    primary = EditoraAccent,
    onPrimary = EditoraTextPrimary,
    secondary = EditoraAccentCyan,
    onSecondary = EditoraBg,
    tertiary = EditoraSuccess,
    background = EditoraBg,
    onBackground = EditoraTextPrimary,
    surface = EditoraSurface,
    onSurface = EditoraTextPrimary,
    surfaceVariant = EditoraSurface2,
    onSurfaceVariant = EditoraTextMuted,
    outline = EditoraBorder,
    error = EditoraDanger,
    onError = EditoraTextPrimary
)

val EditoraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun EditoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EditoraDarkScheme,
        typography = EditoraTypography,
        shapes = EditoraShapes,
        content = content
    )
}
