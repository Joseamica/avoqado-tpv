package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * CustomKeyboard - Teclado numérico reutilizable
 *
 * Diseño inspirado en AvoqadoPOS pero adaptado al dark theme.
 *
 * Layout (con toggle):
 * 1 2 3   [Backspace]
 * 4 5 6   [  $/%    ]
 * 7 8 9   [  Check  ]
 * C 0 .   [         ]
 *
 * Layout (sin toggle):
 * 1 2 3   [Backspace]
 * 4 5 6   [          ]
 * 7 8 9   [  Check   ]
 * C 0 .   [          ]
 *
 * Callbacks:
 * - onNumberClick: (Int) -> Unit - Números 0-9
 * - onDecimalClick: () -> Unit - Punto decimal
 * - onClearClick: () -> Unit - Botón "C" (clear)
 * - onBackspaceClick: () -> Unit - Borrar último dígito
 * - onConfirmClick: () -> Unit - Confirmar entrada
 * - onToggleClick: (() -> Unit)? - Toggle entre $ y % (null = no mostrar botón)
 */
@Composable
fun CustomKeyboard(
    modifier: Modifier = Modifier,
    showToggle: Boolean = false,
    onNumberClick: (Int) -> Unit,
    onDecimalClick: () -> Unit,
    onClearClick: () -> Unit,
    onBackspaceClick: () -> Unit,
    onConfirmClick: () -> Unit,
    onToggleClick: (() -> Unit)? = null
) {
    // Adaptive sizing: square screens (N62 480x480) get compact buttons
    // Uses LocalConfiguration directly — safe to use outside ResponsiveScaffold
    val config = LocalConfiguration.current
    val isSquare = remember(config.screenHeightDp, config.screenWidthDp) {
        abs(config.screenHeightDp - config.screenWidthDp) < 80
    }
    val btnSize: Dp = if (isSquare) 52.dp else 80.dp
    val actionWidth: Dp = if (isSquare) 64.dp else 100.dp
    val gap: Dp = if (isSquare) 4.dp else 8.dp
    val fontSize: Int = if (isSquare) 18 else 24

    val keys = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
        listOf(-1, 0, -2) // -1 = C, -2 = .
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        // Left side: number grid
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { key ->
                        when (key) {
                            -1 -> KeyboardButton(
                                modifier = Modifier.size(btnSize),
                                text = "C",
                                fontSize = fontSize,
                                onClick = onClearClick
                            )
                            -2 -> KeyboardButton(
                                modifier = Modifier.size(btnSize),
                                text = ".",
                                fontSize = fontSize,
                                onClick = onDecimalClick
                            )
                            else -> KeyboardButton(
                                modifier = Modifier.size(btnSize),
                                text = key.toString(),
                                fontSize = fontSize,
                                onClick = { onNumberClick(key) }
                            )
                        }
                    }
                }
            }
        }

        // Right side: action buttons (backspace + toggle? + confirm)
        Column(
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            // Backspace button
            KeyboardButton(
                modifier = Modifier
                    .height(btnSize)
                    .width(actionWidth),
                icon = Icons.AutoMirrored.Filled.Backspace,
                onClick = onBackspaceClick
            )

            // Toggle button ($ / %) - only if showToggle = true
            if (showToggle && onToggleClick != null) {
                KeyboardButton(
                    modifier = Modifier
                        .height(btnSize)
                        .width(actionWidth),
                    text = "$/%",
                    fontSize = fontSize,
                    onClick = onToggleClick
                )
            }

            // Confirm button - fills remaining height
            // Total height: 4 rows * btnSize + 3 gaps
            val totalHeight = btnSize * 4 + gap * 3
            val confirmHeight = if (showToggle && onToggleClick != null) {
                totalHeight - btnSize * 2 - gap * 2
            } else {
                totalHeight - btnSize - gap
            }

            KeyboardButton(
                modifier = Modifier
                    .width(actionWidth)
                    .height(confirmHeight),
                icon = Icons.Default.Check,
                isConfirm = true,
                onClick = onConfirmClick
            )
        }
    }
}

/**
 * KeyboardButton - Botón individual del teclado
 *
 * Adaptado al dark theme de Avoqado TPV:
 * - Fondo: surface (#2A2A2A)
 * - Texto: onSurface (#FAFAFA)
 * - Borde: outline (#383838)
 * - Confirm: primary (#E8E8E8) con texto dark
 */
/**
 * ⚡ Performance: Use Surface + clickable instead of Button
 * Material3 Button has heavy ripple/state handling that causes frame drops on 1GB RAM devices
 * Surface with clickable is ~50% lighter
 */
@Composable
private fun KeyboardButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isConfirm: Boolean = false,
    fontSize: Int = 24,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .border(
                width = if (isConfirm) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isConfirm)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            text?.let {
                Text(
                    text = it,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isConfirm)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isConfirm)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Preview(name = "Teclado básico (sin toggle)", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun CustomKeyboardPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Text(
                text = "Teclado Numérico (sin toggle)",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            CustomKeyboard(
                onNumberClick = {},
                onDecimalClick = {},
                onClearClick = {},
                onBackspaceClick = {},
                onConfirmClick = {}
            )
        }
    }
}

@Preview(name = "Teclado con toggle $/% (propina)", showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun CustomKeyboardWithTogglePreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Text(
                text = "Teclado con Toggle $/% (propina personalizada)",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            CustomKeyboard(
                showToggle = true,
                onNumberClick = {},
                onDecimalClick = {},
                onClearClick = {},
                onBackspaceClick = {},
                onConfirmClick = {},
                onToggleClick = {}
            )
        }
    }
}
