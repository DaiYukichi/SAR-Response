package io.github.daiyukichi.sarresponse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.GpxExporter
import io.github.daiyukichi.sarresponse.core.LinkSource
import io.github.daiyukichi.sarresponse.core.MissionTracker
import io.github.daiyukichi.sarresponse.core.Packet
import io.github.daiyukichi.sarresponse.core.ReplaySource
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

class MissionViewModel : ViewModel() {
    private val tracker = MissionTracker()
    val mission = tracker.state

    private val _link = MutableStateFlow(LinkState())
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _newAlerts = MutableSharedFlow<Packet.Alert>(extraBufferCapacity = 8)
    /** Una emisión por cada detección nueva (para sonar/vibrar). */
    val newAlerts: SharedFlow<Packet.Alert> = _newAlerts

    private var linkJob: Job? = null

    fun startDemo() {
        tracker.reset()
        connect(ReplaySource(), reconnect = false)
    }

    /** [makeSource] recibe el callback que marca el enlace como conectado. */
    fun startBluetooth(makeSource: (onConnected: () -> Unit) -> LinkSource) {
        connect(makeSource { _link.update { it.copy(status = LinkStatus.CONNECTED, error = null) } }, reconnect = true)
    }

    fun stop() {
        linkJob?.cancel()
        _link.value = LinkState()
    }

    fun decide(alertId: Long, status: AlertStatus) = tracker.decide(alertId, status)

    fun exportGpx(): String = GpxExporter.export(mission.value)

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
                        if (p is Packet.Alert) _newAlerts.tryEmit(p)
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
