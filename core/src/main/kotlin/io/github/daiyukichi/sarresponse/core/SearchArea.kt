package io.github.daiyukichi.sarresponse.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Área de búsqueda: un polígono simple dibujado por el operador sobre el mapa.
 * Los cálculos usan una proyección plana local (metros al este/norte del primer vértice),
 * suficiente para áreas de unos pocos km.
 */
data class SearchArea(val vertices: List<GeoPoint>) {
    init {
        require(vertices.size >= 3) { "Un área necesita al menos 3 vértices" }
    }

    private val origin = vertices.first()
    private val local = vertices.map { toLocal(it) }

    val areaSquareMeters: Double
        get() {
            var sum = 0.0
            for (i in local.indices) {
                val (x1, y1) = local[i]
                val (x2, y2) = local[(i + 1) % local.size]
                sum += x1 * y2 - x2 * y1
            }
            return abs(sum) / 2
        }

    fun contains(p: GeoPoint): Boolean = containsLocal(toLocal(p))

    /**
     * Fracción (0–1) del área que ya "vio" la cámara: celdas del área a menos de [swathMeters]/2
     * de algún tramo del recorrido. Es una estimación: supone cámara apuntando hacia abajo y un
     * ancho de barrido constante.
     */
    fun coverage(track: List<GeoPoint>, swathMeters: Double, cellMeters: Double = 10.0): Double {
        if (track.isEmpty() || swathMeters <= 0) return 0.0
        val pts = track.map { toLocal(it) }
        val segs = if (pts.size == 1) listOf(pts[0] to pts[0]) else pts.zipWithNext()
        val half = swathMeters / 2
        val minX = local.minOf { it.first }
        val maxX = local.maxOf { it.first }
        val minY = local.minOf { it.second }
        val maxY = local.maxOf { it.second }
        // Celdas más grandes si el área es enorme, para que el cálculo siga siendo rápido.
        val cell = max(cellMeters, sqrt((maxX - minX) * (maxY - minY) / 40_000.0))
        var inside = 0
        var covered = 0
        var y = minY + cell / 2
        while (y < maxY) {
            var x = minX + cell / 2
            while (x < maxX) {
                if (containsLocal(x to y)) {
                    inside++
                    if (segs.any { (a, b) -> distToSegment(x, y, a, b) <= half }) covered++
                }
                x += cell
            }
            y += cell
        }
        return if (inside == 0) 0.0 else covered.toDouble() / inside
    }

    private fun toLocal(p: GeoPoint): Pair<Double, Double> {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(origin.lat))
        return (p.lon - origin.lon) * metersPerDegLon to (p.lat - origin.lat) * metersPerDegLat
    }

    /** Rayo hacia la derecha: dentro si cruza los bordes un número impar de veces. */
    private fun containsLocal(p: Pair<Double, Double>): Boolean {
        val (x, y) = p
        var inside = false
        var j = local.size - 1
        for (i in local.indices) {
            val (xi, yi) = local[i]
            val (xj, yj) = local[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun distToSegment(px: Double, py: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val (ax, ay) = a
        val (bx, by) = b
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else min(1.0, max(0.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    companion object {
        /** Rectángulo de [widthMeters] × [heightMeters] centrado en [center] (lo usa la demo). */
        fun rectangle(center: GeoPoint, widthMeters: Double, heightMeters: Double): SearchArea = SearchArea(
            listOf(
                Geo.offset(center, -heightMeters / 2, -widthMeters / 2),
                Geo.offset(center, -heightMeters / 2, widthMeters / 2),
                Geo.offset(center, heightMeters / 2, widthMeters / 2),
                Geo.offset(center, heightMeters / 2, -widthMeters / 2),
            ),
        )
    }
}

/**
 * Una búsqueda: su nombre, cuándo se creó, su área (opcional) y la altura de vuelo planificada,
 * con la que se calcula el ancho que ve la cámara ([swathMeters]).
 */
data class Search(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val area: SearchArea? = null,
    /** Ancho de terreno que cubre la cámara en cada pasada, en metros. */
    val swathMeters: Double = DEFAULT_SWATH_METERS,
    val finishedAtMillis: Long? = null,
    /** Altura de vuelo planificada sobre el terreno (null en búsquedas viejas, que solo tenían barrido). */
    val altitudeMeters: Double? = null,
    /** FOV horizontal de la cámara usado para el cálculo. */
    val hfovDegrees: Double = CameraGeometry.DEFAULT_HFOV_DEGREES,
) {
    companion object {
        const val DEFAULT_SWATH_METERS = 40.0
        const val DEFAULT_ALTITUDE_METERS = 30.0

        fun planned(id: String, name: String, createdAtMillis: Long, area: SearchArea?, altitudeMeters: Double, hfovDegrees: Double) =
            Search(
                id = id, name = name, createdAtMillis = createdAtMillis, area = area,
                swathMeters = CameraGeometry.swathMeters(altitudeMeters, hfovDegrees),
                altitudeMeters = altitudeMeters, hfovDegrees = hfovDegrees,
            )
    }
}

