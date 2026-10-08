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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.daiyukichi.sarresponse.LinkState
import io.github.daiyukichi.sarresponse.LinkStatus
import io.github.daiyukichi.sarresponse.MapDownload
import io.github.daiyukichi.sarresponse.R
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.Geo
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.MissionState
import io.github.daiyukichi.sarresponse.core.PacketCodec
import io.github.daiyukichi.sarresponse.core.Search
import io.github.daiyukichi.sarresponse.core.SearchArea
import io.github.daiyukichi.sarresponse.core.map.MapExtractor
import io.github.daiyukichi.sarresponse.core.map.MapRegion
import io.github.daiyukichi.sarresponse.video.UsbVideo
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
private val AreaColor = Color(0xFF2DD4BF)

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
    mapRevision: Int,
    onlineMapEnabled: Boolean,
    video: UsbVideo,
    search: Search?,
    onSearchClick: () -> Unit,
    onSourceClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onDecide: (Long, AlertStatus) -> Unit,
    onShare: (Alert) -> Unit,
    draft: List<GeoPoint>?,
    onMapTap: (GeoPoint) -> Unit,
    onDraftUndo: () -> Unit,
    onDraftCancel: () -> Unit,
    onDraftConfirm: () -> Unit,
    /** Modo "elegir zona para descargar el mapa". */
    pickingMapArea: Boolean = false,
    mapDownload: MapDownload? = null,
    onMapAreaCancel: () -> Unit = {},
    onMapAreaConfirm: (MapRegion) -> Unit = {},
    onMapDownloadCancel: () -> Unit = {},
    onMapDownloadDismiss: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
) {
    var visibleBounds by remember { mutableStateOf<DoubleArray?>(null) }
    var showPanel by rememberSaveable { mutableStateOf(false) }
    var videoFullscreen by rememberSaveable { mutableStateOf(false) }
    var pipHidden by rememberSaveable { mutableStateOf(false) }
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

    // Para dibujar el área hace falta ver el mapa grande.
    LaunchedEffect(draft != null || pickingMapArea) {
        if (draft != null || pickingMapArea) {
            showPanel = false
            mapIsMain = true
        }
    }
    // Pantalla completa solo tiene sentido con el video como vista principal.
    val fullscreen = videoFullscreen && !mapIsMain && !showPanel && draft == null
    val area = search?.area
    val drone = mission.dronePosition
    val droneOutside = area != null && drone != null && !area.contains(drone)

    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        if (!fullscreen) {
            StatusHeader(mission, link, now, showPanel, { showPanel = it }, search, onSearchClick, onSourceClick, onSettingsClick)
        }
        // Aviso de batería baja ($SAB): visible en todas las vistas, hasta que el operador lo cierre
        // o un latido vuelva a mostrar voltaje normal. Un aviso nuevo lo vuelve a mostrar.
        val lastLow = mission.lowBatteryEvents.lastOrNull()
        var dismissedLowAt by rememberSaveable { mutableStateOf<Long?>(null) }
        if (mission.lowBatteryActive && lastLow != null && dismissedLowAt != lastLow.atMillis) {
            LowBatteryBanner(lastLow.volts, onDismiss = { dismissedLowAt = lastLow.atMillis })
        }

        // El panel se dibuja ENCIMA de la vista de operación: así el mapa nunca se destruye
        // y al volver conserva zoom y posición.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    val main = Modifier.fillMaxSize()
                    // Miniatura oculta: la vista sigue montada (1 dp, invisible) para no perder el mapa ni el video.
                    val pip = if (pipHidden) {
                        Modifier.align(Alignment.TopEnd).size(1.dp).graphicsLayer { alpha = 0f }
                    } else {
                        pipModifier()
                    }
                    // Ambos paneles viven siempre en el mismo lugar del árbol: al intercambiarlos solo cambia
                    // su tamaño, así el mapa no se recrea ni pierde zoom/posición.
                    MapPane(
                        state = mission,
                        selectedAlertId = selectedAlertId,
                        operator = operator,
                        navTargetId = navTargetId,
                        pulse = (now / 1000) % 2 == 0L,
                        mapRevision = mapRevision,
                        onlineEnabled = onlineMapEnabled,
                        onAlertClick = { selectedAlertId = it },
                        modifier = if (mapIsMain) main else pip,
                        compact = !mapIsMain,
                        area = area,
                        draft = draft,
                        onMapTap = onMapTap,
                        onVisibleBounds = { visibleBounds = it },
                    )
                    VideoPane(
                        video,
                        compact = mapIsMain,
                        modifier = if (mapIsMain) pip else main,
                        fullscreen = fullscreen,
                        onToggleFullscreen = { videoFullscreen = !fullscreen },
                        onToggleRecording = onToggleRecording,
                    )
                    if (!pipHidden) {
                        // Capa transparente sobre el PiP: tocarlo intercambia las vistas. El botón "–" va
                        // dentro de la capa para que su toque se atienda antes que el de la capa.
                        Box(pipModifier().zIndex(3f).clickable { mapIsMain = !mapIsMain }) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable { pipHidden = true },
                                contentAlignment = Alignment.Center,
                            ) { Text("–", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                        }
                    } else {
                        Text(
                            stringResource(if (mapIsMain) R.string.pip_show_video else R.string.pip_show_map),
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .zIndex(4f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { pipHidden = false }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }

                    if (mapDownload != null) {
                        MapDownloadBanner(
                            state = mapDownload,
                            onCancel = onMapDownloadCancel,
                            onDismiss = onMapDownloadDismiss,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).zIndex(8f),
                        )
                    } else if (pickingMapArea) {
                        MapAreaBanner(
                            bounds = visibleBounds,
                            onCancel = onMapAreaCancel,
                            onConfirm = onMapAreaConfirm,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).zIndex(8f),
                        )
                    } else if (draft != null) {
                        DraftBanner(
                            vertices = draft,
                            onUndo = onDraftUndo,
                            onCancel = onDraftCancel,
                            onConfirm = onDraftConfirm,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).zIndex(8f),
                        )
                    } else if (droneOutside) {
                        Surface(
                            color = Danger, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).zIndex(6f),
                        ) {
                            Text(
                                stringResource(R.string.drone_outside),
                                color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }

                    if (mapIsMain && navTarget == null && draft == null) {
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

                if (!fullscreen) AlertPanel(
                    mission = mission,
                    operator = operator,
                    area = area,
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
            if (showPanel) {
                PanelScreen(mission, operator, now, search, Modifier.fillMaxSize().zIndex(10f))
            }
        }
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
    showPanel: Boolean,
    onShowPanel: (Boolean) -> Unit,
    search: Search?,
    onSearchClick: () -> Unit,
    onSourceClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
) {
    Surface(color = Surface1, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tabs(showPanel, onShowPanel)
                SourceChip(link, onSourceClick)
                Spacer(Modifier.weight(1f))
                SettingsButton(onSettingsClick)
            }
            Spacer(Modifier.height(6.dp))
            SearchChip(search, onSearchClick)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val age = mission.lastPacketAtMillis?.let { now - it }
                val loraColor = when {
                    age == null -> Faint
                    age < HEARTBEAT_MS + 5_000 -> Ok
                    age < 2 * HEARTBEAT_MS + 5_000 -> Warn
                    else -> Danger
                }
                Pill(
                    stringResource(R.string.pill_lora), loraColor,
                    if (age == null) stringResource(R.string.no_data) else stringResource(R.string.ago, formatAge(age)),
                    Modifier.weight(1f),
                )

                val hb = mission.lastHeartbeat
                val fix = hb?.position != null
                Pill(
                    stringResource(R.string.pill_gps),
                    if (hb == null) Faint else if (fix) Ok else Warn,
                    if (hb == null) stringResource(R.string.no_data)
                    else stringResource(if (fix) R.string.gps_fix else R.string.gps_nofix, hb.satellites),
                    Modifier.weight(1.2f),
                )

                val v = mission.batteryVolts
                Pill(
                    stringResource(R.string.pill_battery),
                    batteryColor(v, mission.lowBatteryActive),
                    v?.let { String.format(Locale.ROOT, "%.1f V", it) } ?: stringResource(R.string.no_data),
                    Modifier.weight(0.8f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Counter(Ok, stringResource(R.string.counter_received), mission.received)
                Counter(Warn, stringResource(R.string.counter_lost), mission.lost)
                Counter(Danger, stringResource(R.string.counter_corrupt), mission.corrupt)
            }
        }
    }
}

