package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Avoqado Top App Bar
 *
 * Standard top bar with consistent styling
 *
 * @param title Bar title
 * @param modifier Modifier for customization
 * @param onNavigationClick Optional back button click handler
 * @param actions Optional action buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onNavigationClick != null) {
                IconButton(onClick = onNavigationClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Orders"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarWithBackPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Order Details",
            onNavigationClick = { /* Handle back */ }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarWithActionsPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Menu",
            actions = {
                IconButton(onClick = { /* Handle action */ }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
            }
        )
    }
}
