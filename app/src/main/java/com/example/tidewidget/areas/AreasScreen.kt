package com.example.tidewidget.areas

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tidewidget.core.Area
import com.example.tidewidget.core.Geo
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.core.TideMath
import com.example.tidewidget.core.TideWindow
import com.example.tidewidget.map.LegendDot
import com.example.tidewidget.map.TideMapViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

/**
 * Optional, broader view: stations NOAA predicts from the same reference station rise and fall
 * nearly together, so each area shows a window for its next high and low.
 */
@Composable
fun AreasScreen(vm: TideMapViewModel, onShowOnMap: () -> Unit) {
    val areas by vm.areas.collectAsStateWithLifecycle()
    val forecasts by vm.forecasts.collectAsStateWithLifecycle()
    val here by vm.userLocation.collectAsStateWithLifecycle()
    val nearby = remember(areas, here) { vm.nearbyAreas() }
    val now = Instant.now().epochSecond
    var expanded by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Areas", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Nearby beaches that tide together, grouped by the NOAA station they're predicted from. " +
                    "Use this for a general feel of when highs and lows come through.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (here == null) Text("Showing areas near San Francisco. Allow location to see yours.",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (areas.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 24.dp)) }
        items(nearby, key = { it.first.id }) { (area, km) ->
            LaunchedEffect(area.id) { vm.loadArea(area) }
            AreaCard(area, km, forecasts, now, expanded == area.id,
                onToggle = { expanded = if (expanded == area.id) null else area.id },
                onStation = { vm.open(it); onShowOnMap() })
        }
    }
}

@Composable
private fun AreaCard(
    area: Area, km: Double, forecasts: Map<String, List<TideEvent>>, now: Long, expanded: Boolean,
    onToggle: () -> Unit, onStation: (com.example.tidewidget.core.Station) -> Unit,
) {
    val outlook = Areas2.outlook(area, forecasts, now)
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(area.name, area.state).joinToString(", "), fontWeight = FontWeight.SemiBold)
                    Text("${area.stations.size} station${if (area.stations.size == 1) "" else "s"} · %.0f mi away".format(km / Geo.KM_PER_MILE),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (outlook.stationsReporting == 0) {
                Text("Loading tides…", style = MaterialTheme.typography.bodySmall)
            } else {
                WindowRow("High", 1.0, outlook.high)
                WindowRow("Low", 0.0, outlook.low)
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                area.stations.forEach { s ->
                    val next = forecasts[s.id]?.let { TideMath.nextHighAndLow(it, now) }
                    Row(Modifier.fillMaxWidth().clickable { onStation(s) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        if (next != null) Text(
                            listOfNotNull(next.high?.let { "▲ " + timeFormat.format(Instant.ofEpochSecond(it.epochSec)) },
                                next.low?.let { "▼ " + timeFormat.format(Instant.ofEpochSecond(it.epochSec)) }).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (area.stations.size > 1) {
                Text("Tap to see all stations", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WindowRow(label: String, phase: Double, w: TideWindow?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        LegendDot(phase)
        Text("  $label", Modifier.width(56.dp))
        Text(
            when {
                w == null -> "—"
                w.first.epochSec == w.last.epochSec -> timeFormat.format(Instant.ofEpochSecond(w.first.epochSec))
                else -> timeFormat.format(Instant.ofEpochSecond(w.first.epochSec)) + " – " + timeFormat.format(Instant.ofEpochSecond(w.last.epochSec))
            },
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Area outlook restricted to stations we have data for. */
private object Areas2 {
    fun outlook(area: Area, forecasts: Map<String, List<TideEvent>>, now: Long) =
        com.example.tidewidget.core.Areas.outlook(area.stations.mapNotNull { s -> forecasts[s.id]?.let { s.id to it } }.toMap(), now)
}
