package com.example.tidewidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.tidewidget.MainActivity
import com.example.tidewidget.core.StationForecast
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.core.TideMath
import com.example.tidewidget.data.ForecastStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TideWidget : GlanceAppWidget() {
    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val MEDIUM = DpSize(250.dp, 110.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val forecast = ForecastStore(context).load()
        val now = Instant.now().epochSecond
        val spark = forecast?.let { sparkline(it.events, now) }
        provideContent { GlanceTheme { Content(forecast, now, spark) } }
    }

    @Composable
    private fun Content(f: StationForecast?, now: Long, spark: Bitmap?) {
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp).padding(12.dp).clickable(actionStartActivity<MainActivity>()),
        ) {
            if (f == null) {
                Text("Open Tides to pick a beach", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                return@Column
            }
            val next = TideMath.nextHighAndLow(f.events, now)
            Text(f.station.name, maxLines = 1, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface))
            Spacer(GlanceModifier.height(6.dp))
            TideRow("High", "▲", next.high, 1.0)
            Spacer(GlanceModifier.height(4.dp))
            TideRow("Low", "▼", next.low, 0.0)
            if (LocalSize.current.width >= MEDIUM.width && spark != null) {
                Spacer(GlanceModifier.height(6.dp))
                Image(ImageProvider(spark), "Tide curve, next 24 hours", GlanceModifier.fillMaxWidth().height(36.dp))
            }
        }
    }

    @Composable
    private fun TideRow(label: String, arrow: String, e: TideEvent?, phase: Double) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$arrow ", style = TextStyle(color = ColorProvider(Color(TideMath.colorArgb(phase))), fontSize = 14.sp))
            Text(
                if (e == null) "$label  —" else "$label  ${formatTime(e.epochSec)}",
                style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
            )
            if (e != null) Text("  %.1f ft".format(e.heightFt), style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
    }

    private fun formatTime(sec: Long): String {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochSecond(sec).atZone(zone)
        val time = DateTimeFormatter.ofPattern("h:mm a").format(t)
        return if (t.toLocalDate() == LocalDate.now(zone)) time else "${DateTimeFormatter.ofPattern("EEE").format(t)} $time"
    }

    /** Glance has no Canvas, so the 24h curve is drawn into a small bitmap. */
    private fun sparkline(events: List<TideEvent>, fromSec: Long, w: Int = 500, h: Int = 72): Bitmap? {
        val pts = (0..48).mapNotNull { TideMath.height(events, fromSec + it * 1800L) }
        if (pts.size < 2) return null
        val lo = pts.min(); val hi = maxOf(pts.max(), lo + 0.1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = w * i / (pts.size - 1f); val y = (4 + (h - 8) * (1 - (v - lo) / (hi - lo))).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        Canvas(bmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; color = TideMath.colorArgb(0.0)
        })
        return bmp
    }
}

class TideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TideWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TideRefreshWorker.refreshNow(context)
    }
}
