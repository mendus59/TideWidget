package com.example.tidewidget.core

/**
 * An "area" is a group of stations whose tides move together.
 *
 * NOAA predicts most stations ("subordinate") as fixed time and height offsets from a nearby
 * "reference" station, so a reference station plus its subordinates rise and fall nearly in step.
 * Grouping by that reference gives each area a general feel for when highs and lows happen.
 */
data class Area(
    val id: String,
    val name: String,
    val state: String?,
    /** The reference station first, then subordinates nearest to it. */
    val stations: List<Station>,
) {
    val reference get() = stations.first()
    val lat get() = stations.map { it.lat }.average()
    val lng get() = stations.map { it.lng }.average()
}

/** When the next high (or low) arrives across an area: earliest and latest member. */
data class TideWindow(val first: TideEvent, val last: TideEvent)

data class AreaOutlook(val high: TideWindow?, val low: TideWindow?, val stationsReporting: Int)

object Areas {
    fun group(stations: List<Station>): List<Area> {
        val byId = stations.associateBy { it.id }
        return stations
            .groupBy { s -> s.referenceId?.takeIf { it in byId } ?: s.id }
            .map { (refId, members) ->
                val ref = byId.getValue(refId)
                val ordered = listOf(ref) + members.filter { it.id != refId }
                    .sortedBy { Geo.distanceKm(ref.lat, ref.lng, it.lat, it.lng) }
                Area(id = refId, name = ref.name, state = ref.state, stations = ordered)
            }
    }

    /** The area containing [stationId], if any. */
    fun areaOf(areas: List<Area>, stationId: String): Area? = areas.firstOrNull { a -> a.stations.any { it.id == stationId } }

    /**
     * Next high and low window across an area. Members whose next tide is more than [maxSpreadSec]
     * after the earliest one are already on the following tide and are ignored.
     */
    fun outlook(forecasts: Map<String, List<TideEvent>>, nowSec: Long, maxSpreadSec: Long = 4 * 3600): AreaOutlook {
        fun window(kind: TideKind): TideWindow? {
            val next = forecasts.values.mapNotNull { ev -> ev.firstOrNull { it.kind == kind && it.epochSec > nowSec } }
                .sortedBy { it.epochSec }
            val first = next.firstOrNull() ?: return null
            val inStep = next.filter { it.epochSec - first.epochSec <= maxSpreadSec }
            return TideWindow(first, inStep.last())
        }
        return AreaOutlook(window(TideKind.HIGH), window(TideKind.LOW), forecasts.size)
    }
}
