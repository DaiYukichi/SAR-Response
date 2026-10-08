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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daiyukichi.sarresponse.core.Alert
import io.github.daiyukichi.sarresponse.core.AlertStatus
import io.github.daiyukichi.sarresponse.core.CameraGeometry
import io.github.daiyukichi.sarresponse.core.CsvExporter
import io.github.daiyukichi.sarresponse.core.Geo
import io.github.daiyukichi.sarresponse.core.GeoPoint
import io.github.daiyukichi.sarresponse.core.Search
import io.github.daiyukichi.sarresponse.core.SearchArea
import io.github.daiyukichi.sarresponse.link.BluetoothSppSource
import io.github.daiyukichi.sarresponse.ui.AppLanguage
import io.github.daiyukichi.sarresponse.ui.DashboardScreen
import io.github.daiyukichi.sarresponse.ui.NewSearchDialog
import io.github.daiyukichi.sarresponse.ui.OfflineMap
import io.github.daiyukichi.sarresponse.ui.OnlineMap
import io.github.daiyukichi.sarresponse.ui.SearchesDialog
import io.github.daiyukichi.sarresponse.ui.formatDistance
import io.github.daiyukichi.sarresponse.ui.formatUtc
import io.github.daiyukichi.sarresponse.ui.isEnglish
import io.github.daiyukichi.sarresponse.ui.rememberDeviceHeading
import io.github.daiyukichi.sarresponse.ui.rememberOperatorLocation
import io.github.daiyukichi.sarresponse.video.UsbVideo
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MissionViewModel by viewModels()
    private val video by lazy { UsbVideo(applicationContext) }
    private var tone: ToneGenerator? = null

    // En Android < 13 el idioma elegido en la app se aplica aquí (en 13+ lo hace el sistema).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

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
                val activeSearch by vm.activeSearch.collectAsStateWithLifecycle()
                val searches by vm.searches.collectAsStateWithLifecycle()
                var showSearches by remember { mutableStateOf(false) }
                var showNewSearch by remember { mutableStateOf(false) }
                // Búsqueda en preparación: nombre y barrido ya elegidos, vértices que se van tocando.
                var draftName by remember { mutableStateOf("") }
                var draftAltitude by remember { mutableStateOf(Search.DEFAULT_ALTITUDE_METERS) }
                var draftFov by remember { mutableStateOf(CameraGeometry.DEFAULT_HFOV_DEGREES) }
                var draft by remember { mutableStateOf<List<GeoPoint>?>(null) }
                // true = el área dibujada es para la búsqueda en curso, no para una nueva.
                var draftForActive by remember { mutableStateOf(false) }
                var pickingMapArea by remember { mutableStateOf(false) }
                val mapDownload by vm.mapDownload.collectAsStateWithLifecycle()
                var showSources by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var permissionsAsked by remember { mutableStateOf(0) }
                // El mapa se crea recién después de resolver los permisos: si el aviso de permisos
                // pausa la app mientras MapLibre se inicia, el mapa queda en blanco.
                var startupReady by remember { mutableStateOf(missingPermissions().isEmpty()) }
                var toast by remember { mutableStateOf<String?>(null) }
                var mapRevision by remember { mutableStateOf(0) }
                var hasImportedMap by remember { mutableStateOf(OfflineMap.hasImported(this)) }
                var onlineMapEnabled by remember { mutableStateOf(OnlineMap.isEnabled(this)) }
                val scope = rememberCoroutineScope()

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    permissionsAsked++
                    startupReady = true
                    video.retry()
                }

                // Video del receptor USB: activo solo mientras la app está a la vista.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, startupReady) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> if (startupReady) video.start()
                            // Si está grabando (p. ej. al compartir una detección por WhatsApp), sigue.
                            Lifecycle.Event.ON_STOP -> if (!video.isRecording) video.stop()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                            Toast.makeText(this@MainActivity, getString(R.string.map_imported), Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(this@MainActivity, getString(R.string.import_failed), Toast.LENGTH_LONG).show()
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
                        it.write(CsvExporter.detections(vm.mission.value, operator, isEnglish()).toByteArray())
                    }
                    Toast.makeText(this, getString(R.string.csv_exported), Toast.LENGTH_SHORT).show()
                }

                val exportGpx = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/gpx+xml"),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    contentResolver.openOutputStream(uri)?.use { it.write(vm.exportGpx(isEnglish()).toByteArray()) }
                    Toast.makeText(this, getString(R.string.gpx_exported), Toast.LENGTH_SHORT).show()
                }

                // Se colecta una sola vez: si se reiniciara con cada posición del GPS, una detección que
                // llegara justo en ese momento se perdería sin sonido ni aviso.
                val currentOperator by rememberUpdatedState(operator)
                LaunchedEffect(Unit) {
                    vm.newAlerts.collect { alert ->
                        notifyNewAlert()
                        toast = alertSummary(alert, currentOperator)
                    }
                }
                DisposableEffect(Unit) {
                    video.onRecordingFinished = { saved, detail ->
                        toast = if (saved) getString(R.string.rec_saved, "Movies/${UsbVideo.RECORDINGS_FOLDER}")
                        else getString(R.string.rec_failed, detail ?: "?")
                    }
                    onDispose { video.onRecordingFinished = null }
                }
                LaunchedEffect(Unit) {
                    // Batería baja del payload: mismo sonido y vibración que una detección, más el aviso fijo.
                    vm.lowBattery.collect { b ->
                        notifyNewAlert()
                        toast = getString(R.string.toast_lowbat, String.format(Locale.ROOT, "%.2f", b.batteryVolts))
                    }
                }
                LaunchedEffect(toast) {
                    if (toast != null) {
                        delay(3_500)
                        toast = null
                    }
                }

                if (!startupReady) {
                    StartupScreen()
                } else {
                    DashboardScreen(
                        mission = mission,
                        link = link,
                        operator = operator,
                        heading = heading,
                        toast = toast,
                        mapRevision = mapRevision,
                        onlineMapEnabled = onlineMapEnabled,
                        video = video,
                        onSourceClick = {
                            val missing = missingPermissions()
                            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
                            showSources = true
                        },
                        onSettingsClick = { showSettings = true },
                        onDecide = vm::decide,
                        onShare = { shareAlert(it, operator) },
                        search = activeSearch,
                        onSearchClick = { showSearches = true },
                        draft = draft,
                        onMapTap = { p -> draft = draft?.plus(p) },
                        onDraftUndo = { draft = draft?.dropLast(1) },
                        onDraftCancel = { draft = null; draftForActive = false },
                        onDraftConfirm = {
                            draft?.takeIf { it.size >= 3 }?.let {
                                if (draftForActive) vm.setActiveArea(SearchArea(it))
                                else vm.createSearch(draftName, SearchArea(it), draftAltitude, draftFov)
                            }
                            draft = null
                            draftForActive = false
                        },
                        pickingMapArea = pickingMapArea,
                        mapDownload = mapDownload,
                        onMapAreaCancel = { pickingMapArea = false },
                        onMapAreaConfirm = { region ->
                            pickingMapArea = false
                            vm.downloadMap(region) {
                                hasImportedMap = true
                                mapRevision++
                            }
                        },
                        onMapDownloadCancel = vm::cancelMapDownload,
                        onMapDownloadDismiss = vm::dismissMapDownload,
                        onToggleRecording = {
                            if (video.isRecording) video.stopRecording()
                            else if (video.startRecording("${fileStem(activeSearch)}-video")) {
                                toast = getString(R.string.rec_started)
                            }
                        },
                    )
                }

                if (showSearches) {
                    SearchesDialog(
                        active = activeSearch,
                        searches = searches,
                        onNew = { showSearches = false; showNewSearch = true },
                        onOpen = { vm.openSearch(it); showSearches = false },
                        onFinish = { vm.finishSearch(); showSearches = false },
                        onDelete = vm::deleteSearch,
                        onExportGpx = {
                            showSearches = false
                            exportGpx.launch("${fileStem(activeSearch)}.gpx")
                        },
                        onExportCsv = {
                            showSearches = false
                            exportCsv.launch("${fileStem(activeSearch)}-${getString(R.string.csv_suffix)}.csv")
                        },
                        onEditArea = {
                            showSearches = false
                            draftForActive = true
                            draft = emptyList()
                        },
                        onDismiss = { showSearches = false },
                    )
                }
                if (showNewSearch) {
                    NewSearchDialog(
                        suggestedName = getString(R.string.search_default_n, searches.size + 1),
                        onDrawArea = { name, altitude, fov ->
                            draftName = name
                            draftAltitude = altitude
                            draftFov = fov
                            draftForActive = false
                            draft = emptyList()
                            showNewSearch = false
                        },
                        onCreateWithoutArea = { name, altitude, fov ->
                            vm.createSearch(name, null, altitude, fov)
                            showNewSearch = false
                        },
                        onDismiss = { showNewSearch = false },
                    )
                }

                if (showSources) {
                    ConnectionDialog(
                        devices = bondedDevices(),
                        onDemo = { vm.startDemo(); showSources = false },
                        onDevice = { device, name ->
                            vm.startBluetooth { onConnected -> BluetoothSppSource(device, name, onConnected) }
                            showSources = false
                        },
                        onStop = { vm.stop(); showSources = false },
                        onDismiss = { showSources = false },
                    )
                }
                if (showSettings) {
                    SettingsDialog(
                        onDismiss = { showSettings = false },
                        hasImportedMap = hasImportedMap,
                        onlineMapEnabled = onlineMapEnabled,
                        onToggleOnlineMap = {
                            onlineMapEnabled = !onlineMapEnabled
                            OnlineMap.setEnabled(this@MainActivity, onlineMapEnabled)
                        },
                        onDownloadMap = {
                            showSettings = false
                            pickingMapArea = true
                        },
                        onImportMap = {
                            showSettings = false
                            importMap.launch(arrayOf("*/*"))
                        },
                        onRemoveImportedMap = {
                            OfflineMap.removeImported(this@MainActivity)
                            hasImportedMap = false
                            mapRevision++
                            showSettings = false
                        },
                        language = AppLanguage.current(this@MainActivity),
                        onLanguage = { lang ->
                            showSettings = false
                            AppLanguage.set(this@MainActivity, lang)
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        video.stop()
        tone?.release()
        super.onDestroy()
    }

    /** Nombre de archivo a partir de la búsqueda: sin espacios ni caracteres raros. */
    private fun fileStem(search: Search?): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val name = search?.name?.lowercase()?.replace(Regex("[^a-z0-9áéíóúñ]+"), "-")?.trim('-').orEmpty()
        return listOf("sar", name, stamp).filter { it.isNotEmpty() }.joinToString("-")
    }

    /** Comparte por WhatsApp, SMS, correo, etc. un texto que se entiende sin la app. */
    private fun shareAlert(alert: Alert, operator: GeoPoint?) {
        val pos = alert.packet.position ?: return
        val lat = String.format(Locale.ROOT, "%.6f", pos.lat)
        val lon = String.format(Locale.ROOT, "%.6f", pos.lon)
        val id = alert.id.toInt()
        val status = getString(if (alert.status == AlertStatus.CONFIRMED) R.string.share_status_confirmed else R.string.share_status_pending)
        val fromOperator = operator?.let {
            val b = Geo.bearingDegrees(it, pos)
            "\n" + getString(
                R.string.share_from_operator,
                formatDistance(Geo.distanceMeters(it, pos)), Geo.compassPoint(b, isEnglish()), b.roundToInt(),
            )
        } ?: ""
        val text = getString(R.string.share_title, id, status) + "\n" +
            getString(R.string.share_conf, (alert.packet.confidence * 100).roundToInt(), formatUtc(alert.packet.utc)) + "\n" +
            getString(R.string.share_coords_line, lat, lon) + fromOperator + "\n" +
            getString(R.string.share_map, "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon") + "\n" +
            "geo:$lat,$lon"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject, id))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_coords)))
    }

    private fun alertSummary(alert: Alert, operator: GeoPoint?): String {
        val conf = (alert.packet.confidence * 100).roundToInt()
        val pos = alert.packet.position
        val where = if (pos != null && operator != null) {
            " · ${formatDistance(Geo.distanceMeters(operator, pos))} ${Geo.compassPoint(Geo.bearingDegrees(operator, pos), isEnglish())}"
        } else ""
        return getString(R.string.toast_detection, alert.id.toInt(), conf) + where
    }

    private fun missingPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Android exige el permiso de cámara para leer el receptor de video USB (UVC).
        add(Manifest.permission.CAMERA)
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

