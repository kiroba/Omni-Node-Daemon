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

/**
 * Always-on node: runs the embedded Omni-Daemon in this service's own process (":daemon").
 * ON  -> foreground service (START_STICKY) with a status notification.
 * OFF -> stopSelf(), then the process exits, so the OS closes :5000 / :9100 / :5005 / :9101.
 */
class OmniDaemonService : Service() {
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastLine = ""
    private var radio: NodeRadio? = null
    private var relay: NodeRelay? = null

    companion object {
        private const val CHANNEL = "omni_node_status"
        private const val NOTE_ID = 1
        private const val ACTION_STOP = "com.omniverse.node.STOP"
        private const val ACTION_PEERS = "com.omniverse.node.PEERS"
        private const val ACTION_RADIOS = "com.omniverse.node.RADIOS"
        private const val ACTION_RELAY = "com.omniverse.node.RELAY"
        private const val POLL_MS = 15_000L

        fun start(c: Context) {
            val i = Intent(c, OmniDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        /** The Add peer list changed: hand it to the daemon now. No-op when the node is off. */
        fun peersChanged(c: Context) {
            if (NodePrefs.isOn(c)) c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_PEERS))
        }

        /** Radio permissions were just granted: restart the radios with them. */
        fun radiosChanged(c: Context) {
            if (NodePrefs.isOn(c)) c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_RADIOS))
        }

        /** Relay calls setting changed: apply it now. No-op when the node is off. */
        fun relayChanged(c: Context) {
            if (NodePrefs.isOn(c)) c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_RELAY))
        }

        fun stop(c: Context) {
            c.startService(Intent(c, OmniDaemonService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NodePrefs.setOn(this, false)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PEERS && worker != null) {
            radio?.peersChanged()
            return START_STICKY
        }
        if (intent?.action == ACTION_RELAY && worker != null) {
            relay?.evaluate()
            return START_STICKY
        }
        if (intent?.action == ACTION_RADIOS && worker != null) {
            radio?.stop()
            radio = null      // the next poll starts them again with the new permissions
            return START_STICKY
        }
        goForeground(NodeStatus.STARTING)
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
                Python.getInstance().getModule("omni_node_entry").callAttr("start", filesDir.absolutePath, android.os.Build.MODEL ?: "Android phone")
            } catch (t: Throwable) {
                update(NodeStatus.problem("the node couldn't start (${t.javaClass.simpleName})"))
            }
            handler?.postDelayed(poll, 2_000L)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val err = try {
                Python.getInstance().getModule("omni_node_entry").callAttr("error").toString()
            } catch (_: Throwable) {
                ""
            }
            if (err.isNotEmpty()) {
                update(NodeStatus.problem(err.take(80)))
            } else {
                val s = NodeStatus.fetch(this@OmniDaemonService)
                update(s.line)
                // Radios start once the daemon answers with its key (the BLE / Wi-Fi Aware id).
                if (radio == null && s.nodeId.isNotEmpty()) {
                    val r = NodeRadio(this@OmniDaemonService)
                    radio = r
                    android.os.Handler(mainLooper).post { r.start(s.nodeId) }
                }
                // Phone relay (off unless "Relay calls" is on; Wi-Fi + charging only).
                if (relay == null && s.nodeId.isNotEmpty()) {
                    val rl = NodeRelay(this@OmniDaemonService)
                    relay = rl
                    android.os.Handler(mainLooper).post { rl.start() }
                }
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    private fun update(line: String) {
        if (line == lastLine) return
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
                NotificationChannel(CHANNEL, "Network status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows whether your Omni node is on and who it's connected to."
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
            .setContentTitle("Omni Node")
            .setContentText(line)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .build()
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("omni-node-beacons").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        try { radio?.stop() } catch (_: Throwable) {}
        try { relay?.stop() } catch (_: Throwable) {}
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
