package es.moxlite.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Cómo se dibuja un símbolo de maná de Scryfall ("{W}", "{2/U}", "{G/P}"…), con el aspecto de las cartas:
 * un círculo de color con el icono en negro. Los híbridos se parten en diagonal con un icono pequeño en cada mitad.
 */
internal sealed interface ManaSymbolSpec {
    /** Círculo de un color con un icono en el centro. [scale] agranda o encoge el icono (el pirexiano va más grande). */
    data class Single(val background: Color, val glyph: String, val ink: Color = ManaSymbolSpec.Ink, val scale: Float = 1f) : ManaSymbolSpec

    /** Híbrido: mitad de arriba a la izquierda de [top] con [topGlyph], la otra de [bottom] con [bottomGlyph]. */
    data class Split(val top: Color, val bottom: Color, val topGlyph: String, val bottomGlyph: String) : ManaSymbolSpec

    /** Símbolo sin icono (raro o nuevo): círculo gris con el texto. */
    data class Fallback(val text: String) : ManaSymbolSpec

    companion object {
        val Ink = Color(0xFF111111)
        val Generic = Color(0xFFBEB9B2)

        // Colores de fondo de los símbolos de un color (como en las cartas)…
        private val solid = mapOf(
            "W" to Color(0xFFF0F2C0),
            "U" to Color(0xFFB5CDE3),
            "B" to Color(0xFFACA29A),
            "R" to Color(0xFFDB8664),
            "G" to Color(0xFF93B483),
        )

        // …y de las mitades de los híbridos, algo más claros.
        private val light = mapOf(
            "W" to Color(0xFFFDFBCE),
            "U" to Color(0xFFBCDAF7),
            "B" to Color(0xFFA7999E),
            "R" to Color(0xFFF19B79),
            "G" to Color(0xFF9FCBA6),
            "C" to Color(0xFFD0C6BB),
            "2" to Color(0xFFD0C6BB),
        )

        private const val PHYREXIAN_SCALE = 1.25f

        /** [symbol] es lo que va entre llaves, sin ellas: "W", "2/U", "W/U/P", "T"… */
        fun of(symbol: String): ManaSymbolSpec {
            val s = symbol.trim().uppercase()
            if (s == "Q") return Single(Ink, "Q", ink = Color.White)
            if (s in ManaGlyphs.all) return Single(solid[s] ?: Generic, s)
            if (s == "1/2") return Single(Generic, "½")
            val parts = s.split('/')
            val phyrexian = parts.size > 1 && parts.last() == "P"
            val colors = if (phyrexian) parts.dropLast(1) else parts
            return when {
                phyrexian && colors.size == 1 && colors[0] in solid -> Single(solid.getValue(colors[0]), "P", scale = PHYREXIAN_SCALE)
                phyrexian && colors.size == 1 && colors[0] == "C" -> Single(Generic, "P", scale = PHYREXIAN_SCALE)
                phyrexian && colors.size == 2 && colors.all { it in solid } ->
                    Split(light.getValue(colors[0]), light.getValue(colors[1]), "P", "P")
                !phyrexian && colors.size == 2 && colors[0] in light && colors[1] in solid ->
                    Split(light.getValue(colors[0]), light.getValue(colors[1]), colors[0], colors[1])
                else -> Fallback(symbol.trim())
            }
        }

        private val token = Regex("""\{([^{}]{1,10})\}""")

        /** Trozos de [text]: texto normal (String) y símbolos ([Symbol]) en el orden en que aparecen. */
        fun split(text: String): List<Any> {
            val out = mutableListOf<Any>()
            var last = 0
            token.findAll(text).forEach { m ->
                if (m.range.first > last) out += text.substring(last, m.range.first)
                out += Symbol(m.groupValues[1])
                last = m.range.last + 1
            }
            if (last < text.length) out += text.substring(last)
            return out
        }
    }

    /** Un símbolo dentro de un texto: lo que va entre llaves. */
    data class Symbol(val value: String)
}

private val glyphPaths = HashMap<String, Path>()

