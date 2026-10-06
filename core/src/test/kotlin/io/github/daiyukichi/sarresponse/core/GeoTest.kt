package io.github.daiyukichi.sarresponse.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val origin = GeoPoint(8.4333, -82.4333)

    @Test
    fun offsetDistanceAndBearingAgree() {
        val p = Geo.offset(origin, north = 300.0, east = 400.0)
        assertTrue(abs(Geo.distanceMeters(origin, p) - 500.0) < 1.0)
        assertTrue(abs(Geo.bearingDegrees(origin, p) - 53.13) < 0.5)
    }

    @Test
    fun compassPoints() {
        assertEquals("N", Geo.compassPoint(0.0))
        assertEquals("N", Geo.compassPoint(359.0))
        assertEquals("NNE", Geo.compassPoint(19.0))
        assertEquals("NE", Geo.compassPoint(52.0))
        assertEquals("S", Geo.compassPoint(180.0))
        assertEquals("O", Geo.compassPoint(270.0))
        assertEquals("NO", Geo.compassPoint(315.0))
    }
}
