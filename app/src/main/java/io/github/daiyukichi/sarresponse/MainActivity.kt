package io.github.daiyukichi.sarresponse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.CsvExporter
import io.github.daiyukichi.sarresponse.core.Geo
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.link.BluetoothSppSource
import io.github.daiyukichi.sarresponse.ui.DashboardScreen
import io.github.daiyukichi.sarresponse.ui.OfflineMap
import io.github.daiyukichi.sarresponse.ui.formatDistance
import io.github.daiyukichi.sarresponse.ui.formatUtc
import io.github.daiyukichi.sarresponse.ui.rememberDeviceHeading
import io.github.daiyukichi.sarresponse.ui.rememberOperatorLocation
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: MissionViewModel by viewModels()
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // En operación la pantalla no debe apagarse.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val mission by vm.mission.collectAsStateWithLifecycle()
                val link by vm.link.collectAsStateWithLifecycle()
                val demoOperator by vm.demoOperator.collectAsStateWithLifecycle()
                var showSources by remember { mutableStateOf(false) }
                var permissionsAsked by remember { mutableStateOf(0) }
                var toast by remember { mutableStateOf<String?>(null) }
                var mapRevision by remember { mutableStateOf(0) }
                var hasImportedMap by remember { mutableStateOf(OfflineMap.hasImported(this)) }
                val scope = rememberCoroutineScope()

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    permissionsAsked++
                }

                // Al abrir, pedir de una vez ubicación y Bluetooth: el mapa arranca donde está el operador.
                LaunchedEffect(Unit) {
                    val missing = missingPermissions()
                    if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
                }

                val importMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { OfflineMap.import(this@MainActivity, uri) } }
                        result.onSuccess {
                            hasImportedMap = true
                            mapRevision++
                            Toast.makeText(this@MainActivity, "Mapa importado", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this@MainActivity, it.message ?: "No se pudo importar", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // En la demo el operador está en el punto de despegue simulado; en campo, el GPS del teléfono.
                val phoneLocation by rememberOperatorLocation(permissionsAsked)
                val operator = demoOperator ?: phoneLocation
                val heading by rememberDeviceHeading()

                val exportCsv = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/csv"),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(CsvExporter.detections(vm.mission.value, operator).toByteArray())
                    }
                    Toast.makeText(this, "CSV exportado", Toast.LENGTH_SHORT).show()
                }

                val exportGpx = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/gpx+xml"),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    contentResolver.openOutputStream(uri)?.use { it.write(vm.exportGpx().toByteArray()) }
                    Toast.makeText(this, "GPX exportado", Toast.LENGTH_SHORT).show()
                }

                LaunchedEffect(operator) {
                    vm.newAlerts.collect { alert ->
                        notifyNewAlert()
                        toast = alertSummary(alert, operator)
                    }
                }
                LaunchedEffect(toast) {
                    if (toast != null) {
                        delay(3_500)
                        toast = null
                    }
                }

                DashboardScreen(
                    mission = mission,
                    link = link,
                    operator = operator,
                    heading = heading,
                    toast = toast,
                    mapRevision = mapRevision,
                    onSourceClick = {
                        val missing = missingPermissions()
                        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
                        showSources = true
                    },
                    onDecide = vm::decide,
                    onShare = { shareAlert(it, operator) },
                    onExportGpx = {
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                        exportGpx.launch("sar-$stamp.gpx")
                    },
                    onExportCsv = {
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                        exportCsv.launch("sar-detecciones-$stamp.csv")
                    },
                )

                if (showSources) {
                    SourceDialog(
                        devices = bondedDevices(),
                        onDemo = { vm.startDemo(); showSources = false },
                        onDevice = { device, name ->
                            vm.startBluetooth { onConnected -> BluetoothSppSource(device, name, onConnected) }
                            showSources = false
                        },
                        onStop = { vm.stop(); showSources = false },
                        onDismiss = { showSources = false },
                        hasImportedMap = hasImportedMap,
                        onImportMap = {
                            showSources = false
                            importMap.launch(arrayOf("*/*"))
                        },
                        onRemoveImportedMap = {
                            OfflineMap.removeImported(this@MainActivity)
                            hasImportedMap = false
                            mapRevision++
                            showSources = false
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }

    /** Comparte por WhatsApp, SMS, correo, etc. un texto que se entiende sin la app. */
    private fun shareAlert(alert: Alert, operator: GeoPoint?) {
        val pos = alert.packet.position ?: return
        val lat = String.format(Locale.ROOT, "%.6f", pos.lat)
        val lon = String.format(Locale.ROOT, "%.6f", pos.lon)
        val status = if (alert.status == AlertStatus.CONFIRMED) "CONFIRMADA por el operador" else "pendiente de revisión"
        val fromOperator = operator?.let {
            val b = Geo.bearingDegrees(it, pos)
            "\nDesde el operador: ${formatDistance(Geo.distanceMeters(it, pos))} al ${Geo.compassPoint(b)} (${b.roundToInt()}°)"
        } ?: ""
        val text = "SAR-Response · Persona detectada #${alert.id} ($status)\n" +
            "Confianza IA: ${(alert.packet.confidence * 100).roundToInt()}% · ${formatUtc(alert.packet.utc)} UTC\n" +
            "Coordenadas: $lat, $lon$fromOperator\n" +
            "Mapa: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon\n" +
            "geo:$lat,$lon"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SAR-Response · Detección #${alert.id}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Compartir coordenadas"))
    }

    private fun alertSummary(alert: Alert, operator: GeoPoint?): String {
        val conf = (alert.packet.confidence * 100).roundToInt()
        val pos = alert.packet.position
        val where = if (pos != null && operator != null) {
            " · ${formatDistance(Geo.distanceMeters(operator, pos))} ${Geo.compassPoint(Geo.bearingDegrees(operator, pos))}"
        } else ""
        return "Detección #${alert.id} · $conf%$where"
    }

    private fun missingPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    /** Dispositivos ya emparejados en Ajustes > Bluetooth (la estación tierra se empareja una vez). */
    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<Pair<BluetoothDevice, String>> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { it to (it.name ?: it.address) }
            .sortedBy { it.second }
    }

    private fun notifyNewAlert() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
    }
}

@Composable
private fun SourceDialog(
    devices: List<Pair<BluetoothDevice, String>>,
    onDemo: () -> Unit,
    onDevice: (BluetoothDevice, String) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    hasImportedMap: Boolean,
    onImportMap: () -> Unit,
    onRemoveImportedMap: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fuente de datos") },
        text = {
            Column {
                Text("Estación tierra (Bluetooth emparejado):", style = MaterialTheme.typography.labelLarge)
                if (devices.isEmpty()) {
                    Text(
                        "No hay dispositivos emparejados. Empareja la estación tierra en Ajustes > Bluetooth.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                devices.forEach { (device, name) ->
                    Text(
                        name,
                        modifier = Modifier.fillMaxWidth().clickable { onDevice(device, name) }.padding(vertical = 10.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Demo (misión simulada)",
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDemo).padding(vertical = 10.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Mapa offline:", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (hasImportedMap) "Usando un mapa importado." else "Usando el mapa incluido (Chiriquí).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Importar mapa (.pmtiles)…",
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onImportMap).padding(vertical = 10.dp),
                )
                if (hasImportedMap) {
                    Text(
                        "Volver al mapa incluido",
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onRemoveImportedMap).padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = onStop) { Text("Desconectar") } },
    )
}
