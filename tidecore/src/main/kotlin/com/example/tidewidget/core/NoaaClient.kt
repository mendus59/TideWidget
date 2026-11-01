package com.example.tidewidget.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Thin client for NOAA CO-OPS: free, no API key, US coasts and territories.
 * Docs: https://api.tidesandcurrents.noaa.gov/api/prod/
 */
class NoaaClient(private val fetch: suspend (String) -> String = ::httpGet) {

    /** All stations that publish tide predictions (~3,000). */
    suspend fun stations(): List<Station> =
        parseStations(fetch("https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations.json?type=tidepredictions"))

    /** High/low predictions from 12h before [from] through [hours] after it. */
    suspend fun highsAndLows(stationId: String, from: Instant = Instant.now(), hours: Int = 72): List<TideEvent> {
        val begin = DAY.format(from.minusSeconds(12 * 3600).atOffset(ZoneOffset.UTC))
        val params = mapOf(
            "station" to stationId, "product" to "predictions", "interval" to "hilo",
            "datum" to "MLLW", "units" to "english", "time_zone" to "gmt", "format" to "json",
            "begin_date" to begin, "range" to (hours + 12).toString(), "application" to "TideWidget",
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return parsePredictions(fetch("https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?$params"))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        @Serializable private data class StationsResp(val stations: List<S>) {
            @Serializable data class S(
                val id: String, val name: String, val state: String? = null, val lat: Double, val lng: Double,
                val type: String? = null, val reference_id: String? = null,
            )
        }
        @Serializable private data class PredResp(val predictions: List<P> = emptyList(), val error: Err? = null) {
            @Serializable data class P(val t: String, val v: String, val type: String)
            @Serializable data class Err(val message: String)
        }

        fun parseStations(body: String): List<Station> =
            json.decodeFromString<StationsResp>(body).stations.map {
                val ref = it.reference_id?.ifBlank { null }?.takeIf { r -> it.type == "S" && r != it.id }
                Station(it.id, it.name, it.state?.ifBlank { null }, it.lat, it.lng, ref)
            }

        fun parsePredictions(body: String): List<TideEvent> {
            val resp = json.decodeFromString<PredResp>(body)
            resp.error?.let { throw IllegalStateException("NOAA: ${it.message}") }
            return resp.predictions.mapNotNull { p ->
                val kind = when (p.type) { "H" -> TideKind.HIGH; "L" -> TideKind.LOW; else -> return@mapNotNull null }
                val h = p.v.toDoubleOrNull() ?: return@mapNotNull null
                TideEvent(LocalDateTime.parse(p.t, TIME).toEpochSecond(ZoneOffset.UTC), h, kind)
            }.sortedBy { it.epochSec }
        }

        suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode} for $url")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
    }
}
