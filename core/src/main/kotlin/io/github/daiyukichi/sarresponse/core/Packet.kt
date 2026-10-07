package io.github.daiyukichi.sarresponse.core

import java.util.Locale

/**
 * Paquete LoRa del payload (texto, checksum XOR tipo NMEA, < 58 bytes):
 *
 *   $SAR,<unidad>,<seq>,<lat>,<lon>,<conf>,<hhmmss>*CS          persona detectada
 *   $SAH,<unidad>,<seq>,<lat>,<lon>,<sats>,<hhmmss>,<vbat>*CS   latido cada 30 s (vbat en V, 1 decimal)
 *   $SAB,<unidad>,<seq>,<vbat>*CS                                batería baja (< 6,8 V, 2 decimales)
 *
 * lat/lon (y la hora) vienen vacíos si el GPS del payload no tiene fix. El $SAH también se acepta
 * sin vbat (7 campos, firmware anterior). seq es un contador compartido por todos los tipos y da la
 * vuelta en 65536.
 */
sealed interface Packet {
    val unit: String
    val seq: Int
    val position: GeoPoint?
    val utc: String

    data class Alert(
        override val unit: String,
        override val seq: Int,
        override val position: GeoPoint?,
        val confidence: Double,
        override val utc: String,
    ) : Packet

    data class Heartbeat(
        override val unit: String,
        override val seq: Int,
        override val position: GeoPoint?,
        val satellites: Int,
        override val utc: String,
        /** Voltaje de la batería del payload; null si el firmware no lo manda. */
        val batteryVolts: Double? = null,
    ) : Packet

    /** Aviso de batería baja del payload: no trae posición ni hora. */
    data class LowBattery(
        override val unit: String,
        override val seq: Int,
        val batteryVolts: Double,
    ) : Packet {
        override val position: GeoPoint? get() = null
        override val utc: String get() = ""
    }
}

data class GeoPoint(val lat: Double, val lon: Double)

object PacketCodec {
    const val SEQ_MODULO = 65536

    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(Locale.ROOT, c)
    }

    /** Por debajo de esto el payload manda $SAB (pack 2S de Li-ion). */
    const val LOW_BATTERY_VOLTS = 6.8

    /** Devuelve null si la línea está corrupta, mal formada o no es $SAR/$SAH/$SAB. */
    fun parse(line: String): Packet? {
        val s = line.trim()
        if (!s.startsWith("$")) return null
        val star = s.lastIndexOf('*')
        if (star < 0) return null
        val body = s.substring(1, star)
        if (!checksum(body).equals(s.substring(star + 1), ignoreCase = true)) return null

        val f = body.split(",")
        val ok = when (f.firstOrNull()) {
            "SAR" -> f.size == 7
            "SAH" -> f.size == 7 || f.size == 8
            "SAB" -> f.size == 4
            else -> false
        }
        if (!ok) return null
        val seq = f[2].toIntOrNull() ?: return null
        if (f[0] == "SAB") {
            val volts = f[3].toDoubleOrNull()?.takeIf { it in 0.0..60.0 } ?: return null
            return Packet.LowBattery(f[1], seq, volts)
        }
        val position = if (f[3].isNotEmpty() && f[4].isNotEmpty()) {
            val lat = f[3].toDoubleOrNull() ?: return null
            val lon = f[4].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            GeoPoint(lat, lon)
        } else null

        return when (f[0]) {
            "SAR" -> Packet.Alert(f[1], seq, position, f[5].toDoubleOrNull() ?: return null, f[6])
            "SAH" -> Packet.Heartbeat(
                f[1], seq, position, f[5].toIntOrNull() ?: 0, f[6],
                // vbat vacío o ausente = sin dato; un valor ilegible invalida el paquete.
                batteryVolts = f.getOrNull(7)?.takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return null },
            )
            else -> null
        }
    }

    /** Inverso de [parse]; lo usan el replay y las pruebas. */
    fun format(p: Packet): String {
        val lat = p.position?.let { "%.6f".format(Locale.ROOT, it.lat) } ?: ""
        val lon = p.position?.let { "%.6f".format(Locale.ROOT, it.lon) } ?: ""
        val body = when (p) {
            is Packet.Alert -> "SAR,${p.unit},${p.seq},$lat,$lon,${"%.2f".format(Locale.ROOT, p.confidence)},${p.utc}"
            is Packet.Heartbeat -> "SAH,${p.unit},${p.seq},$lat,$lon,${p.satellites},${p.utc}" +
                (p.batteryVolts?.let { ",${"%.1f".format(Locale.ROOT, it)}" } ?: "")
            is Packet.LowBattery -> "SAB,${p.unit},${p.seq},${"%.2f".format(Locale.ROOT, p.batteryVolts)}"
        }
        return "$$body*${checksum(body)}"
    }
}
