package io.github.daiyukichi.sarresponse.ui

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Mapa base 100 % offline: un archivo .pmtiles (vectorial, OpenStreetMap vía Protomaps) en el
 * almacenamiento interno, con letras e íconos dentro de la app. Nada se descarga en campo.
 *
 * Orden de preferencia:
 *  1. Un mapa descargado desde la app o importado por el usuario (otra zona).
 *  2. El mapa que viene dentro de la app (assets/mapa/region.pmtiles: mundo general + Chiriquí).
 *
 * Cada mapa descargado/importado lleva un nombre único: si se reemplazara el mismo archivo,
 * MapLibre seguiría usando el anterior porque la URL del estilo no cambia.
 *
 * MapLibre no lee .pmtiles directamente desde los assets, por eso se copia una vez a disco.
 */
object OfflineMap {
    private const val BUNDLED_ASSET = "mapa/region.pmtiles"
    private const val STYLE_ASSET = "mapa/estilo-oscuro.json"

    private fun dir(context: Context) = File(context.filesDir, "mapas").apply { mkdirs() }
    private fun importedDir(context: Context) = File(dir(context), "propios").apply { mkdirs() }
    private fun bundled(context: Context) = File(dir(context), "region.pmtiles")

    private fun imported(context: Context): File? =
        importedDir(context).listFiles { f -> f.extension == "pmtiles" && f.length() > 0 }?.maxByOrNull { it.lastModified() }

    /** Archivo nuevo para descargar o importar un mapa; queda activo al llamar a [commit]. */
    fun newTarget(context: Context): File = File(importedDir(context), "mapa-${System.currentTimeMillis()}.pmtiles")

    /** Deja [file] como el mapa propio activo y borra los anteriores. */
    fun commit(context: Context, file: File) {
        importedDir(context).listFiles()?.filter { it != file }?.forEach { it.delete() }
    }

    /** Archivo de mapa a usar, o null si no hay ninguno. Hace E/S: llamar fuera del hilo principal. */
    fun file(context: Context): File? {
        imported(context)?.let { return it }
        val target = bundled(context)
        val assetLength = runCatching {
            context.assets.openFd(BUNDLED_ASSET).use { it.length }
        }.getOrNull() ?: return target.takeIf { it.length() > 0 }
        if (target.length() != assetLength) {
            val tmp = File(target.path + ".tmp")
            context.assets.open(BUNDLED_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
        return target
    }

    /** Copia un .pmtiles elegido por el usuario (p. ej. desde Descargas o una memoria USB). */
    fun import(context: Context, uri: Uri) {
        val target = newTarget(context)
        val tmp = File(target.path + ".tmp")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error("No se pudo leer el archivo")
        // Un .pmtiles válido empieza con la firma "PMTiles".
        val magic = tmp.inputStream().use { s -> ByteArray(7).also { s.read(it) } }
        if (String(magic, Charsets.US_ASCII) != "PMTiles") {
            tmp.delete()
            error("El archivo no es un mapa .pmtiles")
        }
        tmp.renameTo(target)
        commit(context, target)
    }

    fun removeImported(context: Context) {
        importedDir(context).listFiles()?.forEach { it.delete() }
    }

    fun hasImported(context: Context) = imported(context) != null

    /** Estilo MapLibre: el mapa base si hay archivo; si no, solo un fondo liso. */
    fun styleJson(context: Context, map: File?): String =
        if (map == null) EMPTY_STYLE else styleFor(context, "pmtiles://file://${map.absolutePath}")

    /** Mismo estilo (oscuro, en español) para cualquier origen PMTiles: archivo local o URL en línea. */
    fun styleFor(context: Context, pmtilesUrl: String): String {
        val template = context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
        return template.replace("{{PMTILES_URL}}", pmtilesUrl)
    }

    private const val EMPTY_STYLE =
        """{"version":8,"sources":{},"layers":[{"id":"fondo","type":"background","paint":{"background-color":"#0b1118"}}]}"""
}
