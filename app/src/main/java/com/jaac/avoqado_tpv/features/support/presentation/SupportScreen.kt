package com.jaac.avoqado_tpv.features.support.presentation

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.ApiErrorResponse
import com.jaac.avoqado_tpv.core.data.network.TpvFeedbackRequest
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.rememberCoroutineScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// ══════════════════════════════════════════════════════════════════════
// Hilt Entry Point for ApiService
// ══════════════════════════════════════════════════════════════════════

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupportScreenEntryPoint {
    fun apiService(): ApiService
}

// ══════════════════════════════════════════════════════════════════════
// Helper Functions
// ══════════════════════════════════════════════════════════════════════

// Result sealed class for internal use
private sealed class FeedbackResult {
    object Success : FeedbackResult()
    data class Error(val message: String) : FeedbackResult()
}

/**
 * Send feedback to backend via API
 *
 * Sends bug report or feature suggestion to backend, which emails it to hola@avoqado.io.
 * Includes device information automatically for debugging purposes.
 *
 * @param apiService Retrofit API service
 * @param feedbackType "bug" or "feature"
 * @param message User's feedback message
 * @param venueSlug Current venue slug
 * @param onSuccess Callback on successful send (runs on Main thread)
 * @param onError Callback on error with error message (runs on Main thread)
 */
