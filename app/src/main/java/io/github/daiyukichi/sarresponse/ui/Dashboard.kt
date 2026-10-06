package io.github.daiyukichi.sarresponse.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.daiyukichi.sarresponse.LinkState
import io.github.daiyukichi.sarresponse.LinkStatus
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.Geo
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.MissionState
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Paleta oscura de alto contraste (legible al sol, ahorra batería en OLED).
private val Bg = Color(0xFF0E1623)
private val Surface1 = Color(0xFF121A26)
private val Line = Color(0xFF243246)
private val Ink = Color(0xFFF3F7FC)
private val Muted = Color(0xFF9FB0C4)
private val Faint = Color(0xFF64748B)
private val Ok = Color(0xFF34D399)
private val Warn = Color(0xFFFBBF24)
private val Danger = Color(0xFFF87171)
private val Primary = Color(0xFF4F9BFF)
private val Operator = Color(0xFF22D3EE)
private val Pending = Color(0xFFFBBF24)
private val Confirmed = Color(0xFFFB5D7A)
private val Dismissed = Color(0xFF657289)

/** Tiempo en que los botones de las tarjetas ignoran toques tras reordenarse la lista. */
private const val REORDER_GUARD_MS = 800L

/** El payload manda latido cada 30 s; más de dos latidos sin nada = enlace LoRa caído. */
private const val HEARTBEAT_MS = 30_000L

@Composable
fun DashboardScreen(
    mission: MissionState,
    link: LinkState,
    operator: GeoPoint?,
    heading: Float?,
    toast: String?,
    onSourceClick: () -> Unit,
    onDecide: (Long, AlertStatus) -> Unit,
    onShare: (Alert) -> Unit,
    onExportGpx: () -> Unit,
) {
    var mapIsMain by rememberSaveable { mutableStateOf(true) }
    var selectedAlertId by rememberSaveable { mutableStateOf<Long?>(null) }
    var navTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    // Si se descarta la detección a la que se navega, se deja de navegar.
    val navTarget = mission.alerts.firstOrNull { it.id == navTargetId && it.status != AlertStatus.DISMISSED }
    LaunchedEffect(navTarget == null) { if (navTarget == null) navTargetId = null }

    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        StatusHeader(mission, link, now, onSourceClick, onExportGpx)

        // osmdroid dibuja fuera de sus límites si no se recorta, tapando el encabezado.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val main = Modifier.fillMaxSize()
            val pip = pipModifier()
            // Ambos paneles viven siempre en el mismo lugar del árbol: al intercambiarlos solo cambia
            // su tamaño, así el mapa no se recrea ni pierde zoom/posición.
            MapPane(
                state = mission,
                selectedAlertId = selectedAlertId,
                operator = operator,
                navTargetId = navTargetId,
                pulse = (now / 1000) % 2 == 0L,
                onAlertClick = { selectedAlertId = it },
                modifier = if (mapIsMain) main else pip,
            )
            VideoPane(compact = mapIsMain, modifier = if (mapIsMain) pip else main)
            // Capa transparente sobre el PiP: tocarlo intercambia las vistas.
            Box(pipModifier().zIndex(3f).clickable { mapIsMain = !mapIsMain })

            if (mapIsMain && navTarget == null) {
                Legend(showOperator = operator != null, modifier = Modifier.align(Alignment.TopStart).padding(10.dp).zIndex(4f))
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).zIndex(6f),
            ) {
                Surface(color = Pending, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "⚠ ${toast.orEmpty()}",
                        color = Color(0xFF2A1C00), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }

            if (navTarget != null) {
                NavigationBanner(
                    alert = navTarget,
                    operator = operator,
                    heading = heading,
                    onClose = { navTargetId = null },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).zIndex(5f),
                )
            }
        }

        AlertPanel(
            mission = mission,
            operator = operator,
            selectedId = selectedAlertId,
            onSelect = {
                selectedAlertId = it
                mapIsMain = true
            },
            onDecide = onDecide,
            onNavigate = {
                navTargetId = it
                selectedAlertId = it
                mapIsMain = true
            },
            onShare = onShare,
        )
    }
}

@Composable
private fun BoxScope.pipModifier() = Modifier
    .align(Alignment.TopEnd)
    .padding(10.dp)
    .size(width = 112.dp, height = 150.dp)
    .zIndex(2f)
    .clip(RoundedCornerShape(14.dp))
    .border(2.5.dp, Surface1, RoundedCornerShape(14.dp))

// ---------------------------------------------------------------- encabezado

