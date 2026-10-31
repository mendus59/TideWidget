package com.example.tidewidget.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.example.tidewidget.core.Defaults
import com.example.tidewidget.core.NoaaClient
import com.example.tidewidget.core.StationForecast
import com.example.tidewidget.core.TideMath
import com.example.tidewidget.data.ForecastStore
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps the widget current:
 *  - refreshes the cached forecast from NOAA when it is older than 6h or running short;
 *  - re-renders the widget, then schedules itself again just after the next tide turn,
 *    so "next high / next low" flips on time even with no network.
 */
class TideRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = ForecastStore(applicationContext)
        val now = Instant.now().epochSecond
        var forecast = store.load()
        val station = forecast?.station ?: Defaults.station
        val stale = forecast == null || now - forecast.fetchedEpochSec > 6 * 3600 ||
            (forecast.events.lastOrNull()?.epochSec ?: 0) < now + 24 * 3600
        if (stale) {
            runCatching { NoaaClient().highsAndLows(station.id) }.onSuccess {
                forecast = StationForecast(station, it, now).also(store::save)
            }
        }
        TideWidget().updateAll(applicationContext)
        forecast?.let { scheduleAtNextTurn(applicationContext, it) }
        return Result.success()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "tide-refresh", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TideRefreshWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
            )
        }

        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tide-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TideRefreshWorker>().build(),
            )
        }

        private fun scheduleAtNextTurn(context: Context, forecast: StationForecast) {
            val turn = TideMath.nextTurn(forecast.events, Instant.now().epochSecond) ?: return
            val delay = (turn.epochSec - Instant.now().epochSecond + 60).coerceAtLeast(60)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tide-turn", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TideRefreshWorker>().setInitialDelay(delay, TimeUnit.SECONDS).build(),
            )
        }
    }
}
