package com.jaac.avoqado_tpv.features.authentication.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PIN Pad Component
 *
 * Professional numeric keypad for PIN entry, following Square POS and Toast POS patterns.
 *
 * **Design:**
 * - 3x4 grid layout (1-9, Clear, 0, Backspace)
 * - Large touch targets (80dp) for easy use in busy environments
 * - Material3 styling with proper elevation and colors
 * - Visual feedback on press (ripple effect)
 *
 * **Usage:**
 * ```kotlin
 * PinPad(
 *     onNumberClick = { digit -> pin += digit },
 *     onBackspace = { pin = pin.dropLast(1) },
 *     onClear = { pin = "" },
 *     enabled = true
 * )
 * ```
 *
 * @param onNumberClick Callback when number button (0-9) is pressed
 * @param onBackspace Callback when backspace is pressed (remove last digit)
 * @param onClear Callback when clear is pressed (remove all digits)
 * @param enabled Whether the PIN pad is enabled (disables during loading)
 * @param modifier Modifier for the PIN pad container
 */
@Composable
fun PinPad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    enabled: Boolean = true,
    buttonSize: Dp = 80.dp,
    buttonSpacing: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(buttonSpacing)
    ) {
        // Row 1: 1 2 3
        Row(
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            PinButton(text = "1", onClick = { onNumberClick("1") }, enabled = enabled, size = buttonSize)
            PinButton(text = "2", onClick = { onNumberClick("2") }, enabled = enabled, size = buttonSize)
            PinButton(text = "3", onClick = { onNumberClick("3") }, enabled = enabled, size = buttonSize)
        }

        // Row 2: 4 5 6
        Row(
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            PinButton(text = "4", onClick = { onNumberClick("4") }, enabled = enabled, size = buttonSize)
            PinButton(text = "5", onClick = { onNumberClick("5") }, enabled = enabled, size = buttonSize)
            PinButton(text = "6", onClick = { onNumberClick("6") }, enabled = enabled, size = buttonSize)
        }

        // Row 3: 7 8 9
        Row(
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            PinButton(text = "7", onClick = { onNumberClick("7") }, enabled = enabled, size = buttonSize)
            PinButton(text = "8", onClick = { onNumberClick("8") }, enabled = enabled, size = buttonSize)
            PinButton(text = "9", onClick = { onNumberClick("9") }, enabled = enabled, size = buttonSize)
        }

        // Row 4: C 0 ⌫
        Row(
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            // Clear button
            PinButton(
                text = "C",
                onClick = onClear,
                enabled = enabled,
                isAction = true,
                size = buttonSize
            )

            // Zero button
            PinButton(text = "0", onClick = { onNumberClick("0") }, enabled = enabled, size = buttonSize)

            // Backspace button (icon)
            PinButtonIcon(
                onClick = onBackspace,
                enabled = enabled,
                isAction = true,
                size = buttonSize
            )
        }
    }
}

/**
 * Single PIN button (number)
 *
 * Large elevated button optimized for touch input.
 * Follows Material3 guidelines with proper elevation and ripple effects.
 */
@Composable
private fun PinButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isAction: Boolean = false,
    size: Dp = 80.dp,
    modifier: Modifier = Modifier
) {
    val fontSize = when {
        size < 60.dp -> 18.sp
        size < 72.dp -> 20.sp
        else -> 24.sp
    }

    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = CircleShape,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = if (isAction) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isAction) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
            disabledElevation = 0.dp
        )
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * PIN button with icon (backspace)
 */
@Composable
private fun PinButtonIcon(
    onClick: () -> Unit,
    enabled: Boolean,
    isAction: Boolean = false,
    size: Dp = 80.dp,
    modifier: Modifier = Modifier
) {
    val iconSize = when {
        size < 60.dp -> 20.dp
        size < 72.dp -> 24.dp
        else -> 28.dp
    }

    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = CircleShape,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = if (isAction) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isAction) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
            disabledElevation = 0.dp
        )
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Borrar último dígito",
            modifier = Modifier.size(iconSize)
        )
    }
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun PinPadPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Ingresa tu PIN",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PinIndicator(
                pinLength = 2,
                maxLength = 4,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PinPad(
                onNumberClick = {},
                onBackspace = {},
                onClear = {},
                enabled = true
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinPadDisabledPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Ingresa tu PIN",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PinIndicator(
                pinLength = 4,
                maxLength = 4,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PinPad(
                onNumberClick = {},
                onBackspace = {},
                onClear = {},
                enabled = false // Disabled state
            )

            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}
