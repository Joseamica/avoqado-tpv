package com.jaac.avoqado_tpv.features.payment.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState
import kotlinx.coroutines.delay

private val WhatsAppGreen = Color(0xFF25D366)
private val WhatsAppDarkGreen = Color(0xFF128C7E)

private data class CountryDialCode(
    val flag: String,
    val name: String,
    val code: String,
    val digits: Int,
)

private val COUNTRY_CODES = listOf(
    CountryDialCode("\uD83C\uDDF2\uD83C\uDDFD", "Mexico", "+52", 10),
    CountryDialCode("\uD83C\uDDFA\uD83C\uDDF8", "EE.UU.", "+1", 10),
    CountryDialCode("\uD83C\uDDE8\uD83C\uDDF4", "Colombia", "+57", 10),
    CountryDialCode("\uD83C\uDDE6\uD83C\uDDF7", "Argentina", "+54", 10),
    CountryDialCode("\uD83C\uDDE8\uD83C\uDDF1", "Chile", "+56", 9),
    CountryDialCode("\uD83C\uDDF5\uD83C\uDDEA", "Peru", "+51", 9),
)

/**
 * Dialog for sending payment receipt via WhatsApp.
 *
 * Includes customer search (matching EmailReceiptDialog pattern) — tap a customer to
 * auto-fill their phone number. Also supports manual phone entry with country code picker.
 *
 * Sends the full phone (countryCode + digits) to the callback.
 */
