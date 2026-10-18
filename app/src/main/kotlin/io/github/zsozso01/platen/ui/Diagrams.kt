package io.github.zsozso01.platen.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zsozso01.platen.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.ceil
import kotlin.math.sin

// Small drawings that explain settings and show a job at work. Everything is drawn with vector paths on a
// 150 x 120 canvas, so there are no image files to add to the APK.

private const val W = 150f
private const val H = 120f

private class Ink(val line: Color, val paper: Color, val faint: Color, val cool: Color, val warm: Color, val third: Color, val bad: Color)

@Composable
private fun Art(modifier: Modifier, description: String, draw: DrawScope.(Ink) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = Ink(scheme.outline, scheme.surface, scheme.outlineVariant, scheme.primary, scheme.tertiary, scheme.secondary, scheme.error)
    Canvas(modifier.aspectRatio(W / H).semantics { contentDescription = description }) {
        val k = size.width / W
        scale(k, k, pivot = Offset.Zero) { draw(this, ink) }
    }
}

@Composable
private fun loop(millis: Int, reverse: Boolean = false): State<Float> =
    rememberInfiniteTransition(label = "art").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(millis, easing = LinearEasing), if (reverse) RepeatMode.Reverse else RepeatMode.Restart),
        label = "phase",
    )

private fun DrawScope.sheet(ink: Ink, x: Float, y: Float, w: Float, h: Float, fill: Color = ink.paper, edge: Color = ink.line) {
    drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(3f))
    drawRoundRect(edge, Offset(x, y), Size(w, h), CornerRadius(3f), style = Stroke(1f))
}

private fun DrawScope.textLines(ink: Ink, x: Float, y: Float, w: Float, count: Int, gap: Float = 12f) {
    repeat(count) { i ->
        val end = if (i == count - 1) x + w * 0.7f else x + w
        drawLine(ink.faint, Offset(x, y + i * gap), Offset(end, y + i * gap), 1.6f, StrokeCap.Round)
    }
}

private fun DrawScope.arrowPath(path: Path, color: Color, head: List<Offset>, alpha: Float = 1f) {
    val style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(path, color.copy(alpha = alpha), style = style)
    val tip = Path().apply {
        moveTo(head[0].x, head[0].y)
        lineTo(head[1].x, head[1].y)
        lineTo(head[2].x, head[2].y)
    }
    drawPath(tip, color.copy(alpha = alpha), style = style)
}

private fun bezier(a: Offset, b: Offset, c: Offset, d: Offset, t: Float): Offset {
    val u = 1 - t
    return Offset(
        u * u * u * a.x + 3 * u * u * t * b.x + 3 * u * t * t * c.x + t * t * t * d.x,
        u * u * u * a.y + 3 * u * u * t * b.y + 3 * u * t * t * c.y + t * t * t * d.y,
    )
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, topLeft: Offset, color: Color, size: Float = 12f) {
    // Text is measured in pixels, the canvas is scaled; sizing in canvas units keeps it in proportion.
    drawText(measurer, text, topLeft, TextStyle(fontSize = size.toSp(), fontWeight = FontWeight.Bold, color = color))
}

/** A sheet with its binding edge marked and an arrow showing which way it turns over. */
@Composable
fun SidesArt(longEdge: Boolean, modifier: Modifier = Modifier) {
    val phase by loop(1800, reverse = true)
    Art(modifier, if (longEdge) "A sheet that turns over like a book page" else "A sheet that turns over like a notepad page") { ink ->
        val a = 0.45f + 0.55f * phase
        if (longEdge) {
            sheet(ink, 52f, 14f, 60f, 84f)
            drawLine(ink.warm, Offset(52f, 16f), Offset(52f, 96f), 3.2f, StrokeCap.Round)
            textLines(ink, 63f, 32f, 38f, 3)
            val p = Path().apply { moveTo(45f, 30f); cubicTo(20f, 24f, 20f, 88f, 45f, 82f) }
            arrowPath(p, ink.cool, listOf(Offset(40f, 78f), Offset(45f, 82f), Offset(39f, 86f)), a)
        } else {
            sheet(ink, 45f, 30f, 60f, 84f)
            drawLine(ink.warm, Offset(47f, 30f), Offset(103f, 30f), 3.2f, StrokeCap.Round)
            textLines(ink, 56f, 48f, 38f, 3)
            val p = Path().apply { moveTo(52f, 23f); cubicTo(46f, 4f, 104f, 4f, 98f, 23f) }
            arrowPath(p, ink.cool, listOf(Offset(103f, 17f), Offset(98f, 23f), Offset(93f, 18f)), a)
        }
    }
}

