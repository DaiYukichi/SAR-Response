package io.github.daiyukichi.sarresponse.core.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Formato PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md):
 * cabecera de 127 bytes, directorios comprimidos con entradas (tileId, offset, length, runLength)
 * y los datos de las teselas. Las teselas se numeran con una curva de Hilbert por nivel de zoom.
 */
object Pmtiles {
    const val HEADER_SIZE = 127

    /** El directorio raíz debe caber con la cabecera en los primeros 16 KiB del archivo. */
    const val ROOT_MAX = 16_384 - HEADER_SIZE

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2

    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafOffset: Long,
        val leafLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val addressedTiles: Long,
        val tileEntries: Long,
        val tileContents: Long,
        val clustered: Boolean,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLonE7: Int,
        val minLatE7: Int,
        val maxLonE7: Int,
        val maxLatE7: Int,
        val centerZoom: Int,
        val centerLonE7: Int,
        val centerLatE7: Int,
    )

    /** Entrada de directorio. runLength = 0 significa "puntero a un directorio hoja". */
    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    fun parseHeader(b: ByteArray): Header {
        require(b.size >= HEADER_SIZE) { "Cabecera incompleta" }
        require(String(b, 0, 7, Charsets.US_ASCII) == "PMTiles") { "No es un archivo PMTiles" }
        require(b[7].toInt() == 3) { "Versión de PMTiles no soportada: ${b[7]}" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            rootOffset = bb.getLong(8), rootLength = bb.getLong(16),
            metadataOffset = bb.getLong(24), metadataLength = bb.getLong(32),
            leafOffset = bb.getLong(40), leafLength = bb.getLong(48),
            tileDataOffset = bb.getLong(56), tileDataLength = bb.getLong(64),
            addressedTiles = bb.getLong(72), tileEntries = bb.getLong(80), tileContents = bb.getLong(88),
            clustered = b[96].toInt() == 1,
            internalCompression = b[97].toInt(), tileCompression = b[98].toInt(), tileType = b[99].toInt(),
            minZoom = b[100].toInt() and 0xFF, maxZoom = b[101].toInt() and 0xFF,
            minLonE7 = bb.getInt(102), minLatE7 = bb.getInt(106), maxLonE7 = bb.getInt(110), maxLatE7 = bb.getInt(114),
            centerZoom = b[118].toInt() and 0xFF, centerLonE7 = bb.getInt(119), centerLatE7 = bb.getInt(123),
        )
    }

    fun writeHeader(h: Header): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PMTiles".toByteArray(Charsets.US_ASCII)).put(3)
        listOf(
            h.rootOffset, h.rootLength, h.metadataOffset, h.metadataLength, h.leafOffset, h.leafLength,
            h.tileDataOffset, h.tileDataLength, h.addressedTiles, h.tileEntries, h.tileContents,
        ).forEach { bb.putLong(it) }
        bb.put(if (h.clustered) 1 else 0).put(h.internalCompression.toByte()).put(h.tileCompression.toByte())
            .put(h.tileType.toByte()).put(h.minZoom.toByte()).put(h.maxZoom.toByte())
        bb.putInt(h.minLonE7).putInt(h.minLatE7).putInt(h.maxLonE7).putInt(h.maxLatE7)
        bb.put(h.centerZoom.toByte()).putInt(h.centerLonE7).putInt(h.centerLatE7)
        return bb.array()
    }

    // ---------------------------------------------------------------- numeración de teselas

    /** Número de teselas de todos los niveles anteriores a [z]: (4^z − 1) / 3. */
    private fun zoomBase(z: Int): Long = ((1L shl (2 * z)) - 1) / 3

    /** tileId de la tesela z/x/y según la curva de Hilbert (igual que la referencia de PMTiles). */
    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26) { "Zoom fuera de rango" }
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "Tesela fuera de rango" }
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if ((tx and s) > 0) 1L else 0L
            val ry = if ((ty and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            // Rotar el cuadrante (con el tamaño del sub-cuadrado, no del mundo).
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val t = tx
                tx = ty
                ty = t
            }
            s /= 2
        }
        return zoomBase(z) + d
    }

    fun lonToTileX(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToTileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    // ---------------------------------------------------------------- directorios

    fun decodeDirectory(raw: ByteArray, compression: Int): List<Entry> {
        val b = decompress(raw, compression)
        val r = VarintReader(b)
        val n = r.next().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) {
            last += r.next()
            ids[i] = last
        }
        val runs = IntArray(n) { r.next().toInt() }
        val lengths = IntArray(n) { r.next().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.next()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    fun encodeDirectory(entries: List<Entry>, compression: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) {
            writeVarint(out, e.tileId - last)
            last = e.tileId
        }
        entries.forEach { writeVarint(out, it.runLength.toLong()) }
        entries.forEach { writeVarint(out, it.length.toLong()) }
        entries.forEachIndexed { i, e ->
            val contiguous = i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length
            writeVarint(out, if (contiguous) 0 else e.offset + 1)
        }
        return compress(out.toByteArray(), compression)
    }

    /** Entrada cuyo rango contiene [tileId] (o el puntero a la hoja que lo contiene); null si no está. */
    fun findEntry(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].tileId <= tileId) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return null
        val e = entries[best]
        return when {
            e.runLength == 0 -> e // puntero a hoja: hay que bajar un nivel
            tileId - e.tileId < e.runLength -> e
            else -> null
        }
    }

    private fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE -> b
        COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        else -> error("Compresión de directorio no soportada: $compression")
    }

    private fun compress(b: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE -> b
        COMPRESSION_GZIP -> ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
        else -> error("Compresión de directorio no soportada: $compression")
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private class VarintReader(private val b: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte < 0x80) return result
                shift += 7
            }
        }
    }
}
