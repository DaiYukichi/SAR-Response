package io.github.daiyukichi.sarresponse.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Rumbo inicial de a hacia b, en grados 0–360 (0 = norte). */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private val COMPASS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO",
    )

    /** Punto cardinal (16 rumbos, en español: O = oeste) para un rumbo en grados. */
    fun compassPoint(degrees: Double): String =
        COMPASS[(Math.floorMod(Math.round(degrees / 22.5).toInt(), 16))]

    /** Desplaza un punto [north] y [east] metros (aproximación plana, válida a escala de búsqueda). */
    fun offset(p: GeoPoint, north: Double, east: Double): GeoPoint = GeoPoint(
        p.lat + Math.toDegrees(north / EARTH_RADIUS_M),
        p.lon + Math.toDegrees(east / (EARTH_RADIUS_M * cos(Math.toRadians(p.lat)))),
    )
}
