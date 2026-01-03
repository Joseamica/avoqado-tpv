package com.jaac.avoqado_tpv.features.authentication.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PIN Display Component
 *
 * Visual display for PIN entry that supports variable length PINs (4-10 digits).
 * This is a display-only component (not a TextField) - input comes from PinPad.
 *
 * **Features:**
 * - Displays PIN as masked (•) or visible digits
 * - Toggle button to show/hide PIN
 * - Character counter always visible (e.g., "6/10")
 * - Error state styling
 *
 * **Design:**
 * - Replaces the old PinIndicator (4 fixed circles)
 * - Supports 4-10 digit PINs
 * - Follows Material 3 design guidelines
 *
 * **Usage:**
 * ```kotlin
 * PinDisplay(
 *     pin = "123456",
 *     maxLength = 10,
 *     isError = false
 * )
 * ```
 *
 * @param pin Current PIN value (0-maxLength characters)
 * @param modifier Modifier for the display container
 * @param maxLength Maximum PIN length (default 10)
 * @param isError Whether to show error styling
 */
@Composable
fun PinDisplay(
    pin: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 10,
    isError: Boolean = false
) {
    var showPin by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // PIN container with border
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // PIN text (masked or visible)
            Text(
                text = if (pin.isEmpty()) {
                    ""
                } else if (showPin) {
                    pin
                } else {
                    "•".repeat(pin.length)
                },
                style = MaterialTheme.typography.headlineMedium.copy(
                    letterSpacing = 8.sp
                ),
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )

            // Toggle visibility button
            IconButton(
                onClick = { showPin = !showPin },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (showPin) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (showPin) "Ocultar PIN" else "Mostrar PIN",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Character counter (always visible)
        Text(
            text = "${pin.length}/$maxLength",
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun PinDisplayEmptyPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinDisplay(
                pin = "",
                maxLength = 10
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinDisplayPartialPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinDisplay(
                pin = "1234",
                maxLength = 10
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinDisplayFullPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinDisplay(
                pin = "1234567890",
                maxLength = 10
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinDisplayErrorPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PinDisplay(
                pin = "1234",
                maxLength = 10,
                isError = true
            )
        }
    }
}
