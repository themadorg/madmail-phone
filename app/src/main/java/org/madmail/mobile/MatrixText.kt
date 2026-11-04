package org.madmail.mobile

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import kotlin.random.Random

// Half-width katakana and digits, the classic falling-code set, plus a few mail-ish symbols.
private const val Glyphs = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789@<>#*+=:"

/** True when the system's animator duration scale is 0, the "Remove animations" accessibility setting. */
@Composable
internal fun motionOff(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * [text] resolves out of shifting glyphs, left to right, each time [replay] changes. Unresolved glyphs are drawn in [glyphColor].
 * The final text is laid out underneath, invisible, so the block keeps its size and line breaks while the glyphs churn.
 * Screen readers get the final text at once.
 */
@Composable
internal fun DecodeText(
    text: String,
    replay: Any?,
    modifier: Modifier = Modifier,
    style: TextStyle,
    glyphColor: Color,
    textAlign: TextAlign? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    durationMillis: Int = 720,
) {
    val still = motionOff()
    var shown by remember(text) { mutableStateOf(if (still) AnnotatedString(text) else scramble(text, 0, glyphColor)) }
    LaunchedEffect(text, replay, glyphColor) {
        if (still) { shown = AnnotatedString(text); return@LaunchedEffect }
        // Replay 0 means "not shown yet", such as a page sliding in: hold the glyphs until it settles.
        if (replay == 0) { shown = scramble(text, 0, glyphColor); return@LaunchedEffect }
        val start = withFrameMillis { it }
        var last = -1
        while (true) {
            val elapsed = withFrameMillis { it } - start
            val resolved = (elapsed * text.length / durationMillis).toInt().coerceAtMost(text.length)
            // Re-rolling every frame is noise; ~16 changes a second reads as code ticking over.
            val tick = (elapsed / 60).toInt()
            if (tick != last || resolved == text.length) { shown = scramble(text, resolved, glyphColor); last = tick }
            if (resolved == text.length) break
        }
    }
    Box(modifier.semantics(mergeDescendants = true) { this.text = AnnotatedString(text) }) {
        Text(text, Modifier.clearAndSetSemantics {}, color = Color.Transparent, style = style, textAlign = textAlign,
            fontWeight = fontWeight, letterSpacing = letterSpacing, lineHeight = lineHeight)
        // Sized by the final text: fallback fonts for the glyphs run taller and would otherwise push the page down mid-decode.
        Text(shown, Modifier.matchParentSize().clearAndSetSemantics {}, style = style, textAlign = textAlign, fontWeight = fontWeight,
            letterSpacing = letterSpacing, lineHeight = lineHeight)
    }
}

/** The first [resolved] characters as written; the rest as random glyphs. Spaces and line breaks always stay where they are. */
private fun scramble(text: String, resolved: Int, color: Color): AnnotatedString = buildAnnotatedString {
    append(text.substring(0, resolved))
    withStyle(SpanStyle(color = color)) {
        for (c in text.substring(resolved)) append(if (c.isWhitespace()) c else Glyphs[Random.nextInt(Glyphs.length)])
    }
}

/**
 * A one-line terminal readout that types itself out after [delayMillis], with a block cursor that blinks while it waits.
 * Decorative: screen readers skip it, since the page's title and body already say the same in words.
 */
@Composable
internal fun TerminalLine(line: String, replay: Any?, modifier: Modifier = Modifier, style: TextStyle, color: Color, delayMillis: Int = 520) {
    val still = motionOff()
    var typed by remember(line) { mutableIntStateOf(if (still) line.length else 0) }
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(line, replay) {
        if (still) { typed = line.length; return@LaunchedEffect }
        typed = 0
        if (replay == 0) { cursor = true; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val elapsed = withFrameMillis { it } - start
            typed = ((elapsed - delayMillis) / 22).toInt().coerceIn(0, line.length)
            cursor = (elapsed / 480) % 2 == 0L
            // Keep blinking for a couple of seconds after typing ends, then rest with the cursor shown.
            if (typed == line.length && elapsed > delayMillis + line.length * 22 + 2_400) { cursor = true; break }
        }
    }
    Text(buildAnnotatedString {
        append(line.substring(0, typed))
        withStyle(SpanStyle(color = if (cursor) color else Color.Transparent)) { append("▍") }
    }, modifier.clearAndSetSemantics {}, style = style, color = color, fontFamily = FontFamily.Monospace, softWrap = false, maxLines = 1)
}
