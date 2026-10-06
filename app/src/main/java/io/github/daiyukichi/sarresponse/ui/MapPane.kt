package io.github.daiyukichi.sarresponse.ui

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.MissionState
import io.github.daiyukichi.sarresponse.core.SearchArea
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Mapa vectorial offline (MapLibre + .pmtiles). Recorrido, pines, operador y línea de navegación
 * son capas propias encima del mapa base, alimentadas con GeoJSON.
 *
 * [mapRevision] cambia cuando se importa otro mapa, para recargar el estilo.
 */
@Composable
fun MapPane(
    state: MissionState,
    selectedAlertId: Long?,
    operator: GeoPoint?,
    navTargetId: Long?,
    pulse: Boolean,
    mapRevision: Int,
    onAlertClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    area: SearchArea? = null,
    /** Vértices del área que se está dibujando; si no es null, tocar el mapa agrega un vértice. */
    draft: List<GeoPoint>? = null,
    onMapTap: (GeoPoint) -> Unit = {},
) {
    val context = LocalContext.current
    var hasBaseMap by remember { mutableStateOf(true) }
    var style by remember { mutableStateOf<String?>(null) }

    // Copiar el mapa a disco (19 MB la primera vez) y leer el estilo es E/S: fuera del hilo principal.
    LaunchedEffect(mapRevision) {
        style = withContext(Dispatchers.IO) {
            val file = OfflineMap.file(context)
            hasBaseMap = file != null
            OfflineMap.styleJson(context, file)
        }
    }

    // El MapView se crea recién cuando el estilo está listo: si se crea antes y el estilo llega
    // mientras se copia el mapa, MapLibre puede quedar sin dibujar (pasaba en el primer arranque).
    val json = style
    if (json == null) {
        Box(modifier.background(Color(0xFF0B1118)), contentAlignment = Alignment.Center) {
            Text("Preparando mapa offline…", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        return
    }
    val holder = remember { MapHolder(context) }
    holder.onAlertClick = onAlertClick
    holder.onMapTap = if (draft != null) onMapTap else null

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> holder.map.onCreate(null)
                Lifecycle.Event.ON_START -> holder.map.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    holder.map.onResume()
                    holder.repaint()
                }
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                Lifecycle.Event.ON_STOP -> holder.map.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.map.onDestroy()
        }
    }
    LaunchedEffect(json) { holder.loadStyle(json) }

    LaunchedEffect(selectedAlertId) {
        val pos = state.alerts.firstOrNull { it.id == selectedAlertId }?.packet?.position ?: return@LaunchedEffect
        holder.animateTo(pos, 17.0)
    }

    Box(modifier.clipToBounds()) {
        AndroidView(factory = { holder.map }, update = { holder.render(state, selectedAlertId, operator, navTargetId, pulse, area, draft) })
        Text(
            "© OpenStreetMap · Protomaps",
            color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        if (!hasBaseMap) {
            Text(
                "Sin mapa offline: importa un archivo .pmtiles desde \"Elegir fuente\".",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(10.dp),
            )
        }
    }
}

private class MapHolder(context: Context) {
    val map: MapView
    var onAlertClick: (Long) -> Unit = {}
    /** Modo "dibujar área": cada toque en el mapa es un vértice. */
    var onMapTap: ((GeoPoint) -> Unit)? = null

    private var mapLibre: MapLibreMap? = null
    private var style: Style? = null
    private var lastFrame: Frame? = null
    private var centered = Centered.NONE
    private var framedArea: SearchArea? = null

    private enum class Centered { NONE, OPERATOR, DRONE }

    /** Lo último que se dibujó, para volver a dibujarlo tras recargar el estilo. */
    private data class Frame(
        val state: MissionState,
        val selectedId: Long?,
        val operator: GeoPoint?,
        val navTargetId: Long?,
        val pulse: Boolean,
        val area: SearchArea?,
        val draft: List<GeoPoint>?,
    )

