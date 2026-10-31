package io.github.hamzabhai.jhatpat.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.hamzabhai.jhatpat.gallery.AndroidMediaStoreGateway
import io.github.hamzabhai.jhatpat.gallery.GallerySaver
import io.github.hamzabhai.jhatpat.gallery.ReceivedWatcher
import io.github.hamzabhai.jhatpat.net.HotspotController
import io.github.hamzabhai.jhatpat.server.HostApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the JhatPat server, the hotspot and the locks for as long as sharing is on. It is a
 * foreground service so Android keeps it alive with the screen off. Everything it does stops when
 * the service stops, is removed from recents or crashes (the server also exits when its input pipe
 * closes).
 */
class SharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: HotspotController
    private lateinit var machine: SharingStateMachine
    private var watcher: Job? = null

    // Copying a received file into the Gallery can take a while, so it runs here, one file at a time,
    // instead of holding up the event stream.
    private val saveWorker = Executors.newSingleThreadExecutor()
    private val failures = AtomicInteger()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        controller = HotspotController(this)
        machine = SharingStateMachine(
            server = RealServerRunner(this),
            hotspot = HotspotControllerRunner(controller),
            wifi = { controller.currentWifiIp() },
            locks = AndroidLocks(this),
            state = SharingController.mutableState,
        )
        // The state outlives the service (the screen shows the last error), so a new service starts
        // from a clean slate; otherwise a stale Error would stop it before it began.
        SharingController.mutableState.value = SharingState.Idle
        scope.launch { SharingController.state.collect { onState(it) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(Mode.Wifi)
                promoteToForeground(SharingState.Starting(mode))
                scope.launch { machine.start(mode) }
            }
            ACTION_STOP -> shutDown(startId)
            else -> if (SharingController.state.value !is SharingState.Running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutDown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        machine.stop()
        stopWatching()
        saveWorker.shutdown() // lets a file that is being copied finish
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(state: SharingState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID, Notifications.build(this, state), type)
    }

    private fun onState(state: SharingState) {
        when (state) {
            is SharingState.Running -> {
                NotificationManagerCompat.from(this).notifySafely(Notifications.ID, Notifications.build(this, state))
                if (watcher == null) startWatching(state)
            }
            is SharingState.Starting -> promoteToForeground(state)
            is SharingState.Error -> {
                stopWatching()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                NotificationManagerCompat.from(this).notifySafely(Notifications.ID, Notifications.build(this, state))
                stopSelf()
            }
            // Stopping is always requested explicitly (shutDown, onDestroy); Idle is also the initial state.
            SharingState.Idle -> Unit
        }
    }

    /**
     * Stops sharing. With a [startId] the service only ends if no newer start command has arrived,
     * so tapping Stop and then Start straight away is not cancelled by the earlier Stop.
     */
    private fun shutDown(startId: Int? = null) {
        machine.stop()
        stopWatching()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    /** While running: refresh the counts, and follow the WiFi address in WiFi mode. */
    private fun startWatching(running: SharingState.Running) {
        val api = HostApi("http://127.0.0.1:${running.info.port}", running.info.hostToken)
        SharingController.savedUris.clear()
        val received = ReceivedWatcher(
            mover = api,
            saver = GallerySaver(AndroidMediaStoreGateway(this)),
            saved = SharingController.savedUris,
            onSaveFailed = { name ->
                NotificationManagerCompat.from(this).notifySafely(100 + failures.incrementAndGet(), Notifications.saveFailed(this, name, failures.get()))
            },
        )
        watcher = scope.launch {
            launch(Dispatchers.IO) {
                while (isActive) {
                    api.events { type, data ->
                        if (type == "received") saveWorker.execute { received.handle(type, data) }
                        refresh(api)
                    }
                    delay(2_000) // reconnect
                }
            }
            launch(Dispatchers.IO) {
                while (isActive) {
                    refresh(api)
                    delay(15_000)
                }
            }
        }
        if (running.mode == Mode.Wifi) watchWifiAddress(api)
    }

    private fun refresh(api: HostApi) {
        api.summary()?.let { machine.updateCounts(it.connected, it.received) }
    }

    private fun watchWifiAddress(api: HostApi) {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = follow(api)

            override fun onAvailable(network: Network) = follow(api)
        }
        networkCallback = callback
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback,
        )
    }

    private fun follow(api: HostApi) {
        val now = controller.currentWifiIp() ?: return
        val running = SharingController.state.value as? SharingState.Running ?: return
        if (now != running.info.ip && api.setIp(now)) machine.updateIp(now)
    }

    private fun stopWatching() {
        watcher?.cancel()
        watcher = null
        networkCallback?.let {
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    private fun NotificationManagerCompat.notifySafely(id: Int, notification: android.app.Notification) {
        // Without the notifications permission (Android 13+) Android silently drops this.
        try {
            notify(id, notification)
        } catch (_: SecurityException) {
            // permission denied; the service keeps running without a visible update
        }
    }

    companion object {
        const val ACTION_START = "io.github.hamzabhai.jhatpat.START"
        const val ACTION_STOP = "io.github.hamzabhai.jhatpat.STOP"
        const val EXTRA_MODE = "mode"
    }
}
