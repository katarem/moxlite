package es.moxlite.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em

internal const val BRACKET_ICON_ID = "bracket-icon"
private const val SEPARATOR = " · "

/**
 * Builds a deck meta line ("Commander · ⏲3 · @author") where the bracket is shown as Moxfield does:
 * a gauge icon followed by the number. The icon's alternate text ([bracketLabel] + space) keeps the
 * line readable for screen readers ("Commander · Bracket 3 · @author").
 */
internal fun deckMetaText(leading: String?, bracket: Int?, trailing: String?, bracketLabel: String): AnnotatedString =
    buildAnnotatedString {
        val parts = listOfNotNull(leading?.ifBlank { null }, bracket, trailing?.ifBlank { null })
        parts.forEachIndexed { index, part ->
            if (index > 0) append(SEPARATOR)
            if (part is Int) {
                appendInlineContent(BRACKET_ICON_ID, "$bracketLabel ")
                append(part.toString())
            } else {
                append(part as String)
            }
        }
    }

/** One-line deck metadata with the bracket drawn as a gauge icon plus its number (see [deckMetaText]). */
@Composable
fun DeckMetaText(
    leading: String?,
    bracket: Int?,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val label = LocalStrings.current.filterBracket
    val text = remember(leading, bracket, trailing, label) { deckMetaText(leading, bracket, trailing, label) }
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val inline = mapOf(
        BRACKET_ICON_ID to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Outlined.Speed, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
        },
    )
    Text(
        text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inline,
    )
}
