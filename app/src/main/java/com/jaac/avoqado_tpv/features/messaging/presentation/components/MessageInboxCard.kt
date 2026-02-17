package com.jaac.avoqado_tpv.features.messaging.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.messaging.presentation.TpvMessageUiModel
import java.time.Duration
import java.time.Instant

/**
 * Full-screen dialog showing the message inbox (all messages delivered to this terminal).
 * Tapping a message row triggers onMessageClick which opens the TpvMessageDialog.
 */
@Composable
fun MessageInboxDialog(
    messages: List<TpvMessageUiModel>,
    isLoading: Boolean,
    onMessageClick: (TpvMessageUiModel) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Mensajes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (messages.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (messages.size > 99) "99+" else messages.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    messages.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Inbox,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No hay mensajes",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            items(messages, key = { it.messageId }) { message ->
                                MessageRow(
                                    message = message,
                                    onClick = { onMessageClick(message) }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageRow(
    message: TpvMessageUiModel,
    onClick: () -> Unit
) {
    val typeBadgeText = when (message.type) {
        "ANNOUNCEMENT" -> "Anuncio"
        "SURVEY" -> "Encuesta"
        "ACTION" -> "Accion"
        else -> message.type
    }

    val typeBadgeColor = when (message.type) {
        "ANNOUNCEMENT" -> MaterialTheme.avoqadoColors.statusInfo
        "SURVEY" -> MaterialTheme.colorScheme.tertiary
        "ACTION" -> MaterialTheme.avoqadoColors.statusWarning
        else -> MaterialTheme.colorScheme.outline
    }

    val priorityColor = when (message.priority) {
        "URGENT" -> MaterialTheme.avoqadoColors.statusCritical
        "HIGH" -> MaterialTheme.avoqadoColors.statusError
        else -> null
    }

    val statusText = when (message.deliveryStatus) {
        "ACKNOWLEDGED" -> "Leido"
        "DISMISSED" -> "Descartado"
        "DELIVERED" -> "Pendiente"
        "PENDING" -> "Pendiente"
        else -> null
    }

    val statusColor = when (message.deliveryStatus) {
        "ACKNOWLEDGED" -> MaterialTheme.avoqadoColors.statusSuccess
        "DISMISSED" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.avoqadoColors.statusWarning
    }

    val isRead = message.deliveryStatus == "ACKNOWLEDGED" || message.deliveryStatus == "DISMISSED"

    val relativeTime = remember(message.createdAt) {
        formatRelativeTime(message.createdAt)
    }

    val titleAlpha = if (isRead) 0.7f else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRead)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Priority dot (only for URGENT/HIGH)
            if (priorityColor != null) {
                Surface(
                    shape = CircleShape,
                    color = priorityColor,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Content
            Column(modifier = Modifier.weight(1f)) {
                // Top row: type badge + timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Type badge chip
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeBadgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = typeBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = typeBadgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (relativeTime != null) {
                        Text(
                            text = relativeTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Title
                Text(
                    text = message.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = titleAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Status chip
                if (statusText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun formatRelativeTime(isoTimestamp: String?): String? {
    if (isoTimestamp.isNullOrBlank()) return null
    return try {
        val instant = Instant.parse(isoTimestamp)
        val now = Instant.now()
        val duration = Duration.between(instant, now)
        val minutes = duration.toMinutes()
        val hours = duration.toHours()
        val days = duration.toDays()

        when {
            minutes < 1 -> "ahora"
            minutes < 60 -> "hace ${minutes}m"
            hours < 24 -> "hace ${hours}h"
            days < 7 -> "hace ${days}d"
            else -> {
                val weeks = days / 7
                if (weeks < 4) "hace ${weeks}sem" else "hace ${days / 30}mes"
            }
        }
    } catch (_: Exception) {
        null
    }
}