/** Conexión con la estación tierra (Bluetooth) o la demo. */
@Composable
private fun ConnectionDialog(
    devices: List<Pair<BluetoothDevice, String>>,
    onDemo: () -> Unit,
    onDevice: (BluetoothDevice, String) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.ground_station), style = MaterialTheme.typography.labelLarge)
                if (devices.isEmpty()) {
                    Text(
                        stringResource(R.string.no_paired),
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
                    stringResource(R.string.demo_option),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDemo).padding(vertical = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.disconnect)) } },
    )
}

/** Ajustes: mapa (en línea, descargar, importar) e idioma. */
@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    hasImportedMap: Boolean,
    onlineMapEnabled: Boolean,
    onToggleOnlineMap: () -> Unit,
    onDownloadMap: () -> Unit,
    onImportMap: () -> Unit,
    onRemoveImportedMap: () -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.map_section), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onToggleOnlineMap).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.online_toggle))
                        Text(
                            stringResource(R.string.online_sub),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = onlineMapEnabled, onCheckedChange = { onToggleOnlineMap() })
                }
                Text(
                    stringResource(if (hasImportedMap) R.string.offline_imported else R.string.offline_bundled),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.download_zone),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDownloadMap).padding(vertical = 10.dp),
                )
                Text(
                    stringResource(R.string.import_map),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onImportMap).padding(vertical = 10.dp),
                )
                if (hasImportedMap) {
                    Text(
                        stringResource(R.string.back_to_bundled),
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onRemoveImportedMap).padding(vertical = 10.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        AppLanguage.SYSTEM to stringResource(R.string.lang_system),
                        AppLanguage.SPANISH to "Español",
                        AppLanguage.ENGLISH to "English",
                    ).forEach { (code, label) ->
                        FilterChip(selected = language == code, onClick = { if (language != code) onLanguage(code) }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Pantalla mientras se responden los permisos del primer arranque. */
@Composable
private fun StartupScreen() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1623)).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SAR-Response", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.startup_text),
                color = Color(0xFF9FB0C4), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
