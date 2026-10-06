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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.Geo
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.LogEvent
import io.github.daiyukichi.sarresponse.core.MissionLog
import io.github.daiyukichi.sarresponse.core.MissionState
import io.github.daiyukichi.sarresponse.core.MissionStats
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
 * Lo que el protocolo todavía no trae (área cubierta, batería) se indica como no disponible.
 */
@Composable
fun PanelScreen(mission: MissionState, operator: GeoPoint?, now: Long, search: Search?, modifier: Modifier = Modifier) {
    val st = MissionStats.from(mission, now)
    val log = MissionLog.events(mission)
    LazyColumn(
        modifier.background(PBg),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SearchCard(search, mission) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Tiempo de misión", formatDuration(st.elapsedMillis), "desde el primer paquete", Modifier.weight(1f))
                Kpi("Distancia volada", String.format(Locale.ROOT, "%.2f km", st.distanceFlownMeters / 1000), "según el GPS del payload", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Confirmadas", "${st.confirmed} / ${st.total}", "de las detecciones recibidas", Modifier.weight(1f), valueColor = PConfirmed)
                Kpi(
                    "Paquetes perdidos", String.format(Locale.ROOT, "%.1f %%", st.lossPercent),
                    if (st.lossPercent < 5) "enlace estable" else if (st.lossPercent < 15) "enlace irregular" else "enlace degradado",
                    Modifier.weight(1f),
                    valueColor = if (st.lossPercent < 5) POk else if (st.lossPercent < 15) PWarn else PDanger,
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
                "Aún no disponible: batería del payload (requiere agregarla al latido \$SAH). " +
                    "La cobertura es una estimación con el ancho de barrido indicado al crear la búsqueda.",
                color = PFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/** Área de la búsqueda y cuánto se ha cubierto (estimado con el recorrido y el ancho de barrido). */
@Composable
private fun SearchCard(search: Search?, mission: MissionState) {
    Card(search?.name ?: "Sin búsqueda activa") {
        val area = search?.area
        if (search == null || area == null) {
            Text(
                if (search == null) "Crea una búsqueda desde la barra de arriba para registrar la misión."
                else "Esta búsqueda no tiene área. Crea una con área para ver la cobertura.",
                color = PFaint, fontSize = 12.sp,
            )
            return@Card
        }
        // El cálculo recorre una grilla del área: se recuerda mientras el recorrido no cambie.
        val coverage = remember(mission.track.size, area, search.swathMeters) {
            area.coverage(mission.track.map { it.position }, search.swathMeters)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(coverage * 100).roundToInt()} %", color = PTeal, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Text("del área cubierta (estimado)", color = PMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(PSurface2)
                .border(1.dp, PLine, RoundedCornerShape(6.dp)),
        ) {
            if (coverage > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(coverage.toFloat()).clip(RoundedCornerShape(6.dp)).background(PTeal))
        }
        Spacer(Modifier.height(10.dp))
        Row {
            SubKpi("Área", formatArea(area.areaSquareMeters), Modifier.weight(1f))
            SubKpi("Barrido", "${search.swathMeters.roundToInt()} m", Modifier.weight(1f))
            val outside = mission.alerts.count { a -> a.packet.position?.let { !area.contains(it) } == true }
            SubKpi("Fuera del área", "$outside", Modifier.weight(1f))
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
    Card("Detecciones") {
        val max = maxOf(1, st.total)
        Bar("Pendientes", st.pending, max, PPending)
        Bar("Confirmadas", st.confirmed, max, PConfirmed)
        Bar("Descartadas", st.dismissed, max, PDismissed)
        Spacer(Modifier.height(10.dp))
        Row {
            SubKpi("Decisión promedio", st.avgDecisionMillis?.let { formatSeconds(it) } ?: "—", Modifier.weight(1f))
            SubKpi("Total recibidas", st.total.toString(), Modifier.weight(1f))
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
    Card("Línea de tiempo de detecciones") {
        val start = mission.startedAtMillis
        val end = maxOf(mission.lastPacketAtMillis ?: 0L, (start ?: 0L) + 1)
        if (start == null || mission.alerts.isEmpty()) {
            Text("Sin detecciones todavía.", color = PFaint, fontSize = 12.sp)
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
            Text("eje: confianza 50–100 %", color = PFaint, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("00:00 → ${formatDuration(end - start)}", color = PFaint, fontSize = 10.sp)
        }
    }
}

/** Pérdida por tramo de tiempo; una barra punteada gris = tramo sin ningún paquete (silencio). */
@Composable
private fun LinkCard(mission: MissionState, st: MissionStats, now: Long) {
    Card("Calidad del enlace LoRa") {
        Row {
            SubKpi("Último paquete", mission.lastPacketAtMillis?.let { "hace ${formatSeconds(now - it)}" } ?: "—", Modifier.weight(1f))
            SubKpi("Máx. sin señal", formatSeconds(st.maxSilenceMillis), Modifier.weight(1f))
            SubKpi("Recibidos", "${mission.received}", Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(12.dp))
        if (st.lossTimeline.isEmpty()) {
            Text("Sin paquetes todavía.", color = PFaint, fontSize = 12.sp)
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
            "Pérdida de paquetes por tramo de la misión · gris punteado = sin señal",
            color = PFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TeamCard(mission: MissionState, operator: GeoPoint?) {
    Card("Equipo") {
        val drone = mission.dronePosition
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (operator != null && drone != null) formatDistance(Geo.distanceMeters(operator, drone)) else "—",
                color = PInk, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text("operador → dron", color = PMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        val rows = mission.alerts.filter { it.status != AlertStatus.DISMISSED && it.packet.position != null }
        if (operator == null || rows.isEmpty()) {
            Text(
                if (operator == null) "Esperando la posición del teléfono…" else "Sin detecciones activas.",
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
                    "${formatDistance(d)} ${Geo.compassPoint(Geo.bearingDegrees(operator, a.packet.position!!))}",
                    color = PInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(92.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LogCard(log: List<LogEvent>) {
    Card("Registro de misión") {
        if (log.isEmpty()) {
            Text("Todavía no hay eventos.", color = PFaint, fontSize = 12.sp)
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
            }
            Row(Modifier.padding(vertical = 5.dp)) {
                Text(fmt.format(Date(e.atMillis)), color = PFaint, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
                Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(e.text, color = PInk, fontSize = 12.sp)
            }
        }
        if (log.size > 40) Text("… y ${log.size - 40} eventos más (exporta el CSV)", color = PFaint, fontSize = 11.sp)
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
