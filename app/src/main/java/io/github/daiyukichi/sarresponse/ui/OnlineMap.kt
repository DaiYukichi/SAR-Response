package io.github.daiyukichi.sarresponse.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.daiyukichi.sarresponse.core.map.MapExtractor
import java.time.LocalDate

/**
 * Mapa en línea: el mismo mapa base de Protomaps (mundo entero con detalle de calle) leído
 * directamente de internet, con el mismo estilo que el offline. Si no hay internet, o falla,
 * la app usa el mapa offline del teléfono.
 */
object OnlineMap {
    private const val PREFS = "mapa"
    private const val KEY_ENABLED = "en_linea"
    private const val KEY_URL = "url_base"
    private const val KEY_URL_DATE = "url_fecha"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * URL del mapa base diario más reciente; se recuerda por un día para no buscarla en cada arranque.
     * Hace red: llamar fuera del hilo principal. null si no se pudo encontrar.
     */
    fun baseUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        prefs.getString(KEY_URL, null)?.takeIf { prefs.getString(KEY_URL_DATE, null) == today }?.let { return it }
        val url = runCatching { MapExtractor.latestProtomapsBuild() }.getOrNull() ?: return null
        prefs.edit().putString(KEY_URL, url).putString(KEY_URL_DATE, today).apply()
        return url
    }
}

/** true mientras haya una conexión a internet validada (no solo Wi-Fi sin salida). */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(ConnectivityManager::class.java) }
    val state = remember {
        mutableStateOf(
            cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(network: Network) {
                state.value = false
            }
        }
        cm?.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return state
}
