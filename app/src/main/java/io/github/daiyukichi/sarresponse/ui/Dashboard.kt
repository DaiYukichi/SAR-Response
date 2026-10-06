package io.github.daiyukichi.sarresponse.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.daiyukichi.sarresponse.LinkState
import io.github.daiyukichi.sarresponse.LinkStatus
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.MissionState
import java.util.Locale
import kotlinx.coroutines.delay

private val Green = Color(0xFF43A047)
private val Amber = Color(0xFFFFA000)
private val Red = Color(0xFFE53935)
private val Grey = Color(0xFF8C8C8C)
private val Panel = Color(0xE6151A20)

/** El payload manda latido cada 30 s; más de dos latidos sin nada = enlace LoRa caído. */
private const val HEARTBEAT_MS = 30_000L

@Composable
fun DashboardScreen(
    mission: MissionState,
    link: LinkState,
    onSourceClick: () -> Unit,
    onDecide: (Long, AlertStatus) -> Unit,
    onExportGpx: () -> Unit,
) {
    var mapIsMain by rememberSaveable { mutableStateOf(true) }
    var selectedAlertId by rememberSaveable { mutableStateOf<Long?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val main = Modifier.fillMaxSize()
        val pip = pipModifier()
        // Ambos paneles viven siempre en el mismo lugar del árbol: al intercambiarlos solo cambia
        // su tamaño, así el mapa no se recrea ni pierde zoom/posición.
        MapPane(
            state = mission,
            selectedAlertId = selectedAlertId,
            onAlertClick = { selectedAlertId = it },
            modifier = if (mapIsMain) main else pip,
        )
        VideoPane(compact = mapIsMain, modifier = if (mapIsMain) pip else main)
        // Capa transparente sobre el PiP: tocarlo intercambia las vistas.
        Box(pipModifier().zIndex(3f).clickable { mapIsMain = !mapIsMain })

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().zIndex(4f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusBar(mission, link, now, onSourceClick, onExportGpx)
            AlertPanel(
                mission = mission,
                selectedId = selectedAlertId,
                onSelect = {
                    selectedAlertId = it
                    mapIsMain = true
                },
                onDecide = onDecide,
            )
        }
    }
}

@Composable
private fun BoxScope.pipModifier() = Modifier
    .align(Alignment.TopEnd)
    .safeDrawingPadding()
    .padding(top = 104.dp, end = 12.dp)
    .size(width = 168.dp, height = 112.dp)
    .zIndex(2f)
    .clip(RoundedCornerShape(10.dp))
    .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp))

@Composable
private fun StatusBar(
    mission: MissionState,
    link: LinkState,
    now: Long,
    onSourceClick: () -> Unit,
    onExportGpx: () -> Unit,
) {
    Surface(color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (btColor, btText) = when (link.status) {
                    LinkStatus.IDLE -> Grey to "Sin fuente"
                    LinkStatus.CONNECTING -> Amber to "Conectando…"
                    LinkStatus.CONNECTED -> Green to (link.label ?: "Conectado")
                    LinkStatus.RETRYING -> Red to "Reintentando"
                }
                Chip(btColor, btText, onClick = onSourceClick)

                val age = mission.lastPacketAtMillis?.let { now - it }
                val loraColor = when {
                    age == null -> Grey
                    age < HEARTBEAT_MS + 5_000 -> Green
                    age < 2 * HEARTBEAT_MS + 5_000 -> Amber
                    else -> Red
                }
                Chip(loraColor, if (age == null) "LoRa: sin datos" else "LoRa: hace ${formatAge(age)}")

                val sats = mission.lastHeartbeat?.satellites
                val fix = mission.lastHeartbeat?.position != null
                Chip(if (fix) Green else Amber, "GPS: ${if (fix) "fix" else "sin fix"} · ${sats ?: "–"} sat")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Paquetes: ${mission.received} recibidos · ${mission.lost} perdidos · ${mission.corrupt} corruptos",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onExportGpx) { Text("Exportar GPX") }
            }
        }
    }
}

@Composable
private fun Chip(color: Color, text: String, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color),
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else Modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun AlertPanel(
    mission: MissionState,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDecide: (Long, AlertStatus) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(color = Panel, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Detecciones: ${mission.alerts.size}",
                    color = Color.White, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                if (mission.pendingCount > 0) {
                    Text("${mission.pendingCount} por revisar", color = Amber, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▾" else "▴", color = Color.White)
            }
            if (expanded) {
                if (mission.alerts.isEmpty()) {
                    Text(
                        "Sin detecciones todavía.",
                        color = Grey, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                // Las más recientes arriba; las pendientes primero porque requieren acción.
                val ordered = mission.alerts.sortedWith(
                    compareBy<Alert> { it.status != AlertStatus.PENDING }.thenByDescending { it.id },
                )
                // Al llegar una detección nueva, volver arriba para que se vea.
                val listState = rememberLazyListState()
                LaunchedEffect(mission.alerts.size) { listState.animateScrollToItem(0) }
                LazyColumn(Modifier.heightIn(max = 240.dp), state = listState) {
                    items(ordered, key = { it.id }) { a ->
                        AlertRow(a, a.id == selectedId, onSelect, onDecide)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(
    alert: Alert,
    selected: Boolean,
    onSelect: (Long) -> Unit,
    onDecide: (Long, AlertStatus) -> Unit,
) {
    val color = when (alert.status) {
        AlertStatus.PENDING -> Amber
        AlertStatus.CONFIRMED -> Red
        AlertStatus.DISMISSED -> Grey
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable { onSelect(alert.id) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val p = alert.packet
            Text(
                "#${alert.id} · ${(p.confidence * 100).toInt()}% · ${formatUtc(p.utc)} UTC",
                color = Color.White, fontWeight = FontWeight.SemiBold,
            )
            Text(
                p.position?.let { String.format(Locale.ROOT, "%.6f, %.6f", it.lat, it.lon) } ?: "sin posición GPS",
                color = Grey, style = MaterialTheme.typography.bodySmall,
            )
        }
        when (alert.status) {
            AlertStatus.PENDING -> {
                OutlinedButton(onClick = { onDecide(alert.id, AlertStatus.DISMISSED) }) { Text("Descartar") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { onDecide(alert.id, AlertStatus.CONFIRMED) },
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                ) { Text("Confirmar") }
            }
            AlertStatus.CONFIRMED -> Text("CONFIRMADA", color = Red, fontWeight = FontWeight.Bold)
            AlertStatus.DISMISSED -> Text("descartada", color = Grey)
        }
    }
}

private fun formatAge(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}

private fun formatUtc(hhmmss: String) =
    if (hhmmss.length == 6) "${hhmmss.substring(0, 2)}:${hhmmss.substring(2, 4)}:${hhmmss.substring(4)}" else hhmmss