@Composable
fun WhatsAppReceiptDialog(
    onDismiss: () -> Unit,
    onSend: (fullPhone: String) -> Unit,
    isLoading: Boolean = false,
    showCustomerSearch: Boolean = true,
    customerSearchState: CustomerSearchState = CustomerSearchState.Idle,
    recentCustomers: List<Customer> = emptyList(),
    isLoadingRecentCustomers: Boolean = false,
    onSearchCustomer: (String) -> Unit = {},
    onLoadRecentCustomers: () -> Unit = {},
    onResetSearch: () -> Unit = {},
) {
    var phoneDigits by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf(COUNTRY_CODES.first()) }
    var showCountryDropdown by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val isValidPhone = phoneDigits.length == selectedCountry.digits

    // Load recent customers when dialog opens
    LaunchedEffect(Unit) {
        if (showCustomerSearch) onLoadRecentCustomers()
    }

    // Debounce customer search (300ms)
    LaunchedEffect(searchQuery) {
        if (showCustomerSearch && searchQuery.length >= 2) {
            delay(300)
            onSearchCustomer(searchQuery)
        } else if (searchQuery.isEmpty()) {
            onResetSearch()
        }
    }

    // Cleanup on dismiss
    DisposableEffect(Unit) {
        onDispose { onResetSearch() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val focusManager = LocalFocusManager.current

        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    },
            ) {
                // ── Green header bar ─────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WhatsAppGreen)
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Phone icon in white circle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enviar recibo por WhatsApp",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }

                // ── Content ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ── Customer search ──────────────────────────────
                    if (showCustomerSearch) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Buscar cliente") },
                            placeholder = { Text("Nombre, telefono o correo") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingIcon = {
                                if (customerSearchState is CustomerSearchState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { focusManager.clearFocus() },
                            ),
                            singleLine = true,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                        )

                        // Customer results
                        val customersToShow = when {
                            searchQuery.length >= 2 -> {
                                when (val state = customerSearchState) {
                                    is CustomerSearchState.Success -> state.customers
                                    else -> emptyList()
                                }
                            }
                            else -> recentCustomers
                        }

                        val showSearchLoading = isLoadingRecentCustomers || customerSearchState is CustomerSearchState.Loading

                        if (customersToShow.isNotEmpty()) {
                            Text(
                                text = if (searchQuery.length >= 2) "Resultados" else "Clientes recientes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 150.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                customersToShow.take(5).forEach { customer ->
                                    WhatsAppCustomerListItem(
                                        customer = customer,
                                        onClick = {
                                            customer.phone?.let { customerPhone ->
                                                val cleanPhone = customerPhone.replace(Regex("[^0-9]"), "")
                                                val matched = COUNTRY_CODES.firstOrNull { cc ->
                                                    cleanPhone.startsWith(cc.code.removePrefix("+"))
                                                }
                                                if (matched != null) {
                                                    selectedCountry = matched
                                                    phoneDigits = cleanPhone.removePrefix(matched.code.removePrefix("+"))
                                                } else {
                                                    phoneDigits = cleanPhone.takeLast(selectedCountry.digits)
                                                }
                                            }
                                            searchQuery = ""
                                            onResetSearch()
                                            focusManager.clearFocus()
                                        },
                                    )
                                }
                            }
                        } else if (showSearchLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = WhatsAppGreen,
                                )
                            }
                        } else if (searchQuery.length >= 2 && customerSearchState is CustomerSearchState.Success) {
                            Text(
                                text = "No se encontraron clientes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                textAlign = TextAlign.Center,
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    // ── Unified phone input row ──────────────────────
                    Text(
                        text = if (showCustomerSearch) "O escribe el numero" else "Numero de telefono",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Country code + phone in a single bordered row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 1.dp,
                                color = if (phoneDigits.isNotEmpty()) WhatsAppGreen
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .background(MaterialTheme.colorScheme.surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Country picker (left section)
                        Box {
                            Row(
                                modifier = Modifier
                                    .clickable(enabled = !isLoading) { showCountryDropdown = true }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = selectedCountry.flag,
                                    fontSize = 20.sp,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = selectedCountry.code,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Seleccionar pais",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            DropdownMenu(
                                expanded = showCountryDropdown,
                                onDismissRequest = { showCountryDropdown = false },
                            ) {
                                COUNTRY_CODES.forEach { country ->
                                    DropdownMenuItem(
                                        text = {
                                            Text("${country.flag}  ${country.name} (${country.code})")
                                        },
                                        onClick = {
                                            selectedCountry = country
                                            showCountryDropdown = false
                                            phoneDigits = ""
                                        },
                                    )
                                }
                            }
                        }

                        // Vertical divider
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )

                        // Phone digits input (right section)
                        BasicTextField(
                            value = phoneDigits,
                            onValueChange = { newValue ->
                                val digits = newValue.filter { it.isDigit() }
                                if (digits.length <= selectedCountry.digits) {
                                    phoneDigits = digits
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = 1.sp,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() },
                            ),
                            singleLine = true,
                            enabled = !isLoading,
                            cursorBrush = SolidColor(WhatsAppGreen),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (phoneDigits.isEmpty()) {
                                        Text(
                                            text = "${"0".repeat(selectedCountry.digits)}",
                                            style = TextStyle(
                                                fontSize = 18.sp,
                                                letterSpacing = 1.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            ),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }

                    // Digit counter
                    Text(
                        text = "${phoneDigits.length}/${selectedCountry.digits} digitos",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isValidPhone) WhatsAppGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Buttons ──────────────────────────────────────
                    // Full-width green send button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            val fullPhone = selectedCountry.code.removePrefix("+") + phoneDigits
                            onSend(fullPhone)
                        },
                        enabled = isValidPhone && !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhatsAppGreen,
                            contentColor = Color.White,
                            disabledContainerColor = WhatsAppGreen.copy(alpha = 0.3f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f),
                        ),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isLoading) "Enviando..." else "Enviar recibo",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Cancel as text button
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Cancelar",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Customer list item for WhatsApp receipt dialog.
 * Shows customer name + phone (if available). Phone indicator icon.
 */
@Composable
private fun WhatsAppCustomerListItem(
    customer: Customer,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(WhatsAppGreen.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = customer.shortName.take(2).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = WhatsAppDarkGreen,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Name + contact
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customer.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            customer.phone?.let { phone ->
                Text(
                    text = phone,
                    style = MaterialTheme.typography.bodySmall,
                    color = WhatsAppDarkGreen,
                )
            } ?: customer.email?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }

        // Phone badge
        if (customer.phone != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(WhatsAppGreen.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    tint = WhatsAppGreen,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun WhatsAppReceiptDialogPreview() {
    AvoqadoTheme {
        WhatsAppReceiptDialog(
            onDismiss = {},
            onSend = {},
            isLoading = false,
        )
    }
}

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun WhatsAppReceiptDialogLoadingPreview() {
    AvoqadoTheme {
        WhatsAppReceiptDialog(
            onDismiss = {},
            onSend = {},
            isLoading = true,
        )
    }
}
