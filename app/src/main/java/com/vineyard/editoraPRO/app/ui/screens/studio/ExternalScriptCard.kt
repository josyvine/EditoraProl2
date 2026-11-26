package com.vineyard.editoraPRO.app.ui.screens.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.ui.components.EditoraButton
import com.vineyard.editoraPRO.app.ui.components.EditoraButtonTone
import com.vineyard.editoraPRO.app.ui.components.EditoraCard
import com.vineyard.editoraPRO.app.ui.components.EditoraTextField
import com.vineyard.editoraPRO.app.ui.components.PillTone
import com.vineyard.editoraPRO.app.ui.components.StatusPill
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExternalScriptCard(
    scriptText: String,
    isBusy: Boolean,
    hasRefAudio: Boolean,
    refName: String,
    onBuildPromptPack: () -> String,
    onScriptChanged: (String) -> Unit,
    onRunOffline: () -> Unit,
    onShowToast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val pickScriptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val loaded = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrElse { "" }
                withContext(Dispatchers.Main) {
                    if (loaded.isNotBlank()) {
                        onScriptChanged(loaded)
                        onShowToast("Script loaded (${loaded.length} chars). Tap Run Script Offline!")
                    } else {
                        onShowToast("Selected file was empty or unreadable.")
                    }
                }
            }
        }
    }

    EditoraCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "No-API Mode (AI Studio Web)",
                color = EditoraTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            StatusPill(
                text = if (hasRefAudio) "Ref Audio: On" else "Ref Audio: Off",
                tone = if (hasRefAudio) PillTone.READY else PillTone.NEUTRAL
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = EditoraSurface2,
            border = BorderStroke(1.dp, EditoraBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "1. Copy Prompt Pack (includes app runtime SDK, dynamic MediaPipe/EdgeSAM rules & aligned clip names).\n" +
                        "2. Generate script in Google AI Studio Web with your video/media.\n" +
                        "3. Upload Ref Video in Step 1 ONLY if you want its audio; otherwise skip Step 1, paste/upload script below & Run Offline.",
                    color = EditoraTextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                if (hasRefAudio && refName.isNotBlank()) {
                    Text(
                        text = "Active Ref Audio Source: $refName",
                        color = EditoraAccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditoraButton(
                text = "Copy AI Studio Prompt Pack",
                onClick = {
                    val pack = onBuildPromptPack()
                    if (pack.isNotBlank()) {
                        clipboard.setText(AnnotatedString(pack))
                        onShowToast("Prompt Pack copied! Paste into AI Studio Web.")
                    }
                },
                tone = EditoraButtonTone.SECONDARY,
                testTag = "copy_external_prompt_button",
                modifier = Modifier.weight(1f)
            )
            EditoraButton(
                text = "Upload .txt / .js",
                onClick = {
                    pickScriptLauncher.launch(arrayOf("text/*", "application/javascript", "*/*"))
                },
                tone = EditoraButtonTone.SECONDARY,
                testTag = "upload_external_script_button"
            )
        }

        EditoraTextField(
            value = scriptText,
            onValueChange = onScriptChanged,
            placeholder = "Paste script from Google AI Studio Web here (supports ===JSON_RESULT=== + ===CODE_RESULT=== or raw JS)...",
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            testTag = "external_script_input"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditoraButton(
                text = "Run Script Offline (No API)",
                onClick = onRunOffline,
                enabled = !isBusy && scriptText.isNotBlank(),
                testTag = "run_external_script_button",
                modifier = Modifier.weight(1f)
            )
            if (scriptText.isNotBlank()) {
                EditoraButton(
                    text = "Clear",
                    onClick = { onScriptChanged("") },
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "clear_external_script_button"
                )
            }
        }
    }
}