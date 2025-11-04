package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Text Field
 *
 * Standard text input with consistent styling and validation support
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes
 * @param modifier Modifier for customization
 * @param label Field label
 * @param placeholder Placeholder text
 * @param leadingIcon Optional leading icon
 * @param trailingIcon Optional trailing icon
 * @param supportingText Helper text shown below field
 * @param errorMessage Error message (shows field in error state if not null)
 * @param enabled Whether field is enabled
 * @param readOnly Whether field is read-only
 * @param singleLine Whether field is single line
 * @param maxLines Maximum number of lines
 * @param keyboardOptions Keyboard configuration
 * @param keyboardActions Keyboard actions
 * @param isPassword Whether this is a password field (with show/hide toggle)
 * @param showClearButton Whether to show clear button when field has text
 */
@Composable
fun AvoqadoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    supportingText: String? = null,
    errorMessage: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    isPassword: Boolean = false,
    showClearButton: Boolean = false
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val isError = errorMessage != null

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        trailingIcon = {
            when {
                // Password visibility toggle
                isPassword -> {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = if (passwordVisible) {
                                "Hide password"
                            } else {
                                "Show password"
                            }
                        )
                    }
                }
                // Clear button
                showClearButton && value.isNotEmpty() && enabled && !readOnly -> {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear text"
                        )
                    }
                }
                // Custom trailing icon
                trailingIcon != null -> {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        supportingText = {
            if (isError) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error
                )
            } else if (supportingText != null) {
                Text(text = supportingText)
            }
        },
        isError = isError,
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            errorContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoTextFieldPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space3)
        ) {
            // Basic text field
            var basicText by remember { mutableStateOf("") }
            AvoqadoTextField(
                value = basicText,
                onValueChange = { basicText = it },
                label = "Email",
                placeholder = "Enter your email"
            )

            // Text field with leading icon
            var emailText by remember { mutableStateOf("") }
            AvoqadoTextField(
                value = emailText,
                onValueChange = { emailText = it },
                label = "Email",
                placeholder = "user@example.com",
                leadingIcon = Icons.Default.Email,
                showClearButton = true
            )

            // Password field
            var passwordText by remember { mutableStateOf("") }
            AvoqadoTextField(
                value = passwordText,
                onValueChange = { passwordText = it },
                label = "Password",
                placeholder = "Enter password",
                leadingIcon = Icons.Default.Lock,
                isPassword = true,
                supportingText = "At least 8 characters"
            )

            // Text field with error
            var errorText by remember { mutableStateOf("invalid@") }
            AvoqadoTextField(
                value = errorText,
                onValueChange = { errorText = it },
                label = "Email",
                leadingIcon = Icons.Default.Email,
                errorMessage = "Invalid email format",
                showClearButton = true
            )

            // Disabled field
            AvoqadoTextField(
                value = "Disabled input",
                onValueChange = {},
                label = "Disabled Field",
                enabled = false
            )

            // Multiline field
            var multilineText by remember { mutableStateOf("") }
            AvoqadoTextField(
                value = multilineText,
                onValueChange = { multilineText = it },
                label = "Notes",
                placeholder = "Enter notes...",
                singleLine = false,
                maxLines = 4,
                supportingText = "Additional information"
            )
        }
    }
}

@Preview(showBackground = true, name = "Password Field States")
@Composable
private fun PasswordFieldPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space3)
        ) {
            // Empty password
            var emptyPassword by remember { mutableStateOf("") }
            AvoqadoTextField(
                value = emptyPassword,
                onValueChange = { emptyPassword = it },
                label = "Password",
                leadingIcon = Icons.Default.Lock,
                isPassword = true,
                supportingText = "Enter your password"
            )

            // Filled password
            var filledPassword by remember { mutableStateOf("MySecurePassword123") }
            AvoqadoTextField(
                value = filledPassword,
                onValueChange = { filledPassword = it },
                label = "Password",
                leadingIcon = Icons.Default.Lock,
                isPassword = true
            )

            // Password with error
            var errorPassword by remember { mutableStateOf("12") }
            AvoqadoTextField(
                value = errorPassword,
                onValueChange = { errorPassword = it },
                label = "Password",
                leadingIcon = Icons.Default.Lock,
                isPassword = true,
                errorMessage = "Password must be at least 8 characters"
            )
        }
    }
}
