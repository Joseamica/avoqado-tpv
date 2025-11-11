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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CustomKeyboard - Teclado numérico reutilizable
 *
 * Diseño inspirado en AvoqadoPOS pero adaptado al dark theme.
 *
 * Layout:
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
 */
@Composable
fun CustomKeyboard(
    modifier: Modifier = Modifier,
    onNumberClick: (Int) -> Unit,
    onDecimalClick: () -> Unit,
    onClearClick: () -> Unit,
    onBackspaceClick: () -> Unit,
    onConfirmClick: () -> Unit
) {
    val keys = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
        listOf(-1, 0, -2) // -1 = C, -2 = .
    )

    Row(
        modifier = modifier.height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left side: number grid
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        when (key) {
                            -1 -> KeyboardButton(
                                modifier = Modifier.size(80.dp),
                                text = "C",
                                onClick = onClearClick
                            )
                            -2 -> KeyboardButton(
                                modifier = Modifier.size(80.dp),
                                text = ".",
                                onClick = onDecimalClick
                            )
                            else -> KeyboardButton(
                                modifier = Modifier.size(80.dp),
                                text = key.toString(),
                                onClick = { onNumberClick(key) }
                            )
                        }
                    }
                }
            }
        }

        // Right side: action buttons (backspace + confirm)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Backspace button
            KeyboardButton(
                modifier = Modifier
                    .height(80.dp)
                    .width(100.dp),
                icon = Icons.AutoMirrored.Filled.Backspace,
                onClick = onBackspaceClick
            )

            // Confirm button (takes remaining space)
            KeyboardButton(
                modifier = Modifier
                    .width(100.dp)
                    .weight(1f),
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
@Composable
private fun KeyboardButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isConfirm: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isConfirm)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
            .border(
                width = if (isConfirm) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),  // Increased from 0.3f for better visibility
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            text?.let {
                Text(
                    text = it,
                    fontSize = 24.sp,
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun CustomKeyboardPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Text(
                text = "Teclado Numérico",
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
