package com.jaac.avoqado_tpv.features.messaging.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.messaging.presentation.TpvMessageUiModel

@Composable
fun TpvMessageDialog(
    message: TpvMessageUiModel,
    selectedSurveyOptions: Set<Int>,
    isSubmitting: Boolean,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
    onToggleSurveyOption: (Int) -> Unit,
    onSubmitSurvey: () -> Unit,
    onExecuteAction: () -> Unit
) {
    val priorityColor = when (message.priority) {
        "URGENT" -> MaterialTheme.avoqadoColors.statusCritical
        "HIGH" -> MaterialTheme.avoqadoColors.statusError
        "NORMAL" -> MaterialTheme.avoqadoColors.statusInfo
        else -> MaterialTheme.avoqadoColors.statusSuccess
    }

    Dialog(
        onDismissRequest = {
            if (!message.requiresAck) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !message.requiresAck,
            dismissOnClickOutside = !message.requiresAck,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Priority indicator bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(priorityColor)
                )

                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    // Header: Type badge + Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Priority icon
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(priorityColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (message.priority) {
                                        "URGENT", "HIGH" -> Icons.Default.Warning
                                        else -> Icons.Default.Info
                                    },
                                    contentDescription = null,
                                    tint = priorityColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Type badge
                            Text(
                                text = when (message.type) {
                                    "ANNOUNCEMENT" -> "Anuncio"
                                    "SURVEY" -> "Encuesta"
                                    "ACTION" -> "Accion"
                                    else -> message.type
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = priorityColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Close button (only if not requires ack)
                        if (!message.requiresAck) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cerrar",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Title
                    Text(
                        text = message.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Sender
                    Text(
                        text = "De: ${message.createdByName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Body
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )

                    // Survey options (only for SURVEY type)
                    if (message.type == "SURVEY" && !message.surveyOptions.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (message.surveyMultiSelect) "Selecciona una o mas opciones:" else "Selecciona una opcion:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        message.surveyOptions.forEachIndexed { index, option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleSurveyOption(index) }
                                    .background(
                                        if (index in selectedSurveyOptions)
                                            priorityColor.copy(alpha = 0.08f)
                                        else
                                            Color.Transparent
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (message.surveyMultiSelect) {
                                    Checkbox(
                                        checked = index in selectedSurveyOptions,
                                        onCheckedChange = { onToggleSurveyOption(index) }
                                    )
                                } else {
                                    RadioButton(
                                        selected = index in selectedSurveyOptions,
                                        onClick = { onToggleSurveyOption(index) }
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons
                    when (message.type) {
                        "SURVEY" -> {
                            Button(
                                onClick = onSubmitSurvey,
                                enabled = selectedSurveyOptions.isNotEmpty() && !isSubmitting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enviar respuesta")
                            }
                        }

                        "ACTION" -> {
                            Button(
                                onClick = onExecuteAction,
                                enabled = !isSubmitting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(message.actionLabel ?: "Ejecutar")
                            }
                        }

                        else -> {
                            // ANNOUNCEMENT
                            if (message.requiresAck) {
                                Button(
                                    onClick = onAcknowledge,
                                    enabled = !isSubmitting,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Entendido")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Cerrar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
