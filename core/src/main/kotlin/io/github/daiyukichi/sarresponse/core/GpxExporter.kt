package io.github.daiyukichi.sarresponse.core

import java.util.Locale

/** GPX 1.1 con las detecciones (no descartadas) como waypoints y el recorrido del dron. */
object GpxExporter {
    fun export(state: MissionState, searchName: String? = null): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="SAR-Response" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        for (a in state.alerts) {
            val pos = a.packet.position ?: continue
            if (a.status == AlertStatus.DISMISSED) continue
            val status = if (a.status == AlertStatus.CONFIRMED) "CONFIRMADA" else "PENDIENTE"
            append("""  <wpt lat="${pos.lat}" lon="${pos.lon}">""")
            append("<name>Persona #${a.id} ($status)</name>")
            append("<desc>unidad ${a.packet.unit}, confianza ${"%.0f".format(Locale.ROOT, a.packet.confidence * 100)}%, UTC ${a.packet.utc}</desc>")
            append("</wpt>\n")
        }
        if (state.track.isNotEmpty()) {
            append("  <trk><name>Recorrido del dron${searchName?.let { " · " + xml(it) } ?: ""}</name><trkseg>\n")
            for (p in state.track) append("""    <trkpt lat="${p.position.lat}" lon="${p.position.lon}"/>""").append('\n')
            append("  </trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