/** Pack 2S de Li-ion: ~8,4 V llena, 7,4 V nominal; el payload avisa con $SAB por debajo de 6,8 V. */
private fun batteryColor(volts: Double?, low: Boolean): Color = when {
    volts == null -> Faint
    low || volts < PacketCodec.LOW_BATTERY_VOLTS -> Danger
    volts < 7.4 -> Warn
    else -> Ok
}

@Composable
private fun LowBatteryBanner(volts: Double, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Danger).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.lowbat_title, String.format(Locale.ROOT, "%.2f", volts)),
                color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
            )
            Text(stringResource(R.string.lowbat_body), color = Color.White.copy(alpha = 0.9f), fontSize = 11.5.sp)
        }
        Text(
            stringResource(R.string.got_it), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.2f))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Búsqueda en curso; tocarla abre la lista de búsquedas (crear, abrir, terminar, exportar). */
@Composable
private fun SearchChip(search: Search?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bg)
            .border(1.dp, if (search == null) Warn.copy(alpha = 0.6f) else Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.search_label), color = Faint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            search?.name ?: stringResource(R.string.search_none),
            color = if (search == null) Warn else Ink,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        val area = search?.area
        if (area != null) {
            Text(formatArea(area.areaSquareMeters), color = AreaColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
        } else if (search != null) {
            // Búsqueda sin área (p. ej. creada sola al conectar): invitar a dibujarla.
            Text(stringResource(R.string.add_area_hint), color = Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
        }
        Text("▾", color = Muted)
    }
}

