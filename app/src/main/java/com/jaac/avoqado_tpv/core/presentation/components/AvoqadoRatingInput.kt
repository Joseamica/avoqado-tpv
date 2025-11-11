package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 5-star rating input component
 *
 * Simple, functional implementation for collecting user ratings.
 * Displays 5 stars - tap to select rating from 1-5.
 *
 * @param rating Current rating (0 = no rating, 1-5 = selected stars)
 * @param onRatingChange Callback when user taps a star
 * @param modifier Optional modifier
 * @param enabled Whether rating can be changed
 * @param label Optional label above stars
 */
@Composable
fun AvoqadoRatingInput(
    rating: Int,
    onRatingChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Optional label
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // 5 stars in a row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (star in 1..5) {
                val isFilled = star <= rating

                Icon(
                    imageVector = if (isFilled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Star $star",
                    tint = if (isFilled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable(enabled = enabled) {
                            onRatingChange(star)
                        }
                        .size(48.dp)
                        .padding(4.dp)
                )
            }
        }

        // ✅ ALWAYS reserve space for rating text to prevent layout shift
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (rating > 0) "$rating de 5 estrellas" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(20.dp)  // Fixed height - always reserves space
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoRatingInputPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // No rating
            AvoqadoRatingInput(
                rating = 0,
                onRatingChange = {},
                label = "¿Cómo fue tu experiencia?"
            )

            // 3 stars selected
            AvoqadoRatingInput(
                rating = 3,
                onRatingChange = {},
                label = "Calificación actual"
            )

            // 5 stars selected
            AvoqadoRatingInput(
                rating = 5,
                onRatingChange = {}
            )

            // Disabled
            AvoqadoRatingInput(
                rating = 4,
                onRatingChange = {},
                enabled = false,
                label = "Disabled"
            )
        }
    }
}
