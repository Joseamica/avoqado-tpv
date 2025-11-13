package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Data class representing an action button in the grid
 *
 * @param icon The Material icon to display
 * @param label The text label below the icon
 * @param enabled Whether the button is clickable (false for future features)
 * @param badge Optional badge text like "Próximamente" or "Nuevo"
 * @param onClick Callback when button is clicked
 */
data class ActionButton(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val badge: String? = null,
    val onClick: () -> Unit
)
