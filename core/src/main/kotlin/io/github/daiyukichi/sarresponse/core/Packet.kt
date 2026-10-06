package io.github.daiyukichi.sarresponse.core

import java.util.Locale

/**
 * Paquete LoRa del payload (texto, checksum XOR tipo NMEA, < 58 bytes):
 *
 *   $SAR,<unidad>,<seq>,<lat>,<lon>,<conf>,<hhmmss>*CS   persona detectada
 *   $SAH,<unidad>,<seq>,<lat>,<lon>,<sats>,<hhmmss>*CS   latido cada 30 s
 *
 * lat/lon vienen vacíos si el GPS del payload no tiene fix.
 * seq es un contador compartido por ambos tipos y da la vuelta en 65536.
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
    ) : Packet
}

data class GeoPoint(val lat: Double, val lon: Double)

object PacketCodec {
    const val SEQ_MODULO = 65536

    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(Locale.ROOT, c)
    }

    /** Devuelve null si la línea está corrupta, mal formada o no es $SAR/$SAH. */
    fun parse(line: String): Packet? {
        val s = line.trim()
        if (!s.startsWith("$")) return null
        val star = s.lastIndexOf('*')
        if (star < 0) return null
        val body = s.substring(1, star)
        if (!checksum(body).equals(s.substring(star + 1), ignoreCase = true)) return null

        val f = body.split(",")
        if (f.size != 7) return null
        val seq = f[2].toIntOrNull() ?: return null
        val position = if (f[3].isNotEmpty() && f[4].isNotEmpty()) {
            val lat = f[3].toDoubleOrNull() ?: return null
            val lon = f[4].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            GeoPoint(lat, lon)
        } else null

        return when (f[0]) {
            "SAR" -> Packet.Alert(f[1], seq, position, f[5].toDoubleOrNull() ?: return null, f[6])
            "SAH" -> Packet.Heartbeat(f[1], seq, position, f[5].toIntOrNull() ?: 0, f[6])
            else -> null
        }
    }

    /** Inverso de [parse]; lo usan el replay y las pruebas. */
    fun format(p: Packet): String {
        val lat = p.position?.let { "%.6f".format(Locale.ROOT, it.lat) } ?: ""
        val lon = p.position?.let { "%.6f".format(Locale.ROOT, it.lon) } ?: ""
        val (kind, value) = when (p) {
            is Packet.Alert -> "SAR" to "%.2f".format(Locale.ROOT, p.confidence)
            is Packet.Heartbeat -> "SAH" to p.satellites.toString()
        }
        val body = "$kind,${p.unit},${p.seq},$lat,$lon,$value,${p.utc}"
        return "$$body*${checksum(body)}"
    }
}
