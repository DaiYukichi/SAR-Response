package io.github.daiyukichi.sarresponse.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.daiyukichi.sarresponse.core.GeoPoint

/**
 * Posición del operador desde el GPS (o la red) del teléfono.
 * Se reevalúa con [key] para volver a intentarlo cuando se concede el permiso.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberOperatorLocation(key: Any? = null): State<GeoPoint?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<GeoPoint?>(null) }
    DisposableEffect(key) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(LocationManager::class.java)
        if (!granted || manager == null) return@DisposableEffect onDispose { }

        var best: Location? = null
        val listener = LocationListener { loc ->
            // El GPS manda sobre la red salvo que su dato sea viejo.
            val prev = best
            if (prev == null || loc.provider == LocationManager.GPS_PROVIDER ||
                prev.provider != LocationManager.GPS_PROVIDER || loc.time - prev.time > 10_000
            ) {
                best = loc
                state.value = GeoPoint(loc.latitude, loc.longitude)
            }
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { p ->
            manager.getLastKnownLocation(p)?.let(listener::onLocationChanged)
            manager.requestLocationUpdates(p, 2_000L, 1f, listener, context.mainLooper)
        }
        onDispose { manager.removeUpdates(listener) }
    }
    return state
}

/**
 * Hacia dónde apunta la parte de arriba del teléfono, en grados respecto del norte magnético.
 * null si el teléfono no tiene brújula.
 */
@Composable
fun rememberDeviceHeading(): State<Float?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val rot = FloatArray(9)
        val remapped = FloatArray(9)
        val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                val (x, y) = when (displayRotation(context)) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rot, x, y, remapped)
                SensorManager.getOrientation(remapped, orient)
                val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                // Suavizado simple para que la flecha no tiemble (cuidando el salto 359° → 0°).
                val prev = state.value
                state.value = if (prev == null) deg else {
                    val diff = ((deg - prev + 540) % 360) - 180
                    (prev + diff * 0.15f + 360) % 360
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }
