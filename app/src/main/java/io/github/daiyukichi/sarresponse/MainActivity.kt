package io.github.daiyukichi.sarresponse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daiyukichi.sarresponse.link.BluetoothSppSource
import io.github.daiyukichi.sarresponse.ui.DashboardScreen
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
                var showSources by remember { mutableStateOf(false) }

                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { showSources = true }

                val exportGpx = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/gpx+xml"),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    contentResolver.openOutputStream(uri)?.use { it.write(vm.exportGpx().toByteArray()) }
                    Toast.makeText(this, "GPX exportado", Toast.LENGTH_SHORT).show()
                }

                LaunchedEffect(Unit) {
                    vm.newAlerts.collect { notifyNewAlert() }
                }

                DashboardScreen(
                    mission = mission,
                    link = link,
                    onSourceClick = {
                        val missing = requiredPermissions().filter {
                            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) showSources = true else permissions.launch(missing.toTypedArray())
                    },
                    onDecide = vm::decide,
                    onExportGpx = {
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                        exportGpx.launch("sar-$stamp.gpx")
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
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = onStop) { Text("Desconectar") } },
    )
}
