package io.github.daiyukichi.sarresponse.core.map

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Zona a recortar: caja (grados) y zoom máximo. */
data class MapRegion(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val maxZoom: Int,
) {
    /** Teselas que abarca la zona entre zoom 0 y [maxZoom]. */
    fun tileCount(): Long = (0..maxZoom).sumOf { z ->
        val dx = Pmtiles.lonToTileX(east, z) - Pmtiles.lonToTileX(west, z) + 1L
        val dy = Pmtiles.latToTileY(south, z) - Pmtiles.latToTileY(north, z) + 1L
        dx * dy
    }

    /**
     * Mayor zoom (≤ [limit]) cuyo recorte no supera [maxTiles] teselas: para zonas grandes se baja
     * menos detalle en vez de un archivo enorme.
     */
    fun withAffordableZoom(limit: Int = 15, maxTiles: Long = MAX_TILES): MapRegion {
        var z = limit
        while (z > 6 && copy(maxZoom = z).tileCount() > maxTiles) z--
        return copy(maxZoom = z)
    }

    /** Estimación gruesa del tamaño, con el promedio medido en Chiriquí (~1,5 kB por tesela). */
    fun estimatedMegabytes(): Double = tileCount() * 1.5 / 1000

    companion object {
        /** Mundo completo con poco detalle (países, ciudades, carreteras principales): ~15 MB a zoom 5. */
        val WORLD_OVERVIEW = MapRegion(-180.0, -85.0, 180.0, 85.0, 5)

        /** ~60 000 teselas ≈ 90 MB: tope razonable para bajar al teléfono. */
        const val MAX_TILES = 60_000L
    }
}

/** Lee bytes de un PMTiles remoto o local. */
fun interface RangeSource {
    fun read(offset: Long, length: Int): ByteArray
}

/** PMTiles por HTTP con pedidos de rango (solo baja los bytes necesarios, no el planeta). */
class HttpRangeSource(private val url: String) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                c.setRequestProperty("User-Agent", "SAR-Response (mapa offline)")
                try {
                    if (c.responseCode != 206) error("El servidor no aceptó el pedido de rango (HTTP ${c.responseCode})")
                    val bytes = c.inputStream.use { it.readBytes() }
                    if (bytes.size != length) error("Respuesta incompleta (${bytes.size} de $length bytes)")
                    return bytes
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("No se pudo descargar")
    }
}

class LocalRangeSource(private val file: File) : RangeSource {
    override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { f ->
        f.seek(offset)
        ByteArray(length).also { f.readFully(it) }
    }
}

/**
 * Recorta de un PMTiles grande (por ejemplo el mapa mundial diario de Protomaps) solo las teselas
 * de las [MapRegion] pedidas y escribe un PMTiles nuevo, válido y autónomo.
 *
 * Equivale a `pmtiles extract`, pero permite unir varias zonas (mundo general + detalle de una
 * región) en un solo archivo, y corre en el teléfono sin herramientas externas.
 */
