package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Reusable action button grid component
 *
 * Displays a 3-column grid of square action buttons with icon + text layout.
 * Supports disabled states for future features with optional badge overlay.
 *
 * Usage:
 * ```
 * ActionButtonGrid(
 *     buttons = listOf(
 *         ActionButton(
 *             icon = Icons.Default.CreditCard,
 *             label = "Pago rápido",
 *             onClick = { navController.navigate(NavRoute.Payment) }
 *         ),
 *         ActionButton(
 *             icon = Icons.Default.Assessment,
 *             label = "Resumen",
 *             enabled = false,
 *             badge = "Próximamente"
 *         )
 *     )
 * )
 * ```
 *
 * @param buttons List of ActionButton configurations
 * @param modifier Modifier for the grid container
 */
@Composable
fun ActionButtonGrid(
    buttons: List<ActionButton>,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = sizes.paddingScreen),  // ⭐ Reduced horizontal padding (was sizes.paddingScreen)
        horizontalArrangement = Arrangement.spacedBy(8.dp),  // ⭐ Reduced spacing between buttons (was sizes.spacingMedium)
        verticalArrangement = Arrangement.spacedBy(8.dp)     // ⭐ Reduced spacing between buttons (was sizes.spacingMedium)
    ) {
        items(buttons) { button ->
            ActionButtonItem(
                icon = button.icon,
                label = button.label,
                enabled = button.enabled,
                badge = button.badge,
                onClick = button.onClick,
                iconSize = sizes.iconSizeLarge,
                spacing = sizes.spacingSmall
            )
        }
    }
}

/**
 * Individual action button item
 *
 * Square card with icon on top, text below, and optional badge overlay.
 */
@Composable
private fun ActionButtonItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    badge: String?,
    onClick: () -> Unit,
    iconSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp
) {
    Card(
        onClick = if (enabled) onClick else { {} },
        modifier = Modifier.aspectRatio(1f), // Square shape
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 4.dp,
            disabledElevation = 0.dp
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Main content: Icon + Text
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )

                Spacer(modifier = Modifier.height(spacing))

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Badge overlay (top-center, absolute positioned)
            if (badge != null) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopCenter)  // ⭐ Changed from TopEnd to TopCenter (centered at top edge)
                        .padding(top = 4.dp),        // ⭐ Reduced padding, positioned closer to edge
                    containerColor = MaterialTheme.colorScheme.tertiary
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),  // ⭐ Extra small text (9sp for compact badge)
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),  // ⭐ Minimal padding for compact look
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ============================================================
// PREVIEW
// ============================================================

@Preview(showBackground = true, widthDp = 1024, heightDp = 600)
@Composable
private fun ActionButtonGridPreview() {
    AvoqadoTheme {
        ActionButtonGrid(
            buttons = listOf(
                ActionButton(
                    icon = Icons.Default.CreditCard,
                    label = "Pago rápido",
                    enabled = true,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Assessment,
                    label = "Resumen",
                    enabled = false,
                    badge = "Próximamente",
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Schedule,
                    label = "Turnos",
                    enabled = false,
                    badge = "Próximamente",
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Receipt,
                    label = "Pagos",
                    enabled = true,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Restaurant,
                    label = "Órdenes",
                    enabled = false,
                    badge = "Próximamente",
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.History,
                    label = "Historial",
                    enabled = false,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.BarChart,
                    label = "Reportes",
                    enabled = false,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Help,
                    label = "Soporte",
                    enabled = true,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Settings,
                    label = "Configuración",
                    enabled = true,
                    onClick = {}
                )
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "Small Device")
@Composable
private fun ActionButtonGridPreviewSmall() {
    AvoqadoTheme {
        ActionButtonGrid(
            buttons = listOf(
                ActionButton(
                    icon = Icons.Default.CreditCard,
                    label = "Pago rápido",
                    enabled = true,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Assessment,
                    label = "Resumen",
                    enabled = true,
                    onClick = {}
                ),
                ActionButton(
                    icon = Icons.Default.Schedule,
                    label = "Turnos",
                    enabled = false,
                    badge = "Próximamente",
                    onClick = {}
                )
            )
        )
    }
}