    init {
        MapLibre.getInstance(context)
        // La app no usa la red: se le dice a MapLibre que está desconectada para que no intente nada.
        MapLibre.setConnected(false)
        val options = MapLibreMapOptions.createFromAttributes(context)
            // TextureView en vez de SurfaceView: así el mapa respeta recortes, bordes redondeados
            // y el orden de dibujo cuando está en la miniatura (PiP).
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(true)
            .camera(
                org.maplibre.android.camera.CameraPosition.Builder()
                    .target(LatLng(8.4333, -82.4333))
                    .zoom(11.0)
                    .build(),
            )
        map = MapView(context, options)
        map.addOnDidFailLoadingMapListener { Log.e(TAG, "No se pudo cargar el mapa: $it") }
        map.getMapAsync { m ->
            mapLibre = m
            m.setMaxZoomPreference(19.0)
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnMapClickListener { latLng ->
                onMapTap?.let {
                    it(GeoPoint(latLng.latitude, latLng.longitude))
                    return@addOnMapClickListener true
                }
                val p = m.projection.toScreenLocation(latLng)
                val r = 24f * context.resources.displayMetrics.density
                val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), LAYER_ALERTS).firstOrNull()
                val id = hit?.getNumberProperty("id")?.toLong() ?: return@addOnMapClickListener false
                onAlertClick(id)
                true
            }
        }
    }

    fun loadStyle(json: String) {
        Log.i(TAG, "Cargando estilo (${json.length} caracteres)")
        map.getMapAsync { m ->
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                Log.i(TAG, "Estilo listo: ${s.layers.size} capas")
                addDataLayers(s)
                style = s
                lastFrame?.let { draw(it) }
                m.triggerRepaint()
            }
        }
    }

    fun render(
        state: MissionState,
        selectedId: Long?,
        operator: GeoPoint?,
        navTargetId: Long?,
        pulse: Boolean,
        area: SearchArea?,
        draft: List<GeoPoint>?,
    ) {
        val frame = Frame(state, selectedId, operator, navTargetId, pulse, area, draft)
        lastFrame = frame
        draw(frame)
        autoCenter(state, operator, area)
    }

    fun repaint() {
        mapLibre?.triggerRepaint()
    }

    fun animateTo(p: GeoPoint, zoom: Double) {
        mapLibre?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 600)
    }

    /** Al abrir, centra en el operador; cuando llega el primer fix del dron, en el dron. */
    private fun autoCenter(state: MissionState, operator: GeoPoint?, area: SearchArea?) {
        val m = mapLibre ?: return
        val drone = state.dronePosition
        // Al abrir o crear una búsqueda con área, encuadrarla completa.
        if (area != null && area != framedArea && drone == null) {
            framedArea = area
            centered = Centered.OPERATOR
            val bounds = LatLngBounds.Builder().includes(area.vertices.map { LatLng(it.lat, it.lon) }).build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), 600)
            return
        }
        if (drone != null && centered != Centered.DRONE) {
            centered = Centered.DRONE
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(drone.lat, drone.lon), 16.0), 800)
        } else if (drone == null && operator != null && centered == Centered.NONE) {
            centered = Centered.OPERATOR
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(operator.lat, operator.lon), 15.0))
        }
    }

    private fun draw(f: Frame) {
        val s = style ?: return
        val st = f.state
        s.source(SRC_TRACK)?.setGeoJson(lineString(st.track.map { it.position }))
        s.source(SRC_DRONE)?.setGeoJson(points(listOfNotNull(st.dronePosition?.let { it to "" })))
        s.source(SRC_OPERATOR)?.setGeoJson(points(listOfNotNull(f.operator?.let { it to "" })))

        val target = st.alerts.firstOrNull { it.id == f.navTargetId }?.packet?.position
        s.source(SRC_NAV)?.setGeoJson(lineString(if (f.operator != null && target != null) listOf(f.operator, target) else emptyList()))

        val features = st.alerts.mapNotNull { a ->
            val pos = a.packet.position ?: return@mapNotNull null
            pos to """"id":${a.id},"status":"${a.status.name}","selected":${a.id == f.selectedId}"""
        }
        s.source(SRC_ALERTS)?.setGeoJson(points(features))

        s.source(SRC_AREA)?.setGeoJson(f.area?.let { polygon(it.vertices) } ?: EMPTY)
        val d = f.draft.orEmpty()
        s.source(SRC_DRAFT)?.setGeoJson(if (d.size >= 3) polygon(d) else lineString(d))
        s.source(SRC_DRAFT_PTS)?.setGeoJson(points(d.map { it to "" }))

        // Las pendientes "laten": el halo crece y se achica cada segundo.
        s.getLayer(LAYER_HALO)?.setProperties(circleRadius(if (f.pulse) 20f else 13f))
    }

    private fun addDataLayers(s: Style) {
        listOf(SRC_AREA, SRC_DRAFT, SRC_DRAFT_PTS, SRC_TRACK, SRC_NAV, SRC_ALERTS, SRC_OPERATOR, SRC_DRONE)
            .forEach { s.addSource(GeoJsonSource(it, EMPTY)) }

        // Área de la búsqueda: relleno suave y borde.
        s.addLayer(FillLayer("sar-area-fill", SRC_AREA).withProperties(fillColor(AREA), fillOpacity(0.10f)))
        s.addLayer(LineLayer("sar-area-line", SRC_AREA).withProperties(lineColor(AREA), lineWidth(2.5f)))
        // Área en dibujo: más visible, con los vértices marcados.
        s.addLayer(FillLayer("sar-draft-fill", SRC_DRAFT).withProperties(fillColor(AREA), fillOpacity(0.18f)))
        s.addLayer(LineLayer("sar-draft-line", SRC_DRAFT).withProperties(lineColor(AREA), lineWidth(3f), lineDasharray(arrayOf(2f, 1f))))
        s.addLayer(
            CircleLayer("sar-draft-pts", SRC_DRAFT_PTS).withProperties(
                circleRadius(6f), circleColor(AREA), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
            ),
        )

        s.addLayer(
            LineLayer("sar-track", SRC_TRACK).withProperties(
                lineColor(PRIMARY), lineWidth(3.5f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer("sar-nav", SRC_NAV).withProperties(
                lineColor(CONFIRMED), lineWidth(3f), lineDasharray(arrayOf(2f, 1.5f)),
            ),
        )
        s.addLayer(
            CircleLayer(LAYER_HALO, SRC_ALERTS)
                .withFilter(Expression.eq(Expression.get("status"), "PENDING"))
                .withProperties(circleColor(PENDING), circleOpacity(0.35f), circleRadius(13f)),
        )
        s.addLayer(
            CircleLayer("sar-selected", SRC_ALERTS)
                .withFilter(Expression.eq(Expression.get("selected"), true))
                .withProperties(circleRadius(17f), circleOpacity(0f), circleStrokeColor(PRIMARY), circleStrokeWidth(3f)),
        )
        s.addLayer(
            CircleLayer(LAYER_ALERTS, SRC_ALERTS).withProperties(
                circleRadius(9f),
                circleColor(
                    Expression.match(
                        Expression.get("status"), Expression.color(android.graphics.Color.parseColor(DISMISSED)),
                        Expression.stop("PENDING", Expression.color(android.graphics.Color.parseColor(PENDING))),
                        Expression.stop("CONFIRMED", Expression.color(android.graphics.Color.parseColor(CONFIRMED))),
                    ),
                ),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            CircleLayer("sar-operator", SRC_OPERATOR).withProperties(
                circleRadius(8f), circleColor(OPERATOR), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            CircleLayer("sar-drone", SRC_DRONE).withProperties(
                circleRadius(10f), circleColor(PRIMARY), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
            ),
        )
    }

    private fun Style.source(id: String) = getSourceAs<GeoJsonSource>(id)

    private companion object {
        const val TAG = "SAR-Mapa"
        const val SRC_AREA = "sar-area-src"
        const val SRC_DRAFT = "sar-draft-src"
        const val SRC_DRAFT_PTS = "sar-draft-pts-src"
        const val SRC_TRACK = "sar-track-src"
        const val SRC_NAV = "sar-nav-src"
        const val SRC_ALERTS = "sar-alerts-src"
        const val SRC_OPERATOR = "sar-operator-src"
        const val SRC_DRONE = "sar-drone-src"
        const val LAYER_ALERTS = "sar-alerts"
        const val LAYER_HALO = "sar-alerts-halo"

        const val PRIMARY = "#4F9BFF"
        const val PENDING = "#FBBF24"
        const val CONFIRMED = "#FB5D7A"
        const val DISMISSED = "#657289"
        const val OPERATOR = "#22D3EE"
        const val AREA = "#2DD4BF"

        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        fun coord(p: GeoPoint) = String.format(Locale.ROOT, "[%.7f,%.7f]", p.lon, p.lat)

        fun lineString(pts: List<GeoPoint>): String =
            if (pts.size < 2) EMPTY
            else """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${pts.joinToString(",") { coord(it) }}]}}"""

        fun polygon(pts: List<GeoPoint>): String =
            """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[${(pts + pts.first()).joinToString(",") { coord(it) }}]]}}"""

        /** Puntos con propiedades ya escritas en JSON (sin llaves). */
        fun points(items: List<Pair<GeoPoint, String>>): String =
            """{"type":"FeatureCollection","features":[${
                items.joinToString(",") { (p, props) ->
                    """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":${coord(p)}}}"""
                }
            }]}"""
    }
}
