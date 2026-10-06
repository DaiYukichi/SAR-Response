package io.github.daiyukichi.sarresponse.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraGeometryTest {
    @Test
    fun swathAndPixels() {
        // Con 90° de FOV el ancho visto es el doble de la altura.
        assertEquals(80.0, CameraGeometry.swathMeters(40.0, 90.0), 1e-6)
        // 1,7 m en 80 m repartidos en 640 px = 13,6 px.
        assertEquals(13.6, CameraGeometry.pixelsOnModel(1.7, 40.0, 90.0), 1e-6)
        // Y la altura máxima para 13,6 px es justo 40 m.
        assertEquals(40.0, CameraGeometry.maxAltitudeFor(1.7, 90.0, minPx = 13.6), 1e-6)
    }
}
