package io.github.daiyukichi.sarresponse.core

import kotlin.math.tan

/**
 * Geometría de la cámara del payload apuntando hacia abajo (nadir).
 *
 * El modelo recibe la imagen reducida a [MODEL_INPUT_PX] de ancho, así que lo que importa para
 * detectar es cuántos píxeles ocupa una persona en ESA imagen, no en la del sensor.
 */
object CameraGeometry {
    const val MODEL_INPUT_PX = 640

    /**
     * FOV horizontal por defecto: cámara Raspberry Pi v1.3 (OV5647, módulo "P5V04A SUNNY") en modo
     * 1920×1080, que recorta el centro del sensor: 53,5° del sensor completo × 1920/2592 ≈ 41°.
     * En un modo 4:3 con el sensor completo sería 53,5°. Confirmar midiendo contra una pared.
     */
    const val DEFAULT_HFOV_DEGREES = 41.0

    /** Por debajo de esto la detección cae: el modelo se entrenó con personas de ~13×16 px. */
    const val MIN_PERSON_PX = 12.0

    /** Ancho visto de una persona acostada (~1,7 m) y de una de pie vista desde arriba (~0,5 m). */
    const val PERSON_LYING_M = 1.7
    const val PERSON_FROM_ABOVE_M = 0.5

    /** Ancho de terreno que ve la cámara a [altitudeMeters]: 2 · h · tan(FOV/2). */
    fun swathMeters(altitudeMeters: Double, hfovDegrees: Double): Double =
        2 * altitudeMeters * tan(Math.toRadians(hfovDegrees) / 2)

    /** Píxeles que ocupa un objeto de [sizeMeters] en la imagen que ve el modelo. */
    fun pixelsOnModel(sizeMeters: Double, altitudeMeters: Double, hfovDegrees: Double): Double =
        sizeMeters * MODEL_INPUT_PX / swathMeters(altitudeMeters, hfovDegrees)

    /** Altura máxima a la que un objeto de [sizeMeters] ocupa al menos [minPx] píxeles. */
    fun maxAltitudeFor(sizeMeters: Double, hfovDegrees: Double, minPx: Double = MIN_PERSON_PX): Double =
        sizeMeters * MODEL_INPUT_PX / (minPx * 2 * tan(Math.toRadians(hfovDegrees) / 2))
}
