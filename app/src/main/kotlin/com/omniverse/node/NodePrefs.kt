package com.omniverse.node

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Android-only memory, plus a reader for the shared settings file so BootReceiver can decide without
 * starting Python. The Network switch itself lives in the core's ~/.omni/node_settings.json
 * (HOME = filesDir), the same file every platform uses.
 */
object NodePrefs {
    private const val FILE = "omni_node"
    private const val ON = "switch_on"              // Node <= 0.4.0 kept the switch here
    private const val ASKED_BATTERY = "asked_battery"
    private const val UPDATE_CHECKED = "update_checked_ms"
    private const val DAY_MS = 24L * 3600 * 1000

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun settings(c: Context) = File(c.filesDir, ".omni/node_settings.json")

    fun legacyOn(c: Context) = p(c).getBoolean(ON, false)

    fun isOn(c: Context): Boolean = try {
        JSONObject(settings(c).readText()).optBoolean("network_on", legacyOn(c))
    } catch (_: Exception) {
        legacyOn(c)
    }

    /** "Turn off" from the notification, where Python may not be running: flip the one key in place. */
    fun setOff(c: Context) {
        p(c).edit().putBoolean(ON, false).apply()
        val f = settings(c)
        if (!f.exists()) return
        try {
            val j = JSONObject(f.readText()).put("network_on", false)
            val tmp = File(f.path + ".kt.tmp")
            tmp.writeText(j.toString(2))
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    fun askedBattery(c: Context) = p(c).getBoolean(ASKED_BATTERY, false)
    fun setAskedBattery(c: Context) = p(c).edit().putBoolean(ASKED_BATTERY, true).apply()

    /** The quiet update check on launch runs at most once a day (a manual check doesn't count). */
    fun updateCheckDue(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = p(c).getLong(UPDATE_CHECKED, 0L)
        return last <= 0L || last > now || now - last >= DAY_MS
    }
    fun setUpdateChecked(c: Context, now: Long = System.currentTimeMillis()) =
        p(c).edit().putLong(UPDATE_CHECKED, now).apply()

    /** The node's local token (NodeRadio hands Wi-Fi Aware peers to the daemon with it). */
    fun localToken(c: Context): String? = try {
        File(c.filesDir, ".omni/local_token").readText().trim().takeIf { it.length >= 32 }
    } catch (_: Exception) {
        null
    }
}
