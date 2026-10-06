package io.github.daiyukichi.sarresponse.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.daiyukichi.sarresponse.video.UsbVideo
import io.github.daiyukichi.sarresponse.video.VideoStatus

/**
 * Video analógico 5.8 GHz del VTX del payload, recibido por el receptor UVC conectado por OTG.
 * Se dibuja en un TextureView (respeta recortes y bordes redondeados en la miniatura).
 */
@Composable
fun VideoPane(video: UsbVideo, compact: Boolean, modifier: Modifier = Modifier) {
    val status = video.status
    Box(modifier.background(Color(0xFF101418)), contentAlignment = Alignment.Center) {
        val ratio = (status as? VideoStatus.Streaming)?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        // La vista existe siempre para no perder la superficie al cambiar de estado.
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = video.setSurface(Surface(st))
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            video.setSurface(null)
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.aspectRatio(ratio),
        )

        if (status !is VideoStatus.Streaming) {
            Text(
                if (compact) "VIDEO" else when (status) {
                    VideoStatus.NoDevice -> "Video 5.8 GHz\nConecta el receptor al teléfono por USB (OTG)."
                    VideoStatus.NeedCameraPermission -> "Video 5.8 GHz\nFalta el permiso de cámara: Android lo exige para leer el receptor USB."
                    VideoStatus.WaitingUsbPermission -> "Video 5.8 GHz\nAcepta el permiso USB que muestra Android."
                    VideoStatus.Connecting -> "Video 5.8 GHz\nConectando con el receptor…"
                    is VideoStatus.Failed -> "Video 5.8 GHz\n${status.message}"
                    is VideoStatus.Streaming -> ""
                },
                color = Color(0xFF8A96A3),
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        } else if (compact) {
            Text(
                "VIDEO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
