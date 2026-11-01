package com.example.tidewidget.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tidewidget.core.Defaults
import com.example.tidewidget.core.TideMath
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TideMapScreen(vm: TideMapViewModel, hasLocation: Boolean, onLocate: () -> Unit) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val forecasts by vm.forecasts.collectAsStateWithLifecycle()
    val offset by vm.offsetHours.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()

    val camera = rememberCameraPositionState {
        val start = vm.userLocation.value ?: LatLng(Defaults.station.lat, Defaults.station.lng)
        position = CameraPosition.fromLatLngZoom(start, 9f)
    }
    // Fly to the user's location or a searched place.
    LaunchedEffect(focus) {
        focus?.let { (target, zoom) ->
            runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(target, zoom), 800) }
            vm.focus.value = null
        }
    }
    // When the map stops moving (or stations arrive), load predictions for what's on screen.
    LaunchedEffect(camera.isMoving, stations) {
        if (!camera.isMoving) camera.projection?.visibleRegion?.latLngBounds?.let { vm.loadVisible(it) }
    }

    val nowSec = vm.timeSec(offset)
    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = hasLocation),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
            contentPadding = PaddingValues(top = 72.dp, bottom = 96.dp),
        ) {
            stations.forEach { s ->
                val events = forecasts[s.id] ?: return@forEach
                val color = Color(TideMath.colorArgb(TideMath.phase(events, nowSec) ?: 0.5))
                key(s.id) {
                    MarkerComposable(
                        color,
                        state = rememberMarkerState(key = s.id, position = LatLng(s.lat, s.lng)),
                        title = s.name,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        onClick = { vm.open(s); true },
                    ) {
                        Box(Modifier.size(16.dp).background(color, CircleShape).border(1.5.dp, Color.White, CircleShape))
                    }
                }
            }
        }

        SearchBox(vm, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))

        error?.let {
            Text(it, Modifier.align(Alignment.Center).padding(12.dp)
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(8.dp))
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.End) {
            SmallFloatingActionButton(onClick = onLocate) { Icon(Icons.Filled.LocationOn, "Center on my location") }
            Spacer(Modifier.height(8.dp))
            TimeScrubber(offset, nowSec, onChange = { vm.offsetHours.value = it })
        }
    }
}

/** Type a beach, town, or address; we geocode it and suggest the closest tide stations. */
@Composable
private fun SearchBox(vm: TideMapViewModel, modifier: Modifier = Modifier) {
    val query by vm.query.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
            TextField(
                value = query,
                onValueChange = vm::search,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search a beach or place") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.clearSearch(); focusManager.clearFocus() }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(suggestions) { sug ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(sug.label, fontWeight = FontWeight.SemiBold)
                                    if (sug.detail.isNotBlank()) Text(sug.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val isStation = sug.nearest.singleOrNull()?.km == 0.0
                            if (!isStation) Text("Closest tide stations", Modifier.padding(start = 26.dp, top = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            sug.nearest.forEachIndexed { i, n ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.open(n.station); vm.clearSearch(); focusManager.clearFocus() }
                                        .padding(start = 26.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (isStation) "Open station" else n.station.name, Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                                    if (!isStation) Text("%.1f mi".format(n.miles) + if (i == 0) " · best match" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val scrubFormat = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault())

/** Drag from now to +24h and watch the tide move along the coast, like a radar loop. */
@Composable
private fun TimeScrubber(offsetHours: Float, timeSec: Long, onChange: (Float) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(0.0); Text(" Low", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(scrubFormat.format(Instant.ofEpochSecond(timeSec)), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text("High ", style = MaterialTheme.typography.labelMedium); LegendDot(1.0)
            }
            Slider(value = offsetHours, onValueChange = onChange, valueRange = 0f..24f, steps = 95)
        }
    }
}

@Composable
fun LegendDot(phase: Double) =
    Box(Modifier.size(10.dp).background(Color(TideMath.colorArgb(phase)), CircleShape))
