package com.omniverse.node

import android.content.Context

/** The master switch, remembered across restarts and reboots. */
object NodePrefs {
    private const val FILE = "omni_node"
    private const val ON = "switch_on"
    private const val ASKED_BATTERY = "asked_battery"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isOn(c: Context) = p(c).getBoolean(ON, false)
    fun setOn(c: Context, on: Boolean) = p(c).edit().putBoolean(ON, on).apply()
    fun askedBattery(c: Context) = p(c).getBoolean(ASKED_BATTERY, false)
    fun setAskedBattery(c: Context) = p(c).edit().putBoolean(ASKED_BATTERY, true).apply()
}
