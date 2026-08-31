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
    /**
     * Identificador ESTABLE del botón, independiente del texto que ve el cajero.
     *
     * 🔴 Existía un filtro `removeAll { it.label == "Turnos" }` en `WelcomeScreen`: el menú se armaba
     * con un texto y se recortaba comparando ESE MISMO texto. Renombrar el botón en un solo sitio
     * —justo lo que pasó al pasar a «Turnos de caja» (2026-08-27)— dejaba el botón visible en venues
     * que tienen el sistema de turnos APAGADO, sin que nada fallara ni avisara. El texto es para el
     * cajero; para la lógica está esta llave.
     */
    val id: String? = null,
    val enabled: Boolean = true,
    val badge: String? = null,
    val onClick: () -> Unit
)
