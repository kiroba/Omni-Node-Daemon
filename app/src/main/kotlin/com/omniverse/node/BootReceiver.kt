package com.omniverse.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Comes back on after a reboot or an app update, but only if the person left the switch ON. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (NodePrefs.isOn(context)) OmniDaemonService.start(context)
    }
}
