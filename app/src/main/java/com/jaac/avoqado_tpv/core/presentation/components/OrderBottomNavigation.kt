package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.navigation.BottomNavTab
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Bottom navigation bar for ordering system
 *
 * Hybrid design: Toast ergonomics + Square simplicity
 *
 * Features:
 * - 4 tabs (compact space usage: 56dp height)
 * - Material 3 NavigationBar
 * - Selected state with primary color
 * - Icons + labels for clarity
 *
 * Design decisions:
 * - Height: 56dp (compact, not 80dp default)
 * - 4 tabs vs Square's 5: Removed "Tab list" as redundant
 * - Menú tab combines products + check (Toast pattern)
 *
 * @param currentTab Currently selected tab
 * @param onTabSelected Callback when tab is tapped
 * @param modifier Modifier for customization
 *
 * @see BottomNavTab
 */
@Composable
fun OrderBottomNavigation(
    currentTab: BottomNavTab,
    onTabSelected: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.height(56.dp),  // Compact height (not 80dp default)
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        BottomNavTab.all.forEach { tab ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(name = "Mesas Selected", showBackground = true)
@Composable
private fun OrderBottomNavigationMesasPreview() {
    AvoqadoTheme {
        OrderBottomNavigation(
            currentTab = BottomNavTab.MESAS,
            onTabSelected = {}
        )
    }
}

@Preview(name = "Menu Selected", showBackground = true)
@Composable
private fun OrderBottomNavigationMenuPreview() {
    AvoqadoTheme {
        OrderBottomNavigation(
            currentTab = BottomNavTab.MENU,
            onTabSelected = {}
        )
    }
}

@Preview(name = "Ordenes Selected", showBackground = true)
@Composable
private fun OrderBottomNavigationOrdenesPreview() {
    AvoqadoTheme {
        OrderBottomNavigation(
            currentTab = BottomNavTab.ORDENES,
            onTabSelected = {}
        )
    }
}

@Preview(name = "Mas Selected", showBackground = true)
@Composable
private fun OrderBottomNavigationMasPreview() {
    AvoqadoTheme {
        OrderBottomNavigation(
            currentTab = BottomNavTab.MAS,
            onTabSelected = {}
        )
    }
}