private fun glyphPath(key: String): Path? {
    val glyph = ManaGlyphs.all[key] ?: return null
    return glyphPaths.getOrPut(key) { PathParser().parsePathString(glyph.path).toPath() }
}

/** Dibuja el icono [key] centrado en [center], con [height] de alto (o menos si es muy ancho para [maxWidth]). */
private fun DrawScope.drawGlyph(key: String, center: Offset, height: Float, maxWidth: Float, color: Color) {
    val glyph = ManaGlyphs.all[key] ?: return
    val path = glyphPath(key) ?: return
    val k = minOf(height / ManaGlyph.HEIGHT, maxWidth / glyph.width)
    withTransform({
        translate(center.x - glyph.width * k / 2f, center.y - ManaGlyph.HEIGHT * k / 2f)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawPath(path, color)
    }
}

/**
 * Un símbolo de maná ("W", "2/U", "T"…, sin llaves) que ocupa el tamaño de [modifier].
 * Deja un pequeño margen para la sombra, así que varios seguidos no se tocan.
 */
@Composable
fun ManaSymbol(symbol: String, modifier: Modifier = Modifier, shadow: Boolean = true) {
    val spec = remember(symbol) { ManaSymbolSpec.of(symbol) }
    val measurer = if (spec is ManaSymbolSpec.Fallback) rememberTextMeasurer() else null
    Canvas(modifier.semantics { contentDescription = "{$symbol}" }) {
        val d = size.minDimension * 0.88f
        val r = d / 2f
        val c = Offset(size.width / 2f + d * 0.03f, size.height / 2f - d * 0.03f)
        if (shadow) drawCircle(Color(0xFF111111), r, c + Offset(-d * 0.046f, d * 0.054f))
        when (spec) {
            is ManaSymbolSpec.Single -> {
                drawCircle(spec.background, r, c)
                drawGlyph(spec.glyph, c, d * 0.68f * spec.scale, d * 0.74f, spec.ink)
            }
            is ManaSymbolSpec.Split -> {
                val circle = Path().apply { addOval(Rect(c, r)) }
                clipPath(circle) {
                    drawCircle(spec.top, r, c)
                    val half = Path().apply {
                        moveTo(c.x + r, c.y - r)
                        lineTo(c.x + r, c.y + r)
                        lineTo(c.x - r, c.y + r)
                        close()
                    }
                    drawPath(half, spec.bottom)
                }
                val glyph = d * 0.42f
                val shift = d * 0.2f
                drawGlyph(spec.topGlyph, c + Offset(-shift, -shift), glyph, glyph, ManaSymbolSpec.Ink)
                drawGlyph(spec.bottomGlyph, c + Offset(shift, shift), glyph, glyph, ManaSymbolSpec.Ink)
            }
            is ManaSymbolSpec.Fallback -> {
                drawCircle(ManaSymbolSpec.Generic, r, c)
                val text = spec.text.replace("/", "")
                val fontPx = d * if (text.length <= 1) 0.62f else 0.9f / text.length
                val layout = measurer!!.measure(
                    text,
                    TextStyle(color = ManaSymbolSpec.Ink, fontSize = (fontPx / fontScale / density).sp, fontWeight = FontWeight.Bold),
                )
                drawText(layout, topLeft = c - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }
    }
}

/**
 * Texto en el que los símbolos entre llaves ("{2}{U}", "{T}: añade {G}.") se ven como iconos de maná.
 * Sirve para costes y para el texto de reglas. Los iconos se ajustan al tamaño de letra de [style].
 */
@Composable
fun ManaText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val parts = remember(text) { ManaSymbolSpec.split(text) }
    val annotated: AnnotatedString = remember(parts) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is ManaSymbolSpec.Symbol -> appendInlineContent(part.value, "{${part.value}}")
                    else -> append(part as String)
                }
            }
        }
    }
    val inline = remember(parts) {
        parts.filterIsInstance<ManaSymbolSpec.Symbol>().map { it.value }.distinct().associateWith { symbol ->
            InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
                ManaSymbol(symbol, Modifier.fillMaxSize())
            }
        }
    }
    Text(
        annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inline,
    )
}
