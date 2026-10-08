package io.github.daiyukichi.sarresponse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daiyukichi.sarresponse.R
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.CameraGeometry
import io.github.daiyukichi.sarresponse.core.Geo
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.LogEvent
import io.github.daiyukichi.sarresponse.core.MissionLog
import io.github.daiyukichi.sarresponse.core.MissionState
import io.github.daiyukichi.sarresponse.core.MissionStats
import io.github.daiyukichi.sarresponse.core.PacketCodec
import io.github.daiyukichi.sarresponse.core.Search
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val PBg = Color(0xFF0E1623)
private val PSurface = Color(0xFF121A26)
private val PSurface2 = Color(0xFF0D1521)
private val PLine = Color(0xFF243246)
private val PInk = Color(0xFFF3F7FC)
private val PMuted = Color(0xFF9FB0C4)
private val PFaint = Color(0xFF64748B)
private val POk = Color(0xFF34D399)
private val PWarn = Color(0xFFFBBF24)
private val PDanger = Color(0xFFF87171)
private val PPrimary = Color(0xFF4F9BFF)
private val PTeal = Color(0xFF2DD4BF)
private val PPending = Color(0xFFFBBF24)
private val PConfirmed = Color(0xFFFB5D7A)
private val PDismissed = Color(0xFF657289)

/**
 * Panel de la misión: indicadores calculados solo con datos reales recibidos del payload.
 * El % de área cubierta es una estimación (altura planificada y FOV de la cámara).
 */
