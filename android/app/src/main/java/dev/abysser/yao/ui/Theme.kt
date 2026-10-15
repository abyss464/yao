package dev.abysser.yao.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.abysser.yao.R

/** The palette of the design: graphite ground, one amber accent. */
object C {
    val ground = Color(0xFF111315)
    val stage = Color(0xFF0B0C0E)
    val surface = Color(0xFF1A1D21)
    val surfaceHi = Color(0xFF262A2F)
    val pressed = Color(0xFF2E3238)
    val sheet = Color(0xFF15181B)
    val hair = Color(0xFF262A2F)
    val text = Color(0xFFECE8E1)
    val text2 = Color(0xFFC9C5BE)
    val text3 = Color(0xFFA6A29B)
    val muted = Color(0xFF8E8A84)
    val dim = Color(0xFF6F6C67)
    val accent = Color(0xFFF2B24C)
    val onAccent = Color(0xFF1C1405)
    val key = Color(0xFF1E2125)
    val keyMod = Color(0xFF181B1F)
    val underline = Color(0xFF4A4F56)
    val underlineEmpty = Color(0xFF2D3237)
}

val Mono = FontFamily(Font(R.font.mono))
val Mark = FontFamily(Font(R.font.yao_mark, FontWeight.Black))

fun ts(size: TextUnit, color: Color = C.text, weight: FontWeight = FontWeight.Normal, family: FontFamily? = null) =
    TextStyle(fontSize = size, color = color, fontWeight = weight, fontFamily = family, lineHeight = size * 1.35f)

val body = ts(15.sp)

/** Stroke icons drawn from the design's 24×24 SVG paths. */
class Glyph(vararg val paths: String, val rotate: Float = 0f, val pivotX: Float = 12f, val pivotY: Float = 12f)

object Glyphs {
    val touchpad = Glyph("M6,4 H18 A3,3 0 0 1 21,7 V17 A3,3 0 0 1 18,20 H6 A3,3 0 0 1 3,17 V7 A3,3 0 0 1 6,4 Z", "M3 15h18M12 15v5")
    val remote = Glyph(
        "M12,2.5 A5,5 0 0 1 17,7.5 V16.5 A5,5 0 0 1 12,21.5 A5,5 0 0 1 7,16.5 V7.5 A5,5 0 0 1 12,2.5 Z",
        "M14.5,9 A2.5,2.5 0 1 1 9.5,9 A2.5,2.5 0 1 1 14.5,9 Z",
    )
    val keyboard = Glyph("M4.5,6 H19.5 A2,2 0 0 1 21.5,8 V16 A2,2 0 0 1 19.5,18 H4.5 A2,2 0 0 1 2.5,16 V8 A2,2 0 0 1 4.5,6 Z", "M7 14h10")
    val bolt = Glyph("M13 2.5 5 13.5h6l-1 8 8-11h-6l1-8z")
    val type = Glyph("M5 7V5h14v2", "M12 5v14", "M9 19h6")
    val backspace = Glyph("M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7z", "M12 9l6 6M18 9l-6 6")
    val enter = Glyph("M20 5v7a3 3 0 0 1-3 3H5", "M9 11l-4 4 4 4")
    val up = Glyph("M6 15l6-6 6 6")
    val down = Glyph("M6 9l6 6 6-6")
    val left = Glyph("M15 6l-6 6 6 6")
    val right = Glyph("M9 6l6 6-6 6")
    val prev = Glyph("M6 5v14", "M19 5 9 12l10 7z")
    val play = Glyph("M4 5v14l8-7z", "M16 5v14M20 5v14")
    val next = Glyph("M18 5v14", "M5 5l10 7-10 7z")
    val volDown = Glyph("M4 9h4l5-4v14l-5-4H4z", "M17 12h4")
    val mute = Glyph("M4 9h4l5-4v14l-5-4H4z", "M17 10l4 4M21 10l-4 4")
    val volUp = Glyph("M4 9h4l5-4v14l-5-4H4z", "M17 12h4M19 10v4")
    val gyro = Glyph("M10,3 H14 A2,2 0 0 1 16,5 V14 A2,2 0 0 1 14,16 H10 A2,2 0 0 1 8,14 V5 A2,2 0 0 1 10,3 Z", rotate = -14f, pivotX = 12f, pivotY = 9.5f)
    val gyroArc = Glyph("M4 19.5c4.5 2 11.5 2 16 0")
    val plus = Glyph("M12 5v14M5 12h14")
    val chevron = Glyph("M9 6l6 6-6 6")
    val close = Glyph("M6 6l12 12M18 6L6 18")
}

@Composable
fun GlyphIcon(vararg glyphs: Glyph, tint: Color, size: Dp = 24.dp, stroke: Float = 1.75f, modifier: Modifier = Modifier) {
    val vector = remember(glyphs.toList(), stroke) {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (g in glyphs) {
            b.addGroup(rotate = g.rotate, pivotX = g.pivotX, pivotY = g.pivotY)
            for (d in g.paths) {
                b.addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            b.clearGroup()
        }
        b.build()
    }
    Box(modifier) {
        Image(rememberVectorPainter(vector), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size))
    }
}
