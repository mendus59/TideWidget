package com.example.tidewidget.core

import kotlin.math.*

object Geo {
    private const val EARTH_KM = 6371.0
    const val KM_PER_MILE = 1.609344

    /** Great-circle distance in km. */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    data class Nearby(val station: Station, val km: Double) {
        val miles get() = km / KM_PER_MILE
    }

    /** The [count] stations closest to a point, nearest first. */
    fun nearest(stations: List<Station>, lat: Double, lng: Double, count: Int = 3): List<Nearby> =
        stations.asSequence()
            .map { Nearby(it, distanceKm(lat, lng, it.lat, it.lng)) }
            .sortedBy { it.km }
            .take(count)
            .toList()
}
