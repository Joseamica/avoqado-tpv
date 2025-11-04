package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Size
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Primary Button
 *
 * Standard filled button for primary actions
 *
 * @param text Button label
 * @param onClick Click handler
 * @param modifier Modifier for customization
 * @param enabled Whether button is enabled
 * @param loading Whether to show loading indicator
 * @param icon Optional leading icon
 * @param fullWidth Whether button should fill max width
 */
@Composable
fun AvoqadoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fullWidth: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(Size.ButtonHeight)
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier),
        enabled = enabled && !loading,
        contentPadding = PaddingValues(
            horizontal = Spacing.Space4,
            vertical = Spacing.Space3
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Size.IconMedium),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(Size.IconMedium)
                    )
                    Spacer(Modifier.width(Spacing.Space2))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Avoqado Secondary Button
 *
 * Outlined button for secondary actions
 *
 * @param text Button label
 * @param onClick Click handler
 * @param modifier Modifier for customization
 * @param enabled Whether button is enabled
 * @param loading Whether to show loading indicator
 * @param icon Optional leading icon
 * @param fullWidth Whether button should fill max width
 */
@Composable
fun AvoqadoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fullWidth: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(Size.ButtonHeight)
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier),
        enabled = enabled && !loading,
        contentPadding = PaddingValues(
            horizontal = Spacing.Space4,
            vertical = Spacing.Space3
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Size.IconMedium),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(Size.IconMedium)
                    )
                    Spacer(Modifier.width(Spacing.Space2))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Avoqado Text Button
 *
 * Low-emphasis button for tertiary actions
 *
 * @param text Button label
 * @param onClick Click handler
 * @param modifier Modifier for customization
 * @param enabled Whether button is enabled
 * @param icon Optional leading icon
 */
@Composable
fun AvoqadoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(Size.ButtonHeight),
        enabled = enabled,
        contentPadding = PaddingValues(
            horizontal = Spacing.Space4,
            vertical = Spacing.Space3
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Size.IconMedium)
                )
                Spacer(Modifier.width(Spacing.Space2))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoButtonPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            // Primary button
            AvoqadoButton(
                text = "Process Payment",
                onClick = {}
            )

            // Primary button with icon
            AvoqadoButton(
                text = "Add Item",
                onClick = {},
                icon = Icons.Default.Add
            )

            // Primary button loading
            AvoqadoButton(
                text = "Processing...",
                onClick = {},
                loading = true
            )

            // Primary button disabled
            AvoqadoButton(
                text = "Process Payment",
                onClick = {},
                enabled = false
            )

            // Primary button full width
            AvoqadoButton(
                text = "Complete Order",
                onClick = {},
                fullWidth = true,
                icon = Icons.Default.Check
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoSecondaryButtonPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            // Secondary button
            AvoqadoSecondaryButton(
                text = "Cancel",
                onClick = {}
            )

            // Secondary button with icon
            AvoqadoSecondaryButton(
                text = "Edit Order",
                onClick = {},
                icon = Icons.Default.Add
            )

            // Secondary button loading
            AvoqadoSecondaryButton(
                text = "Loading...",
                onClick = {},
                loading = true
            )

            // Secondary button disabled
            AvoqadoSecondaryButton(
                text = "Cancel",
                onClick = {},
                enabled = false
            )

            // Secondary button full width
            AvoqadoSecondaryButton(
                text = "View Details",
                onClick = {},
                fullWidth = true
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTextButtonPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            // Text button
            AvoqadoTextButton(
                text = "Skip",
                onClick = {}
            )

            // Text button with icon
            AvoqadoTextButton(
                text = "Learn More",
                onClick = {},
                icon = Icons.Default.Add
            )

            // Text button disabled
            AvoqadoTextButton(
                text = "Skip",
                onClick = {},
                enabled = false
            )
        }
    }
}

@Preview(showBackground = true, name = "All Button Types")
@Composable
private fun AllButtonTypesPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space3)
        ) {
            Text(
                text = "Button Variants",
                style = MaterialTheme.typography.headlineSmall
            )

            AvoqadoButton(
                text = "Primary Button",
                onClick = {},
                icon = Icons.Default.Check
            )

            AvoqadoSecondaryButton(
                text = "Secondary Button",
                onClick = {},
                icon = Icons.Default.Add
            )

            AvoqadoTextButton(
                text = "Text Button",
                onClick = {}
            )
        }
    }
}
