package com.example.tidewidget.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.tidewidget.core.Station
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.core.TideKind
import com.example.tidewidget.core.TideMath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val rowFormat = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault())

@Composable
fun StationSheet(station: Station, events: List<TideEvent>, onUseForWidget: () -> Unit) {
    val now = Instant.now().epochSecond
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text(listOfNotNull(station.name, station.state).joinToString(", "), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        TideCurve(events, fromSec = now, hours = 24, modifier = Modifier.fillMaxWidth().height(140.dp))
        Spacer(Modifier.height(12.dp))
        events.filter { it.epochSec > now }.take(6).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val high = e.kind == TideKind.HIGH
                Text(if (high) "▲ High" else "▼ Low", color = Color(TideMath.colorArgb(if (high) 1.0 else 0.0)))
                Spacer(Modifier.weight(1f))
                Text(rowFormat.format(Instant.ofEpochSecond(e.epochSec)))
                Text("  %.1f ft".format(e.heightFt), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onUseForWidget, modifier = Modifier.fillMaxWidth()) { Text("Use for my widget") }
    }
}

/** Smooth 24h tide curve with a marker at "now". */
@Composable
fun TideCurve(events: List<TideEvent>, fromSec: Long, hours: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val samples = (0..hours * 4).mapNotNull { i -> TideMath.height(events, fromSec + i * 900L) }
    if (samples.size < 2) { Box(modifier) { Text("No prediction data", Modifier.align(Alignment.Center)) }; return }
    val lo = samples.min(); val hi = maxOf(samples.max(), lo + 0.1)
    Canvas(modifier) {
        fun pt(i: Int, h: Double) = Offset(size.width * i / (samples.size - 1), (size.height * (1 - (h - lo) / (hi - lo))).toFloat())
        val curve = Path().apply { samples.forEachIndexed { i, h -> pt(i, h).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
        val fill = Path().apply { addPath(curve); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))))
        drawPath(curve, line, style = Stroke(width = 3.dp.toPx()))
        drawCircle(line, radius = 5.dp.toPx(), center = pt(0, samples[0]))
    }
}
