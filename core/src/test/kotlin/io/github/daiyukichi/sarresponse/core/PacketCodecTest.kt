package io.github.daiyukichi.sarresponse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PacketCodecTest {
    // Mismo cálculo que checksum() en sar_payload.py / ground_station.py.
    private fun pkt(body: String) = "$$body*${PacketCodec.checksum(body)}\r\n"

    @Test
    fun parsesAlert() {
        val p = PacketCodec.parse(pkt("SAR,A1,2,-12.046410,-77.042810,0.87,120041"))
        assertIs<Packet.Alert>(p)
        assertEquals("A1", p.unit)
        assertEquals(2, p.seq)
        assertEquals(GeoPoint(-12.046410, -77.042810), p.position)
        assertEquals(0.87, p.confidence)
        assertEquals("120041", p.utc)
    }

    @Test
    fun parsesHeartbeatWithoutFix() {
        val p = PacketCodec.parse(pkt("SAH,A1,0,,,3,120000"))
        assertIs<Packet.Heartbeat>(p)
        assertNull(p.position)
        assertEquals(3, p.satellites)
    }

    @Test
    fun knownChecksum() {
        // Valor calculado con la función de Python del payload.
        assertEquals("2A", PacketCodec.checksum("SAH,A1,0,,,3,120000"))
    }

    @Test
    fun rejectsBadChecksumAndGarbage() {
        assertNull(PacketCodec.parse("\$SAR,A1,3,-12.0,-77.0,0.90,120050*00"))
        assertNull(PacketCodec.parse("PING 12"))
        assertNull(PacketCodec.parse(pkt("SAR,A1,3,-12.0,-77.0")))
        assertNull(PacketCodec.parse(pkt("XYZ,A1,3,-12.0,-77.0,0.9,120050")))
        assertNull(PacketCodec.parse(pkt("SAR,A1,3,-120.0,-77.0,0.9,120050")))
    }

    @Test
    fun formatRoundTrips() {
        val a = Packet.Alert("A1", 65535, GeoPoint(8.433301, -82.433302), 0.91, "235959")
        assertEquals(a, PacketCodec.parse(PacketCodec.format(a)))
        val h = Packet.Heartbeat("A1", 7, null, 0, "000000")
        assertEquals(h, PacketCodec.parse(PacketCodec.format(h)))
    }

    @Test
    fun fitsInOneE32Packet() {
        val a = Packet.Alert("A1", 65535, GeoPoint(-89.123456, -179.123456), 0.99, "235959")
        assert(PacketCodec.format(a).length + 2 <= 58)
    }
}
