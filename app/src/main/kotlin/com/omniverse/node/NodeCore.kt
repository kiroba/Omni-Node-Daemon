package com.omniverse.node

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

/**
 * The shared Omni Node core (kiroba/Omni-Daemon Admin/omni_node_core.py), reached through Chaquopy and
 * omni_node_entry.py. Everything the screen shows and every decision (status words, relay policy, peers,
 * pairing, router port, updates, the Node version) comes from there; this app only renders it and runs
 * the Android environment around it. See Omni-Daemon Admin/docs/NODE_SHARED_CORE.md.
 *
 * Every call blocks (Python + loopback HTTP): never call from the main thread.
 */
object NodeCore {
    private fun entry(c: Context): PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(c.applicationContext))
        return Python.getInstance().getModule("omni_node_entry")
    }

    private fun dir(c: Context) = c.filesDir.absolutePath

    /** NodeCore.ui_state(), after reading the node when [refresh] is true. */
    fun state(c: Context, refresh: Boolean): JSONObject =
        JSONObject(entry(c).callAttr("ui_state_json", dir(c), NodeEnv.collect(c).toString(), refresh).toString())

    /** NodeCore.do(action, args): {ok, toast, effects, copy, sensitive, url, token, ...}. */
    fun act(c: Context, action: String, args: JSONObject = JSONObject()): JSONObject =
        JSONObject(entry(c).callAttr("do_json", dir(c), action, args.toString(), NodeEnv.collect(c).toString()).toString())

    /** The :daemon service's tick: {line, node_id, relay_active, network_on}. */
    fun duties(c: Context, forcePeers: Boolean): JSONObject =
        JSONObject(entry(c).callAttr("duties_json", dir(c), NodeEnv.collect(c).toString(), forcePeers).toString())

    /** Every Node word, from Python when it's up, else the copy the core cached last time. */
    fun words(c: Context, live: Boolean): JSONObject = try {
        if (live) JSONObject(entry(c).callAttr("words_json", dir(c)).toString())
        else JSONObject(File(c.filesDir, ".omni/node_words.json").readText())
    } catch (_: Throwable) {
        JSONObject()
    }
}
