package io.github.z3f1rr.autovol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.z3f1rr.autovol.R
import io.github.z3f1rr.autovol.core.Sample

/**
 * Noise level over the last [hours] hours with the step thresholds as dashed lines. Shown in the
 * hero card when the screen has room for it.
 */
@Composable
fun LevelChart(samples: List<Sample>, thresholds: List<Double>, nowSec: Long, hours: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = Oos.Divider
    val hint = Oos.TextSecondary
    Box(modifier) {
        if (samples.size < 2) {
            Text(
                stringResource(R.string.chart_empty),
                color = hint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val minDb = minOf(samples.minOf { it.db }, thresholds.minOrNull() ?: 0.0) - 3
        val maxDb = maxOf(samples.maxOf { it.db }, thresholds.maxOrNull() ?: -120.0) + 3
        val start = nowSec - hours * 3600L
        Canvas(Modifier.fillMaxSize()) {
            fun x(t: Long) = ((t - start).toFloat() / (nowSec - start)).coerceIn(0f, 1f) * size.width
            fun y(db: Double) = (1 - ((db - minDb) / (maxDb - minDb)).toFloat()) * size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            thresholds.forEach { th ->
                drawLine(grid, Offset(0f, y(th)), Offset(size.width, y(th)), 1.dp.toPx(), pathEffect = dash)
            }
            val path = Path()
            samples.forEachIndexed { i, s ->
                if (i == 0) path.moveTo(x(s.tSec), y(s.db)) else path.lineTo(x(s.tSec), y(s.db))
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(
            stringResource(R.string.chart_hours, hours),
            color = hint,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}
