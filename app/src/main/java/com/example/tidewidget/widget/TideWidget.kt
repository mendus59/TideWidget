package com.example.tidewidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.tidewidget.MainActivity
import com.example.tidewidget.core.StationForecast
import com.example.tidewidget.core.TideEvent
import com.example.tidewidget.core.TideKind
import com.example.tidewidget.core.TideMath
import com.example.tidewidget.data.ForecastStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home-screen widget, tuned for 1x1 through 2x3 cells (rows x columns):
 *  - row 1 shows the next high and low times (more detail as it gets wider);
 *  - a second row adds the tide graph for the next ~24 hours.
 */
class TideWidget : GlanceAppWidget() {
    companion object {
        // Approximate launcher cell sizes; Glance picks the largest that fits the real widget size.
        private val COL = listOf(50.dp, 110.dp, 180.dp)
        private val ROW = listOf(40.dp, 100.dp)
        private val SIZES = ROW.flatMap { h -> COL.map { w -> DpSize(w, h) } }.toSet()
    }

    override val sizeMode = SizeMode.Responsive(SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val forecast = ForecastStore(context).load()
        val now = Instant.now().epochSecond
        provideContent { GlanceTheme { Content(forecast, now) } }
    }

    @Composable
    private fun Content(f: StationForecast?, now: Long) {
        val size = LocalSize.current
        val cols = COL.count { size.width >= it }.coerceAtLeast(1)   // 1..3
        val rows = ROW.count { size.height >= it }.coerceAtLeast(1)  // 1..2
        val pad = if (cols == 1 || rows == 1) 6.dp else 10.dp

        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp).padding(horizontal = pad + 2.dp, vertical = pad)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = if (rows == 1) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (f == null) {
                Text(if (cols == 1) "Tap to set beach" else "Open Tides to pick a beach",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                return@Column
            }
            val next = TideMath.nextHighAndLow(f.events, now)
            TimesRow(f, next, cols, rows)
            if (rows >= 2) {
                Spacer(GlanceModifier.height(4.dp))
                val w = (size.width - pad * 2 - 4.dp).value.coerceAtLeast(40f)
                val h = (size.height - 56.dp).value.coerceAtLeast(24f)
                graph(f.events, now, w, h)?.let {
                    Image(ImageProvider(it), "Tide graph, next 24 hours", GlanceModifier.fillMaxWidth().defaultWeight(),
                        contentScale = androidx.glance.layout.ContentScale.FillBounds)
                }
            }
        }
    }

    /** Row 1: times only. 1 column = stacked compact times; 2 = High | Low; 3 = name + both with heights. */
    @Composable
    private fun TimesRow(f: StationForecast, next: TideMath.NextTides, cols: Int, rows: Int) {
        val onSurface = GlanceTheme.colors.onSurface
        val muted = GlanceTheme.colors.onSurfaceVariant
        when (cols) {
            1 -> Column {
                if (rows >= 2) Text(f.station.name, maxLines = 1, style = TextStyle(fontSize = 10.sp, color = muted))
                Compact("▲", next.high, 1.0, 12.sp)
                Compact("▼", next.low, 0.0, 12.sp)
            }
            2 -> Column {
                if (rows >= 2) Text(f.station.name, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = muted))
                Row(GlanceModifier.fillMaxWidth()) {
                    Labeled("High", "▲", next.high, 1.0, GlanceModifier.defaultWeight(), showHeight = false)
                    Labeled("Low", "▼", next.low, 0.0, GlanceModifier.defaultWeight(), showHeight = false)
                }
            }
            else -> Column {
                Text(f.station.name, maxLines = 1, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = onSurface))
                Row(GlanceModifier.fillMaxWidth()) {
                    Labeled("High", "▲", next.high, 1.0, GlanceModifier.defaultWeight(), showHeight = true)
                    Labeled("Low", "▼", next.low, 0.0, GlanceModifier.defaultWeight(), showHeight = true)
                }
            }
        }
    }

    @Composable
    private fun Compact(arrow: String, e: TideEvent?, phase: Double, fs: TextUnit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, style = TextStyle(color = ColorProvider(Color(TideMath.colorArgb(phase))), fontSize = fs))
            Text(e?.let { " " + shortTime(it.epochSec) } ?: " —",
                style = TextStyle(fontSize = fs, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
        }
    }

    @Composable
    private fun Labeled(label: String, arrow: String, e: TideEvent?, phase: Double, modifier: GlanceModifier, showHeight: Boolean) {
        Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$arrow ", style = TextStyle(color = ColorProvider(Color(TideMath.colorArgb(phase))), fontSize = 10.sp))
                Text(label + (if (showHeight && e != null) "  %.1f ft".format(e.heightFt) else ""),
                    style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant))
            }
            Text(e?.let { time(it.epochSec) } ?: "—", maxLines = 1,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
        }
    }

    private fun zoned(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault())
    private fun isToday(sec: Long) = zoned(sec).toLocalDate() == LocalDate.now(ZoneId.systemDefault())

    /** "2:40p" (or "Sa 2:40p" when not today) to fit a single cell. */
    private fun shortTime(sec: Long): String {
        val t = zoned(sec)
        val s = DateTimeFormatter.ofPattern("h:mm").format(t) + (if (t.hour < 12) "a" else "p")
        return if (isToday(sec)) s else DateTimeFormatter.ofPattern("EEE").format(t).take(2) + " " + s
    }

    private fun time(sec: Long): String {
        val t = zoned(sec)
        val s = DateTimeFormatter.ofPattern("h:mm a").format(t)
        return if (isToday(sec)) s else DateTimeFormatter.ofPattern("EEE").format(t) + " " + s
    }

    /**
     * Glance has no Canvas, so the graph is drawn into a bitmap sized to the widget:
     * 3h of history through 21h ahead, with a dashed "now" line and dots on each high and low.
     */
    private fun graph(events: List<TideEvent>, nowSec: Long, wDp: Float, hDp: Float): Bitmap? {
        val density = 2.5f
        val w = (wDp * density).toInt().coerceIn(80, 900)
        val h = (hDp * density).toInt().coerceIn(40, 500)
        val from = nowSec - 3 * 3600; val to = nowSec + 21 * 3600
        val pts = (0..96).mapNotNull { i -> val t = from + i * (to - from) / 96; TideMath.height(events, t)?.let { t to it } }
        if (pts.size < 2) return null
        val lo = pts.minOf { it.second }; val hi = maxOf(pts.maxOf { it.second }, lo + 0.1)
        val padY = 6 * density
        fun x(t: Long) = (w * (t - from) / (to - from).toDouble()).toFloat()
        fun y(v: Double) = (padY + (h - 2 * padY) * (1 - (v - lo) / (hi - lo))).toFloat()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val blue = TideMath.colorArgb(0.0)
        val line = Path().apply { pts.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
        val fill = Path(line).apply { lineTo(x(pts.last().first), h.toFloat()); lineTo(x(pts.first().first), h.toFloat()); close() }
        c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), (blue and 0x00FFFFFF) or 0x66000000, (blue and 0x00FFFFFF) or 0x08000000, Shader.TileMode.CLAMP)
        })
        c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND; color = blue })
        c.drawLine(x(nowSec), 0f, x(nowSec), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99888888.toInt(); strokeWidth = 1.2f * density; pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
        })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        events.filter { it.epochSec in from..to }.forEach { e ->
            dot.color = TideMath.colorArgb(if (e.kind == TideKind.HIGH) 1.0 else 0.0)
            c.drawCircle(x(e.epochSec), y(e.heightFt), 3.2f * density, dot)
        }
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
