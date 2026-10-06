package io.github.daiyukichi.sarresponse.core

import kotlinx.coroutines.flow.Flow

/**
 * Origen de líneas de texto con paquetes $SAR/$SAH.
 * Implementaciones: Bluetooth SPP (estación tierra con ESP32), replay de demo,
 * y a futuro serie por USB (YP-05) en una app desktop.
 *
 * El Flow termina o lanza excepción cuando el enlace se cae; quien lo recolecta decide si reconecta.
 */
interface LinkSource {
    val label: String
    fun lines(): Flow<String>
}