/** Numbered sheets in the order they come out: whole copies in turn, or page by page. */
@Composable
fun CollateArt(collate: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val phase by loop(3000)
    Art(modifier, if (collate) "Copies come out whole, one after another: 1 2 3, 1 2 3" else "Copies come out page by page: 1 1, 2 2, 3 3") { ink ->
        val colors = listOf(ink.cool, ink.warm, ink.third)
        val order = if (collate) listOf(0, 1, 2, 0, 1, 2) else listOf(0, 0, 1, 1, 2, 2)
        val active = (phase * order.size).toInt().coerceIn(0, order.size - 1)
        order.forEachIndexed { i, page ->
            val x = 5f + i * 24.5f
            val lift = if (i == active) -6f else 0f
            sheet(ink, x, 42f + lift, 21f, 30f, fill = colors[page].copy(alpha = if (i == active) 0.35f else 0.16f), edge = colors[page])
            label(measurer, (page + 1).toString(), Offset(x + 10.5f - 3.5f, 57f + lift - 7f), colors[page])
        }
        drawLine(ink.faint, Offset(8f, 86f), Offset(142f, 86f), 1.5f, StrokeCap.Round)
        drawCircle(ink.cool, 3f, Offset(8f + 134f * phase, 86f))
    }
}

/** The pages laid out on one sheet. */
@Composable
fun PagesPerSheetArt(count: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val phase by loop(2400)
    Art(modifier, "$count pages shrunk onto one sheet") { ink ->
        val landscape = count == 2 || count == 6
        val sw = if (landscape) 92f else 66f
        val sh = if (landscape) 66f else 92f
        val sx = (W - sw) / 2f
        val sy = (H - sh) / 2f
        sheet(ink, sx, sy, sw, sh)
        val cols = when (count) { 1 -> 1; 2 -> 2; 4 -> 2; 6 -> 3; 9 -> 3; else -> 4 }
        val rows = ceil(count / cols.toFloat()).toInt()
        val pad = 6f
        val gap = 3f
        val cw = (sw - 2 * pad - gap * (cols - 1)) / cols
        val ch = (sh - 2 * pad - gap * (rows - 1)) / rows
        val active = (phase * count).toInt().coerceIn(0, count - 1)
        repeat(count) { i ->
            val x = sx + pad + (i % cols) * (cw + gap)
            val y = sy + pad + (i / cols) * (ch + gap)
            drawRoundRect(ink.cool.copy(alpha = if (i == active) 0.45f else 0.16f), Offset(x, y), Size(cw, ch), CornerRadius(2f))
            drawRoundRect(ink.cool, Offset(x, y), Size(cw, ch), CornerRadius(2f), style = Stroke(1f))
            if (count <= 9) label(measurer, (i + 1).toString(), Offset(x + cw / 2f - 3.5f, y + ch / 2f - 7f), ink.cool, if (count == 1) 16f else 12f)
        }
    }
}

/**
 * A pile of three portrait sheets. With [flip] set it turns over about the fold line: a vertical line for the
 * long edge (like a book page, sideways) or a horizontal one for the short edge (like a notepad page, upwards).
 */
