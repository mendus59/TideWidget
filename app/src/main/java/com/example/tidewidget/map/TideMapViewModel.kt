package com.example.tidewidget.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tidewidget.core.NoaaClient
import com.example.tidewidget.core.Station
import com.example.tidewidget.core.StationForecast
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.data.ForecastStore
import com.example.tidewidget.widget.TideRefreshWorker
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class TideMapViewModel(app: Application) : AndroidViewModel(app) {
    private val client = NoaaClient()
    private val store = ForecastStore(app)
    private val loading = mutableSetOf<String>()
    private val startSec = Instant.now().epochSecond

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations

    private val _forecasts = MutableStateFlow<Map<String, List<TideEvent>>>(emptyMap())
    val forecasts: StateFlow<Map<String, List<TideEvent>>> = _forecasts

    /** Hours ahead of now shown on the map (the weather-map style scrubber). */
    val offsetHours = MutableStateFlow(0f)
    fun timeSec(offset: Float = offsetHours.value) = startSec + (offset * 3600).toLong()

    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            runCatching { client.stations() }
                .onSuccess { _stations.value = it }
                .onFailure { error.value = "Couldn't load stations: ${it.message}" }
        }
    }

    /** Fetch predictions only for stations on screen, capped so we stay polite to NOAA. */
    fun loadVisible(bounds: LatLngBounds, limit: Int = 60) = viewModelScope.launch {
        val todo = _stations.value
            .filter { bounds.contains(LatLng(it.lat, it.lng)) && it.id !in _forecasts.value && it.id !in loading }
            .take(limit)
        loading += todo.map { it.id }
        todo.map { s ->
            async { s.id to runCatching { client.highsAndLows(s.id, Instant.ofEpochSecond(startSec - 6 * 3600), 48) }.getOrNull() }
        }.awaitAll().forEach { (id, events) ->
            loading -= id
            if (events != null) _forecasts.update { it + (id to events) }
        }
    }

    fun useForWidget(station: Station, onDone: () -> Unit) = viewModelScope.launch {
        val events = runCatching { client.highsAndLows(station.id) }.getOrNull() ?: _forecasts.value[station.id].orEmpty()
        store.save(StationForecast(station, events, Instant.now().epochSecond))
        TideRefreshWorker.refreshNow(getApplication())
        onDone()
    }
}
