package io.github.daiyukichi.sarresponse.core

import java.util.Locale

/**
 * Indicadores del panel, calculados SOLO con lo que de verdad llega del payload.
 * (El % de área cubierta y la batería del payload necesitan datos que el protocolo aún no trae.)
 */
data class MissionStats(
    /** Duración de la búsqueda; null si no hay búsqueda (el reloj no corre sin una). */
    val elapsedMillis: Long?,
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
        /**
         * [missionStartMillis]/[missionEndMillis]: cuándo se creó y se terminó la búsqueda. El tiempo de
         * misión se cuenta desde la creación de la búsqueda, no desde el primer paquete: el payload ya
         * manda latidos en la mesa, antes de que exista una búsqueda.
         */
        fun from(
            s: MissionState,
            now: Long,
            missionStartMillis: Long?,
            missionEndMillis: Long? = null,
            buckets: Int = 12,
        ): MissionStats {
            val start = s.startedAtMillis
            val decided = s.alerts.mapNotNull { a -> a.decidedAtMillis?.let { it - a.receivedAtMillis } }

            var maxSilence = 0L
            s.packets.zipWithNext { a, b -> maxSilence = maxOf(maxSilence, b.atMillis - a.atMillis) }
            s.lastPacketAtMillis?.let { maxSilence = maxOf(maxSilence, now - it) }

            val sent = s.received + s.lost
            return MissionStats(
                elapsedMillis = missionStartMillis?.let { (missionEndMillis ?: now) - it }?.coerceAtLeast(0),
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

/**
 * Evento del registro de misión, derivado del estado (no se guarda aparte). Lleva datos, no frases:
 * la app arma el texto en el idioma elegido.
 *  - DETECTION: [alertId] y [value] = confianza (0–1)
 *  - CONFIRMED / DISMISSED: [alertId] y [value] = segundos que tardó la decisión
 *  - SILENCE: [value] = segundos sin paquetes
 *  - LOW_BATTERY: [value] = voltios
 */
data class LogEvent(val atMillis: Long, val kind: Kind, val alertId: Long? = null, val value: Double? = null) {
    enum class Kind { START, DETECTION, CONFIRMED, DISMISSED, SILENCE, LOW_BATTERY }
}

object MissionLog {
    /** Silencio que vale la pena registrar: más de un latido y medio sin paquetes. */
    private const val SILENCE_MS = 45_000L

    fun events(s: MissionState): List<LogEvent> {
        val out = mutableListOf<LogEvent>()
        s.startedAtMillis?.let { out += LogEvent(it, LogEvent.Kind.START) }
        for (a in s.alerts) {
            out += LogEvent(a.receivedAtMillis, LogEvent.Kind.DETECTION, a.id, a.packet.confidence)
            val decided = a.decidedAtMillis ?: continue
            val secs = ((decided - a.receivedAtMillis) / 1000).toDouble()
            when (a.status) {
                AlertStatus.CONFIRMED -> out += LogEvent(decided, LogEvent.Kind.CONFIRMED, a.id, secs)
                AlertStatus.DISMISSED -> out += LogEvent(decided, LogEvent.Kind.DISMISSED, a.id, secs)
                AlertStatus.PENDING -> Unit
            }
        }
        for (e in s.lowBatteryEvents) out += LogEvent(e.atMillis, LogEvent.Kind.LOW_BATTERY, value = e.volts)
        s.packets.zipWithNext { a, b ->
            val gap = b.atMillis - a.atMillis
            if (gap > SILENCE_MS) out += LogEvent(b.atMillis, LogEvent.Kind.SILENCE, value = (gap / 1000).toDouble())
        }
        return out.sortedByDescending { it.atMillis }
    }
}

/** CSV de detecciones para el informe posterior (Excel, QGIS, etc.). */
object CsvExporter {
    private const val HEADER_ES = "id,unidad,hora_utc,confianza,estado,lat,lon,recibida_ms,decidida_ms,segundos_decision,dist_operador_m,rumbo_operador"
    private const val HEADER_EN = "id,unit,utc_time,confidence,status,lat,lon,received_ms,decided_ms,decision_seconds,operator_dist_m,operator_bearing"

    fun detections(s: MissionState, operator: GeoPoint?, english: Boolean = false): String = buildString {
        append(if (english) HEADER_EN else HEADER_ES).append('\n')
        for (a in s.alerts) {
            val p = a.packet
            val pos = p.position
            val dist = if (pos != null && operator != null) "%.0f".format(Locale.ROOT, Geo.distanceMeters(operator, pos)) else ""
            val brg = if (pos != null && operator != null) "%.0f".format(Locale.ROOT, Geo.bearingDegrees(operator, pos)) else ""
            val decSecs = a.decidedAtMillis?.let { ((it - a.receivedAtMillis) / 1000).toString() } ?: ""
            append(listOf(
                a.id, p.unit, p.utc, "%.2f".format(Locale.ROOT, p.confidence), statusWord(a.status, english),
                pos?.let { "%.6f".format(Locale.ROOT, it.lat) } ?: "", pos?.let { "%.6f".format(Locale.ROOT, it.lon) } ?: "",
                a.receivedAtMillis, a.decidedAtMillis ?: "", decSecs, dist, brg,
            ).joinToString(","))
            append('\n')
        }
    }

    private fun statusWord(st: AlertStatus, english: Boolean) = when (st) {
        AlertStatus.PENDING -> if (english) "pending" else "pendiente"
        AlertStatus.CONFIRMED -> if (english) "confirmed" else "confirmada"
        AlertStatus.DISMISSED -> if (english) "dismissed" else "descartada"
    }
}
