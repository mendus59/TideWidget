package com.example.tidewidget.data

import android.content.Context
import com.example.tidewidget.core.StationForecast
import kotlinx.serialization.json.Json

/** The widget's beach and its cached forecast. Shared by the app and the widget (same process). */
class ForecastStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tides", Context.MODE_PRIVATE)

    fun load(): StationForecast? =
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString<StationForecast>(it) }.getOrNull() }

    fun save(forecast: StationForecast) {
        prefs.edit().putString(KEY, Json.encodeToString(StationForecast.serializer(), forecast)).apply()
    }

    private companion object { const val KEY = "favoriteForecast" }
}
