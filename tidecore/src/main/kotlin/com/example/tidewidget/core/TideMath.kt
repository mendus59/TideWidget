package com.example.tidewidget.core

import kotlin.math.PI
import kotlin.math.cos

object TideMath {
    data class NextTides(val high: TideEvent?, val low: TideEvent?)

    /** The next high and next low strictly after [nowSec]. */
    fun nextHighAndLow(events: List<TideEvent>, nowSec: Long): NextTides {
        val upcoming = events.filter { it.epochSec > nowSec }
        return NextTides(upcoming.firstOrNull { it.kind == TideKind.HIGH }, upcoming.firstOrNull { it.kind == TideKind.LOW })
    }

    /** The first tide turn after [nowSec]; the widget re-renders then. */
    fun nextTurn(events: List<TideEvent>, nowSec: Long): TideEvent? = events.firstOrNull { it.epochSec > nowSec }

    private fun bracket(events: List<TideEvent>, atSec: Long): Triple<TideEvent, TideEvent, Double>? {
        val i = events.indexOfFirst { it.epochSec > atSec }
        if (i <= 0) return null
        val a = events[i - 1]; val b = events[i]
        return Triple(a, b, (atSec - a.epochSec).toDouble() / (b.epochSec - a.epochSec))
    }

    private fun ease(t: Double) = (1 - cos(PI * t)) / 2

    /** Water height at [atSec], using cosine interpolation between consecutive highs/lows. */
    fun height(events: List<TideEvent>, atSec: Long): Double? =
        bracket(events, atSec)?.let { (a, b, t) -> a.heightFt + (b.heightFt - a.heightFt) * ease(t) }

    /**
     * 0 = at low tide, 1 = at high tide, relative to the surrounding tides. This is what
     * colors the map, so stations with very different ranges (Maine vs. Florida) compare fairly.
     */
    fun phase(events: List<TideEvent>, atSec: Long): Double? =
        bracket(events, atSec)?.let { (a, _, t) -> if (a.kind == TideKind.LOW) ease(t) else 1 - ease(t) }

    /** Blue at low tide, through teal and yellow, to red at high tide. Returns ARGB. */
    fun colorArgb(phase: Double): Int {
        val stops = listOf(
            0.0 to intArrayOf(33, 89, 204), 0.35 to intArrayOf(26, 179, 191),
            0.65 to intArrayOf(250, 204, 64), 1.0 to intArrayOf(224, 56, 51),
        )
        val p = phase.coerceIn(0.0, 1.0)
        val j = stops.indexOfFirst { it.first >= p }.coerceAtLeast(1)
        val (p0, c0) = stops[j - 1]; val (p1, c1) = stops[j]
        val f = (p - p0) / (p1 - p0)
        val rgb = IntArray(3) { (c0[it] + (c1[it] - c0[it]) * f).toInt() }
        return (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
}
