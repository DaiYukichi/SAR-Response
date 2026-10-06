package io.github.daiyukichi.sarresponse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchAreaTest {
    private val c = GeoPoint(8.4333, -82.4333)
    private val area = SearchArea.rectangle(c, widthMeters = 200.0, heightMeters = 100.0)

    @Test
    fun areaAndContains() {
        assertEquals(20_000.0, area.areaSquareMeters, 200.0)
        assertTrue(area.contains(c))
        assertTrue(area.contains(Geo.offset(c, 40.0, 90.0)))
        assertFalse(area.contains(Geo.offset(c, 60.0, 0.0)))
        assertFalse(area.contains(Geo.offset(c, 0.0, 110.0)))
    }

    @Test
    fun coverageOfAPassThroughTheMiddle() {
        // Una pasada de oeste a este por el centro, con 40 m de barrido, cubre ~40 de los 100 m de alto.
        val pass = listOf(Geo.offset(c, 0.0, -100.0), Geo.offset(c, 0.0, 100.0))
        assertEquals(0.4, area.coverage(pass, swathMeters = 40.0, cellMeters = 2.0), 0.03)
        assertEquals(0.0, area.coverage(emptyList(), 40.0))
    }

    @Test
    fun fullLawnmowerCoversEverything() {
        val legs = (0..2).flatMap { i ->
            val north = -40.0 + i * 40.0
            val ends = listOf(Geo.offset(c, north, -100.0), Geo.offset(c, north, 100.0))
            if (i % 2 == 0) ends else ends.reversed()
        }
        assertTrue(area.coverage(legs, swathMeters = 45.0, cellMeters = 2.0) > 0.97)
    }
}
