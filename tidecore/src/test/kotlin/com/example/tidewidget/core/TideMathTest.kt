package com.example.tidewidget.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.*

class TideMathTest {
    // Shape of a real CO-OPS "hilo" response.
    private val sample = """
        {"predictions":[
          {"t":"2026-10-31 02:10","v":"5.412","type":"H"},
          {"t":"2026-10-31 08:25","v":"0.913","type":"L"},
          {"t":"2026-10-31 14:40","v":"4.801","type":"H"},
          {"t":"2026-10-31 20:55","v":"1.204","type":"L"}]}
    """.trimIndent()

    private fun at(s: String) = LocalDateTime.parse(s.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC)
    private val events = NoaaClient.parsePredictions(sample)

    @Test fun parses() {
        assertEquals(4, events.size)
        assertEquals(TideKind.HIGH, events[0].kind)
        assertEquals(0.913, events[1].heightFt)
    }

    @Test fun nextHighAndLow() {
        val n = TideMath.nextHighAndLow(events, at("2026-10-31 09:00"))
        assertEquals(at("2026-10-31 14:40"), n.high?.epochSec)
        assertEquals(at("2026-10-31 20:55"), n.low?.epochSec)
        assertNull(TideMath.nextHighAndLow(events, at("2026-10-31 21:00")).high)
    }

    @Test fun interpolation() {
        assertEquals(0.913, TideMath.height(events, at("2026-10-31 08:25") + 1)!!, 0.01)
        // Midway between the 08:25 low and 14:40 high: half height, half phase.
        val mid = at("2026-10-31 11:32") + 30
        assertEquals((0.913 + 4.801) / 2, TideMath.height(events, mid)!!, 0.01)
        assertEquals(0.5, TideMath.phase(events, mid)!!, 0.01)
        assertNull(TideMath.height(events, at("2026-10-31 22:00")))
    }

    @Test fun colorsRunBlueToRed() {
        assertEquals(0xFF2159CC.toInt(), TideMath.colorArgb(0.0))
        assertEquals(0xFFE03833.toInt(), TideMath.colorArgb(1.0))
        assertEquals(TideMath.colorArgb(1.0), TideMath.colorArgb(3.0))
    }

    @Test fun surfacesNoaaErrors() {
        assertFailsWith<IllegalStateException> {
            NoaaClient.parsePredictions("""{"error":{"message":"No Predictions data was found."}}""")
        }
    }

    @Test fun parsesStations() {
        val s = NoaaClient.parseStations("""{"count":1,"stations":[{"id":"9414290","name":"San Francisco","state":"CA","lat":37.8063,"lng":-122.4659,"extra":1}]}""")
        assertEquals(Defaults.station, s.single())
    }
}