@Composable
fun PanelScreen(mission: MissionState, operator: GeoPoint?, now: Long, search: Search?, modifier: Modifier = Modifier) {
    val st = MissionStats.from(mission, now, search?.createdAtMillis, search?.finishedAtMillis)
    val log = MissionLog.events(mission)
    LazyColumn(
        modifier.background(PBg),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SearchCard(search, mission) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi(
                    stringResource(R.string.p_mission_time),
                    st.elapsedMillis?.let { formatDuration(it) } ?: "—",
                    when {
                        search == null -> stringResource(R.string.p_create_to_start)
                        search.finishedAtMillis != null -> stringResource(R.string.p_search_finished)
                        else -> stringResource(R.string.p_since_created)
                    },
                    Modifier.weight(1f),
                )
                Kpi(stringResource(R.string.p_distance), String.format(Locale.ROOT, "%.2f km", st.distanceFlownMeters / 1000), stringResource(R.string.p_by_gps), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi(stringResource(R.string.p_confirmed), "${st.confirmed} / ${st.total}", stringResource(R.string.p_of_received), Modifier.weight(1f), valueColor = PConfirmed)
                Kpi(
                    stringResource(R.string.p_lost), String.format(Locale.ROOT, "%.1f %%", st.lossPercent),
                    stringResource(if (st.lossPercent < 5) R.string.p_link_stable else if (st.lossPercent < 15) R.string.p_link_irregular else R.string.p_link_degraded),
                    Modifier.weight(1f),
                    valueColor = if (st.lossPercent < 5) POk else if (st.lossPercent < 15) PWarn else PDanger,
                )
            }
        }
        item {
            val v = mission.batteryVolts
            val low = mission.lowBatteryActive || (v != null && v < PacketCodec.LOW_BATTERY_VOLTS)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi(
                    stringResource(R.string.p_battery),
                    v?.let { String.format(Locale.ROOT, "%.2f V", it) } ?: "—",
                    mission.batteryAtMillis?.let { stringResource(R.string.p_measured_ago, formatSeconds(now - it)) } ?: stringResource(R.string.p_not_sent),
                    Modifier.weight(1f),
                    valueColor = when {
                        v == null -> PMuted
                        low -> PDanger
                        v < 7.4 -> PWarn
                        else -> POk
                    },
                )
                Kpi(
                    stringResource(R.string.p_lowbat_alerts),
                    "${mission.lowBatteryEvents.size}",
                    if (mission.lowBatteryActive) stringResource(R.string.p_alert_active) else stringResource(R.string.p_threshold, PacketCodec.LOW_BATTERY_VOLTS.toString()),
                    Modifier.weight(1f),
                    valueColor = if (mission.lowBatteryEvents.isEmpty()) PInk else PDanger,
                )
            }
        }
        item { DetectionsCard(st) }
        item { TimelineCard(mission) }
        item { LinkCard(mission, st, now) }
        item { TeamCard(mission, operator) }
        item { LogCard(log) }
        item {
            Text(
                stringResource(R.string.p_coverage_note),
                color = PFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/** Área de la búsqueda y cuánto se ha cubierto (estimado con el recorrido y el ancho de barrido). */
@Composable
private fun SearchCard(search: Search?, mission: MissionState) {
    Card(search?.name ?: stringResource(R.string.p_no_active_search)) {
        val area = search?.area
        if (search == null || area == null) {
            Text(
                stringResource(if (search == null) R.string.p_create_from_bar else R.string.p_no_area),
                color = PFaint, fontSize = 12.sp,
            )
            return@Card
        }
        search.altitudeMeters?.let { h ->
            val px = CameraGeometry.pixelsOnModel(CameraGeometry.PERSON_LYING_M, h, search.hfovDegrees)
            Text(
                stringResource(R.string.p_px_at, h.roundToInt(), px.roundToInt()) +
                    if (px < CameraGeometry.MIN_PERSON_PX) stringResource(R.string.p_px_few) else "",
                color = if (px < CameraGeometry.MIN_PERSON_PX) PWarn else PMuted, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        // El cálculo recorre una grilla del área: se recuerda mientras el recorrido no cambie.
        val coverage = remember(mission.track.size, area, search.swathMeters) {
            area.coverage(mission.track.map { it.position }, search.swathMeters)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(coverage * 100).roundToInt()} %", color = PTeal, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.p_area_covered), color = PMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(PSurface2)
                .border(1.dp, PLine, RoundedCornerShape(6.dp)),
        ) {
            if (coverage > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(coverage.toFloat()).clip(RoundedCornerShape(6.dp)).background(PTeal))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            SubKpi(stringResource(R.string.p_area), formatArea(area.areaSquareMeters), Modifier.weight(1f))
            SubKpi(
                stringResource(R.string.p_swath),
                "${search.swathMeters.roundToInt()} m" + (search.altitudeMeters?.let { stringResource(R.string.p_alt_suffix, it.roundToInt()) } ?: ""),
                Modifier.weight(1.4f),
            )
            val outside = mission.alerts.count { a -> a.packet.position?.let { !area.contains(it) } == true }
            SubKpi(stringResource(R.string.p_outside), "$outside", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, sub: String, modifier: Modifier = Modifier, valueColor: Color = PInk) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PSurface)
            .border(1.dp, PLine, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(label.uppercase(), color = PFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
        Text(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(sub, color = PMuted, fontSize = 10.5.sp, maxLines = 1)
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PSurface)
            .border(1.dp, PLine, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(title.uppercase(), color = PFaint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun DetectionsCard(st: MissionStats) {
    Card(stringResource(R.string.detections)) {
        val max = maxOf(1, st.total)
        Bar(stringResource(R.string.p_pending), st.pending, max, PPending)
        Bar(stringResource(R.string.p_confirmed), st.confirmed, max, PConfirmed)
        Bar(stringResource(R.string.p_dismissed), st.dismissed, max, PDismissed)
        Spacer(Modifier.height(10.dp))
        Row {
            SubKpi(stringResource(R.string.p_avg_decision), st.avgDecisionMillis?.let { formatSeconds(it) } ?: "—", Modifier.weight(1f))
            SubKpi(stringResource(R.string.p_total_received), st.total.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Bar(label: String, n: Int, max: Int, color: Color) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = PInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(92.dp))
        Box(
            Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(PSurface2)
                .border(1.dp, PLine, RoundedCornerShape(6.dp)),
        ) {
            if (n > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(n.toFloat() / max).clip(RoundedCornerShape(6.dp)).background(color))
        }
        Text("$n", color = PInk, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(30.dp).padding(start = 8.dp))
    }
}

@Composable
private fun SubKpi(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = PFaint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        Text(value, color = PInk, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Confianza de cada detección (eje Y, 50–100 %) según el momento de la misión en que llegó (eje X). */
@Composable
private fun TimelineCard(mission: MissionState) {
    Card(stringResource(R.string.p_timeline)) {
        val start = mission.startedAtMillis
        val end = maxOf(mission.lastPacketAtMillis ?: 0L, (start ?: 0L) + 1)
        if (start == null || mission.alerts.isEmpty()) {
            Text(stringResource(R.string.no_detections), color = PFaint, fontSize = 12.sp)
            return@Card
        }
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val left = 30.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            val w = size.width - left - 8.dp.toPx()
            val h = bottom - 6.dp.toPx()
            fun y(c: Double) = bottom - ((c.coerceIn(0.5, 1.0) - 0.5) / 0.5).toFloat() * h
            for (c in listOf(0.5, 0.75, 1.0)) {
                drawLine(PLine, Offset(left, y(c)), Offset(left + w, y(c)), strokeWidth = 1f)
            }
            for (a in mission.alerts) {
                val x = left + ((a.receivedAtMillis - start).toFloat() / (end - start)) * w
                val color = when (a.status) {
                    AlertStatus.PENDING -> PPending
                    AlertStatus.CONFIRMED -> PConfirmed
                    AlertStatus.DISMISSED -> PDismissed
                }
                drawLine(color.copy(alpha = 0.3f), Offset(x, y(a.packet.confidence)), Offset(x, bottom), strokeWidth = 2f)
                drawCircle(color, radius = 7.dp.toPx(), center = Offset(x, y(a.packet.confidence)))
                drawCircle(PSurface, radius = 7.dp.toPx(), center = Offset(x, y(a.packet.confidence)), style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.p_axis), color = PFaint, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("00:00 → ${formatDuration(end - start)}", color = PFaint, fontSize = 10.sp)
        }
    }
}

/** Pérdida por tramo de tiempo; una barra punteada gris = tramo sin ningún paquete (silencio). */
@Composable
private fun LinkCard(mission: MissionState, st: MissionStats, now: Long) {
    Card(stringResource(R.string.p_link_quality)) {
        Row {
            SubKpi(stringResource(R.string.p_last_packet), mission.lastPacketAtMillis?.let { stringResource(R.string.ago, formatSeconds(now - it)) } ?: "—", Modifier.weight(1f))
            SubKpi(stringResource(R.string.p_max_silence), formatSeconds(st.maxSilenceMillis), Modifier.weight(1f))
            SubKpi(stringResource(R.string.p_received), "${mission.received}", Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(12.dp))
        if (st.lossTimeline.isEmpty()) {
            Text(stringResource(R.string.p_no_packets), color = PFaint, fontSize = 12.sp)
            return@Card
        }
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val n = st.lossTimeline.size
            val gap = 4.dp.toPx()
            val bw = (size.width - gap * (n - 1)) / n
            st.lossTimeline.forEachIndexed { i, b ->
                val x = i * (bw + gap)
                val pct = b.lossPercent
                if (pct == null) {
                    drawRoundRect(
                        PFaint, Offset(x, 0f), Size(bw, size.height), CornerRadius(4f),
                        style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                    )
                } else {
                    // Siempre una barra mínima, para que "0 % de pérdida" se vea distinto de "silencio".
                    val hh = maxOf(3f, (pct / 100).toFloat() * size.height)
                    val color = if (pct < 5) POk else if (pct < 15) PWarn else PDanger
                    drawRoundRect(color, Offset(x, size.height - hh), Size(bw, hh), CornerRadius(4f))
                }
            }
        }
        Text(
            stringResource(R.string.p_loss_caption),
            color = PFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TeamCard(mission: MissionState, operator: GeoPoint?) {
    Card(stringResource(R.string.p_team)) {
        val drone = mission.dronePosition
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (operator != null && drone != null) formatDistance(Geo.distanceMeters(operator, drone)) else "—",
                color = PInk, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text(stringResource(R.string.p_operator_drone), color = PMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        val rows = mission.alerts.filter { it.status != AlertStatus.DISMISSED && it.packet.position != null }
        if (operator == null || rows.isEmpty()) {
            Text(
                stringResource(if (operator == null) R.string.p_waiting_phone else R.string.p_no_active_detections),
                color = PFaint, fontSize = 12.sp,
            )
            return@Card
        }
        val dists = rows.associateWith { Geo.distanceMeters(operator, it.packet.position!!) }
        val max = dists.values.max().coerceAtLeast(1.0)
        rows.sortedBy { dists[it] }.forEach { a ->
            val d = dists.getValue(a)
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (a.status == AlertStatus.CONFIRMED) PConfirmed else PPending))
                Spacer(Modifier.width(6.dp))
                Text("#${a.id}", color = PInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(34.dp))
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(PSurface2)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((d / max).toFloat()).clip(RoundedCornerShape(5.dp)).background(PTeal))
                }
                Text(
                    "${formatDistance(d)} ${Geo.compassPoint(Geo.bearingDegrees(operator, a.packet.position!!), isEnglish())}",
                    color = PInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(92.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LogCard(log: List<LogEvent>) {
    Card(stringResource(R.string.p_log)) {
        if (log.isEmpty()) {
            Text(stringResource(R.string.p_no_events), color = PFaint, fontSize = 12.sp)
            return@Card
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        log.take(40).forEach { e ->
            val color = when (e.kind) {
                LogEvent.Kind.START -> PPrimary
                LogEvent.Kind.DETECTION -> PPending
                LogEvent.Kind.CONFIRMED -> PConfirmed
                LogEvent.Kind.DISMISSED -> PDismissed
                LogEvent.Kind.SILENCE -> PDanger
                LogEvent.Kind.LOW_BATTERY -> PDanger
            }
            Row(Modifier.padding(vertical = 5.dp)) {
                Text(fmt.format(Date(e.atMillis)), color = PFaint, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
                Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(logText(e), color = PInk, fontSize = 12.sp)
            }
        }
        if (log.size > 40) Text(stringResource(R.string.p_more_events, log.size - 40), color = PFaint, fontSize = 11.sp)
    }
}

@Composable
private fun logText(e: LogEvent): String {
    val id = (e.alertId ?: 0L).toInt()
    val v = e.value ?: 0.0
    return when (e.kind) {
        LogEvent.Kind.START -> stringResource(R.string.log_start)
        LogEvent.Kind.DETECTION -> stringResource(R.string.log_detection, id, (v * 100).toInt())
        LogEvent.Kind.CONFIRMED -> stringResource(R.string.log_confirmed, id, v.toInt())
        LogEvent.Kind.DISMISSED -> stringResource(R.string.log_dismissed, id, v.toInt())
        LogEvent.Kind.SILENCE -> stringResource(R.string.log_silence, v.toInt())
        LogEvent.Kind.LOW_BATTERY -> stringResource(R.string.log_lowbat, String.format(Locale.ROOT, "%.2f", v))
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
}

private fun formatSeconds(ms: Long): String {
    val s = (ms / 1000.0).roundToInt()
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}
