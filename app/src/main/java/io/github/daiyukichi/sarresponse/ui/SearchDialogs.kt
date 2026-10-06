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
import androidx.compose.foundation.text.KeyboardOptions
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
import io.github.daiyukichi.sarresponse.core.Search
import io.github.daiyukichi.sarresponse.data.SearchSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** Datos de una búsqueda nueva antes de (opcionalmente) dibujar su área. */
@Composable
fun NewSearchDialog(
    suggestedName: String,
    onDrawArea: (name: String, swathMeters: Double) -> Unit,
    onCreateWithoutArea: (name: String, swathMeters: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var swath by remember { mutableStateOf(Search.DEFAULT_SWATH_METERS.toInt().toString()) }
    val swathValue = swath.toDoubleOrNull()?.takeIf { it in 5.0..500.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva búsqueda") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = swath,
                    onValueChange = { swath = it.filter(Char::isDigit).take(3) },
                    label = { Text("Ancho de barrido (m)") },
                    singleLine = true,
                    isError = swathValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Ancho de terreno que ve la cámara en cada pasada; depende de la altura de vuelo. " +
                        "Se usa para estimar el % del área cubierta.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Después podrás dibujar el área tocando sus esquinas en el mapa (funciona sin internet).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = swathValue != null, onClick = { onDrawArea(name, swathValue!!) }) {
                Text("Dibujar área", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                TextButton(enabled = swathValue != null, onClick = { onCreateWithoutArea(name, swathValue!!) }) {
                    Text("Sin área")
                }
            }
        },
    )
}
