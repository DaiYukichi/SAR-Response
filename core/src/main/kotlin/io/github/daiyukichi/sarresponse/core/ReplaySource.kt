package io.github.daiyukichi.sarresponse.core

import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Misión simulada para demos sin dron: barrido en "cortadora de césped" alrededor de [center],
 * con latidos y algunas detecciones. Los tiempos van acelerados por [speedup].
 *
 * Las coordenadas por defecto son un punto genérico, no un lugar de pruebas real.
 */
class ReplaySource(
    val center: GeoPoint = GeoPoint(8.4333, -82.4333),
    private val speedup: Double = 10.0,
    private val unit: String = "A1",
) : LinkSource {
    override val label = "Demo (simulado)"

    private val legs = 6
    private val legLength = 300.0
    private val spacing = 50.0

    /** Punto de despegue simulado (donde está el operador), al suroeste del área barrida. */
    val operatorPosition: GeoPoint =
        Geo.offset(center, -legLength / 2 - 60, -(legs - 1) * spacing / 2 - 40)

    override fun lines(): Flow<String> = flow {
        var seq = 0
        var t = 12 * 3600 // 12:00:00 UTC simulado
        val stepMeters = 25.0
        val start = Geo.offset(center, -legLength / 2, -(legs - 1) * spacing / 2)
        // Índices de paso donde "aparece" una persona, con su confianza.
        val detections = mapOf(9 to 0.87, 31 to 0.62, 47 to 0.91, 70 to 0.55)

        emit(PacketCodec.format(Packet.Heartbeat(unit, seq++, null, 3, utc(t))))
        var step = 0
        for (leg in 0 until legs) {
            val steps = (legLength / stepMeters).toInt()
            for (i in 0..steps) {
                val north = if (leg % 2 == 0) i * stepMeters else legLength - i * stepMeters
                val pos = Geo.offset(start, north, leg * spacing)
                val conf = detections[step]
                if (conf != null) {
                    // La persona está a unos metros del dron, no justo debajo.
                    val person = Geo.offset(pos, 6.0, -4.0)
                    emit(PacketCodec.format(Packet.Alert(unit, seq++, person, conf, utc(t))))
                }
                if (step % 3 == 0) {
                    emit(PacketCodec.format(Packet.Heartbeat(unit, seq++, pos, 9, utc(t))))
                }
                if (step == 55) seq++ // simula un paquete perdido en el aire
                step++
                t += 10
                delay((10_000 / speedup).toLong())
            }
        }
    }

    private fun utc(seconds: Int): String {
        val s = seconds % 86_400
        return "%02d%02d%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    }
}
