package io.github.daiyukichi.sarresponse.video

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.VideoCapture
import java.io.File

sealed interface VideoStatus {
    data object NoDevice : VideoStatus
    data object NeedCameraPermission : VideoStatus
    data object WaitingUsbPermission : VideoStatus
    data object Connecting : VideoStatus
    data class Streaming(val width: Int, val height: Int) : VideoStatus
    data object UsbDenied : VideoStatus
    /** [message] = detalle técnico de la librería, si lo hay. */
    data class Failed(val message: String?) : VideoStatus
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

    /** Momento en que empezó la grabación en curso; null si no se está grabando. */
    var recordingSince by mutableStateOf<Long?>(null)
        private set

    /** Aviso al terminar una grabación: true = guardada en [RECORDINGS_FOLDER], false = falló. */
    var onRecordingFinished: ((saved: Boolean, detail: String?) -> Unit)? = null

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
        val h = helper
        if (recordingSince != null) {
            // Dar tiempo a que el grabador cierre el MP4 antes de soltar el receptor (si no, queda ilegible).
            stopRecording()
            recordingSince = null
            main.postDelayed({ h?.release() }, 1_500)
        } else {
            h?.release()
        }
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

    /**
     * Graba el video tal como llega del receptor (MP4 H.264, sin audio) en Movies/SAR del teléfono.
     * [name] = nombre del archivo sin extensión. Devuelve false si no hay video para grabar.
     */
    fun startRecording(name: String): Boolean {
        val h = helper ?: return false
        if (status !is VideoStatus.Streaming || recordingSince != null) return false
        // Sin audio: el receptor UVC no lo trae y grabaría el micrófono del teléfono (pide otro permiso).
        h.videoCaptureConfig = h.videoCaptureConfig.setAudioCaptureEnable(false).setBitRate(BIT_RATE)
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$RECORDINGS_FOLDER")
            }
            VideoCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values).build()
        } else {
            // Android 8–9: carpeta propia de la app (no pide permiso de almacenamiento).
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), RECORDINGS_FOLDER).apply { mkdirs() }
            VideoCapture.OutputFileOptions.Builder(File(dir, "$name.mp4")).build()
        }
        recordingSince = System.currentTimeMillis()
        h.startRecording(options, object : VideoCapture.OnVideoCaptureCallback {
            override fun onStart() = Unit

            override fun onVideoSaved(results: VideoCapture.OutputFileResults) {
                main.post {
                    recordingSince = null
                    onRecordingFinished?.invoke(true, null)
                }
            }

            override fun onError(code: Int, message: String, cause: Throwable?) {
                Log.e(TAG, "Error al grabar ($code): $message", cause)
                main.post {
                    recordingSince = null
                    onRecordingFinished?.invoke(false, message)
                }
            }
        })
        return true
    }

    /** Termina la grabación; el archivo queda listo cuando llega [onRecordingFinished]. */
    fun stopRecording() {
        if (recordingSince == null) return
        runCatching { helper?.stopRecording() }.onFailure { Log.e(TAG, "stopRecording", it) }
    }

    val isRecording: Boolean get() = recordingSince != null

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
                // Si se corta el receptor, se cierra el archivo con lo grabado hasta ahí.
                stopRecording()
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
                stopRecording()
                selected = null
                surfaceAdded = false
                status = VideoStatus.NoDevice
            }
        }

        override fun onCancel(device: UsbDevice) {
            main.post {
                selected = null
                status = VideoStatus.UsbDenied
            }
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            Log.e(TAG, "Error de video", e)
            main.post { status = VideoStatus.Failed(e?.message) }
        }
    }

    companion object {
        /** Subcarpeta de Movies donde quedan las grabaciones. */
        const val RECORDINGS_FOLDER = "SAR"
        private const val TAG = "SAR-Video"
        private const val WATCHDOG_MS = 6_000L
        /**
         * 2 Mbit/s ≈ 0,9 GB por hora. Alcanza: lo que llega por el VTX es una miniatura de 128×120
         * del payload, ampliada por el receptor a 1080p; más bitrate no agrega detalle.
         */
        private const val BIT_RATE = 2_000_000
    }
}
