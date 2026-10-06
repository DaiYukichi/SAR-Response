package io.github.daiyukichi.sarresponse.data

import android.content.Context
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.CameraGeometry
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.MissionState
import io.github.daiyukichi.sarresponse.core.Packet
import io.github.daiyukichi.sarresponse.core.PacketRecord
import io.github.daiyukichi.sarresponse.core.Search
import io.github.daiyukichi.sarresponse.core.SearchArea
import io.github.daiyukichi.sarresponse.core.TrackPoint
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Resumen para la lista de búsquedas, sin cargar todo el recorrido. */
data class SearchSummary(
    val search: Search,
    val detections: Int,
    val confirmed: Int,
    val updatedAtMillis: Long,
)

/**
 * Guarda cada búsqueda como un archivo JSON en el almacenamiento interno de la app
 * (files/busquedas/<id>.json). Sin internet, sin servidor: todo queda en el teléfono.
 * Se escribe en un archivo temporal y se renombra, así un corte de batería no lo corrompe.
 */
class SearchStore(context: Context) {
    private val dir = File(context.filesDir, "busquedas").apply { mkdirs() }
    private val activeFile = File(dir, "activa.txt")

    var activeId: String?
        get() = activeFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        set(value) {
            if (value == null) activeFile.delete() else activeFile.writeText(value)
        }

    fun save(search: Search, state: MissionState) {
        val json = JSONObject()
            .put("version", 1)
            .put("search", searchJson(search))
            .put("state", stateJson(state))
        val target = File(dir, "${search.id}.json")
        val tmp = File(dir, "${search.id}.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(target)
    }

    fun load(id: String): Pair<Search, MissionState>? = runCatching {
        val json = JSONObject(File(dir, "$id.json").readText())
        parseSearch(json.getJSONObject("search")) to parseState(json.getJSONObject("state"))
    }.getOrNull()

    fun list(): List<SearchSummary> = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        runCatching {
            val json = JSONObject(f.readText())
            val alerts = json.getJSONObject("state").getJSONArray("alerts")
            val statuses = (0 until alerts.length()).map { alerts.getJSONObject(it).getString("status") }
            SearchSummary(
                parseSearch(json.getJSONObject("search")),
                detections = statuses.size,
                confirmed = statuses.count { it == AlertStatus.CONFIRMED.name },
                updatedAtMillis = f.lastModified(),
            )
        }.getOrNull()
    }.sortedByDescending { it.search.createdAtMillis }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        if (activeId == id) activeId = null
    }

    // ---------------------------------------------------------------- JSON

    private fun point(p: GeoPoint) = JSONArray().put(p.lat).put(p.lon)
    private fun point(a: JSONArray) = GeoPoint(a.getDouble(0), a.getDouble(1))

    private fun searchJson(s: Search) = JSONObject()
        .put("id", s.id)
        .put("name", s.name)
        .put("createdAt", s.createdAtMillis)
        .put("swath", s.swathMeters)
        .put("altitude", s.altitudeMeters ?: JSONObject.NULL)
        .put("hfov", s.hfovDegrees)
        .put("finishedAt", s.finishedAtMillis ?: JSONObject.NULL)
        .put("area", s.area?.let { a -> JSONArray().apply { a.vertices.forEach { put(point(it)) } } } ?: JSONObject.NULL)

    private fun parseSearch(j: JSONObject) = Search(
        id = j.getString("id"),
        name = j.getString("name"),
        createdAtMillis = j.getLong("createdAt"),
        swathMeters = j.optDouble("swath", Search.DEFAULT_SWATH_METERS),
        altitudeMeters = if (j.isNull("altitude") || !j.has("altitude")) null else j.getDouble("altitude"),
        hfovDegrees = j.optDouble("hfov", CameraGeometry.DEFAULT_HFOV_DEGREES),
        finishedAtMillis = if (j.isNull("finishedAt")) null else j.getLong("finishedAt"),
        area = if (j.isNull("area")) null else j.getJSONArray("area").let { a ->
            SearchArea((0 until a.length()).map { point(a.getJSONArray(it)) })
        },
    )

    private fun stateJson(s: MissionState) = JSONObject()
        .put("received", s.received)
        .put("lost", s.lost)
        .put("corrupt", s.corrupt)
        .put("lastPacketAt", s.lastPacketAtMillis ?: JSONObject.NULL)
        .put("lastHeartbeat", s.lastHeartbeat?.let { packetJson(it) } ?: JSONObject.NULL)
        .put("track", JSONArray().apply {
            s.track.forEach { put(JSONArray().put(it.position.lat).put(it.position.lon).put(it.atMillis)) }
        })
        .put("packets", JSONArray().apply { s.packets.forEach { put(JSONArray().put(it.atMillis).put(it.lostBefore)) } })
        .put("alerts", JSONArray().apply {
            s.alerts.forEach { a ->
                put(
                    JSONObject()
                        .put("id", a.id)
                        .put("packet", packetJson(a.packet))
                        .put("receivedAt", a.receivedAtMillis)
                        .put("status", a.status.name)
                        .put("decidedAt", a.decidedAtMillis ?: JSONObject.NULL),
                )
            }
        })

    private fun parseState(j: JSONObject): MissionState {
        val track = j.getJSONArray("track")
        val packets = j.getJSONArray("packets")
        val alerts = j.getJSONArray("alerts")
        return MissionState(
            received = j.getInt("received"),
            lost = j.getInt("lost"),
            corrupt = j.getInt("corrupt"),
            lastPacketAtMillis = if (j.isNull("lastPacketAt")) null else j.getLong("lastPacketAt"),
            lastHeartbeat = if (j.isNull("lastHeartbeat")) null else parsePacket(j.getJSONObject("lastHeartbeat")) as? Packet.Heartbeat,
            track = (0 until track.length()).map {
                val t = track.getJSONArray(it)
                TrackPoint(GeoPoint(t.getDouble(0), t.getDouble(1)), t.getLong(2))
            },
            packets = (0 until packets.length()).map {
                val p = packets.getJSONArray(it)
                PacketRecord(p.getLong(0), p.getInt(1))
            },
            alerts = (0 until alerts.length()).map {
                val a = alerts.getJSONObject(it)
                Alert(
                    id = a.getLong("id"),
                    packet = parsePacket(a.getJSONObject("packet")) as Packet.Alert,
                    receivedAtMillis = a.getLong("receivedAt"),
                    status = AlertStatus.valueOf(a.getString("status")),
                    decidedAtMillis = if (a.isNull("decidedAt")) null else a.getLong("decidedAt"),
                )
            },
        )
    }

    private fun packetJson(p: Packet): JSONObject = JSONObject()
        .put("kind", if (p is Packet.Alert) "SAR" else "SAH")
        .put("unit", p.unit)
        .put("seq", p.seq)
        .put("pos", p.position?.let { point(it) } ?: JSONObject.NULL)
        .put("value", if (p is Packet.Alert) p.confidence else (p as Packet.Heartbeat).satellites.toDouble())
        .put("utc", p.utc)

    private fun parsePacket(j: JSONObject): Packet {
        val pos = if (j.isNull("pos")) null else point(j.getJSONArray("pos"))
        return if (j.getString("kind") == "SAR") {
            Packet.Alert(j.getString("unit"), j.getInt("seq"), pos, j.getDouble("value"), j.getString("utc"))
        } else {
            Packet.Heartbeat(j.getString("unit"), j.getInt("seq"), pos, j.getDouble("value").toInt(), j.getString("utc"))
        }
    }
}
