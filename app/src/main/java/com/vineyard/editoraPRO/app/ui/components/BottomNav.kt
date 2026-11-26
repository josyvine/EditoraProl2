package com.vineyard.editoraPRO.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.MainTab
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted

private data class NavTabItem(
    val tab: MainTab,
    val label: String,
    val icon: ImageVector,
    val tag: String
)

private val NAV_ITEMS = listOf(
    NavTabItem(MainTab.STUDIO, "Studio", Icons.Outlined.Videocam, "nav_tab_studio"),
    NavTabItem(MainTab.AI_LOGIC, "AI Logic", Icons.Outlined.Code, "nav_tab_ai_logic"),
    NavTabItem(MainTab.SETTINGS, "Settings", Icons.Outlined.Settings, "nav_tab_settings")
)

@Composable
fun BottomNavBar(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(EditoraSurface.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        HorizontalDivider(thickness = 1.dp, color = EditoraBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NAV_ITEMS.forEach { item ->
                val selected = activeTab == item.tab
                val tint = if (selected) EditoraAccentCyan else EditoraTextMuted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(item.tag)
                        .clickable(
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = EditoraAccentCyan)
                        ) { onSelectTab(item.tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun SideNavRail(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier.width(78.dp),
        containerColor = EditoraSurface,
        contentColor = EditoraTextMuted
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        NAV_ITEMS.forEach { item ->
            val selected = activeTab == item.tab
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(item.tab) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = EditoraAccentCyan,
                    selectedTextColor = EditoraAccentCyan,
                    unselectedIconColor = EditoraTextMuted,
                    unselectedTextColor = EditoraTextMuted,
                    indicatorColor = EditoraAccentCyan.copy(alpha = 0.14f)
                ),
                modifier = Modifier.testTag("${item.tag}_rail")
            )
        }
    }
}
