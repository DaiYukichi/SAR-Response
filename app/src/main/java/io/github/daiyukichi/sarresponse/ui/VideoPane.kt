package io.github.daiyukichi.sarresponse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/**
 * Video analógico 5.8 GHz del VTX, recibido por un receptor UVC conectado por OTG.
 * Por ahora es un marcador de posición; la integración UVC es el siguiente paso.
 */
@Composable
fun VideoPane(compact: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF101418)), contentAlignment = Alignment.Center) {
        Text(
            if (compact) "VIDEO" else "Video 5.8 GHz\nConecta el receptor por OTG\n(integración UVC pendiente)",
            color = Color(0xFF8A96A3),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
