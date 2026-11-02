package com.omniverse.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/**
 * Always-on node: runs the embedded Omni-Daemon in this service's own process (":daemon").
 * ON  -> foreground service (START_STICKY) with a status notification.
 * OFF -> stopSelf(), then the process exits, so the OS closes :5000 / :9100 / :5005 / :9101.
 *
 * Android environment only. What the node should do (Relay calls policy, router port, re-handing added
 * peers, the status line) is the shared core's duties(), called every 15 s and whenever charging, the
 * network or a setting changes. This service keeps the process, the notification, the radios and the
 * multicast / Wi-Fi locks.
 */
class OmniDaemonService : Service() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastLine = ""
    private var radio: NodeRadio? = null
    private var envWatcher: NodeEnvWatcher? = null
    private var words = JSONObject()

    companion object {
        private const val CHANNEL = "omni_node_status"
        private const val NOTE_ID = 1
        private const val ACTION_STOP = "com.omniverse.node.STOP"
        private const val ACTION_KICK = "com.omniverse.node.KICK"
        private const val ACTION_RADIOS = "com.omniverse.node.RADIOS"
        private const val POLL_MS = 15_000L

        fun start(c: Context) {
            val i = Intent(c, OmniDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        /** A Node setting changed (peers, relay, router port): run the core's duties now. No-op when off. */
        fun kick(c: Context) {
            if (NodePrefs.isOn(c)) c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_KICK))
        }

        /** Radio permissions were just granted: restart the radios with them. */
        fun radiosChanged(c: Context) {
            if (NodePrefs.isOn(c)) c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_RADIOS))
        }

        fun stop(c: Context) {
            c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NodePrefs.setOff(this)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_KICK && worker != null) {
            handler?.post { tick(forcePeers = true) }
            return START_STICKY
        }
        if (intent?.action == ACTION_RADIOS && worker != null) {
            radio?.stop()
            radio = null      // the next tick starts them again with the new permissions
            return START_STICKY
        }
        words = NodeCore.words(this, live = false)     // cached labels until Python is up
        goForeground(words.optString("STARTING"))
        if (worker == null) startNode()
        return START_STICKY
    }

    private fun startNode() {
        val w = HandlerThread("omni-node").also { it.start() }
        worker = w
        handler = Handler(w.looper)
        handler?.post {
            try {
                acquireMulticastLock()
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("omni_node_entry")
                    .callAttr("start", filesDir.absolutePath, Build.MODEL ?: "Android phone")
                words = NodeCore.words(this, live = true)
            } catch (t: Throwable) {
                update(t.javaClass.simpleName)
            }
            envWatcher = NodeEnvWatcher(this) { handler?.post { tick(forcePeers = false) } }.also {
                android.os.Handler(mainLooper).post { it.start() }
            }
            handler?.postDelayed(poll, 2_000L)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            tick(forcePeers = false)
            handler?.postDelayed(this, POLL_MS)
        }
    }

    /** One pass of the core's duties (on the worker thread), then the Android side of its answer. */
    private fun tick(forcePeers: Boolean) {
        val r = try { NodeCore.duties(this, forcePeers) } catch (t: Throwable) { null } ?: return
        update(r.optString("line"))
        val nodeId = r.optString("node_id")
        // Radios start once the daemon answers with its key (the BLE / Wi-Fi Aware id).
        if (radio == null && nodeId.isNotEmpty()) {
            val rd = NodeRadio(this)
            radio = rd
            android.os.Handler(mainLooper).post { rd.start(nodeId) }
        }
        // The core decided whether this phone relays now; keep Wi-Fi awake only while it does.
        if (r.optBoolean("relay_active")) acquireWifiLock() else releaseWifiLock()
    }

    private fun update(line: String) {
        if (line.isEmpty() || line == lastLine) return
        lastLine = line
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, buildNote(line))
    }

    private fun goForeground(line: String) {
        lastLine = line
        val note = buildNote(line)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTE_ID, note)
        }
    }

    private fun buildNote(line: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, words.optString("NOTIF_CHANNEL", getString(R.string.app_name)),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = words.optString("NOTIF_CHANNEL_DESC")
                    setShowBadge(true)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val off = PendingIntent.getService(
            this, 1, Intent(this, OmniDaemonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_node)
            .setContentTitle(words.optString("TITLE", getString(R.string.app_name)))
            .setContentText(line)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, words.optString("NOTIF_TURN_OFF", "\u23FB"), off).build())
            .build()
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("omni-node-beacons").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omni-node-relay").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        try { radio?.stop() } catch (_: Throwable) {}
        try { envWatcher?.stop() } catch (_: Throwable) {}
        releaseWifiLock()
        try {
            multicastLock?.release()
        } catch (_: Throwable) {
        }
        worker?.quitSafely()
        super.onDestroy()
        // The daemon has no in-process shutdown; ending this process closes every socket cleanly.
        Process.killProcess(Process.myPid())
    }
}
