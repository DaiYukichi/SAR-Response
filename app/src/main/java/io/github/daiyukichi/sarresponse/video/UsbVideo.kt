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
import com.serenegiant.usb.Size

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
 * Flujo: conectar → permiso USB (Android pregunta) → abrir → elegir 1280×720 → mostrar en [surface].
 */
class UsbVideo(private val context: Context) {
    var status by mutableStateOf<VideoStatus>(VideoStatus.NoDevice)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var helper: CameraHelper? = null
    private var surface: Surface? = null
    private var surfaceAdded = false

    fun start() {
        if (helper != null) return
        helper = CameraHelper().apply { setStateCallback(callback) }
        // Si el receptor ya estaba conectado antes de abrir la app, no llega onAttach: buscarlo.
        main.postDelayed({ helper?.deviceList?.firstOrNull()?.let { select(it) } }, 800)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        helper?.release()
        helper = null
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

    /** Reintentar después de conceder el permiso de cámara. */
    fun retry() {
        helper?.deviceList?.firstOrNull()?.let { select(it) }
    }

    private fun select(device: UsbDevice) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Sin este permiso Android no deja abrir cámaras USB.
            status = VideoStatus.NeedCameraPermission
            return
        }
        status = VideoStatus.WaitingUsbPermission
        helper?.selectDevice(device)
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
                h.startPreview()
                // El receptor entrega 720p; si la librería abrió otra resolución, cambiar a la mejor.
                val best = pickSize(h.supportedSizeList.orEmpty())
                val current = h.previewSize
                if (best != null && (current == null || current.width != best.width || current.height != best.height)) {
                    h.setPreviewSize(best)
                }
                surface?.let {
                    h.addSurface(it, false)
                    surfaceAdded = true
                }
                val size = h.previewSize ?: best
                Log.i(TAG, "Video abierto: ${size?.width}x${size?.height}; disponibles: ${h.supportedSizeList?.map { "${it.width}x${it.height}" }}")
                status = VideoStatus.Streaming(size?.width ?: 1280, size?.height ?: 720)
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
            main.post { status = VideoStatus.NoDevice }
        }

        override fun onDetach(device: UsbDevice) {
            main.post {
                surfaceAdded = false
                status = VideoStatus.NoDevice
            }
        }

        override fun onCancel(device: UsbDevice) {
            main.post {
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

        /** 1280×720 si está; si no, la mayor que no pase de 1080p. */
        fun pickSize(sizes: List<Size>): Size? =
            sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height }
    }
}
