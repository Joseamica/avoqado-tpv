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
    columns: Int = 2,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(buttons) { button ->
            ActionButtonItem(
                icon = button.icon,
                label = button.label,
                enabled = button.enabled,
                badge = button.badge,
                onClick = button.onClick,
                iconSize = 32.dp, // ⭐ Crisp, standard size
                spacing = sizes.spacingSmall
            )
        }
    }
}

/**
 * Static (non-lazy) Action Button Grid
 *
 * Identical visual output to [ActionButtonGrid] but uses Column+Row
 * instead of LazyVerticalGrid. This allows embedding inside a scrollable
 * parent (e.g., Column with verticalScroll) where LazyVerticalGrid can't
 * be nested due to infinite height constraints.
 *
 * Use this for screens that need pull-to-refresh over the entire content
 * area (e.g., WelcomeScreen). For 6-8 buttons, there's zero performance
 * difference vs lazy rendering.
 *
 * @param buttons List of ActionButton configurations
 * @param columns Number of columns (default 2)
 * @param modifier Modifier for the grid container
 */
@Composable
fun StaticActionButtonGrid(
    buttons: List<ActionButton>,
    columns: Int = 2,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        buttons.chunked(columns).forEach { rowButtons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowButtons.forEach { button ->
                    Box(modifier = Modifier.weight(1f)) {
                        ActionButtonItem(
                            icon = button.icon,
                            label = button.label,
                            enabled = button.enabled,
                            badge = button.badge,
                            onClick = button.onClick,
                            iconSize = 32.dp,
                            spacing = sizes.spacingSmall
                        )
                    }
                }
                repeat(columns - rowButtons.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Individual action button item
 *
 * Square/Professional POS Style:
 * - Left-aligned content
 * - Minimalist (White background, subtle border)
 * - Moderate rounding (12dp)
 * - No clutter (removed icon circle backgrounds)
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
        modifier = Modifier
            .aspectRatio(1.3f) // ⭐ Wider aspect ratio (Square style tiles)
            .fillMaxWidth(),
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), // ⭐ Professional radius (Square is ~8-12dp)
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface, // Pure white/surface
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (enabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp) // ⭐ Flat design
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp) // Generous padding
        ) {
            // Icon (Top-Left)
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier
                    .size(iconSize)
                    .align(Alignment.TopStart),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )

            // Text (Bottom-Left)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart)
            )

            // Badge overlay (Top-Right)
            if (badge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
