package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleSalesGoal
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Sales Goal Progress Card
 *
 * Displays a progress bar showing how close the staff is to reaching their sales goal.
 * Only shown for SERIALIZED_INVENTORY module when a sales goal is configured.
 *
 * **Visual Design:**
 * - Compact card with progress indicator
 * - Shows current sales vs goal amount
 * - Percentage completion with animated progress bar
 * - Period indicator (daily/weekly/monthly)
 * - Trophy icon when goal is achieved
 *
 * **Usage:**
 * ```kotlin
 * SalesGoalProgressCard(
 *     salesGoal = moduleSalesGoal,
 *     modifier = Modifier.fillMaxWidth()
 * )
 * ```
 *
 * @param salesGoal The sales goal configuration with current sales and target
 * @param modifier Modifier for customization
 */
@Composable
fun SalesGoalProgressCard(
    salesGoal: ModuleSalesGoal,
    modifier: Modifier = Modifier
) {
    // Calculate progress (0.0 to 1.0, capped at 1.0 for exceeded goals)
    val progress = if (salesGoal.goal > BigDecimal.ZERO) {
        salesGoal.currentSales.divide(salesGoal.goal, 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    // Calculate percentage for display (can exceed 100%)
    val percentage = if (salesGoal.goal > BigDecimal.ZERO) {
        salesGoal.currentSales.divide(salesGoal.goal, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
    } else {
        0
    }

    val isGoalAchieved = salesGoal.currentSales >= salesGoal.goal

    // Animated progress value
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 800),
        label = "progress_animation"
    )

    // Currency formatter
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "MX"))

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row: Title + Period badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Icon + Title
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isGoalAchieved) Icons.Default.EmojiEvents else Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = if (isGoalAchieved) "Meta alcanzada" else "Progreso de ventas",
                        tint = if (isGoalAchieved) {
                            Color(0xFFFFD700) // Gold for achieved
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (isGoalAchieved) "Meta Alcanzada!" else "Meta de Ventas",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Right: Period badge
                PeriodBadge(period = salesGoal.period)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = if (isGoalAchieved) {
                        MaterialTheme.avoqadoColors.statusSuccess
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = Color.Transparent
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom row: Current sales + Goal + Percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Left: Current sales
                Column {
                    Text(
                        text = "Vendido",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = currencyFormat.format(salesGoal.currentSales),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = if (isGoalAchieved) {
                            MaterialTheme.avoqadoColors.statusSuccess
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                }

                // Center: Percentage
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$percentage%",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isGoalAchieved) {
                            MaterialTheme.avoqadoColors.statusSuccess
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }

                // Right: Goal amount
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Meta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = currencyFormat.format(salesGoal.goal),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

            // Remaining amount (if not achieved)
            if (!isGoalAchieved) {
                Spacer(modifier = Modifier.height(8.dp))

                val remaining = salesGoal.goal.subtract(salesGoal.currentSales)
                Text(
                    text = "Faltan ${currencyFormat.format(remaining)} para alcanzar tu meta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Period Badge
 *
 * Small chip showing the goal period (Diario, Semanal, Mensual).
 */
@Composable
private fun PeriodBadge(
    period: SalesGoalPeriod,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (period) {
        SalesGoalPeriod.DAILY -> "Diario" to MaterialTheme.avoqadoColors.statusInfo
        SalesGoalPeriod.WEEKLY -> "Semanal" to Color(0xFF9C27B0)
        SalesGoalPeriod.MONTHLY -> "Mensual" to MaterialTheme.avoqadoColors.statusWarning
    }

    Box(
        modifier = modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = color
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun SalesGoalProgressCardPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 50% progress - Daily
            SalesGoalProgressCard(
                salesGoal = ModuleSalesGoal(
                    goal = BigDecimal("10000"),
                    period = SalesGoalPeriod.DAILY,
                    currentSales = BigDecimal("5000")
                )
            )

            // 75% progress - Weekly
            SalesGoalProgressCard(
                salesGoal = ModuleSalesGoal(
                    goal = BigDecimal("50000"),
                    period = SalesGoalPeriod.WEEKLY,
                    currentSales = BigDecimal("37500")
                )
            )

            // Goal achieved - Monthly
            SalesGoalProgressCard(
                salesGoal = ModuleSalesGoal(
                    goal = BigDecimal("100000"),
                    period = SalesGoalPeriod.MONTHLY,
                    currentSales = BigDecimal("125000")
                )
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun SalesGoalProgressCardLowProgressPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // 10% progress
            SalesGoalProgressCard(
                salesGoal = ModuleSalesGoal(
                    goal = BigDecimal("20000"),
                    period = SalesGoalPeriod.DAILY,
                    currentSales = BigDecimal("2000")
                )
            )
        }
    }
}
