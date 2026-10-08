package io.github.daiyukichi.sarresponse.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import io.github.daiyukichi.sarresponse.core.LinkSource
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Lee líneas de la estación tierra (ESP32 con BluetoothSerial) por RFCOMM/SPP.
 * La lectura bloqueante va en su propio hilo; cancelar el Flow cierra el socket y lo desbloquea.
 * Requiere BLUETOOTH_CONNECT concedido (lo verifica quien lo crea).
 */
class BluetoothSppSource(
    private val device: BluetoothDevice,
    override val label: String,
    private val onConnected: () -> Unit,
) : LinkSource {

    @SuppressLint("MissingPermission")
    override fun lines(): Flow<String> = callbackFlow {
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        thread(name = "bt-spp", isDaemon = true) {
            try {
                socket.connect()
                onConnected()
                socket.inputStream.bufferedReader(Charsets.US_ASCII).forEachLine { trySendBlocking(it) }
                close(IOException("Ground station closed the connection"))
            } catch (e: IOException) {
                close(e)
            }
        }
        awaitClose { runCatching { socket.close() } }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
