package com.example.f95updater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest

@Composable
internal fun CatalogThumbnail(entry: SourceCatalogEntry) {
    val context = LocalContext.current
    val imageUrl = entry.thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: entry.coverUrl?.takeIf { it.isNotBlank() }
        ?: dlsiteImageUrls(entry.canonicalUrl)?.thumbnailUrl
    Surface(
        modifier = Modifier.width(60.dp).height(80.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
            imageUrl?.let { url ->
                coil.compose.AsyncImage(
                    model = ImageRequest.Builder(context).data(url).size(160, 220).build(),
                    contentDescription = "${entry.title} thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
