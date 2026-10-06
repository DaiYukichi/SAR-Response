package io.github.daiyukichi.sarresponse.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MissionTrackerTest {
    private var now = 1_000L
    private val tracker = MissionTracker { now }

    private fun hb(seq: Int, pos: GeoPoint? = GeoPoint(8.0, -82.0)) =
        PacketCodec.format(Packet.Heartbeat("A1", seq, pos, 9, "120000"))

    private fun alert(seq: Int) =
        PacketCodec.format(Packet.Alert("A1", seq, GeoPoint(8.001, -82.001), 0.8, "120010"))

    @Test
    fun countsLostCorruptAndDuplicates() {
        tracker.onLine(hb(0))
        tracker.onLine(hb(1))
        tracker.onLine(hb(4))          // se perdieron 2 y 3
        tracker.onLine(hb(4))          // duplicado: no cuenta
        tracker.onLine("\$SAH,basura*00")
        val s = tracker.state.value
        assertEquals(3, s.received)
        assertEquals(2, s.lost)
        assertEquals(1, s.corrupt)
        assertEquals(3, s.track.size)
    }

    @Test
    fun seqWrapAroundIsNotLoss() {
        tracker.onLine(hb(65535))
        tracker.onLine(hb(0))
        assertEquals(0, tracker.state.value.lost)
    }

    @Test
    fun payloadRebootIsNotCountedAsMassiveLoss() {
        tracker.onLine(hb(500))
        tracker.onLine(hb(0))
        assertEquals(0, tracker.state.value.lost)
    }

    @Test
    fun alertsCanBeConfirmedOrDismissed() {
        tracker.onLine(alert(0))
        tracker.onLine(alert(1))
        val (a, b) = tracker.state.value.alerts
        now = 5_000L
        tracker.decide(a.id, AlertStatus.CONFIRMED)
        tracker.decide(b.id, AlertStatus.DISMISSED)
        val s = tracker.state.value
        assertEquals(AlertStatus.CONFIRMED, s.alerts[0].status)
        assertEquals(5_000L, s.alerts[0].decidedAtMillis)
        assertEquals(0, s.pendingCount)

        val gpx = GpxExporter.export(s)
        assertEquals(1, Regex("<wpt ").findAll(gpx).count()) // la descartada no se exporta
    }
}
