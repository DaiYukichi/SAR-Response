package io.github.daiyukichi.sarresponse.core.map

import java.io.File

/**
 * Genera en la computadora el mapa que va dentro de la app:
 *   ./gradlew :core:extraerMapa --args="salida.pmtiles oeste,sur,este,norte [zoomMax]"
 * Incluye siempre el mundo con poco detalle (zoom ≤ 5) más el detalle de la zona pedida.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Uso: <salida.pmtiles> <oeste,sur,este,norte> [zoomMax=15] [origen.pmtiles|URL]" }
    val (w, s, e, n) = args[1].split(",").map { it.trim().toDouble() }
    val maxZoom = args.getOrNull(2)?.toInt() ?: 15
    val src = args.getOrNull(3) ?: MapExtractor.latestProtomapsBuild()
    println("Origen: $src")
    val source = if (src.startsWith("http")) HttpRangeSource(src) else LocalRangeSource(File(src))
    var lastStage = ""
    val result = MapExtractor(source, onProgress = { p ->
        if (p.stage != lastStage || p.done == p.total) {
            println("${p.stage}: ${p.done}/${p.total}")
            lastStage = p.stage
        }
    }).extract(listOf(MapRegion.WORLD_OVERVIEW, MapRegion(w, s, e, n, maxZoom)), File(args[0]))
    println("Listo: ${result.tiles} teselas, ${result.bytes / 1_000_000.0} MB -> ${args[0]}")
}
