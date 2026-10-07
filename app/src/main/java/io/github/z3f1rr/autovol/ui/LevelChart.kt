package io.github.z3f1rr.autovol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.z3f1rr.autovol.R
import io.github.z3f1rr.autovol.core.Sample
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Samples further apart than this are not joined (paused, phone off). */
private const val GAP_SEC = 20 * 60L

/** How long the value popup stays after the finger is lifted. */
private const val POPUP_MS = 4000L

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun hhmm(tSec: Long): String = TimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(tSec))

private fun dbText(db: Double): String = String.format(Locale.ROOT, "%.0f", db).replace("-", "−")

/**
 * Noise level over the last [hours] hours: dB scale on the left, time scale at the bottom, the step
 * thresholds as dashed lines. Touching the chart shows the nearest measurement in a popup.
 */
@Composable
fun LevelChart(samples: List<Sample>, thresholds: List<Double>, nowSec: Long, hours: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = Oos.Divider
    val hint = Oos.TextSecondary
    val ink = Oos.TextPrimary
    val popupBg = Oos.CardHigh
    val ring = Oos.Card
    val small = MaterialTheme.typography.labelSmall
    val unit = stringResource(R.string.unit_dba)
    val measurer = rememberTextMeasurer()
    var selected by remember { mutableStateOf<Int?>(null) }
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(selected, touching) {
        if (selected != null && !touching) {
            delay(POPUP_MS)
            selected = null
        }
    }

    Column(modifier) {
        Text(
            stringResource(R.string.chart_hours, hours),
            color = hint,
            style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            if (samples.size < 2) {
                Text(
                    stringResource(R.string.chart_empty),
                    color = hint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
                return@Box
            }
            val lo = floor((minOf(samples.minOf { it.db }, thresholds.minOrNull() ?: 0.0) - 2) / 10) * 10
            val hi = ceil((maxOf(samples.maxOf { it.db }, thresholds.maxOrNull() ?: -120.0) + 2) / 10) * 10
            val yStep = if (hi - lo > 60) 20.0 else 10.0
            val start = nowSec - hours * 3600L
            val desc = stringResource(R.string.chart_desc, dbText(samples.minOf { it.db }), dbText(samples.maxOf { it.db }), unit)

            // plot geometry, shared by drawing and touch handling
            val yLabels = generateSequence(lo) { it + yStep }.takeWhile { it <= hi }.toList()
            val gutter = yLabels.maxOf { measurer.measure(dbText(it), small).size.width }
            val axisH = measurer.measure("00:00", small).size.height

            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = desc }
                    .pointerInput(samples, nowSec) {
                        val left = gutter + 6.dp.toPx()
                        fun nearest(px: Float): Int {
                            val t = start + ((px - left) / (size.width - left)).coerceIn(0f, 1f) * (nowSec - start)
                            return samples.indices.minBy { abs(samples[it].tSec - t) }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            touching = true
                            selected = nearest(down.position.x)
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (ch.pressed) {
                                    selected = nearest(ch.position.x)
                                    // a horizontal scrub belongs to the chart
                                    if (abs(ch.position.x - ch.previousPosition.x) > abs(ch.position.y - ch.previousPosition.y)) ch.consume()
                                }
                            } while (ev.changes.any { it.pressed })
                            touching = false
                        }
                    },
            ) {
                val left = gutter + 6.dp.toPx()
                val bottom = size.height - axisH - 4.dp.toPx()
                val w = size.width - left
                fun x(t: Long) = left + ((t - start).toFloat() / (nowSec - start)).coerceIn(0f, 1f) * w
                fun y(db: Double) = (1 - ((db - lo) / (hi - lo)).toFloat()) * bottom

                // dB scale and recessive grid
                yLabels.forEach { v ->
                    val yy = y(v)
                    drawLine(grid, Offset(left, yy), Offset(size.width, yy), 1.dp.toPx() / 2)
                    val tl = measurer.measure(dbText(v), small)
                    val ty = (yy - tl.size.height / 2f).coerceIn(0f, bottom - tl.size.height)
                    drawText(tl, hint, Offset(gutter - tl.size.width.toFloat(), ty))
                }
                // time scale: whole hours, thinned to fit
                val labelW = measurer.measure("00:00", small).size.width + 8.dp.toPx()
                val hourPx = w / hours
                val every = (1..hours).first { it * hourPx >= labelW }
                val firstHour = (start / 3600 + 1) * 3600
                var t = firstHour
                var i = 0
                while (t <= nowSec) {
                    val xx = x(t)
                    drawLine(grid, Offset(xx, bottom), Offset(xx, bottom + 3.dp.toPx()), 1.dp.toPx())
                    if (i % every == 0) {
                        val tl = measurer.measure(hhmm(t), small)
                        val tx = (xx - tl.size.width / 2f).coerceIn(left, size.width - tl.size.width)
                        drawText(tl, hint, Offset(tx, bottom + 4.dp.toPx()))
                    }
                    t += 3600
                    i++
                }
                drawLine(grid, Offset(left, bottom), Offset(size.width, bottom), 1.dp.toPx())

                // step thresholds
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                thresholds.forEach { th ->
                    drawLine(grid, Offset(left, y(th)), Offset(size.width, y(th)), 1.dp.toPx(), pathEffect = dash)
                }

                // the level line, broken where there were no measurements
                val path = Path()
                samples.forEachIndexed { k, s ->
                    if (k == 0 || s.tSec - samples[k - 1].tSec > GAP_SEC) path.moveTo(x(s.tSec), y(s.db)) else path.lineTo(x(s.tSec), y(s.db))
                }
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                // crosshair, marker and popup for the touched measurement
                val sel = selected?.takeIf { it in samples.indices } ?: return@Canvas
                val s = samples[sel]
                val px = x(s.tSec)
                val py = y(s.db)
                drawLine(hint, Offset(px, 0f), Offset(px, bottom), 1.dp.toPx())
                drawCircle(ring, 6.dp.toPx(), Offset(px, py))
                drawCircle(line, 4.dp.toPx(), Offset(px, py))

                val tl = measurer.measure("${hhmm(s.tSec)}  ${dbText(s.db)} $unit", small.copy(color = ink))
                val pad = 6.dp.toPx()
                val bw = tl.size.width + 2 * pad
                val bh = tl.size.height + 2 * pad
                val bx = (px - bw / 2).coerceIn(left, size.width - bw)
                // above the point if there is room, otherwise below
                val by = if (py - bh - 10.dp.toPx() >= 0) py - bh - 10.dp.toPx() else (py + 10.dp.toPx()).coerceAtMost(bottom - bh)
                drawRoundRect(popupBg, Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()))
                drawRoundRect(grid, Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawText(tl, topLeft = Offset(bx + pad, by + pad))
            }
        }
    }
}
