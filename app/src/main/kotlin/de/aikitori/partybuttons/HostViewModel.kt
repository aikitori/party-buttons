package de.aikitori.partybuttons

import android.app.Application
import android.content.res.AssetManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.aikitori.partybuttons.core.PlayerView
import de.aikitori.partybuttons.server.PartyServer
import de.aikitori.partybuttons.server.StaticFiles
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HostState {
    data object Idle : HostState
    data object Starting : HostState
    data class Failed(val message: String) : HostState

    data class Running(
        val roomCode: String,
        /** Adressen, unter denen die Mitspielenden die Seite öffnen. Leer ohne WLAN. */
        val urls: List<String>,
        val players: List<PlayerView>,
    ) : HostState
}

/**
 * Hält den Webserver, solange die App offen ist (übersteht Drehen und Sprachwechsel).
 * Weiterlaufen bei ausgeschaltetem Bildschirm kommt mit dem Vordergrund-Dienst in M3.
 */
class HostViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private var server: PartyServer? = null
    private var job: Job? = null

    fun start() {
        if (server != null || _state.value == HostState.Starting) return
        _state.value = HostState.Starting
        job = viewModelScope.launch {
            val started = runCatching {
                withContext(Dispatchers.IO) {
                    PartyServer(AssetStaticFiles(getApplication<Application>().assets)).also { it.start() }
                }
            }
            started.onFailure { _state.value = HostState.Failed(it.message ?: it.javaClass.simpleName) }
            val running = started.getOrNull() ?: return@launch
            server = running
            val urls = lanAddresses().map { "http://$it:${running.port}/j/${running.room.roomCode}" }
            running.room.players.collect { players ->
                _state.value = HostState.Running(running.room.roomCode, urls, players)
            }
        }
    }

    fun stop() {
        val running = server ?: return
        server = null
        job?.cancel()
        job = null
        _state.value = HostState.Idle
        viewModelScope.launch(Dispatchers.IO) { running.stop() }
    }

    fun removePlayer(id: Int) {
        val running = server ?: return
        viewModelScope.launch { running.room.removePlayer(id) }
    }

    override fun onCleared() {
        // viewModelScope ist hier schon beendet
        server?.let { Thread { it.stop() }.start() }
        server = null
    }
}

/** Die gebaute Seite aus app/src/main/assets/web. */
private class AssetStaticFiles(private val assets: AssetManager) : StaticFiles {
    override fun read(path: String): ByteArray? {
        if (path.split('/').any { it == ".." || it.isEmpty() }) return null
        return runCatching { assets.open("web/$path").use { it.readBytes() } }.getOrNull()
    }
}

/** IPv4-Adressen im lokalen Netz (WLAN, Hotspot). Genauere Erkennung kommt in M3. */
private fun lanAddresses(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
