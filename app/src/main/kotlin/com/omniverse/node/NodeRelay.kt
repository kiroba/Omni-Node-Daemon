package com.omniverse.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Phone relay (daemon v8.22.0 built-in STUN + TURN). Off unless the person turns on "Relay calls",
 * and even then it only runs while the phone is charging on Wi-Fi/Ethernet (never on mobile data),
 * under a daily cap. The daemon itself defaults to relay off on Android (OMNI_RELAY=off in
 * omni_node_entry.py); this class turns it on/off over loopback POST /api/relay/config.
 *
 * Settings live in <filesDir>/relay.json (not SharedPreferences: the UI and this :daemon process are
 * different processes). Live state is written to <filesDir>/relay_status.json for the UI.
 */
class NodeRelay(private val context: Context) {
    data class Settings(val on: Boolean = false, val mbDay: Int = 1024, val kbps: Int = 1500)

    companion object {
        private const val TICK_MS = 60_000L
        val CAPS_MB = intArrayOf(250, 1024, 5120)

        private fun file(c: Context) = File(c.filesDir, "relay.json")
        fun statusFile(c: Context) = File(c.filesDir, "relay_status.json")

        fun read(c: Context): Settings = try {
            val j = JSONObject(file(c).readText())
            Settings(j.optBoolean("on", false), j.optInt("mb_day", 1024).coerceIn(0, 10_000_000),
                j.optInt("kbps", 1500).coerceIn(64, 1_000_000))
        } catch (_: Exception) { Settings() }

        fun write(c: Context, s: Settings) = file(c).writeText(
            JSONObject().put("on", s.on).put("mb_day", s.mbDay).put("kbps", s.kbps).toString())

        fun status(c: Context): JSONObject? = try { JSONObject(statusFile(c).readText()) } catch (_: Exception) { null }

        fun capLabel(mb: Int) = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var wifiLock: WifiManager.WifiLock? = null
    private var running = false
    private var lastResult = ""

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = evaluate()
    }
    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate()
        override fun onLost(network: Network) = evaluate()
    }
    private val tick = object : Runnable {
        override fun run() { evaluate(); main.postDelayed(this, TICK_MS) }
    }

    fun start() {
        if (running) return
        running = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(power, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(power, f)
        try { cm?.registerDefaultNetworkCallback(net) } catch (_: Exception) {}
        main.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        try { context.unregisterReceiver(power) } catch (_: Exception) {}
        try { cm?.unregisterNetworkCallback(net) } catch (_: Exception) {}
        releaseWifiLock()
        io.shutdownNow()
    }

    private fun charging(): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun unmetered(): Boolean {
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null } ?: return false
        val wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return wired && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Recompute and hand the result to the daemon. Safe to call from any thread, any time. */
    fun evaluate() {
        if (!running) return
        val s = read(context)
        val plugged = charging()
        val wifi = unmetered()
        val active = s.on && plugged && wifi
        val reason = when {
            !s.on -> "off"
            !wifi -> "waiting for Wi-Fi (never relays on mobile data)"
            !plugged -> "waiting until the phone is charging"
            else -> "relaying"
        }
        if (active) acquireWifiLock() else releaseWifiLock()
        try {
            io.execute {
                lastResult = post(active, s)
                writeStatus(s, plugged, wifi, active, reason)
            }
        } catch (_: Exception) { /* stopped */ }
    }

    private fun post(enabled: Boolean, s: Settings): String {
        val token = NodeStatus.localToken(context) ?: return "no node token yet"
        return try {
            val c = URL("http://127.0.0.1:5000/api/relay/config").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 4000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Omni-Local-Token", token)
            val body = JSONObject().put("enabled", enabled).put("max_kbps", s.kbps).put("max_mb_day", s.mbDay)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            when (val code = c.responseCode) {
                200 -> {
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    if (enabled && !j.optBoolean("enabled")) "daemon couldn't start the relay: ${j.optString("error")}" else "ok"
                }
                404 -> "this node's daemon is too old for relaying (needs v8.22.0)"
                else -> "daemon answered $code"
            }
        } catch (e: Exception) {
            "not handed in (${e.javaClass.simpleName})"
        }
    }

    private fun writeStatus(s: Settings, plugged: Boolean, wifi: Boolean, active: Boolean, reason: String) {
        try {
            statusFile(context).writeText(JSONObject()
                .put("on", s.on).put("charging", plugged).put("wifi", wifi).put("active", active)
                .put("reason", reason).put("daemon", lastResult).put("at", System.currentTimeMillis()).toString())
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omni-node-relay").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }
}
