package io.github.daiyukichi.sarresponse.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.daiyukichi.sarresponse.R
import io.github.daiyukichi.sarresponse.video.UsbVideo
import io.github.daiyukichi.sarresponse.video.VideoStatus
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Video analógico 5.8 GHz del VTX del payload, recibido por el receptor UVC conectado por OTG.
 * Se dibuja en un TextureView (respeta recortes, bordes redondeados y rotación).
 *
 * En la vista grande hay dos botones: girar el video de a 90° (girado aprovecha el alto del
 * teléfono en vertical) y pantalla completa (oculta encabezado y lista). Con video, ● REC graba
 * lo que llega a un MP4 en Movies/SAR.
 */
@Composable
fun VideoPane(
    video: UsbVideo,
    compact: Boolean,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
) {
    val status = video.status
    val recordingSince = video.recordingSince
    var rotation by rememberSaveable { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.background(Color(0xFF101418)), contentAlignment = Alignment.Center) {
        val ratio = (status as? VideoStatus.Streaming)?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        // Tamaño visible que entra en el panel; si está girado 90/270, el video "acostado" se ve
        // con la proporción invertida, así que la vista se dimensiona con ancho y alto cruzados.
        val sideways = !compact && rotation % 180 != 0
        val (fitW, fitH) = fit(if (sideways) 1f / ratio else ratio, maxWidth, maxHeight)
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
            modifier = Modifier
                .requiredSize(if (sideways) fitH else fitW, if (sideways) fitW else fitH)
                .graphicsLayer { rotationZ = if (compact) 0f else rotation.toFloat() },
        )

        if (status !is VideoStatus.Streaming) {
            val canRetry = status is VideoStatus.WaitingUsbPermission || status is VideoStatus.Connecting ||
                status is VideoStatus.Failed || status is VideoStatus.UsbDenied
            Text(
                if (compact) stringResource(R.string.video_label) else when (status) {
                    VideoStatus.NoDevice -> stringResource(R.string.video_no_device)
                    VideoStatus.NeedCameraPermission -> stringResource(R.string.video_need_camera)
                    VideoStatus.WaitingUsbPermission -> stringResource(R.string.video_wait_usb)
                    VideoStatus.Connecting -> stringResource(R.string.video_connecting)
                    VideoStatus.UsbDenied -> stringResource(R.string.video_failed, stringResource(R.string.video_usb_denied))
                    is VideoStatus.Failed -> stringResource(R.string.video_failed, status.message ?: stringResource(R.string.video_open_failed))
                    is VideoStatus.Streaming -> ""
                },
                color = Color(0xFF8A96A3),
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (!compact && canRetry) Modifier.clickable { video.retry() } else Modifier)
                    .padding(16.dp),
            )
        } else if (compact) {
            Text(
                if (recordingSince != null) "● REC" else stringResource(R.string.video_label),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (recordingSince != null) RecRed else Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (!compact) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status is VideoStatus.Streaming || recordingSince != null) {
                    if (recordingSince != null) {
                        // Contador de la grabación en curso.
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(recordingSince) {
                            while (true) {
                                now = System.currentTimeMillis()
                                delay(500)
                            }
                        }
                        VideoButton("■ ${formatClock(now - recordingSince)}", background = RecRed, onClick = onToggleRecording)
                    } else {
                        VideoButton("● REC", onClick = onToggleRecording)
                    }
                }
                VideoButton("⟳ ${rotation}°") { rotation = (rotation + 90) % 360 }
                VideoButton(stringResource(if (fullscreen) R.string.video_exit else R.string.video_fullscreen), onClick = onToggleFullscreen)
            }
        }
    }
}

@Composable
private fun VideoButton(text: String, background: Color = Color.Black.copy(alpha = 0.55f), onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val RecRed = Color(0xFFDC2626)

/** mm:ss (o h:mm:ss) desde que empezó la grabación. */
private fun formatClock(millis: Long): String {
    val t = (millis / 1000).coerceAtLeast(0)
    return if (t >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    else String.format(Locale.ROOT, "%02d:%02d", t / 60, t % 60)
}

/** Mayor rectángulo con proporción [ratio] (ancho/alto) que entra en [w]×[h]. */
private fun fit(ratio: Float, w: Dp, h: Dp): Pair<Dp, Dp> =
    if (w / h > ratio) (h * ratio) to h else w to (w / ratio)
