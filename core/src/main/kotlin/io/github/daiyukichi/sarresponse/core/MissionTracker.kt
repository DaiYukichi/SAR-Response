package io.github.daiyukichi.sarresponse.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AlertStatus { PENDING, CONFIRMED, DISMISSED }

data class Alert(
    val id: Long,
    val packet: Packet.Alert,
    val receivedAtMillis: Long,
    val status: AlertStatus = AlertStatus.PENDING,
    val decidedAtMillis: Long? = null,
)

data class TrackPoint(val position: GeoPoint, val atMillis: Long)

/** Llegada de un paquete válido; [lostBefore] = paquetes que faltaron justo antes (por seq). */
data class PacketRecord(val atMillis: Long, val lostBefore: Int)

/** Aviso de batería baja recibido ($SAB). */
data class BatteryEvent(val atMillis: Long, val volts: Double)

data class MissionState(
    val alerts: List<Alert> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val lastPacketAtMillis: Long? = null,
    val lastHeartbeat: Packet.Heartbeat? = null,
    val received: Int = 0,
    val lost: Int = 0,
    val corrupt: Int = 0,
    val packets: List<PacketRecord> = emptyList(),
    /** Último voltaje conocido de la batería del payload (de $SAH o $SAB). */
    val batteryVolts: Double? = null,
    val batteryAtMillis: Long? = null,
    val lowBatteryEvents: List<BatteryEvent> = emptyList(),
    /** Hay un aviso de batería baja vigente: se apaga si un latido vuelve a mostrar voltaje normal. */
    val lowBatteryActive: Boolean = false,
) {
    val startedAtMillis: Long? get() = packets.firstOrNull()?.atMillis
    val dronePosition: GeoPoint? get() = track.lastOrNull()?.position
    val pendingCount: Int get() = alerts.count { it.status == AlertStatus.PENDING }
}

/**
 * Convierte líneas recibidas en el estado de la misión.
 * El reloj se inyecta para poder probarlo sin esperar.
 */
class MissionTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(MissionState())
    val state: StateFlow<MissionState> = _state.asStateFlow()

    private var lastSeq: Int? = null
    private var lastType: Class<out Packet>? = null
    private var nextAlertId = 1L

    /** Procesa una línea. Devuelve el paquete si es válido y nuevo; null si está corrupto o repetido. */
    fun onLine(line: String): Packet? {
        if (line.isBlank()) return null
        val now = clock()
        val p = PacketCodec.parse(line)
        if (p == null) {
            _state.update { it.copy(corrupt = it.corrupt + 1) }
            return null
        }
        val gap = lastSeq?.let { prev ->
            val d = Math.floorMod(p.seq - prev, PacketCodec.SEQ_MODULO)
            // d == 0 es un duplicado; un salto enorme suele ser un reinicio del payload, no pérdidas.
            if (d in 2..MAX_PLAUSIBLE_GAP) d - 1 else 0
        } ?: 0
        // Repetido = mismo seq y mismo tipo (un reenvío). Un $SAB con el mismo seq que el $SAH anterior
        // no es un repetido: el payload puede no avanzar el contador entre tipos distintos.
        val duplicate = lastSeq == p.seq && lastType == p.javaClass
        lastSeq = p.seq
        lastType = p.javaClass
        if (duplicate) return null

        _state.update { s ->
            val track = p.position?.let { s.track + TrackPoint(it, now) } ?: s.track
            when (p) {
                is Packet.Alert -> s.copy(
                    alerts = s.alerts + Alert(nextAlertId++, p, now),
                    track = track, lastPacketAtMillis = now,
                    received = s.received + 1, lost = s.lost + gap,
                    packets = s.packets + PacketRecord(now, gap),
                )
                is Packet.Heartbeat -> s.copy(
                    lastHeartbeat = p,
                    track = track, lastPacketAtMillis = now,
                    received = s.received + 1, lost = s.lost + gap,
                    packets = s.packets + PacketRecord(now, gap),
                    batteryVolts = p.batteryVolts ?: s.batteryVolts,
                    batteryAtMillis = if (p.batteryVolts != null) now else s.batteryAtMillis,
                    lowBatteryActive = when {
                        p.batteryVolts == null -> s.lowBatteryActive
                        else -> p.batteryVolts < PacketCodec.LOW_BATTERY_VOLTS && s.lowBatteryActive
                    },
                )
                is Packet.LowBattery -> s.copy(
                    lastPacketAtMillis = now,
                    received = s.received + 1, lost = s.lost + gap,
                    packets = s.packets + PacketRecord(now, gap),
                    batteryVolts = p.batteryVolts, batteryAtMillis = now,
                    lowBatteryEvents = s.lowBatteryEvents + BatteryEvent(now, p.batteryVolts),
                    lowBatteryActive = true,
                )
            }
        }
        return p
    }

    fun decide(alertId: Long, status: AlertStatus) {
        val now = clock()
        _state.update { s ->
            s.copy(alerts = s.alerts.map {
                if (it.id == alertId) it.copy(status = status, decidedAtMillis = now) else it
            })
        }
    }

    fun reset() = restore(MissionState())

    /** Retoma una búsqueda guardada; el próximo paquete no cuenta pérdidas contra el último seq viejo. */
    fun restore(saved: MissionState) {
        lastSeq = null
        lastType = null
        nextAlertId = (saved.alerts.maxOfOrNull { it.id } ?: 0L) + 1
        _state.value = saved
    }

    private companion object {
        const val MAX_PLAUSIBLE_GAP = 1000
    }
}