class MapExtractor(
    private val source: RangeSource,
    private val onProgress: (Progress) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) {
    data class Progress(val stage: String, val done: Long, val total: Long)

    data class Result(val tiles: Int, val bytes: Long)

    private lateinit var header: Pmtiles.Header
    private val dirCache = HashMap<Long, List<Pmtiles.Entry>>()

    /** Cuántas teselas pide un conjunto de zonas (para avisar antes de bajar algo enorme). */
    fun countTiles(regions: List<MapRegion>): Int = tileIds(regions).size

    fun extract(regions: List<MapRegion>, output: File): Result {
        header = Pmtiles.parseHeader(source.read(0, Pmtiles.HEADER_SIZE))
        val maxZoom = min(regions.maxOf { it.maxZoom }, header.maxZoom)
        val wanted = tileIds(regions.map { it.copy(maxZoom = min(it.maxZoom, maxZoom)) })

        // 1. Ubicar cada tesela en el archivo de origen (bajando por los directorios necesarios).
        data class Found(val tileId: Long, val srcOffset: Long, val length: Int)
        val found = ArrayList<Found>(wanted.size)
        wanted.forEachIndexed { i, id ->
            checkCancelled()
            locate(id)?.let { (off, len) -> found += Found(id, off, len) }
            if (i % 500 == 0) onProgress(Progress("Buscando teselas", i.toLong(), wanted.size.toLong()))
        }
        require(found.isNotEmpty()) { "La zona elegida no tiene datos de mapa" }

        // 2. Bajar los datos agrupando rangos cercanos en pocos pedidos.
        val unique = found.map { it.srcOffset to it.length }.distinct().sortedBy { it.first }
        val total = unique.sumOf { it.second.toLong() }
        val data = HashMap<Long, ByteArray>(unique.size)
        var done = 0L
        var i = 0
        while (i < unique.size) {
            checkCancelled()
            var j = i
            var end = unique[i].first + unique[i].second
            while (j + 1 < unique.size && unique[j + 1].first - end <= MERGE_GAP &&
                unique[j + 1].first + unique[j + 1].second - unique[i].first <= MAX_CHUNK
            ) {
                j++
                end = max(end, unique[j].first + unique[j].second)
            }
            val start = unique[i].first
            val chunk = source.read(header.tileDataOffset + start, (end - start).toInt())
            for (k in i..j) {
                val (off, len) = unique[k]
                data[off] = chunk.copyOfRange((off - start).toInt(), (off - start).toInt() + len)
                done += len
            }
            onProgress(Progress("Descargando", done, total))
            i = j + 1
        }

        // 3. Escribir el archivo nuevo: teselas en orden de tileId, contenidos repetidos una sola vez.
        val newOffset = HashMap<Long, Long>()
        val tileOrder = ArrayList<ByteArray>()
        var cursor = 0L
        val entries = ArrayList<Pmtiles.Entry>()
        for (f in found.sortedBy { it.tileId }) {
            val off = newOffset.getOrPut(f.srcOffset) {
                tileOrder += data.getValue(f.srcOffset)
                cursor.also { cursor += f.length }
            }
            val last = entries.lastOrNull()
            if (last != null && last.offset == off && last.tileId + last.runLength == f.tileId) {
                entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            } else {
                entries += Pmtiles.Entry(f.tileId, off, f.length, 1)
            }
        }

        val compression = header.internalCompression
        val (root, leaves) = buildDirectories(entries, compression)
        val metadata = source.read(header.metadataOffset, header.metadataLength.toInt())

        val detail = regions.filter { it.maxZoom == maxZoom }.ifEmpty { regions }
        val west = regions.minOf { it.west }
        val south = regions.minOf { it.south }
        val east = regions.maxOf { it.east }
        val north = regions.maxOf { it.north }
        val cLon = detail.map { (it.west + it.east) / 2 }.average()
        val cLat = detail.map { (it.south + it.north) / 2 }.average()

        val rootOffset = Pmtiles.HEADER_SIZE.toLong()
        val metadataOffset = rootOffset + root.size
        val leafOffset = metadataOffset + metadata.size
        val tileDataOffset = leafOffset + leaves.size
        val out = Pmtiles.Header(
            rootOffset = rootOffset, rootLength = root.size.toLong(),
            metadataOffset = metadataOffset, metadataLength = metadata.size.toLong(),
            leafOffset = leafOffset, leafLength = leaves.size.toLong(),
            tileDataOffset = tileDataOffset, tileDataLength = cursor,
            addressedTiles = entries.sumOf { it.runLength.toLong() },
            tileEntries = entries.size.toLong(), tileContents = tileOrder.size.toLong(),
            clustered = true,
            internalCompression = compression, tileCompression = header.tileCompression, tileType = header.tileType,
            minZoom = 0, maxZoom = maxZoom,
            minLonE7 = e7(west), minLatE7 = e7(south), maxLonE7 = e7(east), maxLatE7 = e7(north),
            centerZoom = min(maxZoom, 12), centerLonE7 = e7(cLon), centerLatE7 = e7(cLat),
        )

        val tmp = File(output.path + ".tmp")
        tmp.outputStream().buffered().use { o ->
            o.write(Pmtiles.writeHeader(out))
            o.write(root)
            o.write(metadata)
            o.write(leaves)
            tileOrder.forEach { o.write(it) }
        }
        if (output.exists()) output.delete()
        check(tmp.renameTo(output)) { "No se pudo guardar el mapa" }
        onProgress(Progress("Listo", total, total))
        return Result(found.size, output.length())
    }

    /** Raíz sola si cabe; si no, hojas de tamaño creciente hasta que la raíz quepa en 16 KiB. */
    private fun buildDirectories(entries: List<Pmtiles.Entry>, compression: Int): Pair<ByteArray, ByteArray> {
        val rootOnly = Pmtiles.encodeDirectory(entries, compression)
        if (rootOnly.size <= Pmtiles.ROOT_MAX) return rootOnly to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leafBytes = java.io.ByteArrayOutputStream()
            val rootEntries = ArrayList<Pmtiles.Entry>()
            for (chunk in entries.chunked(leafSize)) {
                val enc = Pmtiles.encodeDirectory(chunk, compression)
                rootEntries += Pmtiles.Entry(chunk.first().tileId, leafBytes.size().toLong(), enc.size, 0)
                leafBytes.write(enc)
            }
            val root = Pmtiles.encodeDirectory(rootEntries, compression)
            if (root.size <= Pmtiles.ROOT_MAX) return root to leafBytes.toByteArray()
            leafSize *= 2
        }
    }

    /** (offset dentro de los datos, longitud) de la tesela, o null si no existe (por ejemplo, mar abierto). */
    private fun locate(tileId: Long): Pair<Long, Int>? {
        var dirOffset = header.rootOffset
        var dirLength = header.rootLength.toInt()
        repeat(4) { // la especificación limita la profundidad a 3 niveles bajo la raíz
            val entries = dirCache.getOrPut(dirOffset) {
                Pmtiles.decodeDirectory(source.read(dirOffset, dirLength), header.internalCompression)
            }
            val e = Pmtiles.findEntry(entries, tileId) ?: return null
            if (e.runLength > 0) return e.offset to e.length
            dirOffset = header.leafOffset + e.offset
            dirLength = e.length
        }
        return null
    }

    private fun tileIds(regions: List<MapRegion>): LongArray {
        val ids = HashSet<Long>()
        for (r in regions) {
            for (z in 0..r.maxZoom) {
                val x0 = Pmtiles.lonToTileX(r.west, z)
                val x1 = Pmtiles.lonToTileX(r.east, z)
                val y0 = Pmtiles.latToTileY(r.north, z)
                val y1 = Pmtiles.latToTileY(r.south, z)
                for (x in x0..x1) for (y in y0..y1) ids += Pmtiles.tileId(z, x, y)
            }
        }
        return ids.toLongArray().also { it.sort() }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw InterruptedException("Descarga cancelada")
    }

    private fun e7(deg: Double) = (deg * 1e7).roundToInt()

    companion object {
        /** Rangos separados por menos de esto se piden juntos (se baja un poco de más, pero con menos pedidos). */
        const val MERGE_GAP = 256 * 1024L
        const val MAX_CHUNK = 8 * 1024 * 1024L

        /**
         * Mapa base diario de Protomaps (OpenStreetMap, ODbL). Se guardan las builds de la última
         * semana, así que se busca la más reciente que exista. Para uso en producción conviene
         * alojar una copia propia en vez de depender de estas URLs.
         */
        fun latestProtomapsBuild(today: java.time.LocalDate = java.time.LocalDate.now()): String {
            for (d in 0..6) {
                val date = today.minusDays(d.toLong()).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val url = "https://build.protomaps.com/$date.pmtiles"
                if (runCatching { HttpRangeSource(url).read(0, 7) }.getOrNull()?.let { String(it) } == "PMTiles") return url
            }
            error("No se encontró un mapa base reciente en build.protomaps.com")
        }
    }
}