@Composable
fun PileArt(pageLabel: String, flip: Boolean, longEdge: Boolean, modifier: Modifier = Modifier, backFace: Boolean = false, showAxis: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val phase by loop(2800)
    Art(modifier, "A pile of printed sheets") { ink ->
        val t = if (flip) ((phase - 0.2f) / 0.55f).coerceIn(0f, 1f) else 0f
        val k = kotlin.math.cos(Math.PI.toFloat() * FastOutSlowInEasing.transform(t))
        val showBack = backFace || (flip && k < 0f)
        val colour = if (showBack) ink.warm else ink.cool
        val cx = 75f
        val cy = 60f
        val sw = 48f
        val sh = 68f
        val kx = if (longEdge && flip) k else 1f
        val ky = if (!longEdge && flip) k else 1f
        repeat(3) { i ->
            val ox = (i - 1) * 4f
            val oy = (1 - i) * 4f
            val w = sw * kotlin.math.abs(kx)
            val h = sh * kotlin.math.abs(ky)
            val x = cx - w / 2f + ox * kx
            val y = cy - h / 2f + oy * ky
            val isTop = i == 2
            drawRoundRect(if (isTop) colour.copy(alpha = 0.18f) else ink.paper, Offset(x, y), Size(w.coerceAtLeast(1.5f), h.coerceAtLeast(1.5f)), CornerRadius(3f))
            drawRoundRect(if (isTop) colour else ink.line, Offset(x, y), Size(w.coerceAtLeast(1.5f), h.coerceAtLeast(1.5f)), CornerRadius(3f), style = Stroke(1.3f))
            if (isTop && pageLabel.isNotEmpty() && w > 24f && h > 34f) {
                label(measurer, pageLabel, Offset(x + w / 2f - 7f, y + h / 2f - 12f), colour, 22f)
            }
        }
        if (showAxis) {
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
            if (longEdge) drawLine(ink.warm, Offset(cx, cy - 46f), Offset(cx, cy + 46f), 2f, StrokeCap.Round, pathEffect = dash)
            else drawLine(ink.warm, Offset(cx - 44f, cy), Offset(cx + 44f, cy), 2f, StrokeCap.Round, pathEffect = dash)
        }
    }
}

/**
 * The three steps of manual two-sided printing, one row each: a picture and a caption with real page numbers.
 * [firstFront] is the page label of the first sheet printed. [firstBack] and [firstBackFront] are the pages on the
 * back and on the front of the first sheet that gets a back printed, or null when no sheet does.
 */
@Composable
fun FlipSteps(longEdge: Boolean, firstFront: String, firstBack: String?, firstBackFront: String?, modifier: Modifier = Modifier) {
    // The picture has room for one number only: "1-4" and "1-3, 7" show as "1".
    fun firstPage(pages: String) = pages.substringBefore('–').substringBefore(',')
    val captions = listOf(
        stringResource(R.string.flip_step1, firstFront),
        stringResource(if (longEdge) R.string.flip_step2_long else R.string.flip_step2_short),
        if (firstBack != null && firstBackFront != null) stringResource(R.string.flip_step3, firstBack, firstBackFront) else stringResource(R.string.flip_step3_plain),
    )
    val labels = listOf(firstPage(firstFront), "", firstBack?.let { firstPage(it) }.orEmpty())
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        captions.forEachIndexed { i, caption ->
            val flip = i == 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                PileArt(labels[i], flip, longEdge, Modifier.width(76.dp), backFace = i == 2, showAxis = flip)
                Text(caption, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
        }
    }
}

/** What a running job is doing, which decides how the printer is animated. */
enum class JobPhase { WORKING, PRINTING, WAITING, DONE, FAILED }

