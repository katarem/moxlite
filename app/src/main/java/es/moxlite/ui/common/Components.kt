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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Lock
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
import es.moxlite.ui.theme.ManaColors

/** Datos mínimos para pintar una fila de mazo (sirve para Room y para resultados de búsqueda). */
data class DeckRowData(
    val publicId: String,
    val name: String,
    val format: String,
    val author: String,
    val colors: String,
    /** Miniatura (recorte del arte de la carta destacada). */
    val imageUrl: String? = null,
    val likes: Int = 0,
    val cards: Int = 0,
    val saved: Boolean = false,
    val localOnly: Boolean = false,
    val localChanges: Boolean = false,
    val isPrivate: Boolean = false,
    val bracket: Int? = null,
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
            deck.imageUrl?.let {
                AsyncImage(
                    model = it,
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
                DeckMetaText(
                    leading = deck.format.ifBlank { null }?.let { LocalStrings.current.formatLabel(it) },
                    bracket = deck.bracket,
                    trailing = deck.author.ifBlank { null }?.let { "@$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val t = LocalStrings.current
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (deck.isPrivate) SmallIcon(Icons.Outlined.Lock, t.visibilityPrivate)
            if (deck.localChanges) SmallIcon(Icons.Outlined.CloudUpload, t.pendingUpload, MaterialTheme.colorScheme.tertiary)
            if (deck.localOnly) SmallIcon(Icons.Outlined.PhoneAndroid, t.onlyOnPhone)
            else if (deck.saved) SmallIcon(Icons.Outlined.DownloadDone, t.availableOffline, MaterialTheme.colorScheme.primary)
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
            Text(LocalStrings.current.offlineBanner, style = MaterialTheme.typography.labelMedium)
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

/** Claves de formato de Moxfield (`fmt`). */
val FORMATS = listOf(
    "commander", "standard", "pioneer", "modern", "legacy", "vintage", "pauper", "premodern",
    "timeless", "historic", "brawl", "historicBrawl", "oathbreaker", "pauperEdh", "duelCommander",
    "predh", "none",
)

/** Visibilidades de mazo de Moxfield. */
val VISIBILITIES = listOf("public", "unlisted", "private")