@Composable
private fun StatusHeader(
    mission: MissionState,
    link: LinkState,
    now: Long,
    onSourceClick: () -> Unit,
    onExportGpx: () -> Unit,
) {
    Surface(color = Surface1, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SAR-Response", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onExportGpx, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Exportar GPX", color = Primary, fontSize = 12.sp)
                }
                SourceChip(link, onSourceClick)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val age = mission.lastPacketAtMillis?.let { now - it }
                val loraColor = when {
                    age == null -> Faint
                    age < HEARTBEAT_MS + 5_000 -> Ok
                    age < 2 * HEARTBEAT_MS + 5_000 -> Warn
                    else -> Danger
                }
                Pill("ENLACE LORA", loraColor, if (age == null) "sin datos" else "hace ${formatAge(age)}", Modifier.weight(1f))

                val hb = mission.lastHeartbeat
                val fix = hb?.position != null
                Pill(
                    "GPS PAYLOAD",
                    if (hb == null) Faint else if (fix) Ok else Warn,
                    if (hb == null) "sin datos" else "${if (fix) "fix" else "sin fix"} · ${hb.satellites} sat",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Counter(Ok, "Recibidos", mission.received)
                Counter(Warn, "Perdidos", mission.lost)
                Counter(Danger, "Corruptos", mission.corrupt)
            }
        }
    }
}

