package io.github.daiyukichi.sarresponse.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.util.GeoPoint as OsmPoint

/** Carpeta donde el usuario copia sus .mbtiles para trabajar sin internet. */
fun offlineMapDir(context: Context): File = File(context.getExternalFilesDir(null), "mapas")

@Composable
fun MapPane(
    state: MissionState,
    selectedAlertId: Long?,
    onAlertClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember { MapHolder(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.resume()
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
        update = { holder.render(state, selectedAlertId, onAlertClick) },
        modifier = modifier,
    )
}

private class MapHolder(private val context: Context) {
    val map: MapView
    private val track: Polyline
    private val drone: Marker
    private val alertMarkers = mutableListOf<Marker>()
    private var myLocation: MyLocationNewOverlay? = null
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
        map.overlays.add(CopyrightOverlay(context))
        map.overlays.add(track)
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

    fun resume() {
        map.onResume()
        if (myLocation == null && ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            myLocation = MyLocationNewOverlay(GpsMyLocationProvider(context), map).also {
                it.enableMyLocation()
                map.overlays.add(it)
            }
        }
    }

    fun render(state: MissionState, selectedId: Long?, onAlertClick: (Long) -> Unit) {
        track.setPoints(state.track.map { it.position.osm() })

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
                AlertStatus.PENDING -> Color.rgb(255, 152, 0)
                AlertStatus.CONFIRMED -> Color.rgb(229, 57, 53)
                AlertStatus.DISMISSED -> Color.rgb(140, 140, 140)
            }
            alertMarkers += Marker(map).apply {
                position = pos.osm()
                icon = dot(color, if (a.id == selectedId) 30 else 22)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> onAlertClick(a.id); true }
            }
        }
        map.overlays.addAll(alertMarkers)
        map.invalidate()
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
