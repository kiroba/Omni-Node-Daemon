package com.omniverse.node

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One plain status line, the same words everywhere (Directive v7). */
data class NodeStatus(val line: String, val nodeId: String = "", val version: String = "", val peers: Int = 0) {
    /** Name + short key tag, e.g. "kiro·4f23". No username in this app yet, so the tag alone. */
    val tag: String get() = if (nodeId.length >= 4) "·" + nodeId.take(4) else ""

    companion object {
        const val OFF = "Network status: Off"
        const val LOOKING = "Looking for nearby people..."
        const val STARTING = "Starting..."

        fun connected(n: Int) = "Connected to $n nearby"
        fun problem(reason: String) = "Problem: $reason"

        fun localToken(context: Context): String? = try {
            File(context.filesDir, ".omni/local_token").readText().trim().takeIf { it.length >= 32 }
        } catch (_: Exception) {
            null
        }

        /** Reads :9100 node-health with the local token. Blocking; call off the main thread. */
        fun fetch(context: Context): NodeStatus {
            val token = localToken(context) ?: return NodeStatus(STARTING)
            return try {
                val c = URL("http://127.0.0.1:9100/api/v1/telemetry/node-health").openConnection() as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 1500
                c.setRequestProperty("X-Omni-Local-Token", token)
                if (c.responseCode != 200) return NodeStatus(problem("the node answered ${c.responseCode}"))
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val peers = j.optInt("active_peers", 0)
                NodeStatus(
                    line = if (peers > 0) connected(peers) else LOOKING,
                    nodeId = j.optString("node_id"),
                    version = j.optString("version"),
                    peers = peers
                )
            } catch (_: java.net.ConnectException) {
                NodeStatus(STARTING)
            } catch (e: Exception) {
                NodeStatus(problem(e.message ?: "can't reach the node"))
            }
        }
    }
}
