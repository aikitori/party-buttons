package de.aikitori.partybuttons

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import androidx.core.content.ContextCompat
import de.aikitori.partybuttons.core.Mode
import de.aikitori.partybuttons.core.PlayerView
import de.aikitori.partybuttons.core.RoundView
import de.aikitori.partybuttons.server.LanAddresses
import de.aikitori.partybuttons.server.PartyRoom
import de.aikitori.partybuttons.server.PartyServer
import de.aikitori.partybuttons.server.StaticFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HostState {
    data object Idle : HostState
    data object Starting : HostState
    data class Failed(val message: String) : HostState

    data class Running(
        val roomCode: String,
        /** Adressen, unter denen die Mitspielenden die Seite öffnen, die beste zuerst. Leer ohne WLAN. */
        val urls: List<String>,
        val players: List<PlayerView>,
        val round: RoundView,
    ) : HostState
}

/**
 * Das laufende Spiel, unabhängig vom Bildschirm: Die Activity kann zugehen, der [GameService] hält die App am
 * Leben, bis der Host das Spiel beendet.
 */
object HostSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private var server: PartyServer? = null
    private var job: Job? = null

    fun start(context: Context) {
        if (server != null || _state.value == HostState.Starting) return
        val app = context.applicationContext
        _state.value = HostState.Starting
        job = scope.launch {
            val started = runCatching {
                withContext(Dispatchers.IO) { PartyServer(AssetStaticFiles(app.assets)).also { it.start() } }
            }
            started.onFailure { _state.value = HostState.Failed(it.message ?: it.javaClass.simpleName) }
            val running = started.getOrNull() ?: return@launch
            server = running
            ContextCompat.startForegroundService(app, Intent(app, GameService::class.java))

            // Adressen ändern sich, wenn WLAN oder Hotspot an- oder ausgehen; deshalb regelmäßig neu ermitteln
            val addresses = MutableStateFlow(LanAddresses.current())
            launch {
                while (true) {
                    delay(3_000)
                    addresses.value = withContext(Dispatchers.IO) { LanAddresses.current() }
                }
            }
            val code = running.room.roomCode
            combine(running.room.snapshot, addresses) { snapshot, ips ->
                HostState.Running(code, ips.map { "http://$it:${running.port}/j/$code" }, snapshot.players, snapshot.round)
            }.collect { _state.value = it }
        }
    }

    fun stop(context: Context) {
        val running = server ?: return
        server = null
        job?.cancel()
        job = null
        _state.value = HostState.Idle
        val app = context.applicationContext
        app.stopService(Intent(app, GameService::class.java))
        scope.launch(Dispatchers.IO) { running.stop() }
    }

    fun openRound() = withRoom { it.openRound() }

    fun lockRound() = withRoom { it.lockRound() }

    fun setLockOnFirst(value: Boolean) = withRoom { it.setLockOnFirst(value) }

    fun setMode(mode: Mode) = withRoom { it.setMode(mode) }

    fun setOptions(count: Int) = withRoom { it.setOptions(count) }

    fun reveal(correct: Int?) = withRoom { it.reveal(correct) }

    fun removePlayer(id: Int) = withRoom { it.removePlayer(id) }

    private fun withRoom(action: suspend (PartyRoom) -> Unit) {
        val running = server ?: return
        scope.launch { action(running.room) }
    }
}

/** Die gebaute Seite aus app/src/main/assets/web. */
private class AssetStaticFiles(private val assets: AssetManager) : StaticFiles {
    override fun read(path: String): ByteArray? {
        if (path.split('/').any { it == ".." || it.isEmpty() }) return null
        return runCatching { assets.open("web/$path").use { it.readBytes() } }.getOrNull()
    }
}
