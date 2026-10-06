package io.github.daiyukichi.sarresponse.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper

sealed interface VideoStatus {
    data object NoDevice : VideoStatus
    data object NeedCameraPermission : VideoStatus
    data object WaitingUsbPermission : VideoStatus
    data object Connecting : VideoStatus
    data class Streaming(val width: Int, val height: Int) : VideoStatus
    data class Failed(val message: String) : VideoStatus
}

/**
 * Video del receptor 5.8 GHz conectado por USB (OTG). El receptor es una cámara UVC estándar;
 * se lee con UVCAndroid (libusb/libuvc), sin depender de que el teléfono soporte cámaras USB.
 *
 * Flujo: conectar → permiso USB (Android pregunta) → abrir → mostrar en [surface].
 */
class UsbVideo(private val context: Context) {
    var status by mutableStateOf<VideoStatus>(VideoStatus.NoDevice)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var helper: CameraHelper? = null
    private var surface: Surface? = null
    private var surfaceAdded = false
    /** Receptor ya pedido: Android avisa la conexión por varias vías y pedirlo dos veces lo trababa. */
    private var selected: UsbDevice? = null

    fun start() {
        if (helper != null) return
        helper = CameraHelper().apply { setStateCallback(callback) }
        // Si el receptor ya estaba conectado antes de abrir la app, no llega onAttach: buscarlo.
        main.postDelayed({ helper?.deviceList?.firstOrNull()?.let { select(it) } }, 800)
    }

    /** Si quedó esperando (permiso que no apareció, apertura trabada), vuelve a intentarlo una vez. */
    private val watchdog = Runnable {
        if (status == VideoStatus.WaitingUsbPermission || status == VideoStatus.Connecting) {
            Log.w(TAG, "Sin video después de ${WATCHDOG_MS / 1000} s ($status): reintentando")
            retry()
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        helper?.release()
        helper = null
        selected = null
        surfaceAdded = false
        status = VideoStatus.NoDevice
    }

    /** Superficie donde se dibuja el video (null cuando la vista desaparece). */
    fun setSurface(s: Surface?) {
        val h = helper
        surface?.takeIf { surfaceAdded }?.let { h?.removeSurface(it) }
        surfaceAdded = false
        surface = s
        if (s != null && status is VideoStatus.Streaming && h != null) {
            h.addSurface(s, false)
            surfaceAdded = true
        }
    }

    /** Reintentar: tras conceder el permiso de cámara, o si el video quedó trabado. */
    fun retry() {
        val h = helper ?: return
        if (status is VideoStatus.Streaming) return
        selected = null
        h.closeCamera()
        h.deviceList?.firstOrNull()?.let { select(it) }
    }

    private fun select(device: UsbDevice) {
        if (selected?.deviceName == device.deviceName) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Sin este permiso Android no deja abrir cámaras USB.
            status = VideoStatus.NeedCameraPermission
            return
        }
        selected = device
        status = VideoStatus.WaitingUsbPermission
        helper?.selectDevice(device)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            main.post { select(device) }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            main.post {
                status = VideoStatus.Connecting
                helper?.openCamera()
            }
        }

        override fun onCameraOpen(device: UsbDevice) {
            main.post {
                val h = helper ?: return@post
                // Se usa la resolución con la que abre el receptor. Cambiarla después de abrir hizo que
                // el receptor real (chip MacroSilicon) rechazara la negociación y el video quedara negro.
                h.startPreview()
                surface?.let {
                    if (!surfaceAdded) h.addSurface(it, false)
                    surfaceAdded = true
                }
                val size = h.previewSize
                Log.i(TAG, "Video abierto: ${size?.width}x${size?.height}; disponibles: ${h.supportedSizeList?.map { "${it.width}x${it.height}" }}")
                status = VideoStatus.Streaming(size?.width ?: 1920, size?.height ?: 1080)
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            main.post {
                surface?.takeIf { surfaceAdded }?.let { helper?.removeSurface(it) }
                surfaceAdded = false
                if (status is VideoStatus.Streaming) status = VideoStatus.Connecting
            }
        }

        override fun onDeviceClose(device: UsbDevice) {
            main.post {
                selected = null
                status = VideoStatus.NoDevice
            }
        }

        override fun onDetach(device: UsbDevice) {
            main.post {
                selected = null
                surfaceAdded = false
                status = VideoStatus.NoDevice
            }
        }

        override fun onCancel(device: UsbDevice) {
            main.post {
                selected = null
                status = VideoStatus.Failed("Permiso USB denegado. Desconecta y vuelve a conectar el receptor.")
            }
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            Log.e(TAG, "Error de video", e)
            main.post { status = VideoStatus.Failed(e?.message ?: "No se pudo abrir el receptor de video") }
        }
    }

    private companion object {
        const val TAG = "SAR-Video"
        const val WATCHDOG_MS = 6_000L
    }
}
