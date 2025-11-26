package com.jaac.avoqado_tpv.features.support.presentation

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

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

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Soporte",
                onNavigationClick = onBack
            )
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
                        onEmailClick = {
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:soporte@avoqado.io")
                                    putExtra(Intent.EXTRA_SUBJECT, "Soporte TPV - ${BuildConfig.VERSION_NAME}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Email app not available - ignore gracefully
                            }
                        },
                        onPhoneClick = {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL).apply {
                                    data = Uri.parse("tel:+525512345678")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Phone app not available - ignore gracefully
                            }
                        },
                        onWhatsAppClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://wa.me/525512345678?text=Hola,%20necesito%20ayuda%20con%20Avoqado%20TPV")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // WhatsApp not installed - ignore gracefully
                            }
                        }
                    )
                }

                // Quick Actions
                item {
                    Spacer(modifier = Modifier.height(sizes.spacingMedium))
                    SectionHeader(title = "Acciones Rápidas")
                }

                item {
                    QuickActions(
                        onReportBugClick = {
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:bugs@avoqado.io")
                                    putExtra(Intent.EXTRA_SUBJECT, "Bug Report - TPV ${BuildConfig.VERSION_NAME}")
                                    putExtra(Intent.EXTRA_TEXT, """
                                        |Describe el problema:
                                        |
                                        |
                                        |Pasos para reproducir:
                                        |1.
                                        |2.
                                        |3.
                                        |
                                        |---
                                        |App Version: ${BuildConfig.VERSION_NAME}
                                        |Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                                        |Device: ${Build.MANUFACTURER} ${Build.MODEL}
                                    """.trimMargin())
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Email app not available - ignore gracefully
                            }
                        },
                        onRequestFeatureClick = {
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:features@avoqado.io")
                                    putExtra(Intent.EXTRA_SUBJECT, "Feature Request - TPV")
                                    putExtra(Intent.EXTRA_TEXT, "Describe la funcionalidad que necesitas:\n\n")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Email app not available - ignore gracefully
                            }
                        }
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
        answer = "Presiona el botón de menú (☰) en la esquina superior izquierda y selecciona 'Cambiar Usuario'. Ingresa el PIN del nuevo usuario."
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
