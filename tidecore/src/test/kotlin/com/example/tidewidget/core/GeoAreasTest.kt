package com.example.tidewidget.core

import kotlin.test.*

class GeoAreasTest {
    private val sf = Station("9414290", "San Francisco", "CA", 37.8063, -122.4659)
    private val alameda = Station("9414750", "Alameda", "CA", 37.7717, -122.3000, referenceId = "9414290")
    private val sausalito = Station("9414806", "Sausalito", "CA", 37.8467, -122.4767, referenceId = "9414290")
    private val monterey = Station("9413450", "Monterey", "CA", 36.6050, -121.8883)
    private val orphan = Station("1", "Orphan", "CA", 36.0, -121.0, referenceId = "missing")
    private val all = listOf(alameda, sf, monterey, sausalito, orphan)

    @Test fun distance() {
        // SF to Monterey is roughly 143 km as the crow flies.
        assertEquals(143.0, Geo.distanceKm(sf.lat, sf.lng, monterey.lat, monterey.lng), 5.0)
        assertEquals(0.0, Geo.distanceKm(1.0, 2.0, 1.0, 2.0), 1e-9)
    }

    @Test fun nearestOrdersByDistance() {
        // A point on Ocean Beach, SF.
        val near = Geo.nearest(all, 37.7594, -122.5107, 3).map { it.station.id }
        assertEquals(listOf("9414290", "9414806", "9414750"), near)
    }

    @Test fun groupsByReferenceStation() {
        val areas = Areas.group(all).associateBy { it.id }
        assertEquals(3, areas.size)
        val bay = areas.getValue("9414290")
        assertEquals("San Francisco", bay.name)
        assertEquals(listOf("9414290", "9414806", "9414750"), bay.stations.map { it.id }) // reference first, then nearest
        assertEquals(listOf("1"), areas.getValue("1").stations.map { it.id })           // unknown reference stands alone
        assertEquals("9414290", Areas.areaOf(areas.values.toList(), "9414750")?.id)
    }

    @Test fun outlookWindows() {
        fun ev(min: Int, kind: TideKind) = TideEvent(min * 60L, 1.0, kind)
        val f = mapOf(
            "a" to listOf(ev(100, TideKind.HIGH), ev(480, TideKind.LOW)),
            "b" to listOf(ev(130, TideKind.HIGH), ev(500, TideKind.LOW)),
            // Already past its high: its next one is the following tide, ~12h later, so it is ignored for highs.
            "c" to listOf(ev(470, TideKind.LOW), ev(850, TideKind.HIGH)),
        )
        val o = Areas.outlook(f, nowSec = 0)
        assertEquals(100 * 60L, o.high!!.first.epochSec)
        assertEquals(130 * 60L, o.high!!.last.epochSec)
        assertEquals(470 * 60L, o.low!!.first.epochSec)
        assertEquals(500 * 60L, o.low!!.last.epochSec)
        assertEquals(3, o.stationsReporting)
    }

    @Test fun parsesReferenceIds() {
        val s = NoaaClient.parseStations("""{"stations":[
            {"id":"1610367","name":"Nonopapa","state":"HI","lat":21.87,"lng":-160.235,"type":"S","reference_id":"1612340"},
            {"id":"1611347","name":"Port Allen","state":"HI","lat":21.9,"lng":-159.6,"type":"R","reference_id":""}]}""")
        assertEquals("1612340", s[0].referenceId)
        assertNull(s[1].referenceId)
    }
}
