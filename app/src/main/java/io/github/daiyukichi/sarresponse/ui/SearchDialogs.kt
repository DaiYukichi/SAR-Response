package io.github.daiyukichi.sarresponse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.daiyukichi.sarresponse.core.CameraGeometry
import io.github.daiyukichi.sarresponse.core.Search
import io.github.daiyukichi.sarresponse.data.SearchSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val DateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT)

/** Lista de búsquedas: la activa con sus acciones, y las anteriores para abrir o borrar. */
@Composable
fun SearchesDialog(
    active: Search?,
    searches: List<SearchSummary>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onFinish: () -> Unit,
    onDelete: (String) -> Unit,
    onExportGpx: () -> Unit,
    onExportCsv: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<SearchSummary?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Búsquedas") },
        text = {
            Column {
                if (active != null) {
                    Text("En curso", style = MaterialTheme.typography.labelLarge)
                    Text(active.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "Creada ${DateFmt.format(Date(active.createdAtMillis))}" +
                            (active.area?.let { " · área ${formatArea(it.areaSquareMeters)}" } ?: " · sin área"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = onExportGpx) { Text("Exportar GPX") }
                        TextButton(onClick = onExportCsv) { Text("Exportar CSV") }
                    }
                    TextButton(onClick = onFinish) { Text("Terminar esta búsqueda") }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                TextButton(onClick = onNew) { Text("＋ Nueva búsqueda", fontWeight = FontWeight.Bold) }
                val others = searches.filter { it.search.id != active?.id }
                if (others.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Anteriores (toca para abrir)", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(others, key = { it.search.id }) { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(s.search.id) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.search.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        "${DateFmt.format(Date(s.search.createdAtMillis))} · ${s.detections} detecciones, " +
                                            "${s.confirmed} confirmadas" + if (s.search.finishedAtMillis != null) " · terminada" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { confirmDelete = s }) { Text("Borrar", color = Color(0xFFF87171)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("¿Borrar \"${s.search.name}\"?") },
            text = { Text("Se borran su recorrido, sus ${s.detections} detecciones y las decisiones. No se puede deshacer. Si la necesitas como evidencia, exporta antes el GPX o el CSV.") },
            confirmButton = {
                TextButton(onClick = { onDelete(s.search.id); confirmDelete = null }) { Text("Borrar", color = Color(0xFFF87171)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * Datos de una búsqueda nueva antes de (opcionalmente) dibujar su área.
 * Con la altura de vuelo y el FOV de la cámara se calcula el ancho de barrido y cuántos píxeles
 * ocupa una persona en la imagen que ve el modelo, con un aviso si es demasiado poco.
 */
@Composable
fun NewSearchDialog(
    suggestedName: String,
    onDrawArea: (name: String, altitudeMeters: Double, hfovDegrees: Double) -> Unit,
    onCreateWithoutArea: (name: String, altitudeMeters: Double, hfovDegrees: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var altitude by remember { mutableStateOf(Search.DEFAULT_ALTITUDE_METERS.toInt().toString()) }
    var fov by remember { mutableStateOf(CameraGeometry.DEFAULT_HFOV_DEGREES.toInt().toString()) }
    val h = altitude.toDoubleOrNull()?.takeIf { it in 2.0..300.0 }
    val f = fov.toDoubleOrNull()?.takeIf { it in 10.0..170.0 }
    val valid = h != null && f != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva búsqueda") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
                Spacer(Modifier.padding(4.dp))
                Row {
                    OutlinedTextField(
                        value = altitude,
                        onValueChange = { altitude = it.filter(Char::isDigit).take(3) },
                        label = { Text("Altura (m)") },
                        singleLine = true,
                        isError = h == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = fov,
                        onValueChange = { fov = it.filter(Char::isDigit).take(3) },
                        label = { Text("FOV cámara (°)") },
                        singleLine = true,
                        isError = f == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (h != null && f != null) {
                    val swath = CameraGeometry.swathMeters(h, f)
                    val lying = CameraGeometry.pixelsOnModel(CameraGeometry.PERSON_LYING_M, h, f)
                    val above = CameraGeometry.pixelsOnModel(CameraGeometry.PERSON_FROM_ABOVE_M, h, f)
                    val min = CameraGeometry.MIN_PERSON_PX
                    Text(
                        "Barrido: ${swath.roundToInt()} m de ancho por pasada",
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Persona acostada ≈ ${lying.roundToInt()} px ${if (lying >= min) "✓" else "⚠"} · " +
                            "de pie vista desde arriba ≈ ${above.roundToInt()} px ${if (above >= min) "✓" else "⚠"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (lying < min) {
                        val maxH = CameraGeometry.maxAltitudeFor(CameraGeometry.PERSON_LYING_M, f)
                        Text(
                            "Demasiado alto: una persona ocuparía muy pocos píxeles para que el modelo la detecte bien. " +
                                "Baja a ${maxH.toInt()} m o menos.",
                            color = Color(0xFFF87171), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    "FOV por defecto: 41°, cámara Pi v1.3 (OV5647) en modo 1080p, que recorta el sensor " +
                        "(con el sensor completo en 4:3 serían 53,5°). Para confirmarlo: cámara a 2 m de una " +
                        "pared, mide el ancho W (en m) que se ve completo y usa FOV = 2·atan(W/4).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Después podrás dibujar el área tocando sus esquinas en el mapa (funciona sin internet).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onDrawArea(name, h!!, f!!) }) {
                Text("Dibujar área", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                TextButton(enabled = valid, onClick = { onCreateWithoutArea(name, h!!, f!!) }) {
                    Text("Sin área")
                }
            }
        },
    )
}