@Composable
private fun SourceChip(link: LinkState, onClick: () -> Unit) {
    val (color, text) = when (link.status) {
        LinkStatus.IDLE -> Faint to "Elegir fuente"
        LinkStatus.CONNECTING -> Warn to "Conectando…"
        LinkStatus.CONNECTED -> Ok to (link.label ?: "Conectado")
        LinkStatus.RETRYING -> Danger to "Reintentando"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Pill(label: String, color: Color, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Bg)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(label, color = Faint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun Counter(color: Color, label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text("$label ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("$value", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- mapa: leyenda y navegación

@Composable
private fun Legend(showOperator: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Surface1.copy(alpha = 0.85f))
            .border(1.dp, Line, RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendRow(Pending, "Pendiente")
        LegendRow(Confirmed, "Confirmada")
        LegendRow(Dismissed, "Descartada")
        LegendRow(Primary, "Dron")
        if (showOperator) LegendRow(Operator, "Operador")
    }
}

@Composable
private fun LegendRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color).border(1.5.dp, Color.White, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Guía al operador hacia una detección. Con brújula, la flecha apunta hacia la persona
 * según hacia dónde mira el teléfono; sin brújula, el rumbo es respecto del norte.
 */
@Composable
private fun NavigationBanner(
    alert: Alert,
    operator: GeoPoint?,
    heading: Float?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = alert.packet.position
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Confirmed, Color(0xFFC01848))))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (operator == null || target == null) {
            Text(
                if (target == null) "La detección #${alert.id} no tiene posición GPS."
                else "Esperando la posición de tu teléfono…",
                color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
        } else {
            val bearing = Geo.bearingDegrees(operator, target)
            val distance = Geo.distanceMeters(operator, target)
            val arrow = if (heading != null) (bearing - heading).toFloat() else bearing.toFloat()
            Box(
                Modifier.size(52.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DirectionArrow(arrow, Color.White, Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NAVEGANDO A #${alert.id}", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatDistance(distance), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Geo.compassPoint(bearing)} · ${bearing.roundToInt()}°",
                        color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    if (heading != null) "La flecha apunta según tu teléfono" else "Rumbo respecto al norte (sin brújula)",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 10.5.sp,
                )
            }
        }
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

/** Flecha que apunta hacia arriba girada [degrees] en sentido horario. */
@Composable
private fun DirectionArrow(degrees: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.rotate(degrees)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w * 0.85f, h)
            lineTo(w / 2, h * 0.75f)
            lineTo(w * 0.15f, h)
            close()
        }
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------- detecciones

@Composable
private fun AlertPanel(
    mission: MissionState,
    operator: GeoPoint?,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDecide: (Long, AlertStatus) -> Unit,
    onNavigate: (Long) -> Unit,
    onShare: (Alert) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    // La lista NO se desplaza sola al llegar una detección: si se moviera mientras el operador
    // toca, podría confirmar o descartar la tarjeta equivocada. Las nuevas se avisan arriba.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Aun así, si la lista está arriba del todo, la tarjeta nueva aparece primera y empuja a las demás,
    // y al decidir, la tarjeta cambia de grupo. Por eso los botones ignoran toques durante un instante
    // después de cada cambio de orden.
    var lastReorder by remember { mutableStateOf(0L) }
    LaunchedEffect(mission.alerts.size) { lastReorder = System.currentTimeMillis() }
    fun guarded(action: () -> Unit) {
        if (System.currentTimeMillis() - lastReorder > REORDER_GUARD_MS) action()
    }
    Surface(color = Surface1, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Detecciones", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                val pending = mission.pendingCount
                Surface(shape = RoundedCornerShape(50), color = if (pending > 0) Pending else Line) {
                    Text(
                        "$pending por revisar",
                        color = if (pending > 0) Color(0xFF2A1C00) else Muted,
                        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (expanded && listState.canScrollBackward) {
                    Text(
                        "↑ Ver más",
                        color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { listState.animateScrollToItem(0) } }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("${mission.alerts.size} en total", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "▾" else "▴", color = Muted)
            }
            if (expanded) {
                if (mission.alerts.isEmpty()) {
                    Text(
                        "Sin detecciones todavía.",
                        color = Faint, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
                // Pendientes primero porque requieren acción; dentro de cada grupo, la más reciente arriba.
                val ordered = mission.alerts.sortedWith(
                    compareBy<Alert> { it.status.ordinal }.thenByDescending { it.id },
                )
                LazyColumn(Modifier.heightIn(max = 260.dp), state = listState) {
                    items(ordered, key = { it.id }) { a ->
                        AlertCard(
                            alert = a,
                            operator = operator,
                            selected = a.id == selectedId,
                            onSelect = onSelect,
                            onDecide = { id, st ->
                                guarded {
                                    onDecide(id, st)
                                    lastReorder = System.currentTimeMillis()
                                    // Volver arriba, donde quedan las pendientes que faltan revisar.
                                    scope.launch { listState.animateScrollToItem(0) }
                                }
                            },
                            onNavigate = { id -> guarded { onNavigate(id) } },
                            onShare = { al -> guarded { onShare(al) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    alert: Alert,
    operator: GeoPoint?,
    selected: Boolean,
    onSelect: (Long) -> Unit,
    onDecide: (Long, AlertStatus) -> Unit,
    onNavigate: (Long) -> Unit,
    onShare: (Alert) -> Unit,
) {
    val p = alert.packet
    val statusColor = when (alert.status) {
        AlertStatus.PENDING -> Pending
        AlertStatus.CONFIRMED -> Confirmed
        AlertStatus.DISMISSED -> Dismissed
    }
    Column(
        Modifier
            .padding(top = 7.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Primary else Line, RoundedCornerShape(13.dp))
            .clickable { onSelect(alert.id) }
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(statusColor).border(2.dp, Surface1, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Detección #${alert.id}", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${(p.confidence * 100).roundToInt()}%",
                color = confidenceColor(p.confidence), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            StatusTag(alert.status)
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            val pos = p.position
            if (pos != null && operator != null) {
                val bearing = Geo.bearingDegrees(operator, pos)
                DirectionArrow(bearing.toFloat(), Primary, Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "${formatDistance(Geo.distanceMeters(operator, pos))} · ${Geo.compassPoint(bearing)} ${bearing.roundToInt()}°",
                    color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                "${formatUtc(p.utc)} UTC · " +
                    (pos?.let { String.format(Locale.ROOT, "%.6f, %.6f", it.lat, it.lon) } ?: "sin posición GPS"),
                color = Muted, fontSize = 11.sp, maxLines = 1,
            )
        }
        val canNavigate = p.position != null
        when (alert.status) {
            AlertStatus.PENDING -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { onDecide(alert.id, AlertStatus.CONFIRMED) },
                    colors = ButtonDefaults.buttonColors(containerColor = Confirmed, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) { Text("✓ Confirmar", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { onDecide(alert.id, AlertStatus.DISMISSED) },
                    border = BorderStroke(1.dp, Line),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) { Text("✕ Descartar", color = Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                if (canNavigate) NavButton { onNavigate(alert.id) }
            }
            AlertStatus.CONFIRMED -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canNavigate) {
                    Button(
                        onClick = { onNavigate(alert.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color(0xFF001227)),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(38.dp),
                    ) { Text("➤ Navegar a", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
                OutlinedButton(
                    onClick = { onShare(alert) },
                    border = BorderStroke(1.dp, Operator.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) { Text("Compartir coordenadas", color = Operator, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            AlertStatus.DISMISSED -> Unit
        }
    }
}

@Composable
private fun NavButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { DirectionArrow(0f, Color(0xFF001227), Modifier.size(18.dp)) }
}

@Composable
private fun StatusTag(status: AlertStatus) {
    val (bg, fg, text) = when (status) {
        AlertStatus.PENDING -> Triple(Pending, Color(0xFF2A1C00), "PENDIENTE")
        AlertStatus.CONFIRMED -> Triple(Confirmed, Color.White, "CONFIRMADA")
        AlertStatus.DISMISSED -> Triple(Line, Muted, "DESCARTADA")
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            text, color = fg, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** Alta (≥ 80 %) resalta; media en ámbar; baja apagada para que el operador la mire con más cuidado. */
private fun confidenceColor(c: Double) = when {
    c >= 0.80 -> Confirmed
    c >= 0.60 -> Warn
    else -> Muted
}

fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.ROOT, "%.1f km", meters / 1000)

private fun formatAge(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}

fun formatUtc(hhmmss: String) =
    if (hhmmss.length == 6) "${hhmmss.substring(0, 2)}:${hhmmss.substring(2, 4)}:${hhmmss.substring(4)}" else hhmmss
