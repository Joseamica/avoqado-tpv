package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.presentation.InputTab

/**
 * Horizontal tab selector for the Checkout screen.
 *
 * Uses [ScrollableTabRow] because "Todos los productos" + "Configurar" do not
 * fit horizontally at PAX A910S width (360dp). The seleted tab gets bold text
 * and the indicator underline.
 */
@Composable
fun TabSelectorView(
    selectedTab: InputTab,
    onTabSelected: (InputTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTab.ordinal,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 12.dp,
        // Transparent container so the navbar inherits the screen background
        // (avoids the muted-gray strip that Material3 paints by default).
        containerColor = Color.Transparent,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
            )
        },
        // Compacto: bodyLarge (16sp) → labelMedium (12sp) reduce el alto del navbar
        // y permite que "Todos los productos" se vea entero sin scroll en PAX A910S.
    ) {
        InputTab.entries.forEach { tab ->
            val isSelected = selectedTab == tab
            Tab(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                text = {
                    Text(
                        text = tab.label,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Preview(name = "Tabs - Keypad selected", widthDp = 360, heightDp = 80, showBackground = true)
@Composable
private fun TabSelectorKeypadPreview() {
    AvoqadoTheme {
        TabSelectorView(selectedTab = InputTab.KEYPAD, onTabSelected = {})
    }
}

@Preview(name = "Tabs - Products selected", widthDp = 360, heightDp = 80, showBackground = true)
@Composable
private fun TabSelectorProductsPreview() {
    AvoqadoTheme {
        TabSelectorView(selectedTab = InputTab.PRODUCTS, onTabSelected = {})
    }
}
