package io.github.daiyukichi.sarresponse.core

import java.util.Locale

/**
 * Indicadores del panel, calculados SOLO con lo que de verdad llega del payload.
 * (El % de área cubierta y la batería del payload necesitan datos que el protocolo aún no trae.)
 */
data class MissionStats(
    val elapsedMillis: Long,
    val distanceFlownMeters: Double,
    val pending: Int,
    val confirmed: Int,
    val dismissed: Int,
    /** Promedio entre que llega una detección y el operador la confirma o descarta. */
    val avgDecisionMillis: Long?,
    val lossPercent: Double,
    /** Mayor silencio entre dos paquetes, contando el silencio actual. */
    val maxSilenceMillis: Long,
    /** % de paquetes perdidos por tramo de tiempo, del más viejo al más reciente. */
    val lossTimeline: List<LossBucket>,
) {
    val total: Int get() = pending + confirmed + dismissed

    companion object {
        fun from(s: MissionState, now: Long, buckets: Int = 12): MissionStats {
            val start = s.startedAtMillis
            val decided = s.alerts.mapNotNull { a -> a.decidedAtMillis?.let { it - a.receivedAtMillis } }

            var maxSilence = 0L
            s.packets.zipWithNext { a, b -> maxSilence = maxOf(maxSilence, b.atMillis - a.atMillis) }
            s.lastPacketAtMillis?.let { maxSilence = maxOf(maxSilence, now - it) }

            val sent = s.received + s.lost
            return MissionStats(
                elapsedMillis = start?.let { now - it } ?: 0L,
                distanceFlownMeters = s.track.zipWithNext { a, b -> Geo.distanceMeters(a.position, b.position) }.sum(),
                pending = s.alerts.count { it.status == AlertStatus.PENDING },
                confirmed = s.alerts.count { it.status == AlertStatus.CONFIRMED },
                dismissed = s.alerts.count { it.status == AlertStatus.DISMISSED },
                avgDecisionMillis = decided.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                lossPercent = if (sent == 0) 0.0 else 100.0 * s.lost / sent,
                maxSilenceMillis = maxSilence,
                lossTimeline = lossTimeline(s.packets, start, now, buckets),
            )
        }

        private fun lossTimeline(packets: List<PacketRecord>, start: Long?, now: Long, n: Int): List<LossBucket> {
            if (start == null || now <= start) return emptyList()
            val width = maxOf(1L, (now - start + n - 1) / n)
            return (0 until n).map { i ->
                val from = start + i * width
                val inBucket = packets.filter { it.atMillis >= from && it.atMillis < from + width }
                val lost = inBucket.sumOf { it.lostBefore }
                val total = inBucket.size + lost
                LossBucket(from - start, width, received = inBucket.size, lost = lost,
                    lossPercent = if (total == 0) null else 100.0 * lost / total)
            }
        }
    }
}

/** [lossPercent] es null si en ese tramo no llegó nada (silencio total, distinto de 0 % de pérdida). */
data class LossBucket(
    val offsetMillis: Long,
    val widthMillis: Long,
    val received: Int,
    val lost: Int,
    val lossPercent: Double?,
)

/** Evento del registro de misión, derivado del estado (no se guarda aparte). */
data class LogEvent(val atMillis: Long, val kind: Kind, val text: String) {
    enum class Kind { START, DETECTION, CONFIRMED, DISMISSED, SILENCE, LOW_BATTERY }
}

object MissionLog {
    /** Silencio que vale la pena registrar: más de un latido y medio sin paquetes. */
    private const val SILENCE_MS = 45_000L

    fun events(s: MissionState): List<LogEvent> {
        val out = mutableListOf<LogEvent>()
        s.startedAtMillis?.let { out += LogEvent(it, LogEvent.Kind.START, "Primer paquete del payload: misión en curso") }
        for (a in s.alerts) {
            val conf = (a.packet.confidence * 100).toInt()
            out += LogEvent(a.receivedAtMillis, LogEvent.Kind.DETECTION, "Detección #${a.id} recibida · $conf%")
            val decided = a.decidedAtMillis ?: continue
            val secs = (decided - a.receivedAtMillis) / 1000
            when (a.status) {
                AlertStatus.CONFIRMED -> out += LogEvent(decided, LogEvent.Kind.CONFIRMED, "#${a.id} confirmada por el operador (en $secs s)")
                AlertStatus.DISMISSED -> out += LogEvent(decided, LogEvent.Kind.DISMISSED, "#${a.id} descartada por el operador (en $secs s)")
                AlertStatus.PENDING -> Unit
            }
        }
        for (e in s.lowBatteryEvents) {
            out += LogEvent(e.atMillis, LogEvent.Kind.LOW_BATTERY, "Batería baja del payload: ${"%.2f".format(Locale.ROOT, e.volts)} V")
        }
        for (e in s.lowBatteryEvents) {
            out += LogEvent(e.atMillis, LogEvent.Kind.LOW_BATTERY, "Batería baja del payload: ${"%.2f".format(Locale.ROOT, e.volts)} V")
        }
        s.packets.zipWithNext { a, b ->
            val gap = b.atMillis - a.atMillis
            if (gap > SILENCE_MS) out += LogEvent(b.atMillis, LogEvent.Kind.SILENCE, "Enlace recuperado tras ${gap / 1000} s sin paquetes")
        }
        return out.sortedByDescending { it.atMillis }
    }
}

/** CSV de detecciones para el informe posterior (Excel, QGIS, etc.). */
object CsvExporter {
    fun detections(s: MissionState, operator: GeoPoint?): String = buildString {
        append("id,unidad,hora_utc,confianza,estado,lat,lon,recibida_ms,decidida_ms,segundos_decision,dist_operador_m,rumbo_operador\n")
        for (a in s.alerts) {
            val p = a.packet
            val pos = p.position
            val dist = if (pos != null && operator != null) "%.0f".format(Locale.ROOT, Geo.distanceMeters(operator, pos)) else ""
            val brg = if (pos != null && operator != null) "%.0f".format(Locale.ROOT, Geo.bearingDegrees(operator, pos)) else ""
            val decSecs = a.decidedAtMillis?.let { ((it - a.receivedAtMillis) / 1000).toString() } ?: ""
            append(listOf(
                a.id, p.unit, p.utc, "%.2f".format(Locale.ROOT, p.confidence), a.status.name.lowercase(),
                pos?.let { "%.6f".format(Locale.ROOT, it.lat) } ?: "", pos?.let { "%.6f".format(Locale.ROOT, it.lon) } ?: "",
                a.receivedAtMillis, a.decidedAtMillis ?: "", decSecs, dist, brg,
            ).joinToString(","))
            append('\n')
        }
    }
}
