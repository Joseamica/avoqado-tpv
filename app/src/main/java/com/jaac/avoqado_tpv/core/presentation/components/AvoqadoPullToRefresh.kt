package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Avoqado Pull-to-Refresh Container
 *
 * Wrapper component that adds pull-to-refresh functionality to any scrollable content.
 * Uses Material3's pullToRefresh API for native Android feel.
 *
 * **Usage:**
 * ```kotlin
 * val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
 *
 * AvoqadoPullToRefresh(
 *     isRefreshing = isRefreshing,
 *     onRefresh = { viewModel.refresh() }
 * ) {
 *     LazyColumn { ... }
 * }
 * ```
 *
 * **Features:**
 * - Uses Material3 primary color for indicator
 * - Smooth pull gesture with spring animation
 * - Automatically hides when refresh completes
 * - Can be disabled during critical operations
 *
 * @param isRefreshing Whether the refresh is currently in progress
 * @param onRefresh Callback when user pulls to refresh
 * @param modifier Modifier for the container
 * @param enabled Whether pull-to-refresh is enabled (disable during payments, etc.)
 * @param content The scrollable content (LazyColumn, LazyVerticalGrid, etc.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val state = rememberPullToRefreshState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = state,
                enabled = enabled,
                onRefresh = onRefresh
            )
    ) {
        content()

        // Pull-to-refresh indicator at top center
        Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = MaterialTheme.colorScheme.surface,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
