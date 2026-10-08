package io.github.daiyukichi.sarresponse.core

import java.util.Locale

/** GPX 1.1 con las detecciones (no descartadas) como waypoints y el recorrido del dron. */
object GpxExporter {
    fun export(state: MissionState, searchName: String? = null, english: Boolean = false): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="SAR-Response" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        for (a in state.alerts) {
            val pos = a.packet.position ?: continue
            if (a.status == AlertStatus.DISMISSED) continue
            val confirmed = a.status == AlertStatus.CONFIRMED
            val conf = "%.0f".format(Locale.ROOT, a.packet.confidence * 100)
            append("""  <wpt lat="${pos.lat}" lon="${pos.lon}">""")
            if (english) {
                append("<name>Person #${a.id} (${if (confirmed) "CONFIRMED" else "PENDING"})</name>")
                append("<desc>unit ${a.packet.unit}, confidence $conf%, UTC ${a.packet.utc}</desc>")
            } else {
                append("<name>Persona #${a.id} (${if (confirmed) "CONFIRMADA" else "PENDIENTE"})</name>")
                append("<desc>unidad ${a.packet.unit}, confianza $conf%, UTC ${a.packet.utc}</desc>")
            }
            append("</wpt>\n")
        }
        if (state.track.isNotEmpty()) {
            val track = if (english) "Drone track" else "Recorrido del dron"
            append("  <trk><name>$track${searchName?.let { " · " + xml(it) } ?: ""}</name><trkseg>\n")
            for (p in state.track) append("""    <trkpt lat="${p.position.lat}" lon="${p.position.lon}"/>""").append('\n')
            append("  </trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
