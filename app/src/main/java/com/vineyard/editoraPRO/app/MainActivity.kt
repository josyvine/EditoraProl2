package com.vineyard.editoraPRO.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vineyard.editoraPRO.app.ui.StudioViewModel
import com.vineyard.editoraPRO.app.ui.theme.EditoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            EditoraTheme {
                val studioViewModel: StudioViewModel = viewModel()
                AppNavigation(viewModel = studioViewModel)
            }
        }
    }
}
