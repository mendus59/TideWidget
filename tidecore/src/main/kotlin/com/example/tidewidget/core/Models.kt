package com.example.tidewidget.core

import kotlinx.serialization.Serializable

/** A NOAA CO-OPS tide prediction station. */
@Serializable
data class Station(
    val id: String,
    val name: String,
    val state: String? = null,
    val lat: Double,
    val lng: Double,
    /** For NOAA subordinate stations ("S"): the reference station whose tide curve this one is offset from. */
    val referenceId: String? = null,
)

@Serializable
enum class TideKind { HIGH, LOW }

/** One predicted high or low tide. [epochSec] is UTC seconds; [heightFt] is feet above MLLW. */
@Serializable
data class TideEvent(val epochSec: Long, val heightFt: Double, val kind: TideKind)

/** What the widget needs, cached on disk so it works offline. */
@Serializable
data class StationForecast(val station: Station, val events: List<TideEvent>, val fetchedEpochSec: Long)

object Defaults {
    /** Used until the user picks a beach. */
    val station = Station(id = "9414290", name = "San Francisco", state = "CA", lat = 37.8063, lng = -122.4659)
}
