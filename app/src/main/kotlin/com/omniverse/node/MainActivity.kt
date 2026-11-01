package com.omniverse.node

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.PersistableBundle
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.text.InputType
import org.json.JSONObject
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** One screen: one switch, a status card, and Connect apps (token behind the screen lock). */
class MainActivity : Activity() {
    private lateinit var master: Switch
    private lateinit var statusLine: TextView
    private lateinit var statusDot: TextView
    private lateinit var statusDetail: TextView
    private lateinit var nodeLine: TextView
    private lateinit var radioLine: TextView
    private lateinit var wanLine: TextView
    private lateinit var relaySwitch: Switch
    private lateinit var relayLine: TextView
    private lateinit var peerBox: LinearLayout
    private lateinit var quietLine: TextView
    private lateinit var offerCard: LinearLayout
    private lateinit var offerCode: TextView
    private lateinit var offerExpiry: TextView
    private lateinit var offerText: TextView
    private lateinit var pairedBox: LinearLayout
    private var offer: JSONObject? = null        // the open pairing offer while this phone shows a code
    private var pairing: JSONObject? = null      // GET /api/pair/list
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var nodeId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(TextView(this).apply {
            text = "Omni Node"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Keeps you connected to people nearby, even when your other Omni apps are closed."
            textSize = 15f
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad * 2)
        })
        master = Switch(this).apply {
            text = "Network"
            textSize = 20f
            setTextColor(Color.WHITE)
            isChecked = NodePrefs.isOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> onSwitch(on) }
        }
        root.addView(master, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Status card: a colored dot + the plain status line + peers / daemon version.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
            background = GradientDrawable().apply {
                cornerRadius = 16 * resources.displayMetrics.density
                setColor(Color.parseColor("#1E1E1E"))
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#333333"))
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = TextView(this).apply { text = "\u25CF"; textSize = 22f; setPadding(0, 0, pad / 2, 0) }
        statusLine = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(statusDot)
        row.addView(statusLine)
        card.addView(row)
        statusDetail = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, pad / 4, 0, 0)
        }
        card.addView(statusDetail)
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = pad
            bottomMargin = pad / 2
        })
        nodeLine = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#808080"))
            setOnClickListener { copyNodeId() }
        }
        root.addView(nodeLine)
        root.addView(Button(this).apply {
            text = "Connect apps"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { unlockThenShowToken() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = pad
        })
        root.addView(TextView(this).apply {
            text = "KickBack, Mesh Monitor and other Omni apps need this phone's node token. It's behind your screen lock."
            textSize = 13f
            setTextColor(Color.parseColor("#808080"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, 0)
        })
        // ---- Radios + internet ----
        root.addView(section("Nearby radios", pad))
        radioLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setOnClickListener { askRadioPermissions() }
        }
        root.addView(radioLine, fullWidth())
        root.addView(section("Internet", pad))
        wanLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setOnLongClickListener { copyWan(); true }
        }
        root.addView(wanLine, fullWidth())
        // ---- Relay calls (daemon v8.22.0): opt-in, Wi-Fi + charging only ----
        root.addView(section("Relay calls", pad))
        relaySwitch = Switch(this).apply {
            text = "Help calls connect"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = NodeRelay.read(this@MainActivity).on
            setOnCheckedChangeListener { _, on -> setRelay(on) }
        }
        root.addView(relaySwitch, fullWidth())
        root.addView(TextView(this).apply {
            text = "When two people on your mesh are both on mobile data, their call can pass through this phone. " +
                "It only relays while the phone is charging on Wi-Fi, never on mobile data. Tap the line below to change the daily limit."
            textSize = 13f
            setTextColor(Color.parseColor("#808080"))
        }, fullWidth())
        relayLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, pad / 4, 0, 0)
            setOnClickListener { relayCapDialog() }
        }
        root.addView(relayLine, fullWidth())
        // ---- Add peer: addresses this node always syncs with (a friend's node, your PC, a server) ----
        root.addView(section("Peers", pad))
        root.addView(TextView(this).apply {
            text = "Add a friend's address (from their Internet line), your PC or a server. Your node keeps syncing with it over the internet. Tap one to remove it."
            textSize = 13f
            setTextColor(Color.parseColor("#808080"))
        }, fullWidth())
        peerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peerBox, fullWidth())
        root.addView(Button(this).apply {
            text = "Add peer"
            isAllCaps = false
            textSize = 16f
            setOnClickListener { addPeerDialog() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = pad / 3
        })
        // ---- Pair a device (daemon v8.24.0): code, enter a code, paired list, quiet mode ----
        root.addView(section(NodePairing.TITLE, pad))
        root.addView(TextView(this).apply {
            text = NodePairing.HINT
            textSize = 13f
            setTextColor(Color.parseColor("#808080"))
        }, fullWidth())
        quietLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#808080"))
            setPadding(0, pad / 4, 0, 0)
        }
        root.addView(quietLine, fullWidth())
        val pairButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pairButtons.addView(Button(this).apply {
            text = NodePairing.SHOW
            isAllCaps = false
            textSize = 15f
            setOnClickListener { showPairCode() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pairButtons.addView(Button(this).apply {
            text = NodePairing.ENTER
            isAllCaps = false
            textSize = 15f
            setOnClickListener { enterPairCodeDialog() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(pairButtons, fullWidth().apply { topMargin = pad / 3 })
        offerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
            background = GradientDrawable().apply {
                cornerRadius = 16 * resources.displayMetrics.density
                setColor(Color.parseColor("#1E1E1E"))
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#333333"))
            }
        }
        offerCode = TextView(this).apply {
            textSize = 34f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        offerExpiry = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#B0B0B0")) }
        offerText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#808080"))
            setTextIsSelectable(true)
            setPadding(0, pad / 3, 0, 0)
        }
        offerCard.addView(offerCode)
        offerCard.addView(offerExpiry)
        offerCard.addView(TextView(this).apply {
            text = NodePairing.CODE_HOWTO
            textSize = 13f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, pad / 4, 0, 0)
        })
        offerCard.addView(offerText)
        offerCard.addView(TextView(this).apply {
            text = NodePairing.TEXT_WARNING
            textSize = 12f
            setTextColor(Color.parseColor("#808080"))
        })
        val offerButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        offerButtons.addView(Button(this).apply {
            text = NodePairing.COPY
            isAllCaps = false
            setOnClickListener { copyPairText() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        offerButtons.addView(Button(this).apply {
            text = NodePairing.STOP
            isAllCaps = false
            setOnClickListener { stopPairCode() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        offerCard.addView(offerButtons)
        root.addView(offerCard, fullWidth().apply { topMargin = pad / 3 })
        pairedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairedBox, fullWidth())
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(pad, pad * 2, pad, pad * 2)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
            addView(root)
        })
        showPeers()
        showPairing()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun section(title: String, pad: Int) = TextView(this).apply {
        text = title
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(0, pad, 0, pad / 4)
        layoutParams = fullWidth()
    }

    // ---- Add peer ----

    private fun showPeers() {
        peerBox.removeAllViews()
        val states = radioStatus()?.optJSONArray("peers")
        for (peer in PeerList.read(this)) {
            var state = ""
            for (i in 0 until (states?.length() ?: 0)) {
                val o = states!!.getJSONObject(i)
                if (o.optString("peer") == "${peer.host}:${peer.port}") state = o.optString("state")
            }
            val label = when {
                state == "ok" -> "\u25CF  $peer  ·  syncing"
                state.isEmpty() -> "\u25CB  $peer  ·  waiting for the node"
                else -> "\u25CB  $peer  ·  $state"
            }
            peerBox.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor(if (state == "ok") "#4CAF50" else "#FFB74D"))
                setPadding(0, 12, 0, 12)
                setOnClickListener { removePeer(peer) }
            })
        }
    }

    private fun addPeerDialog() {
        val input = EditText(this).apply {
            hint = "203.0.113.7 or [2001:db8::1]:5005 or mypc.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input, fullWidth()) }
        AlertDialog.Builder(this)
            .setTitle("Add peer")
            .setMessage("Address of another node. Port 5005 unless you add one.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val peer = PeerList.parse(input.text.toString())
                if (peer == null) {
                    Toast.makeText(this, "That address doesn't look right.", Toast.LENGTH_LONG).show()
                } else {
                    PeerList.write(this, PeerList.read(this) + peer)
                    OmniDaemonService.peersChanged(this)
                    showPeers()
                    Toast.makeText(this, "Added. Your node will sync with $peer.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun removePeer(peer: PeerList.Peer) {
        AlertDialog.Builder(this)
            .setTitle("Remove $peer?")
            .setMessage("Your node stops syncing with it within two minutes.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Remove") { _, _ ->
                PeerList.write(this, PeerList.read(this).filter { it != peer })
                showPeers()
            }
            .show()
    }

    // ---- Pair a device (daemon v8.24.0) ----

    private fun showPairing() {
        val on = NodePrefs.isOn(this)
        val (qText, qColor) = if (on) NodePairing.quietLine(pairing?.optJSONObject("quiet"))
            else "Quiet mode: off (turn on Network first)" to "#808080"
        quietLine.text = qText
        quietLine.setTextColor(Color.parseColor(qColor))
        val o = if (on) offer else null
        if (o != null) {
            offerCode.text = NodePairing.codeDisplay(o.optString("code"))
            offerExpiry.text = NodePairing.expires(o.optInt("expires_in"), o.optInt("attempts_left"))
            offerText.text = o.optString("payload")
            offerCard.visibility = android.view.View.VISIBLE
        } else {
            offerCard.visibility = android.view.View.GONE
        }
        pairedBox.removeAllViews()
        val peers = pairing?.optJSONArray("peers")
        if (peers == null || peers.length() == 0) {
            pairedBox.addView(TextView(this).apply {
                text = NodePairing.NONE
                textSize = 15f
                setTextColor(Color.parseColor("#808080"))
                setPadding(0, 12, 0, 12)
            })
        } else {
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                pairedBox.addView(TextView(this).apply {
                    text = NodePairing.pairedLine(p)
                    textSize = 15f
                    setTextColor(Color.parseColor(if (p.optBoolean("online")) "#4CAF50" else "#B0B0B0"))
                    setPadding(0, 12, 0, 12)
                    setOnClickListener { removePaired(p) }
                })
            }
        }
    }

    /** Off the main thread: the paired list, and the offer's state while a code shows. */
    private fun fetchPairing() {
        val list = NodePairing.list(this)
        var ended: String? = null
        var newOffer = offer
        if (offer != null) {
            val st = NodePairing.offerStatus(this)
            if (st.code == 200 && st.body.optBoolean("active")) newOffer = st.body
            else if (st.code == 200) { newOffer = null; ended = st.body.optString("ended") }
        }
        ui.post {
            if (list.code == 200) pairing = list.body
            offer = newOffer
            showPairing()
            when (ended) {
                null -> {}
                "paired" -> toast(NodePairing.DONE)
                "locked" -> toast(NodePairing.LOCKED)
                else -> toast(NodePairing.ENDED)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun showPairCode() {
        if (!NodePrefs.isOn(this)) { toast("Turn on Network first."); return }
        io.execute {
            val r = NodePairing.offer(this)
            ui.post {
                if (r.code == 200 && r.body.optBoolean("active")) offer = r.body
                else toast(r.body.optString("message").ifEmpty { "The node answered ${r.code}" })
                showPairing()
            }
        }
    }

    private fun stopPairCode() {
        offer = null
        showPairing()
        io.execute { NodePairing.cancel(this) }
    }

    private fun copyPairText() {
        val payload = offer?.optString("payload").orEmpty()
        if (payload.isEmpty()) return
        val clip = ClipData.newPlainText("Omni pairing text", payload)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "Pairing text copied.", Toast.LENGTH_SHORT).show()
    }

    private fun enterPairCodeDialog() {
        if (!NodePrefs.isOn(this)) { toast("Turn on Network first."); return }
        val input = EditText(this).apply {
            hint = "123 456"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input, fullWidth()) }
        AlertDialog.Builder(this)
            .setTitle(NodePairing.ENTER)
            .setMessage(NodePairing.ENTER_HOWTO)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pair") { _, _ ->
                val body = NodePairing.parseInput(input.text.toString())
                if (body == null) {
                    toast("Type the 6-digit code, or paste the pairing text.")
                } else {
                    toast(NodePairing.LOOKING)
                    io.execute {
                        val r = NodePairing.accept(this, body)
                        ui.post {
                            toast(when (r.code) {
                                200 -> NodePairing.DONE
                                403 -> NodePairing.WRONG
                                410 -> NodePairing.LOCKED
                                else -> r.body.optString("message").ifEmpty { "The node answered ${r.code}" }
                            })
                        }
                        fetchPairing()
                    }
                }
            }
            .show()
    }

    private fun removePaired(p: JSONObject) {
        val name = p.optString("name").ifEmpty { "this device" }
        AlertDialog.Builder(this)
            .setTitle(NodePairing.REMOVE)
            .setMessage(NodePairing.removeConfirm(name))
            .setNegativeButton("Keep", null)
            .setPositiveButton(NodePairing.REMOVE) { _, _ ->
                io.execute {
                    val r = NodePairing.remove(this, p.optString("pubkey"))
                    if (r.code != 200) ui.post { toast(r.body.optString("message").ifEmpty { "The node answered ${r.code}" }) }
                    fetchPairing()
                }
            }
            .show()
    }

    // ---- Relay calls ----

    private fun setRelay(on: Boolean) {
        val s = NodeRelay.read(this)
        if (s.on == on) return
        NodeRelay.write(this, s.copy(on = on))
        OmniDaemonService.relayChanged(this)
        relayLine.text = if (on) "Checking Wi-Fi and charging..." else "Off"
    }

    private fun relayCapDialog() {
        val s = NodeRelay.read(this)
        val labels = NodeRelay.CAPS_MB.map { "Up to ${NodeRelay.capLabel(it)} a day" }.toTypedArray()
        val current = NodeRelay.CAPS_MB.indexOf(s.mbDay).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Daily relay limit")
            .setSingleChoiceItems(labels, current) { d, which ->
                NodeRelay.write(this, s.copy(mbDay = NodeRelay.CAPS_MB[which]))
                OmniDaemonService.relayChanged(this)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRelay(s: NodeStatus) {
        val set = NodeRelay.read(this)
        if (relaySwitch.isChecked != set.on) relaySwitch.isChecked = set.on
        val cap = NodeRelay.capLabel(set.mbDay)
        if (!NodePrefs.isOn(this)) { relayLine.text = "Off (turn on Network first)"; return }
        if (!set.on) { relayLine.text = "Off · limit $cap a day"; relayLine.setTextColor(Color.parseColor("#808080")); return }
        val st = NodeRelay.status(this)
        val r = s.relay
        val daemonMsg = st?.optString("daemon").orEmpty()
        relayLine.setTextColor(Color.parseColor("#B0B0B0"))
        relayLine.text = when {
            st == null -> "Checking Wi-Fi and charging..."
            !st.optBoolean("active") -> "Waiting: ${st.optString("reason").removePrefix("waiting ")} · limit $cap a day"
            daemonMsg.isNotEmpty() && daemonMsg != "ok" -> "Problem: $daemonMsg".also { relayLine.setTextColor(Color.parseColor("#FFB74D")) }
            r != null && r.optBoolean("enabled") -> {
                relayLine.setTextColor(Color.parseColor("#4CAF50"))
                val calls = r.optInt("allocations")
                val used = r.optDouble("mb_today", 0.0)
                val pub = r.optString("public").takeIf { it.isNotEmpty() && it != "null" }
                "Relaying · $calls active · ${"%.0f".format(used)} of $cap today" +
                    (if (pub == null) "\nOnly reachable on this Wi-Fi so far (router didn't open the ports)." else "")
            }
            else -> "Starting the relay..."
        }
    }

    // ---- Radios ----

    private fun radioStatus(): JSONObject? = try {
        JSONObject(NodeRadio.statusFile(this).readText())
    } catch (_: Exception) { null }

    private fun missingRadioPermissions() = NodeRadio.permissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun showRadios() {
        if (!NodePrefs.isOn(this)) { radioLine.text = "Off"; return }
        val missing = missingRadioPermissions()
        if (missing.isNotEmpty()) {
            radioLine.text = "Bluetooth and Wi-Fi Aware need the Nearby devices permission. Tap to allow."
            radioLine.setTextColor(Color.parseColor("#FFB74D"))
            return
        }
        radioLine.setTextColor(Color.parseColor("#B0B0B0"))
        val r = radioStatus()
        if (r == null) { radioLine.text = "Starting..."; return }
        val n = r.optInt("ble_nearby")
        radioLine.text = "Bluetooth: ${r.optString("ble")}" + (if (n > 0) " · $n nearby" else "") +
            "\nWi-Fi Aware: ${r.optString("wifi_aware")}"
    }

    private fun askRadioPermissions() {
        val missing = missingRadioPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_RADIOS)
    }

    // ---- Internet (from the daemon's node-health "wan" block, daemon v8.21.0+) ----

    private var wanAddr = ""

    private fun showWan(s: NodeStatus) {
        if (!NodePrefs.isOn(this)) { wanLine.text = "Off"; wanAddr = ""; return }
        val w = s.wan
        if (w == null) { wanLine.text = "Checking..."; return }
        val pub = w.optString("public_addr")
        val reachable = w.optBoolean("reachable")
        wanAddr = pub
        wanLine.text = when {
            reachable && pub.isNotEmpty() -> "Reachable at $pub\nLong-press to copy, then a friend adds it under Add peer."
            pub.isNotEmpty() -> "Your address is $pub, but the router blocks incoming connections (${w.optString("nat", "NAT")}).\nYou can still add a friend or server that is reachable."
            else -> w.optString("detail", "No internet path found yet.")
        }
    }

    private fun copyWan() {
        if (wanAddr.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Omni node address", wanAddr))
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        master.isChecked = NodePrefs.isOn(this)
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!NodePrefs.isOn(this@MainActivity)) {
                show(NodeStatus(NodeStatus.OFF))
                showWan(NodeStatus(NodeStatus.OFF)); showRadios(); showRelay(NodeStatus(NodeStatus.OFF)); showPairing()
            } else {
                io.execute {
                    val s = NodeStatus.fetch(this@MainActivity)
                    ui.post { show(s); showWan(s); showRadios(); showPeers(); showRelay(s) }
                    fetchPairing()
                }
            }
            ui.postDelayed(this, 3_000L)
        }
    }

    private fun show(s: NodeStatus) {
        statusLine.text = s.line
        val color = when {
            s.line == NodeStatus.OFF -> "#808080"
            s.line.startsWith("Problem") -> "#CF6679"
            s.peers > 0 -> "#4CAF50"
            s.line == NodeStatus.LOOKING -> "#03DAC6"
            else -> "#FFB74D"   // starting
        }
        statusDot.setTextColor(Color.parseColor(color))
        statusDetail.text = when {
            s.line == NodeStatus.OFF -> "Turn on Network to connect"
            s.version.isNotEmpty() -> "Running · ${s.peers} peer" + (if (s.peers == 1) "" else "s") + " · daemon v${s.version}"
            s.line.startsWith("Problem") -> "Turn Network off and on. If it stays, send a report."
            else -> "Starting the node..."
        }
        if (s.nodeId.isNotEmpty()) nodeId = s.nodeId
        nodeLine.text = if (nodeId.isEmpty()) "" else "Your node ${NodeStatus(s.line, nodeId).tag}  ·  tap to copy full key"
    }

    // ---- Connect apps: the local token, only after the phone's own lock (fingerprint, face, PIN) ----

    private fun unlockThenShowToken() {
        val token = NodeStatus.localToken(this)
        if (token == null) {
            Toast.makeText(this, "Turn on Network first; the token is made when the node starts.", Toast.LENGTH_LONG).show()
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) {
            AlertDialog.Builder(this)
                .setTitle("No screen lock")
                .setMessage("Set a screen lock (PIN, pattern or fingerprint) so only you can see this token. Show it anyway?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Show") { _, _ -> showToken(token) }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                BiometricPrompt.Builder(this)
                    .setTitle("Connect apps")
                    .setSubtitle("Unlock to see your node token")
                    .setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                    .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            showToken(token)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            // Cancelled or locked out: stay on the screen, never crash.
                        }
                    })
            } catch (e: Exception) {
                unlockWithScreenLock(km, token)
            }
        } else {
            unlockWithScreenLock(km, token)
        }
    }

    /** Fallback: the phone's own PIN/pattern screen. If even that is unavailable, show after the warning above. */
    private fun unlockWithScreenLock(km: KeyguardManager, token: String) {
        try {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Connect apps", "Unlock to see your node token")
            if (i == null) showToken(token) else startActivityForResult(i, REQ_UNLOCK)
        } catch (e: Exception) {
            showToken(token)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK && resultCode == RESULT_OK) NodeStatus.localToken(this)?.let { showToken(it) }
    }

    private fun showToken(token: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        body.addView(TextView(this).apply {
            text = "In KickBack or Mesh Monitor open Hub settings, choose Remote hub, type 127.0.0.1 as the address and paste this token. Don't share it: any app with it can use your node."
            textSize = 14f
        })
        body.addView(TextView(this).apply {
            text = token
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Your node token")
            .setView(body)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy token") { _, _ -> copyToken(token) }
            .show()
    }

    private fun copyToken(token: String) {
        val clip = ClipData.newPlainText("Omni node token", token)
        // Keeps it out of the keyboard's clipboard preview on Android 13+.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "Token copied. Paste it in the app's Hub settings.", Toast.LENGTH_LONG).show()
    }

    private fun onSwitch(on: Boolean) {
        if (on == NodePrefs.isOn(this)) return
        if (on) {
            NodePrefs.setOn(this, true)
            askNotificationsThen { OmniDaemonService.start(this); askBatteryOnce() }
        } else {
            OmniDaemonService.stop(this)
            show(NodeStatus(NodeStatus.OFF))
        }
    }

    private var afterNotifications: (() -> Unit)? = null

    private fun askNotificationsThen(next: () -> Unit) {
        val ask = ArrayList(missingRadioPermissions())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask += Manifest.permission.POST_NOTIFICATIONS
        if (ask.isNotEmpty()) {
            afterNotifications = next
            requestPermissions(ask.toTypedArray(), 1)
        } else {
            next()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The node runs either way; without the permission the status just isn't in the shade.
        if (requestCode == REQ_RADIOS) { OmniDaemonService.radiosChanged(this); return }
        afterNotifications?.invoke()
        afterNotifications = null
    }

    /** Asked once, in plain words, and only after the person turned the node on. */
    private fun askBatteryOnce() {
        if (NodePrefs.askedBattery(this)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        NodePrefs.setAskedBattery(this)
        AlertDialog.Builder(this)
            .setTitle("Stay connected in the background?")
            .setMessage("Android may pause Omni Node to save battery, which drops your connections. " +
                "Allow it to keep running? You can change this anytime in Settings.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .show()
    }

    private fun copyNodeId() {
        if (nodeId.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Omni node key", nodeId))
        Toast.makeText(this, "Node key copied", Toast.LENGTH_SHORT).show()
    }

    companion object { private const val REQ_UNLOCK = 7; private const val REQ_RADIOS = 8 }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
