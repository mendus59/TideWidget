package com.example.tidewidget.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tidewidget.core.Station
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.core.TideKind
import com.example.tidewidget.core.TideMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone get() = ZoneId.systemDefault()
private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val dayFormat = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val dayTick = DateTimeFormatter.ofPattern("EEE")

/** Station detail: today's curve, a 7-day chart, and every high and low for the week. */
@Composable
fun StationSheet(vm: TideMapViewModel, station: Station, onUseForWidget: () -> Unit) {
    val week by vm.week.collectAsStateWithLifecycle()
    val short by vm.forecasts.collectAsStateWithLifecycle()
    LaunchedEffect(station.id) { vm.loadWeek(station.id) }

    val events = week[station.id] ?: short[station.id].orEmpty()
    val now = Instant.now().epochSecond
    val upcoming = events.filter { it.epochSec > now }
    val byDay = upcoming.groupBy { Instant.ofEpochSecond(it.epochSec).atZone(zone).toLocalDate() }.toSortedMap()
    val area = remember(station.id) { vm.areaOf(station.id) }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(listOfNotNull(station.name, station.state).joinToString(", "), style = MaterialTheme.typography.titleLarge)
            if (area != null && area.stations.size > 1) {
                Text("Part of the ${area.name} area · ${area.stations.size} stations tide together",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text("Next 24 hours", style = MaterialTheme.typography.labelLarge)
            TideCurve(events, fromSec = now, hours = 24, modifier = Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onUseForWidget, modifier = Modifier.fillMaxWidth()) { Text("Use for my widget") }
            Spacer(Modifier.height(16.dp))
            Text("Next 7 days", style = MaterialTheme.typography.labelLarge)
            if (station.id !in week) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 24.dp))
            } else {
                // Scrolls sideways: ~110dp per day keeps each tide cycle readable.
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    WeekChart(events, now, Modifier.width(770.dp).height(140.dp))
                }
            }
        }
        byDay.forEach { (day, list) ->
            item(key = day.toString()) {
                val label = when (day) {
                    LocalDate.now(zone) -> "Today"
                    LocalDate.now(zone).plusDays(1) -> "Tomorrow"
                    else -> dayFormat.format(day)
                }
                Text(label, Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold)
            }
            items(list, key = { it.epochSec }) { e -> TideRow(e) }
        }
    }
}

@Composable
private fun TideRow(e: TideEvent) {
    val high = e.kind == TideKind.HIGH
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(if (high) 1.0 else 0.0)
        Text(if (high) "  High" else "  Low", Modifier.width(64.dp))
        Text(timeFormat.format(Instant.ofEpochSecond(e.epochSec).atZone(zone)), Modifier.weight(1f))
        Text("%.1f ft".format(e.heightFt), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Smooth tide curve with a marker at [fromSec]. */
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

/** Seven days on one chart, with midnight gridlines and day labels. */
@Composable
private fun WeekChart(events: List<TideEvent>, nowSec: Long, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val start = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
    val end = start + 7 * 86400
    val step = 1800L
    val samples = ((start / step)..(end / step)).mapNotNull { k -> TideMath.height(events, k * step)?.let { k * step to it } }
    if (samples.size < 2) { Box(modifier) { Text("No prediction data", Modifier.align(Alignment.Center)) }; return }
    val lo = samples.minOf { it.second }; val hi = maxOf(samples.maxOf { it.second }, lo + 0.1)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            fun x(t: Long) = (size.width * (t - start) / (end - start).toDouble()).toFloat()
            fun y(h: Double) = (size.height * (1 - (h - lo) / (hi - lo))).toFloat()
            for (d in 0..7) drawLine(grid, Offset(x(start + d * 86400L), 0f), Offset(x(start + d * 86400L), size.height), 1.dp.toPx())
            val curve = Path().apply { samples.forEachIndexed { i, (t, h) -> if (i == 0) moveTo(x(t), y(h)) else lineTo(x(t), y(h)) } }
            val fill = Path().apply { addPath(curve); lineTo(x(samples.last().first), size.height); lineTo(x(samples.first().first), size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.3f), line.copy(alpha = 0.02f))))
            drawPath(curve, line, style = Stroke(width = 2.dp.toPx()))
            drawLine(labelColor, Offset(x(nowSec), 0f), Offset(x(nowSec), size.height), 1.5.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            for (d in 0 until 7) {
                Text(dayTick.format(LocalDate.now(zone).plusDays(d.toLong())), Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}
