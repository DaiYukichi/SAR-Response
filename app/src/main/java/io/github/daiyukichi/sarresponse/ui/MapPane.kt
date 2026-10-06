package io.github.daiyukichi.sarresponse.ui

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.MissionState
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint as OsmPoint

/** Carpeta donde el usuario copia sus .mbtiles para trabajar sin internet. */
fun offlineMapDir(context: Context): File = File(context.getExternalFilesDir(null), "mapas")

@Composable
fun MapPane(
    state: MissionState,
    selectedAlertId: Long?,
    operator: GeoPoint?,
    navTargetId: Long?,
    pulse: Boolean,
    onAlertClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember { MapHolder(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.map.onDetach()
        }
    }

    LaunchedEffect(selectedAlertId) {
        val pos = state.alerts.firstOrNull { it.id == selectedAlertId }?.packet?.position ?: return@LaunchedEffect
        holder.map.controller.animateTo(pos.osm(), 18.0, 600L)
    }

    AndroidView(
        factory = { holder.map },
        update = { holder.render(state, selectedAlertId, operator, navTargetId, pulse, onAlertClick) },
        modifier = modifier.clipToBounds(),
    )
}

private class MapHolder(private val context: Context) {
    val map: MapView
    private val track: Polyline
    private val drone: Marker
    private val operatorMarker: Marker
    private val navLine: Polyline
    private val alertMarkers = mutableListOf<Marker>()
    private var centeredOnDrone = false

    init {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        map = MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(15.0)
            controller.setCenter(OsmPoint(8.4333, -82.4333))
        }
        useOfflineTilesIfAvailable()

        track = Polyline(map).apply {
            outlinePaint.color = Color.rgb(64, 160, 255)
            outlinePaint.strokeWidth = 6f
        }
        drone = Marker(map).apply {
            icon = dot(Color.rgb(64, 160, 255), 22)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            title = "Dron"
        }
        operatorMarker = Marker(map).apply {
            icon = dot(OPERATOR, 18)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        }
        navLine = Polyline(map).apply {
            outlinePaint.color = CONFIRMED
            outlinePaint.strokeWidth = 7f
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 18f), 0f)
        }
        map.overlays.add(CopyrightOverlay(context))
        map.overlays.add(track)
        map.overlays.add(navLine)
    }

    /** Si hay .mbtiles en Android/data/<app>/files/mapas/, se usan y no se toca la red. */
    private fun useOfflineTilesIfAvailable() {
        val files = offlineMapDir(context).listFiles { f -> f.extension.equals("mbtiles", true) }
        if (files.isNullOrEmpty()) {
            map.setTileSource(TileSourceFactory.MAPNIK)
            return
        }
        val provider = OfflineTileProvider(SimpleRegisterReceiver(context), files)
        map.setTileProvider(provider)
        val sourceName = provider.archives.firstOrNull()?.tileSources?.firstOrNull()
        map.setTileSource(sourceName?.let { FileBasedTileSource.getSource(it) } ?: TileSourceFactory.MAPNIK)
        map.setUseDataConnection(false)
    }

    fun render(
        state: MissionState,
        selectedId: Long?,
        operator: GeoPoint?,
        navTargetId: Long?,
        pulse: Boolean,
        onAlertClick: (Long) -> Unit,
    ) {
        track.setPoints(state.track.map { it.position.osm() })

        map.overlays.remove(operatorMarker)
        operator?.let {
            operatorMarker.position = it.osm()
            map.overlays.add(operatorMarker)
        }
        val target = state.alerts.firstOrNull { it.id == navTargetId }?.packet?.position
        navLine.setPoints(if (operator != null && target != null) listOf(operator.osm(), target.osm()) else emptyList())

        map.overlays.remove(drone)
        state.dronePosition?.let {
            drone.position = it.osm()
            map.overlays.add(drone)
            if (!centeredOnDrone) {
                map.controller.animateTo(drone.position, 17.0, 800L)
                centeredOnDrone = true
            }
        }

        map.overlays.removeAll(alertMarkers)
        alertMarkers.clear()
        for (a in state.alerts) {
            val pos = a.packet.position ?: continue
            val color = when (a.status) {
                AlertStatus.PENDING -> PENDING
                AlertStatus.CONFIRMED -> CONFIRMED
                AlertStatus.DISMISSED -> DISMISSED
            }
            val size = if (a.id == selectedId) 30 else 22
            alertMarkers += Marker(map).apply {
                position = pos.osm()
                // Las pendientes "laten" (halo que crece y se achica) para llamar la atención.
                icon = when {
                    a.id == selectedId -> ringed(dot(color, size), Color.rgb(64, 160, 255), size + 14)
                    a.status == AlertStatus.PENDING -> ringed(dot(color, size), color, if (pulse) size + 18 else size + 6, alpha = 90)
                    else -> dot(color, size)
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> onAlertClick(a.id); true }
            }
        }
        map.overlays.addAll(alertMarkers)
        map.invalidate()
    }

    /** [inner] centrado sobre un círculo de [sizeDp] (halo o anillo de selección). */
    private fun ringed(inner: Drawable, color: Int, sizeDp: Int, alpha: Int = 255): Drawable {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (alpha < 255) setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
            else setStroke(px / 12, color)
            setSize(px, px)
        }
        val inset = (px - inner.intrinsicWidth) / 2
        return LayerDrawable(arrayOf(halo, inner)).apply { setLayerInset(1, inset, inset, inset, inset) }
    }

    private fun dot(color: Int, sizeDp: Int): GradientDrawable {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(px / 8, Color.WHITE)
            setSize(px, px)
        }
    }
}

private fun GeoPoint.osm() = OsmPoint(lat, lon)

private val PENDING = Color.rgb(251, 191, 36)
private val CONFIRMED = Color.rgb(251, 93, 122)
private val DISMISSED = Color.rgb(101, 114, 137)
private val OPERATOR = Color.rgb(34, 211, 238)
