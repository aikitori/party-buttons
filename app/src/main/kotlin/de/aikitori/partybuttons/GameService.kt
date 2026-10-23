package de.aikitori.partybuttons

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.aikitori.partybuttons.core.Mode
import de.aikitori.partybuttons.core.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hält das Spiel am Laufen, auch bei ausgeschaltetem Bildschirm: Vordergrund-Dienst mit Benachrichtigung,
 * WakeLock für die CPU und WifiLock gegen den WLAN-Stromsparmodus (sonst kommen Drücke verzögert an).
 */
class GameService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var tones: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // gehalten, bis der Host das Spiel beendet
    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PartyButtons:game")
            .apply { acquire() }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiLockMode(), "PartyButtons:game")
            .apply { acquire() }
        tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HostSession.stop(this)
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(HostSession.state.value), type)
        scope.launch {
            var last: HostState.Running? = null
            HostSession.state.collect { state ->
                if (state is HostState.Running) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    playSounds(last, state)
                    last = state
                }
            }
        }
        // stirbt der Prozess, ist das Spiel ohnehin weg; nicht von selbst neu starten
        return START_NOT_STICKY
    }

    /** Ton beim ersten Druck einer Runde und wenn alle geantwortet haben; auch bei ausgeschaltetem Bildschirm. */
    private fun playSounds(before: HostState.Running?, now: HostState.Running) {
        val old = before?.round?.takeIf { it.number == now.round.number }
        val round = now.round
        if (round.mode == Mode.BUZZER && round.presses.isNotEmpty() && old?.presses.isNullOrEmpty()) {
            tones?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        }
        val connected = now.players.count { it.connected }
        val allAnswered = round.mode == Mode.CHOICE && round.phase == Phase.OPEN && connected > 0 &&
            round.answers.size >= connected
        val allAnsweredBefore = old != null && old.answers.size >= connected && old.phase == Phase.OPEN
        if (allAnswered && !allAnsweredBefore) tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
    }

    override fun onDestroy() {
        scope.cancel()
        tones?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(state: HostState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GameService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val running = state as? HostState.Running
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title, running?.roomCode ?: ""))
            .setContentText(
                running?.let { getString(R.string.notification_text, it.players.count { p -> p.connected }) },
            )
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop_game), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION") // HIGH_PERF ist der einzige Modus, der auch bei ausgeschaltetem Bildschirm wirkt
    private fun wifiLockMode() = WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private companion object {
        const val CHANNEL_ID = "game"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "de.aikitori.partybuttons.STOP"
    }
}
