package io.github.daiyukichi.sarresponse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MissionStatsTest {
    private var now = 0L
    private val tracker = MissionTracker { now }
    private val base = GeoPoint(8.43, -82.43)

    private fun hb(seq: Int, north: Double) =
        tracker.onLine(PacketCodec.format(Packet.Heartbeat("A1", seq, Geo.offset(base, north, 0.0), 9, "120000")))

    @Test
    fun computesDistanceLossSilenceAndDecisionTime() {
        hb(0, 0.0)
        now = 30_000; hb(1, 100.0)
        now = 120_000; hb(4, 200.0)            // 90 s de silencio y 2 perdidos
        now = 125_000
        tracker.onLine(PacketCodec.format(Packet.Alert("A1", 5, base, 0.9, "120200")))
        now = 140_000
        val id = tracker.state.value.alerts.single().id
        tracker.decide(id, AlertStatus.CONFIRMED)

        // La búsqueda se creó a los 10 s: el tiempo de misión cuenta desde ahí, no desde el primer paquete.
        val st = MissionStats.from(tracker.state.value, now = 150_000, missionStartMillis = 10_000)
        assertEquals(140_000, st.elapsedMillis)
        // Terminada a los 100 s: el reloj se detiene.
        assertEquals(90_000, MissionStats.from(tracker.state.value, 150_000, 10_000, missionEndMillis = 100_000).elapsedMillis)
        assertTrue(st.distanceFlownMeters in 395.0..405.0) // 0→100→200 m y vuelta a la base
        assertEquals(1, st.confirmed)
        assertEquals(15_000, st.avgDecisionMillis)
        assertEquals(100.0 * 2 / 6, st.lossPercent, 0.01)  // 4 recibidos + 2 perdidos
        assertEquals(90_000, st.maxSilenceMillis)

        val log = MissionLog.events(tracker.state.value)
        assertTrue(log.any { it.kind == LogEvent.Kind.SILENCE })
        assertTrue(log.any { it.kind == LogEvent.Kind.CONFIRMED && it.value == 15.0 })

        val csv = CsvExporter.detections(tracker.state.value, base).lines()
        assertTrue(csv[0].startsWith("id,unidad,"))
        assertTrue(csv[1].startsWith("1,A1,120200,0.90,confirmada,"))
        val csvEn = CsvExporter.detections(tracker.state.value, base, english = true).lines()
        assertTrue(csvEn[0].startsWith("id,unit,"))
        assertTrue(csvEn[1].startsWith("1,A1,120200,0.90,confirmed,"))
    }

    @Test
    fun emptyMission() {
        val st = MissionStats.from(MissionState(), now = 1_000, missionStartMillis = null)
        assertNull(st.elapsedMillis)                // sin búsqueda, el reloj no corre
        assertNull(st.avgDecisionMillis)
        assertTrue(st.lossTimeline.isEmpty())
    }
}
