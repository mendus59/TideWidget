package com.example.tidewidget.data

import android.content.Context
import com.example.tidewidget.core.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Caches NOAA's ~3,000-station list on disk for a week so launches are instant. */
class StationCache(context: Context) {
    private val file = File(context.cacheDir, "stations.json")
    private val serializer = ListSerializer(Station.serializer())

    fun isStale(): Boolean = !file.exists() || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3600 * 1000

    suspend fun load(): List<Station>? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    suspend fun save(stations: List<Station>) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(Json.encodeToString(serializer, stations)) }
    }
}
