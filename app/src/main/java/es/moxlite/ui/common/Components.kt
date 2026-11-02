package es.moxlite.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import es.moxlite.data.remote.scryfall.ScryfallImages
import es.moxlite.ui.theme.ManaColors

/** Datos mínimos para pintar una fila de mazo (sirve para Room y para resultados de búsqueda). */
data class DeckRowData(
    val publicId: String,
    val name: String,
    val format: String,
    val author: String,
    val colors: String,
    val featuredScryfallId: String? = null,
    val likes: Int = 0,
    val cards: Int = 0,
    val saved: Boolean = false,
    val localOnly: Boolean = false,
    val localChanges: Boolean = false,
)

@Composable
fun DeckRow(deck: DeckRowData, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            deck.featuredScryfallId?.let {
                AsyncImage(
                    model = ScryfallImages.url(it, ScryfallImages.Size.ART_CROP),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(deck.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorPips(deck.colors)
                Spacer(Modifier.width(6.dp))
                Text(
                    listOf(deck.format.ifBlank { null }, deck.author.ifBlank { null }?.let { "@$it" })
                        .filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (deck.localChanges) SmallIcon(Icons.Outlined.Edit, "Cambios sin subir", MaterialTheme.colorScheme.tertiary)
            if (deck.localOnly) SmallIcon(Icons.Outlined.PhoneAndroid, "Solo en el móvil")
            else if (deck.saved) SmallIcon(Icons.Outlined.DownloadDone, "Disponible sin conexión", MaterialTheme.colorScheme.primary)
            if (deck.likes > 0) {
                SmallIcon(Icons.Outlined.ThumbUp, null)
                Text("${deck.likes}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String?, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
}

@Composable
fun ColorPips(colors: String, size: Int = 10) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val list = colors.ifBlank { "C" }
        list.forEach { c ->
            Box(
                Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(ManaColors.of(c))
                    .border(0.5.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

@Composable
fun OfflineBanner(visible: Boolean) {
    if (!visible) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sin conexión: mostrando lo guardado en el móvil", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun MessageBox(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isError) Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(8.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(12.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionHeader(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

val FORMATS = listOf(
    "commander", "standard", "pioneer", "modern", "legacy", "vintage", "pauper",
    "historic", "brawl", "oathbreaker", "paupercommander", "none",
)
