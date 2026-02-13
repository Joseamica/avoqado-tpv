package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleSalesGoal
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalPeriod
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalSource
import com.jaac.avoqado_tpv.features.modules.domain.model.SalesGoalType
import java.math.BigDecimal

/**
 * Sales Goals Pager
 *
 * Displays one or more sales goals in a horizontal pager with dot indicators.
 * If only one goal exists, renders the card directly without pager chrome.
 *
 * @param salesGoals List of sales goals to display (empty = renders nothing)
 * @param modifier Modifier for layout customization
 */
@Composable
fun SalesGoalsPager(
    salesGoals: List<ModuleSalesGoal>,
    modifier: Modifier = Modifier
) {
    if (salesGoals.isEmpty()) return

    if (salesGoals.size == 1) {
        SalesGoalProgressCard(
            salesGoal = salesGoals.first(),
            modifier = modifier
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { salesGoals.size })

    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            SalesGoalProgressCard(
                salesGoal = salesGoals[page],
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Dot indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(salesGoals.size) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == index)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun SalesGoalsPagerSinglePreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            SalesGoalsPager(
                salesGoals = listOf(
                    ModuleSalesGoal(
                        goal = BigDecimal("10000"),
                        period = SalesGoalPeriod.DAILY,
                        currentSales = BigDecimal("5000")
                    )
                )
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun SalesGoalsPagerMultiplePreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            SalesGoalsPager(
                salesGoals = listOf(
                    ModuleSalesGoal(
                        goal = BigDecimal("10000"),
                        period = SalesGoalPeriod.DAILY,
                        currentSales = BigDecimal("5000"),
                        source = SalesGoalSource.VENUE
                    ),
                    ModuleSalesGoal(
                        goal = BigDecimal("50"),
                        goalType = SalesGoalType.QUANTITY,
                        period = SalesGoalPeriod.WEEKLY,
                        currentSales = BigDecimal("30"),
                        source = SalesGoalSource.ORGANIZATION
                    ),
                    ModuleSalesGoal(
                        goal = BigDecimal("100000"),
                        period = SalesGoalPeriod.MONTHLY,
                        currentSales = BigDecimal("125000"),
                        source = SalesGoalSource.ORGANIZATION
                    )
                )
            )
        }
    }
}
