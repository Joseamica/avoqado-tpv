package com.jaac.avoqado_tpv.features.ordering.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

private val ThumbnailShape = RoundedCornerShape(8.dp)

/**
 * Square product/category thumbnail.
 *
 * - If [imageUrl] is non-null/non-empty: Coil AsyncImage with crop
 * - Otherwise: colored box with first two letters of [name]
 *
 * @param name Display name (initials fallback uses first 2 chars)
 * @param imageUrl Optional product image URL
 * @param categoryColor Background color for initials fallback
 * @param size Thumbnail size (default 56dp)
 */
@Composable
fun ProductThumbnail(
    name: String,
    imageUrl: String?,
    categoryColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val initials = remember(name) { name.take(2).uppercase() }

    Box(
        modifier = modifier
            .size(size)
            .clip(ThumbnailShape),
        contentAlignment = Alignment.Center
    ) {
        if (!imageUrl.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = name,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(categoryColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor
                )
            }
        }
    }
}
