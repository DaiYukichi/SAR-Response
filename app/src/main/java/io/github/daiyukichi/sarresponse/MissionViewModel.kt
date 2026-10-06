package io.github.daiyukichi.sarresponse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.CameraGeometry
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.GpxExporter
import io.github.daiyukichi.sarresponse.core.LinkSource
import io.github.daiyukichi.sarresponse.core.MissionTracker
import io.github.daiyukichi.sarresponse.core.Packet
import io.github.daiyukichi.sarresponse.core.ReplaySource
import io.github.daiyukichi.sarresponse.core.Search
import io.github.daiyukichi.sarresponse.core.SearchArea
import io.github.daiyukichi.sarresponse.data.SearchStore
import io.github.daiyukichi.sarresponse.data.SearchSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LinkStatus { IDLE, CONNECTING, CONNECTED, RETRYING }

data class LinkState(
    val label: String? = null,
    val status: LinkStatus = LinkStatus.IDLE,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class MissionViewModel(app: Application) : AndroidViewModel(app) {
    private val tracker = MissionTracker()
    val mission = tracker.state

    private val store = SearchStore(app)

    private val _activeSearch = MutableStateFlow<Search?>(null)
    /** La búsqueda en curso; los paquetes que llegan se anotan en ella. */
    val activeSearch: StateFlow<Search?> = _activeSearch.asStateFlow()

    private val _searches = MutableStateFlow<List<SearchSummary>>(emptyList())
    val searches: StateFlow<List<SearchSummary>> = _searches.asStateFlow()

    private val _link = MutableStateFlow(LinkState())
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _newAlerts = MutableSharedFlow<Alert>(extraBufferCapacity = 8)
    /** Una emisión por cada detección nueva (para avisar con sonido, vibración y mensaje). */
    val newAlerts: SharedFlow<Alert> = _newAlerts

    private val _demoOperator = MutableStateFlow<GeoPoint?>(null)
    /** En la demo, la posición simulada del operador; null cuando se usa el GPS real del teléfono. */
    val demoOperator: StateFlow<GeoPoint?> = _demoOperator.asStateFlow()

    private var linkJob: Job? = null

    init {
        viewModelScope.launch {
            // Retomar la búsqueda que estaba activa (por ejemplo, si Android cerró la app).
            val restored = withContext(Dispatchers.IO) { store.activeId?.let { store.load(it) } }
            restored?.let { (search, state) ->
                tracker.restore(state)
                _activeSearch.value = search
            }
            refreshList()
            // Guardado automático: como mucho cada 1,5 s, fuera del hilo principal.
            combine(tracker.state, _activeSearch) { state, search -> search?.let { it to state } }
                .debounce(1_500)
                .collect { pair -> pair?.let { (search, state) -> withContext(Dispatchers.IO) { store.save(search, state) } } }
        }
    }

    fun createSearch(name: String, area: SearchArea?, altitudeMeters: Double, hfovDegrees: Double) {
        viewModelScope.launch { activate(newSearch(name, area, altitudeMeters, hfovDegrees)) }
    }

    fun openSearch(id: String) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load(id) } ?: return@launch
            saveActive()
            tracker.restore(loaded.second)
            _activeSearch.value = loaded.first
            withContext(Dispatchers.IO) { store.activeId = id }
            refreshList()
        }
    }

    /** Cierra la búsqueda actual: queda guardada en la lista y los paquetes nuevos van a otra. */
    fun finishSearch() {
        viewModelScope.launch {
            val current = _activeSearch.value ?: return@launch
            val finished = current.copy(finishedAtMillis = System.currentTimeMillis())
            withContext(Dispatchers.IO) {
                store.save(finished, mission.value)
                store.activeId = null
            }
            _activeSearch.value = null
            tracker.reset()
            refreshList()
        }
    }

    fun deleteSearch(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            if (_activeSearch.value?.id == id) {
                _activeSearch.value = null
                tracker.reset()
            }
            refreshList()
        }
    }

    fun startDemo() {
        val demo = ReplaySource()
        _demoOperator.value = demo.operatorPosition
        viewModelScope.launch {
            // La demo crea su propia búsqueda, con un área alrededor del barrido simulado.
            // 36 m de altura con el FOV provisional ≈ 50 m de barrido, igual a la separación de pasadas.
            activate(newSearch("Demo ${timeLabel()}", SearchArea.rectangle(demo.center, 280.0, 330.0), 36.0, CameraGeometry.DEFAULT_HFOV_DEGREES))
            connect(demo, reconnect = false)
        }
    }

    /** [makeSource] recibe el callback que marca el enlace como conectado. */
    fun startBluetooth(makeSource: (onConnected: () -> Unit) -> LinkSource) {
        _demoOperator.value = null
        viewModelScope.launch {
            // Si llegan datos sin una búsqueda abierta, se crea una para no perder nada.
            if (_activeSearch.value == null) {
                activate(newSearch("Búsqueda ${timeLabel()}", null, Search.DEFAULT_ALTITUDE_METERS, CameraGeometry.DEFAULT_HFOV_DEGREES))
            }
            connect(makeSource { _link.update { it.copy(status = LinkStatus.CONNECTED, error = null) } }, reconnect = true)
        }
    }

    private fun newSearch(name: String, area: SearchArea?, altitudeMeters: Double, hfovDegrees: Double): Search {
        val now = System.currentTimeMillis()
        return Search.planned("b$now", name.ifBlank { "Búsqueda ${timeLabel()}" }, now, area, altitudeMeters, hfovDegrees)
    }

    private suspend fun activate(search: Search) {
        saveActive()
        tracker.reset()
        _activeSearch.value = search
        withContext(Dispatchers.IO) {
            store.save(search, mission.value)
            store.activeId = search.id
        }
        refreshList()
    }

    private suspend fun saveActive() {
        val current = _activeSearch.value ?: return
        val state = mission.value
        withContext(Dispatchers.IO) { store.save(current, state) }
    }

    private suspend fun refreshList() {
        _searches.value = withContext(Dispatchers.IO) { store.list() }
    }

    private fun timeLabel() = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date())

    fun stop() {
        linkJob?.cancel()
        _link.value = LinkState()
    }

    fun decide(alertId: Long, status: AlertStatus) = tracker.decide(alertId, status)

    fun exportGpx(): String = GpxExporter.export(mission.value, _activeSearch.value?.name)

    private fun connect(source: LinkSource, reconnect: Boolean) {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            var backoff = 1_000L
            while (isActive) {
                _link.update { it.copy(label = source.label, status = LinkStatus.CONNECTING) }
                try {
                    source.lines().collect { line ->
                        if (_link.value.status != LinkStatus.CONNECTED) {
                            _link.update { it.copy(status = LinkStatus.CONNECTED, error = null) }
                        }
                        backoff = 1_000L
                        val p = tracker.onLine(line)
                        if (p is Packet.Alert) mission.value.alerts.lastOrNull()?.let { _newAlerts.tryEmit(it) }
                    }
                    _link.update { it.copy(error = if (reconnect) "Enlace cerrado" else null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _link.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                }
                if (!reconnect) {
                    _link.update { it.copy(status = LinkStatus.IDLE) }
                    break
                }
                _link.update { it.copy(status = LinkStatus.RETRYING) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(10_000L)
            }
        }
    }
}