/** A printer that works while a job runs, then ticks or shakes when it ends. */
@Composable
fun PrinterArt(phase: JobPhase, modifier: Modifier = Modifier) {
    val tick by loop(1400)
    val settle by animateFloatAsState(if (phase == JobPhase.DONE || phase == JobPhase.FAILED) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "settle")
    Art(modifier, "A printer") { ink ->
        val shake = if (phase == JobPhase.FAILED) sin(settle * 28f) * (1f - settle) * 3f else 0f
        val body = if (phase == JobPhase.FAILED) ink.bad else ink.cool
        drawRoundRect(body.copy(alpha = 0.16f), Offset(24f + shake, 40f), Size(102f, 46f), CornerRadius(9f))
        drawRoundRect(body, Offset(24f + shake, 40f), Size(102f, 46f), CornerRadius(9f), style = Stroke(1.6f))
        sheet(ink, 44f + shake, 16f, 62f, 28f)
        // Output slot, and the sheet that slides out of it.
        drawLine(ink.line, Offset(40f + shake, 80f), Offset(110f + shake, 80f), 2f, StrokeCap.Round)
        if (phase == JobPhase.PRINTING || phase == JobPhase.WORKING) {
            val out = if (phase == JobPhase.PRINTING) tick else 0.18f + 0.06f * sin(tick * 6.28f)
            val y = 79f + 24f * out
            clipRect(0f, 80f, W, H) {
                sheet(ink, 46f, y - 30f, 58f, 30f)
                textLines(ink, 54f, y - 22f, 42f, 2, 8f)
            }
        }
        val led = when (phase) {
            JobPhase.WAITING -> ink.warm
            JobPhase.FAILED -> ink.bad
            else -> ink.cool
        }
        val blink = if (phase == JobPhase.DONE) 1f else 0.35f + 0.65f * (0.5f + 0.5f * sin(tick * 6.28f))
        drawCircle(led.copy(alpha = blink), 3.4f, Offset(112f + shake, 52f))
        when (phase) {
            JobPhase.DONE -> {
                val r = 17f * settle
                drawCircle(ink.cool, r, Offset(108f, 90f))
                if (settle > 0.5f) {
                    val t = ((settle - 0.5f) * 2f).coerceIn(0f, 1f)
                    val tickPath = Path().apply {
                        moveTo(100f, 90f); lineTo(106f, 96f); lineTo(100f + 16f * t, 90f + 6f - 14f * t)
                    }
                    drawPath(tickPath, ink.paper, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            JobPhase.FAILED -> {
                drawCircle(ink.bad, 15f * settle, Offset(108f, 92f))
                if (settle > 0.6f) {
                    drawLine(ink.paper, Offset(102f, 86f), Offset(114f, 98f), 3f, StrokeCap.Round)
                    drawLine(ink.paper, Offset(114f, 86f), Offset(102f, 98f), 3f, StrokeCap.Round)
                }
            }
            JobPhase.WAITING -> {
                val p = Path().apply { moveTo(96f, 100f); cubicTo(96f, 114f, 124f, 114f, 124f, 100f) }
                arrowPath(p, ink.warm, listOf(Offset(119f, 99f), Offset(124f, 100f), Offset(125f, 105f)), 0.4f + 0.6f * sin(tick * 3.14f))
            }
            else -> Unit
        }
    }
}

/**
 * A printer with the page that is printing right now coming out of it. [fraction] is how much of the page the
 * printer has put on paper, so the visible part of the sheet is exactly the part already printed.
 */
@Composable
fun PrintingArt(page: android.graphics.Bitmap?, fraction: Float, modifier: Modifier = Modifier) {
    val tick by loop(900)
    val image = remember(page) { page?.asImageBitmap() }
    val colorsBody = MaterialTheme.colorScheme.primary
    val colorsAccent = MaterialTheme.colorScheme.primary
    val colorsLine = MaterialTheme.colorScheme.outline
    Canvas(modifier.aspectRatio(100f / 128f).semantics { contentDescription = "The page being printed" }) {
        val k = size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            val body = colorsBody
            drawRoundRect(body.copy(alpha = 0.16f), Offset(6f, 4f), Size(88f, 36f), CornerRadius(8f))
            drawRoundRect(body, Offset(6f, 4f), Size(88f, 36f), CornerRadius(8f), style = Stroke(1.5f))
            drawLine(colorsLine, Offset(14f, 38f), Offset(86f, 38f), 2f, StrokeCap.Round)
            val ratio = if (page != null && page.width > 0) page.height.toFloat() / page.width else 1.414f
            val maxW = 64f
            val maxH = 84f
            val w = if (ratio * maxW <= maxH) maxW else maxH / ratio
            val h = w * ratio
            val x = 50f - w / 2f
            val visible = (h * fraction.coerceIn(0f, 1f))
            if (visible > 0.5f) {
                drawRect(Color.White, Offset(x, 39f), Size(w, visible))
                if (image != null) {
                    val srcH = (image.height * fraction.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
                    drawImage(image, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(image.width, srcH), dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), 39), dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), visible.toInt().coerceAtLeast(1)))
                }
                drawRect(colorsLine, Offset(x, 39f), Size(w, visible), style = Stroke(0.8f))
                // The wet edge: where the ink is going on right now.
                drawLine(colorsAccent.copy(alpha = 0.5f + 0.5f * kotlin.math.abs(kotlin.math.sin(tick * 3.14f))), Offset(x, 39f + visible), Offset(x + w, 39f + visible), 1.6f, StrokeCap.Round)
            }
            drawCircle(colorsAccent.copy(alpha = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(tick * 3.14f))), 3f, Offset(84f, 14f))
        }
    }
}
