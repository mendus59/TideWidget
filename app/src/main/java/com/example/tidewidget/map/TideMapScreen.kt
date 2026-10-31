package com.example.tidewidget.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tidewidget.core.Defaults
import com.example.tidewidget.core.Station
import com.example.tidewidget.core.TideMath
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TideMapScreen(vm: TideMapViewModel = viewModel()) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val forecasts by vm.forecasts.collectAsStateWithLifecycle()
    val offset by vm.offsetHours.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Station?>(null) }

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(Defaults.station.lat, Defaults.station.lng), 8f)
    }
    // When the map stops moving (or stations arrive), load predictions for what's on screen.
    LaunchedEffect(camera.isMoving, stations) {
        if (!camera.isMoving) camera.projection?.visibleRegion?.latLngBounds?.let { vm.loadVisible(it) }
    }

    val nowSec = vm.timeSec(offset)
    Box(Modifier.fillMaxSize()) {
        GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, uiSettings = MapUiSettings(zoomControlsEnabled = false)) {
            stations.forEach { s ->
                val events = forecasts[s.id] ?: return@forEach
                val color = Color(TideMath.colorArgb(TideMath.phase(events, nowSec) ?: 0.5))
                key(s.id) {
                    MarkerComposable(
                        color,
                        state = rememberMarkerState(key = s.id, position = LatLng(s.lat, s.lng)),
                        title = s.name,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                        onClick = { selected = s; true },
                    ) {
                        Box(Modifier.size(16.dp).background(color, CircleShape).border(1.5.dp, Color.White, CircleShape))
                    }
                }
            }
        }

        error?.let {
            Text(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(8.dp))
        }

        TimeScrubber(
            offsetHours = offset,
            timeSec = nowSec,
            onChange = { vm.offsetHours.value = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }

    selected?.let { s ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            StationSheet(
                station = s,
                events = forecasts[s.id].orEmpty(),
                onUseForWidget = { vm.useForWidget(s) { selected = null } },
            )
        }
    }
}

private val scrubFormat = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault())

/** Drag from now to +24h and watch the tide move along the coast, like a radar loop. */
@Composable
private fun TimeScrubber(offsetHours: Float, timeSec: Long, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
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
private fun LegendDot(phase: Double) =
    Box(Modifier.size(10.dp).background(Color(TideMath.colorArgb(phase)), CircleShape))
