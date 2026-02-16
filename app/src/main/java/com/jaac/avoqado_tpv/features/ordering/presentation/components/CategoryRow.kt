package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory

private val CategoryRowShape = RoundedCornerShape(12.dp)

/**
 * Square POS-style category row.
 *
 * ```
 * [Thumbnail w/ initials] [Name + "N articulos"] [Chevron >]
 * ```
 *
 * @param category The product category to display
 * @param onClick Callback when the row is tapped (drill into category)
 */
@Composable
fun CategoryRow(
    category: ProductCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryColor = remember(category.effectiveColor) {
        try {
            Color(android.graphics.Color.parseColor(category.effectiveColor))
        } catch (_: Exception) {
            Color.Gray
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = CategoryRowShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProductThumbnail(
                name = category.name,
                imageUrl = null,
                categoryColor = categoryColor,
                size = 40.dp
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${category.productCount} articulos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Ver ${category.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
