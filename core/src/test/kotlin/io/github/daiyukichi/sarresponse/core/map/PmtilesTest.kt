package io.github.daiyukichi.sarresponse.core.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PmtilesTest {
    @Test
    fun hilbertTileIds() {
        // Valores de la especificación / implementación de referencia.
        assertEquals(0, Pmtiles.tileId(0, 0, 0))
        assertEquals(1, Pmtiles.tileId(1, 0, 0))
        assertEquals(2, Pmtiles.tileId(1, 0, 1))
        assertEquals(3, Pmtiles.tileId(1, 1, 1))
        assertEquals(4, Pmtiles.tileId(1, 1, 0))
        assertEquals(5, Pmtiles.tileId(2, 0, 0))
        assertEquals(19_078_479L, Pmtiles.tileId(12, 3423, 1763))
    }

    @Test
    fun directoryRoundTrip() {
        val entries = listOf(
            Pmtiles.Entry(5, 0, 100, 1),
            Pmtiles.Entry(6, 100, 50, 3),   // contiguo al anterior
            Pmtiles.Entry(20, 999, 10, 1),
            Pmtiles.Entry(21, 0, 7, 0),     // puntero a hoja
        )
        val back = Pmtiles.decodeDirectory(Pmtiles.encodeDirectory(entries, Pmtiles.COMPRESSION_GZIP), Pmtiles.COMPRESSION_GZIP)
        assertEquals(entries, back)
        assertEquals(entries[1], Pmtiles.findEntry(entries, 8))
        assertEquals(null, Pmtiles.findEntry(entries, 10))
    }

    /** Recorta David del mapa de Chiriquí incluido y verifica tesela por tesela contra el original. */
    @Test
    fun extractMatchesSourceTileByTile() {
        val src = File("../app/src/main/assets/mapa/region.pmtiles")
        if (!src.exists()) return // el mapa no va al repo; se genera con tools/mapa/descargar_mapa.sh
        val out = File.createTempFile("david", ".pmtiles").apply { deleteOnExit() }
        val david = MapRegion(-82.47, 8.40, -82.40, 8.46, 14)
        val r = MapExtractor(LocalRangeSource(src)).extract(listOf(david), out)
        assertTrue(r.tiles > 10)

        val a = Reader(LocalRangeSource(src))
        val b = Reader(LocalRangeSource(out))
        assertEquals(14, b.header.maxZoom)
        var compared = 0
        for (z in 0..14) {
            for (x in Pmtiles.lonToTileX(-82.47, z)..Pmtiles.lonToTileX(-82.40, z)) {
                for (y in Pmtiles.latToTileY(8.46, z)..Pmtiles.latToTileY(8.40, z)) {
                    val id = Pmtiles.tileId(z, x, y)
                    val ta = a.tile(id)
                    val tb = b.tile(id)
                    if (ta == null) continue
                    assertContentEquals(ta, tb, "tesela $z/$x/$y")
                    compared++
                }
            }
        }
        assertEquals(r.tiles, compared)
    }

    private class Reader(val src: RangeSource) {
        val header = Pmtiles.parseHeader(src.read(0, Pmtiles.HEADER_SIZE))
        fun tile(id: Long): ByteArray? {
            var off = header.rootOffset
            var len = header.rootLength.toInt()
            repeat(4) {
                val e = Pmtiles.findEntry(Pmtiles.decodeDirectory(src.read(off, len), header.internalCompression), id) ?: return null
                if (e.runLength > 0) return src.read(header.tileDataOffset + e.offset, e.length)
                off = header.leafOffset + e.offset
                len = e.length
            }
            return null
        }
    }
}