/** Instrucciones y acciones mientras se dibuja el área de una búsqueda nueva. */
@Composable
private fun DraftBanner(
    vertices: List<GeoPoint>,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1.copy(alpha = 0.95f))
            .border(1.5.dp, AreaColor, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.draft_title), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        val info = when {
            vertices.isEmpty() -> stringResource(R.string.draft_empty)
            vertices.size < 3 -> stringResource(R.string.draft_few, vertices.size, 3 - vertices.size)
            else -> stringResource(R.string.draft_points, vertices.size, formatArea(SearchArea(vertices).areaSquareMeters))
        }
        Text(info, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onCancel, border = BorderStroke(1.dp, Line),
                contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp),
            ) { Text(stringResource(R.string.cancel), color = Muted, fontSize = 12.sp) }
            OutlinedButton(
                onClick = onUndo, enabled = vertices.isNotEmpty(), border = BorderStroke(1.dp, Line),
                contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp),
            ) { Text(stringResource(R.string.undo), color = Muted, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onConfirm, enabled = vertices.size >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = AreaColor, contentColor = Color(0xFF042F2A)),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp),
            ) { Text(stringResource(R.string.create_search), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
}

fun formatArea(m2: Double): String =
    if (m2 < 1_000_000) String.format(Locale.ROOT, "%.1f ha", m2 / 10_000)
    else String.format(Locale.ROOT, "%.2f km²", m2 / 1_000_000)

/** Elegir la zona a descargar: la que se ve en el mapa. */
@Composable
private fun MapAreaBanner(
    bounds: DoubleArray?,
    onCancel: () -> Unit,
    onConfirm: (MapRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val region = bounds?.let { (w, s, e, n) -> MapRegion(w, s, e, n, 15).withAffordableZoom() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1.copy(alpha = 0.95f))
            .border(1.5.dp, Primary, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.mapdl_title), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            stringResource(R.string.mapdl_body),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
        if (region != null) {
            Text(
                stringResource(
                    if (region.maxZoom < 15) R.string.mapdl_detail_large else R.string.mapdl_detail,
                    region.maxZoom, region.estimatedMegabytes().roundToInt() + 15,
                ),
                color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onCancel, border = BorderStroke(1.dp, Line),
                contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp),
            ) { Text(stringResource(R.string.cancel), color = Muted, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { region?.let(onConfirm) }, enabled = region != null,
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color(0xFF001227)),
                contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp),
            ) { Text(stringResource(R.string.mapdl_confirm), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MapDownloadBanner(state: MapDownload, onCancel: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1.copy(alpha = 0.95f))
            .border(1.5.dp, if (state.error != null) Danger else Primary, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(
            when {
                state.error != null -> stringResource(R.string.mapdl_failed)
                state.finished -> stringResource(R.string.mapdl_ready)
                else -> stringResource(R.string.mapdl_running)
            },
            color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
        Text(
            state.error ?: if (state.finished) stringResource(R.string.mapdl_ready_body) else
                stringResource(
                    when (state.stage) {
                        null -> R.string.mapdl_stage_base
                        MapExtractor.Stage.FINDING_TILES -> R.string.mapdl_stage_find
                        else -> R.string.mapdl_stage_download
                    },
                ) + (state.fraction?.let { " · ${(it * 100).roundToInt()} %" } ?: ""),
            color = if (state.error != null) Danger else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
        if (state.error == null && !state.finished) {
            Box(
                Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Bg),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(state.fraction ?: 0.02f).background(Primary))
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            if (state.error != null || state.finished) {
                OutlinedButton(onClick = onDismiss, border = BorderStroke(1.dp, Line), modifier = Modifier.height(36.dp)) {
                    Text(stringResource(R.string.close), color = Muted, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = onCancel, border = BorderStroke(1.dp, Line), modifier = Modifier.height(36.dp)) {
                    Text(stringResource(R.string.cancel), color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Selector Operación / Panel. */
@Composable
private fun Tabs(showPanel: Boolean, onShowPanel: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Bg).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(3.dp),
    ) {
        listOf(false to stringResource(R.string.tab_operation), true to stringResource(R.string.tab_panel)).forEach { (panel, label) ->
            val active = showPanel == panel
            Text(
                label,
                color = if (active) Color(0xFF001227) else Muted,
                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Primary else Color.Transparent)
                    .clickable { onShowPanel(panel) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** Ajustes de la app (mapa e idioma), separados de la conexión con la estación tierra. */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Faint.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, Faint.copy(alpha = 0.45f)),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            "⚙ " + stringResource(R.string.settings_title), color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SourceChip(link: LinkState, onClick: () -> Unit) {
    val (color, text) = when (link.status) {
        LinkStatus.IDLE -> Faint to stringResource(R.string.source_choose)
        LinkStatus.CONNECTING -> Warn to stringResource(R.string.source_connecting)
        LinkStatus.CONNECTED -> Ok to (if (link.demo) stringResource(R.string.source_demo) else link.label ?: stringResource(R.string.source_connected))
        LinkStatus.RETRYING -> Danger to stringResource(R.string.source_retrying)
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
        LegendRow(Pending, stringResource(R.string.legend_pending))
        LegendRow(Confirmed, stringResource(R.string.legend_confirmed))
        LegendRow(Dismissed, stringResource(R.string.legend_dismissed))
        LegendRow(Primary, stringResource(R.string.legend_drone))
        if (showOperator) LegendRow(Operator, stringResource(R.string.legend_operator))
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
                if (target == null) stringResource(R.string.nav_no_gps, alert.id.toInt())
                else stringResource(R.string.nav_wait_phone),
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
                Text(stringResource(R.string.nav_to, alert.id.toInt()), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatDistance(distance), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Geo.compassPoint(bearing, isEnglish())} · ${bearing.roundToInt()}°",
                        color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    stringResource(if (heading != null) R.string.nav_compass else R.string.nav_north),
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
    area: SearchArea?,
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
                Text(stringResource(R.string.detections), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                val pending = mission.pendingCount
                Surface(shape = RoundedCornerShape(50), color = if (pending > 0) Pending else Line) {
                    Text(
                        stringResource(R.string.to_review, pending),
                        color = if (pending > 0) Color(0xFF2A1C00) else Muted,
                        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (expanded && listState.canScrollBackward) {
                    Text(
                        stringResource(R.string.see_more),
                        color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { listState.animateScrollToItem(0) } }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.in_total, mission.alerts.size), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "▾" else "▴", color = Muted)
            }
            if (expanded) {
                if (mission.alerts.isEmpty()) {
                    Text(
                        stringResource(R.string.no_detections),
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
                            outsideArea = area != null && a.packet.position?.let { !area.contains(it) } == true,
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
    outsideArea: Boolean,
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
            Text(stringResource(R.string.detection_n, alert.id.toInt()), color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${(p.confidence * 100).roundToInt()}%",
                color = confidenceColor(p.confidence), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            if (outsideArea) {
                Text(stringResource(R.string.outside_area), color = Warn, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(6.dp))
            }
            StatusTag(alert.status)
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            val pos = p.position
            if (pos != null && operator != null) {
                val bearing = Geo.bearingDegrees(operator, pos)
                DirectionArrow(bearing.toFloat(), Primary, Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "${formatDistance(Geo.distanceMeters(operator, pos))} · ${Geo.compassPoint(bearing, isEnglish())} ${bearing.roundToInt()}°",
                    color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                "${formatUtc(p.utc)} UTC · " +
                    (pos?.let { String.format(Locale.ROOT, "%.6f, %.6f", it.lat, it.lon) } ?: stringResource(R.string.no_gps_position)),
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
                ) { Text(stringResource(R.string.confirm), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { onDecide(alert.id, AlertStatus.DISMISSED) },
                    border = BorderStroke(1.dp, Line),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) { Text(stringResource(R.string.dismiss), color = Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                if (canNavigate) NavButton { onNavigate(alert.id) }
            }
            AlertStatus.CONFIRMED -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canNavigate) {
                    Button(
                        onClick = { onNavigate(alert.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color(0xFF001227)),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(38.dp),
                    ) { Text(stringResource(R.string.navigate), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
                OutlinedButton(
                    onClick = { onShare(alert) },
                    border = BorderStroke(1.dp, Operator.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp),
                ) { Text(stringResource(R.string.share_coords), color = Operator, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
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
        AlertStatus.PENDING -> Triple(Pending, Color(0xFF2A1C00), stringResource(R.string.status_pending))
        AlertStatus.CONFIRMED -> Triple(Confirmed, Color.White, stringResource(R.string.status_confirmed))
        AlertStatus.DISMISSED -> Triple(Line, Muted, stringResource(R.string.status_dismissed))
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