private suspend fun sendFeedbackToBackend(
    apiService: ApiService,
    feedbackType: String,
    message: String,
    venueSlug: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    // Execute heavy work on IO thread
    val result = withContext(Dispatchers.IO) {
        try {
            val request = TpvFeedbackRequest(
                feedbackType = feedbackType,
                message = message,
                venueSlug = venueSlug,
                appVersion = BuildConfig.VERSION_NAME,
                buildVersion = BuildConfig.VERSION_CODE.toString(),
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER
            )

            val response = apiService.sendFeedback(request)

            if (response.isSuccessful && response.body()?.success == true) {
                FeedbackResult.Success
            } else {
                // Parse error response body to extract validation errors
                // NOTE: response.errorBody()?.string() is blocking I/O, so it MUST be on Dispatchers.IO
                val errorMsg = try {
                    val errorBody = response.errorBody()?.string()
                    if (errorBody != null) {
                        val gson = com.google.gson.Gson()
                        val errorResponse = gson.fromJson(errorBody, ApiErrorResponse::class.java)

                        // Extract specific validation error if present
                        val msg = errorResponse.message ?: "Error desconocido"

                        // If it's a validation error, extract the user-friendly message
                        if (msg.contains("Validation failed:") && msg.contains("body.message:")) {
                            // Extract message after "body.message: "
                            val validationMsg = msg.substringAfter("body.message: ").substringBefore(",")
                            validationMsg.ifEmpty { msg }
                        } else {
                            msg
                        }
                    } else {
                        response.body()?.message ?: "Error desconocido"
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse error response")
                    response.body()?.message ?: "Error desconocido"
                }
                
                Timber.w("⚠️ Failed to send feedback: $errorMsg")
                FeedbackResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error sending feedback")
            FeedbackResult.Error("No se pudo enviar el feedback. Verifica tu conexión.")
        }
    }

    // Handle result on Main thread
    when (result) {
        is FeedbackResult.Success -> {
            Timber.i("✅ Feedback sent successfully")
            onSuccess()
        }
        is FeedbackResult.Error -> {
            onError(result.message)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Main Screen
// ══════════════════════════════════════════════════════════════════════

/**
 * Support Screen
 *
 * Provides help and support resources for TPV users:
 * - Contact information (email, phone, WhatsApp)
 * - App version and device information
 * - Quick actions (report bug, request feature)
 * - FAQ section
 * - Documentation links
 *
 * Pattern: Toast POS + Square Terminal help screens
 */
@Composable
fun SupportScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val secureStorage = remember {
        SecureStorage(context.applicationContext)
    }
    val venueSlug = secureStorage.getVenueSlug() ?: "unknown"

    // Get ApiService via Hilt EntryPoint
    val apiService = remember {
        val appContext = context.applicationContext
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            SupportScreenEntryPoint::class.java
        )
        hiltEntryPoint.apiService()
    }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Contact dialogs
    var showEmailDialog by remember { mutableStateOf(false) }
    var showPhoneDialog by remember { mutableStateOf(false) }
    var showWhatsAppDialog by remember { mutableStateOf(false) }

    // Feedback dialogs
    var showBugReportDialog by remember { mutableStateOf(false) }
    var showFeatureSuggestionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Soporte",
                onNavigationClick = onBack
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false  // ✅ FIXED: LazyColumn handles its own scrolling
        ) {
            val sizes = LocalResponsiveSizes.current

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(sizes.paddingScreen),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingLarge)
            ) {
                // Contact Section
                item {
                    SectionHeader(title = "Contacto")
                }

                item {
                    ContactOptions(
                        onEmailClick = { showEmailDialog = true },
                        onPhoneClick = { showPhoneDialog = true },
                        onWhatsAppClick = { showWhatsAppDialog = true }
                    )
                }

                // Quick Actions
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(title = "Acciones Rápidas")
                }

                item {
                    QuickActions(
                        onReportBugClick = { showBugReportDialog = true },
                        onRequestFeatureClick = { showFeatureSuggestionDialog = true }
                    )
                }

                // App Information
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(title = "Información de la App")
                }

                item {
                    AppInformation()
                }

                // FAQ Section
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(title = "Preguntas Frecuentes")
                }

                items(faqItems) { faq ->
                    FAQItem(faq = faq)
                }

                // Documentation Links
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(title = "Recursos")
                }

                item {
                    DocumentationLinks(
                        onUserGuideClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://docs.avoqado.io/tpv/user-guide")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Browser not available - ignore gracefully
                            }
                        },
                        onVideoTutorialsClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://www.youtube.com/@avoqado")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Browser not available - ignore gracefully
                            }
                        }
                    )
                }
            }
        }
    }

    // Contact Info Dialogs
    if (showEmailDialog) {
        ContactInfoDialog(
            title = "Contacto por Email",
            icon = Icons.Default.Email,
            message = "Escríbenos a: hola@avoqado.io",
            onDismiss = { showEmailDialog = false }
        )
    }

    if (showPhoneDialog) {
        ContactInfoDialog(
            title = "Contacto Telefónico",
            icon = Icons.Default.Phone,
            message = "Para comunicarte con nosotros marca a +52 56 400 70001",
            onDismiss = { showPhoneDialog = false }
        )
    }

    if (showWhatsAppDialog) {
        ContactInfoDialog(
            title = "Contacto por WhatsApp",
            icon = Icons.Default.Chat,
            message = "Para comunicarte con nosotros escribe a +52 56 400 70001",
            onDismiss = { showWhatsAppDialog = false }
        )
    }

    // Feedback Dialogs
    if (showBugReportDialog) {
        FeedbackDialog(
            title = "Reportar Bug",
            subtitle = "Describe el problema que encontraste",
            icon = Icons.Default.BugReport,
            iconTint = MaterialTheme.avoqadoColors.statusError,
            onDismiss = { showBugReportDialog = false },
            onSend = { message, onError, onSuccess ->
                coroutineScope.launch {
                    sendFeedbackToBackend(
                        apiService = apiService,
                        feedbackType = "bug",
                        message = message,
                        venueSlug = venueSlug,
                        onSuccess = {
                            showBugReportDialog = false
                            Timber.i("✅ Bug report sent successfully")
                            onSuccess()
                            // Show success snackbar after dialog closes
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "✅ Reporte enviado correctamente",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        onError = { error ->
                            Timber.e("❌ Failed to send bug report: $error")
                            // Show error in dialog, not snackbar
                            onError(error)
                        }
                    )
                }
            }
        )
    }

    if (showFeatureSuggestionDialog) {
        FeedbackDialog(
            title = "Sugerir Función",
            subtitle = "Comparte tu idea para mejorar Avoqado TPV",
            icon = Icons.Default.Lightbulb,
            iconTint = MaterialTheme.avoqadoColors.statusWarning,
            onDismiss = { showFeatureSuggestionDialog = false },
            onSend = { message, onError, onSuccess ->
                coroutineScope.launch {
                    sendFeedbackToBackend(
                        apiService = apiService,
                        feedbackType = "feature",
                        message = message,
                        venueSlug = venueSlug,
                        onSuccess = {
                            showFeatureSuggestionDialog = false
                            Timber.i("✅ Feature suggestion sent successfully")
                            onSuccess()
                            // Show success snackbar after dialog closes
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "✅ Sugerencia enviada correctamente",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        onError = { error ->
                            Timber.e("❌ Failed to send feature suggestion: $error")
                            // Show error in dialog, not snackbar
                            onError(error)
                        }
                    )
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    val sizes = LocalResponsiveSizes.current

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = sizes.spacingSmall)
    )
}

@Composable
private fun ContactOptions(
    onEmailClick: () -> Unit,
    onPhoneClick: () -> Unit,
    onWhatsAppClick: () -> Unit
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
    ) {
        ContactCard(
            icon = Icons.Default.Email,
            title = "Email",
            subtitle = "soporte@avoqado.io",
            onClick = onEmailClick
        )

        ContactCard(
            icon = Icons.Default.Phone,
            title = "Teléfono",
            subtitle = "+52 55 1234 5678",
            onClick = onPhoneClick
        )

        ContactCard(
            icon = Icons.Default.Chat,
            title = "WhatsApp",
            subtitle = "Chatea con nosotros",
            onClick = onWhatsAppClick
        )
    }
}

@Composable
private fun ContactCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.paddingScreen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickActions(
    onReportBugClick: () -> Unit,
    onRequestFeatureClick: () -> Unit
) {
    val sizes = LocalResponsiveSizes.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
    ) {
        OutlinedButton(
            onClick = onReportBugClick,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reportar Bug")
        }

        OutlinedButton(
            onClick = onRequestFeatureClick,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sugerir Función")
        }
    }
}

@Composable
private fun AppInformation() {
    val sizes = LocalResponsiveSizes.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.paddingScreen),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
        ) {
            InfoRow(label = "Versión", value = BuildConfig.VERSION_NAME)
            InfoRow(label = "Build", value = BuildConfig.VERSION_CODE.toString())
            InfoRow(label = "Android", value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow(label = "Dispositivo", value = "${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun FAQItem(faq: FAQ) {
    var expanded by remember { mutableStateOf(false) }
    val sizes = LocalResponsiveSizes.current

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.paddingScreen)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = faq.question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(sizes.spacingSmall))
                Text(
                    text = faq.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DocumentationLinks(
    onUserGuideClick: () -> Unit,
    onVideoTutorialsClick: () -> Unit
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
    ) {
        OutlinedCard(
            onClick = onUserGuideClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(sizes.paddingScreen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Guía de Usuario",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        OutlinedCard(
            onClick = onVideoTutorialsClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(sizes.paddingScreen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Video Tutoriales",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Dialog Components
// ══════════════════════════════════════════════════════════════════════

/**
 * Contact Info Dialog
 *
 * Shows informational dialog with contact details
 * (phone, email, WhatsApp) without opening external apps.
 *
 * Hardware PAX terminals don't have external apps installed,
 * so we show the info for the user to copy manually.
 */
@Composable
private fun ContactInfoDialog(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text(title) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Entendido")
            }
        }
    )
}

/**
 * Feedback Dialog
 *
 * Fullscreen dialog with TextField for bug reports
 * and feature suggestions. Sends email to hola@avoqado.io
 * with venue slug and device info.
 */
@Composable
private fun FeedbackDialog(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    onDismiss: () -> Unit,
    onSend: (message: String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit
) {
    var message by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Clear error when user types
    LaunchedEffect(message) {
        if (errorMessage != null) {
            errorMessage = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,  // Allow custom width
            decorFitsSystemWindows = false     // Handle keyboard properly
        )
    ) {
        // ✅ Capture FocusManager INSIDE Dialog (not outside)
        val focusManager = LocalFocusManager.current

        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)  // Leave space for keyboard
                .imePadding(),  // ✅ Add IME padding
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    // ✅ Detect taps outside TextField to hide keyboard
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                        })
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon + Title
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(40.dp)
                    )

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Subtitle
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // TextField
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text("Describe el problema o sugerencia...") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                // ✅ Clear focus instead of just hiding keyboard
                                focusManager.clearFocus()
                            }
                        ),
                        maxLines = 15,
                        singleLine = false,
                        isError = errorMessage != null
                    )

                    // Error message in red
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading
                    ) {
                        Text("Cancelar")
                    }

                    Button(
                        onClick = {
                            // Validate locally first
                            when {
                                message.isBlank() -> {
                                    errorMessage = "El mensaje no puede estar vacío"
                                }
                                message.length < 10 -> {
                                    errorMessage = "El mensaje debe tener al menos 10 caracteres"
                                }
                                else -> {
                                    // Clear focus before sending
                                    focusManager.clearFocus()
                                    isLoading = true

                                    onSend(
                                        message,
                                        { error ->
                                            isLoading = false
                                            errorMessage = error
                                        },
                                        {
                                            isLoading = false
                                            // Dialog will be dismissed by parent
                                        }
                                    )
                                }
                            }
                        },
                        enabled = message.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Enviar")
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Data Models
// ══════════════════════════════════════════════════════════════════════

data class FAQ(
    val question: String,
    val answer: String
)

private val faqItems = listOf(
    FAQ(
        question = "¿Cómo inicio un turno?",
        answer = "Ve a la pantalla de Turnos desde el menú principal y presiona 'Abrir Turno'. Ingresa el efectivo inicial en caja y confirma."
    ),
    FAQ(
        question = "¿Cómo registro un pago con tarjeta?",
        answer = "En la pantalla de pago, selecciona 'Tarjeta', ingresa el monto y sigue las instrucciones en la terminal PAX. Acerca la tarjeta al lector para pagos contactless."
    ),
    FAQ(
        question = "¿Qué hago si la terminal no responde?",
        answer = "Verifica que la terminal esté conectada a la red y tiene batería. Si el problema persiste, reinicia la terminal manteniendo presionado el botón de encendido por 5 segundos."
    ),
    FAQ(
        question = "¿Cómo cambio de usuario?",
        answer = "Presiona el botón de Configuración en la pantalla principal y selecciona 'Cambiar Usuario'. Ingresa el PIN del nuevo usuario."
    ),
    FAQ(
        question = "¿Puedo trabajar sin internet?",
        answer = "Sí, la app funciona en modo offline. Los datos se sincronizarán automáticamente cuando se restaure la conexión."
    ),
    FAQ(
        question = "¿Cómo cierro un turno?",
        answer = "Ve a Turnos → Cerrar Turno. Registra el efectivo final en caja y cualquier diferencia será calculada automáticamente."
    )
)

// ══════════════════════════════════════════════════════════════════════
// Previews
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun SupportScreenPreview() {
    AvoqadoTheme {
        SupportScreen()
    }
}
