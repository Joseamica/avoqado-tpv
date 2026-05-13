package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Small dialog to pick the order-level tax percent: presets (Sin / 8% / 16%)
 * plus a freeform input for custom percents. `Apply` returns `null` when the
 * operator selects "Sin impuesto", an Int otherwise.
 */
@Composable
fun TaxPercentDialog(
    currentPercent: Int?,
    onDismiss: () -> Unit,
    onApply: (Int?) -> Unit,
) {
    var customText by remember {
        mutableStateOf(
            currentPercent?.takeIf { it !in PRESETS }?.toString().orEmpty(),
        )
    }
    var selected by remember {
        mutableStateOf<Int?>(
            when {
                currentPercent == null -> null
                currentPercent in PRESETS -> currentPercent
                else -> -1 // custom
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Agregar impuesto",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESETS.forEach { preset ->
                        val isSelected = selected == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable {
                                    selected = preset
                                    customText = ""
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$preset%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    // "Sin" option
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected == null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable {
                                selected = null
                                customText = ""
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Sin",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected == null) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = customText,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }.take(3)
                        customText = filtered
                        if (filtered.isNotEmpty()) selected = -1
                    },
                    label = { Text("Otro %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val applied = when {
                        selected == null && customText.isBlank() -> null
                        customText.isNotBlank() -> customText.toIntOrNull()?.takeIf { it in 0..100 }
                        else -> selected
                    }
                    onApply(applied)
                },
            ) { Text("Aplicar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private val PRESETS = listOf(8, 16)

@Preview(name = "Tax dialog - None", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun TaxDialogNonePreview() {
    AvoqadoTheme {
        TaxPercentDialog(currentPercent = null, onDismiss = {}, onApply = {})
    }
}

@Preview(name = "Tax dialog - 16% applied", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun TaxDialogAppliedPreview() {
    AvoqadoTheme {
        TaxPercentDialog(currentPercent = 16, onDismiss = {}, onApply = {})
    }
}
