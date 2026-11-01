package com.example.tidewidget.map

import android.annotation.SuppressLint
import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tidewidget.core.Area
import com.example.tidewidget.core.AreaOutlook
import com.example.tidewidget.core.Areas
import com.example.tidewidget.core.Geo
import com.example.tidewidget.core.NoaaClient
import com.example.tidewidget.core.Station
import com.example.tidewidget.core.StationForecast
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.data.ForecastStore
import com.example.tidewidget.data.StationCache
import com.example.tidewidget.widget.TideRefreshWorker
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.resume

/** A search hit: a place (or station) and the tide stations closest to it. */
data class Suggestion(val label: String, val detail: String, val lat: Double, val lng: Double, val nearest: List<Geo.Nearby>)

/** Shared by the Map and Areas tabs (one instance per activity). */
class TideMapViewModel(app: Application) : AndroidViewModel(app) {
    private val client = NoaaClient()
    private val store = ForecastStore(app)
    private val stationCache = StationCache(app)
    private val loading = mutableSetOf<String>()
    private val startSec = Instant.now().epochSecond

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations

    private val _areas = MutableStateFlow<List<Area>>(emptyList())
    val areas: StateFlow<List<Area>> = _areas

    /** ~48h of highs/lows per station, for the map colors and area outlooks. */
    private val _forecasts = MutableStateFlow<Map<String, List<TideEvent>>>(emptyMap())
    val forecasts: StateFlow<Map<String, List<TideEvent>>> = _forecasts

    /** 8 days of highs/lows for stations the user opened. */
    private val _week = MutableStateFlow<Map<String, List<TideEvent>>>(emptyMap())
    val week: StateFlow<Map<String, List<TideEvent>>> = _week

    /** Hours ahead of now shown on the map (the weather-map style scrubber). */
    val offsetHours = MutableStateFlow(0f)
    fun timeSec(offset: Float = offsetHours.value) = startSec + (offset * 3600).toLong()

    val error = MutableStateFlow<String?>(null)
    val userLocation = MutableStateFlow<LatLng?>(null)
    /** Where the map should fly to next; consumed by the map screen. */
    val focus = MutableStateFlow<Pair<LatLng, Float>?>(null)
    val selected = MutableStateFlow<Station?>(null)

    val query = MutableStateFlow("")
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            stationCache.load()?.let { setStations(it) }
            if (stationCache.isStale()) {
                runCatching { client.stations() }
                    .onSuccess { setStations(it); stationCache.save(it) }
                    .onFailure { if (_stations.value.isEmpty()) error.value = "Couldn't load stations: ${it.message}" }
            }
        }
    }

    private fun setStations(list: List<Station>) {
        _stations.value = list
        _areas.value = Areas.group(list)
    }

    // --- Location -------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // Only called after the permission is granted.
    fun locateUser(flyTo: Boolean = true) = viewModelScope.launch {
        val client = LocationServices.getFusedLocationProviderClient(getApplication<Application>())
        val loc = runCatching {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
                ?: client.lastLocation.await()
        }.getOrNull() ?: return@launch
        val here = LatLng(loc.latitude, loc.longitude)
        userLocation.value = here
        if (flyTo) focus.value = here to 10f
    }

    // --- Map data -------------------------------------------------------------------------

    /** Fetch predictions only for stations on screen, capped so we stay polite to NOAA. */
    fun loadVisible(bounds: LatLngBounds, limit: Int = 60) = viewModelScope.launch {
        fetchForecasts(_stations.value.filter { bounds.contains(LatLng(it.lat, it.lng)) }.map { it.id }.take(limit * 2), limit)
    }

    private suspend fun fetchForecasts(ids: List<String>, limit: Int = 60) {
        val todo = ids.filter { it !in _forecasts.value && it !in loading }.take(limit)
        if (todo.isEmpty()) return
        loading += todo
        todo.map { id ->
            viewModelScope.async { id to runCatching { client.highsAndLows(id, Instant.ofEpochSecond(startSec - 6 * 3600), 48) }.getOrNull() }
        }.awaitAll().forEach { (id, events) ->
            loading -= id
            if (events != null) _forecasts.update { it + (id to events) }
        }
    }

    fun loadWeek(stationId: String) = viewModelScope.launch {
        if (stationId in _week.value) return@launch
        runCatching { client.highsAndLows(stationId, Instant.now(), hours = 8 * 24) }
            .onSuccess { ev -> _week.update { it + (stationId to ev) } }
    }

    // --- Areas ----------------------------------------------------------------------------

    fun areaOf(stationId: String): Area? = Areas.areaOf(_areas.value, stationId)

    /** Areas sorted by distance from the user (or the default beach). */
    fun nearbyAreas(count: Int = 40): List<Pair<Area, Double>> {
        val here = userLocation.value ?: LatLng(com.example.tidewidget.core.Defaults.station.lat, com.example.tidewidget.core.Defaults.station.lng)
        return _areas.value.map { it to Geo.distanceKm(here.latitude, here.longitude, it.lat, it.lng) }
            .sortedBy { it.second }.take(count)
    }

    /** Loads the reference station plus a few nearby members, enough for a general outlook. */
    fun loadArea(area: Area, members: Int = 4) = viewModelScope.launch {
        fetchForecasts(area.stations.take(members).map { it.id })
    }

    fun outlook(area: Area, forecasts: Map<String, List<TideEvent>>, nowSec: Long): AreaOutlook =
        Areas.outlook(area.stations.mapNotNull { s -> forecasts[s.id]?.let { s.id to it } }.toMap(), nowSec)

    // --- Search ---------------------------------------------------------------------------

    fun search(text: String) {
        query.value = text
        searchJob?.cancel()
        if (text.isBlank()) { _suggestions.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(350) // debounce typing
            val q = text.trim()
            val stations = _stations.value
            val byName = stations.filter { it.name.contains(q, ignoreCase = true) }.take(4).map { s ->
                Suggestion(s.name, listOfNotNull("Tide station", s.state).joinToString(" · "), s.lat, s.lng, listOf(Geo.Nearby(s, 0.0)))
            }
            val places = geocode(q).map { a ->
                val label = listOfNotNull(a.featureName, a.locality, a.adminArea).distinct().joinToString(", ")
                Suggestion(label, a.countryName ?: "", a.latitude, a.longitude, Geo.nearest(stations, a.latitude, a.longitude, 3))
            }
            _suggestions.value = byName + places
        }
    }

    fun clearSearch() { searchJob?.cancel(); query.value = ""; _suggestions.value = emptyList() }

    fun open(station: Station) {
        selected.value = station
        focus.value = LatLng(station.lat, station.lng) to 11f
        loadWeek(station.id)
        viewModelScope.launch { fetchForecasts(listOf(station.id)) }
    }

    private suspend fun geocode(q: String): List<Address> {
        val app = getApplication<Application>()
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(app)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(q, 4, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                    })
                }
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION") geocoder.getFromLocationName(q, 4).orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    // --- Widget ---------------------------------------------------------------------------

    fun useForWidget(station: Station, onDone: () -> Unit) = viewModelScope.launch {
        val events = runCatching { client.highsAndLows(station.id) }.getOrNull() ?: _forecasts.value[station.id].orEmpty()
        store.save(StationForecast(station, events, Instant.now().epochSecond))
        TideRefreshWorker.refreshNow(getApplication())
        onDone()
    }
}
