package com.omniverse.node

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pair a device (daemon v8.24.0 quiet mesh): the words, and the loopback calls to the :5000 pairing API.
 *
 * The words are the same as the desktop Omni Node (kiroba/Omni-Daemon Admin/omni_node_words.py,
 * pinned by tests/test_node_desktop.py SameWordsAsAndroid.test_pairing_words). Change both together.
 * Network calls block: run them off the main thread.
 */
object NodePairing {
    const val TITLE = "Pair a device"
    const val HINT = "Pair your phone, your PC or a friend's device. Paired nodes find each other quietly: " +
        "other people on the same Wi-Fi can't see who you are."
    const val SHOW = "Show a code"
    const val ENTER = "Enter a code"
    const val STOP = "Stop showing the code"
    const val CODE_HOWTO = "On the other device open Omni Node, choose Pair a device, then Enter a code and type " +
        "this code. Keep both on the same Wi-Fi."
    const val ENTER_HOWTO = "Type the 6-digit code the other device shows, or paste its pairing text. If it can't " +
        "be found, add its address after the code, like 123456 192.168.1.20"
    const val LOOKING = "Looking for the other device..."
    const val DONE = "Paired. Your nodes now find each other quietly."
    const val WRONG = "That code didn't match."
    const val LOCKED = "Too many wrong codes. Make a new code on the other device."
    const val NONE = "No paired devices yet."
    const val COPY = "Copy pairing text"
    const val TEXT_WARNING = "Anyone with this text can pair with you while the code shows. Only send it to yourself."
    const val REMOVE = "Remove"
    const val ENDED = "The code stopped working. Show a new one."
    const val NEEDS_UPDATE = "This node is too old to pair (needs daemon v8.24.0 or newer)."

    fun expires(seconds: Int, triesLeft: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "Expires in ${s / 60}:${"%02d".format(s % 60)} · $triesLeft ${if (triesLeft == 1) "try" else "tries"} left"
    }

    fun codeDisplay(code: String) = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

    fun removeConfirm(name: String) =
        "Remove $name? Your nodes stop syncing with each other. To connect again, pair again."

    fun pairedLine(p: JSONObject): String {
        val name = p.optString("name").ifEmpty { "Omni node" }
        val where = if (p.optBoolean("online")) "nearby now" else "not nearby"
        return "$name ${p.optString("tag")}  ·  $where"
    }

    /** (text, colour) for the Quiet mode line, from node-health / pair list "quiet". */
    fun quietLine(q: JSONObject?): Pair<String, String> {
        if (q == null) return "Quiet mode: checking..." to "#808080"
        if (q.optString("mode") == "off") return "Quiet mode: off on this node" to "#808080"
        val n = q.optInt("paired")
        return when {
            q.optBoolean("active") && n > 0 ->
                "Quiet mode: on · only your $n paired device${if (n == 1) "" else "s"} can see this node" to "#4CAF50"
            q.optBoolean("active") -> "Quiet mode: on · no devices paired yet" to "#FFB74D"
            else -> "Quiet mode: off until you pair a device" to "#808080"
        }
    }

    /** What the person typed -> the /api/pair/accept body, or null (same rules as desktop parse_pair_input). */
    fun parseInput(text: String): JSONObject? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("omni://pair") || (t.length > 40 && !t.contains(' '))) return JSONObject().put("payload", t)
        var parts = t.split(Regex("\\s+"))
        var host: String? = null
        var port = 5005
        if (parts.size > 1 && !parts.last().replace("-", "").all { it.isDigit() }) {
            val peer = PeerList.parse(parts.last()) ?: return null
            host = peer.host
            port = peer.port
            parts = parts.dropLast(1)
        }
        val joined = parts.joinToString(" ")
        if (joined.any { !(it.isDigit() || it == ' ' || it == '-') }) return null
        val digits = joined.filter { it.isDigit() }
        if (digits.length != 6) return null
        val body = JSONObject().put("code", digits)
        if (host != null) body.put("host", host).put("port", port)
        return body
    }

    data class Reply(val code: Int, val body: JSONObject)

    private fun call(c: Context, path: String, body: JSONObject?, readTimeoutMs: Int = 4000): Reply {
        val token = NodeStatus.localToken(c) ?: return Reply(0, JSONObject().put("message", "Turn on Network first."))
        return try {
            val conn = URL("http://127.0.0.1:5000$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("X-Omni-Local-Token", token)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code == 404 && !json.has("status")) Reply(404, JSONObject().put("message", NEEDS_UPDATE).put("old", true))
            else Reply(code, json)
        } catch (e: Exception) {
            Reply(0, JSONObject().put("message", "Can't reach the node (${e.javaClass.simpleName})."))
        }
    }

    fun list(c: Context) = call(c, "/api/pair/list", null)
    fun offer(c: Context) = call(c, "/api/pair/offer", JSONObject())
    fun offerStatus(c: Context) = call(c, "/api/pair/offer", null)
    fun cancel(c: Context) = call(c, "/api/pair/cancel", JSONObject())
    fun accept(c: Context, body: JSONObject) = call(c, "/api/pair/accept", body, readTimeoutMs = 45_000)
    fun remove(c: Context, pubkey: String) = call(c, "/api/pair/remove", JSONObject().put("pubkey", pubkey))
}
