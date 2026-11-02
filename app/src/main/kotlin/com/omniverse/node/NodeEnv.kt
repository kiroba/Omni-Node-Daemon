package com.omniverse.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/**
 * The env adapter: Android facts the shared core needs (omni_node_core ENV_KEYS). Charging, unmetered
 * Wi-Fi and battery saver feed the Relay calls policy; radios feed the Nearby radios line. The core
 * decides what they mean; this only reads them.
 */
object NodeEnv {
    fun charging(c: Context): Boolean {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun unmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null } ?: return false
        val wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return wired && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun batterySaver(c: Context): Boolean = try {
        c.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
    } catch (_: Exception) { false }

    fun missingRadioPermissions(c: Context): List<String> = NodeRadio.permissions().filter {
        c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun collect(c: Context): JSONObject {
        val radioStatus = try { JSONObject(NodeRadio.statusFile(c).readText()) } catch (_: Exception) { null }
        return JSONObject()
            .put("platform", "android")
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("charging", charging(c))
            .put("unmetered", unmetered(c))
            .put("battery_saver", batterySaver(c))
            .put("autostart_available", false)      // BootReceiver always brings it back if left ON
            .put("default_network_on", NodePrefs.legacyOn(c))
            .put("radios", JSONObject()
                .put("missing", missingRadioPermissions(c).isNotEmpty())
                .put("status", radioStatus ?: JSONObject.NULL))
    }
}

/** Calls [onChange] when charging, the network or battery saver changes (the :daemon service listens). */
class NodeEnvWatcher(private val context: Context, private val onChange: () -> Unit) {
    private var running = false
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onChange()
    }
    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
        override fun onLost(network: Network) = onChange()
    }

    fun start() {
        if (running) return
        running = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(power, f, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(power, f)
        try { cm?.registerDefaultNetworkCallback(net) } catch (_: Exception) {}
    }

    fun stop() {
        if (!running) return
        running = false
        try { context.unregisterReceiver(power) } catch (_: Exception) {}
        try { cm?.unregisterNetworkCallback(net) } catch (_: Exception) {}
    }
}
