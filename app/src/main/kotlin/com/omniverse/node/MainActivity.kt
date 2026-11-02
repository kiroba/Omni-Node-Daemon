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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The Omni Node screen: a thin Android shell around the shared core (omni_node_core through
 * Chaquopy, see NodeCore). It renders NodeCore.state() every 3 s and sends every tap to NodeCore.act().
 * No status logic and no words live here: every string on screen comes from the core's ui_state()
 * (CI's parity check fails if a .kt file spells out Node words). What stays is Android: views, dialogs,
 * the screen lock before the token, permissions, the battery prompt, the clipboard and starting or
 * stopping OmniDaemonService when the core says so.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val reads = Executors.newSingleThreadExecutor()     // ui_state refreshes
    private val acts = Executors.newSingleThreadExecutor()      // actions (pairing can take 45 s)
    private var state = JSONObject()
    private var words = JSONObject()
    private var built = false
    private var painting = false
    private var reading = false
    private var pad = 0

    private lateinit var root: LinearLayout
    private lateinit var master: Switch
    private lateinit var statusDot: TextView
    private lateinit var statusLine: TextView
    private lateinit var statusDetail: TextView
    private lateinit var nodeLine: TextView
    private lateinit var versionLine: TextView
    private lateinit var connectHint: TextView
    private lateinit var radioTitle: TextView
    private lateinit var radioLine: TextView
    private lateinit var wanLine: TextView
    private lateinit var internetSwitch: Switch
    private lateinit var relaySwitch: Switch
    private lateinit var relayHint: TextView
    private lateinit var relayLine: TextView
    private lateinit var peersHint: TextView
    private lateinit var peerBox: LinearLayout
    private lateinit var quietLine: TextView
    private lateinit var offerCard: LinearLayout
    private lateinit var offerCode: TextView
    private lateinit var offerExpiry: TextView
    private lateinit var offerText: TextView
    private lateinit var pairedBox: LinearLayout
    private lateinit var updateLine: TextView
    private lateinit var updateGet: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (24 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad * 2)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(root)
        })
        load(refresh = false)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        reads.shutdownNow()
        acts.shutdownNow()
        super.onDestroy()
    }

    private val refresh = object : Runnable {
        override fun run() {
            load(refresh = true)
            ui.postDelayed(this, 3_000L)
        }
    }

    /** Off the main thread: ask the core for the screen, then paint it. */
    private fun load(refresh: Boolean) {
        if (reading) return
        reading = true
        reads.execute {
            val s = try { NodeCore.state(this, refresh) } catch (t: Throwable) { JSONObject().put("error", t.toString()) }
            ui.post {
                reading = false
                if (s.has("error")) showError(s.optString("error")) else render(s)
            }
        }
    }

    private fun showError(text: String) {
        if (built) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); return }
        root.removeAllViews()
        root.addView(TextView(this).apply { this.text = text; setTextColor(Color.WHITE) })
    }

    private fun w(key: String) = words.optString(key)
    private fun color(hex: String?) = try { Color.parseColor(hex) } catch (_: Exception) { Color.WHITE }
    private fun c(key: String) = color(state.optJSONObject("colors")?.optString(key))

    // ---- building the views once, from the first state ----

    private fun build(s: JSONObject) {
        state = s
        words = s.getJSONObject("words")
        root.removeAllViews()
        (root.parent as? View)?.setBackgroundColor(c("bg"))
        root.addView(text(s.optString("title"), 26f, c("text"), bold = true))
        root.addView(text(s.optString("subtitle"), 15f, c("text_2")).apply {
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad * 2)
        })
        master = Switch(this).apply {
            text = s.getJSONObject("network").optString("label")
            textSize = 20f
            setTextColor(c("text"))
            setOnCheckedChangeListener { _, on -> if (!painting) act("network", JSONObject().put("on", on)) }
        }
        root.addView(master, full())
        // Status card: a coloured dot + the one status line + detail, node key, versions.
        val card = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = TextView(this).apply { text = "\u25CF"; textSize = 22f; setPadding(0, 0, pad / 2, 0) }
        statusLine = text("", 18f, c("text"), bold = true)
        row.addView(statusDot)
        row.addView(statusLine)
        card.addView(row)
        statusDetail = text("", 14f, c("text_2")).apply { setPadding(0, pad / 4, 0, 0) }
        card.addView(statusDetail)
        versionLine = text("", 12f, c("text_3"))
        card.addView(versionLine)
        root.addView(card, full().apply { topMargin = pad; bottomMargin = pad / 2 })
        nodeLine = text("", 13f, c("text_3")).apply { setOnClickListener { act("copy_node_key") } }
        root.addView(nodeLine, full())
        root.addView(button(s.getJSONObject("connect").optString("button")) { connectApps() },
            full().apply { topMargin = pad })
        connectHint = text("", 13f, c("text_3")).apply { gravity = Gravity.CENTER; setPadding(0, pad / 3, 0, 0) }
        root.addView(connectHint, full())
        // Nearby radios (phones)
        radioTitle = section(w("RADIOS_TITLE"))
        root.addView(radioTitle)
        radioLine = text("", 14f, c("text_2")).apply { setOnClickListener { askRadioPermissionsIfNeeded() } }
        root.addView(radioLine, full())
        // Internet
        val inet = s.getJSONObject("internet")
        root.addView(section(inet.optString("title")))
        wanLine = text("", 14f, c("text_2")).apply { setOnLongClickListener { act("copy_address"); true } }
        root.addView(wanLine, full())
        internetSwitch = switch(inet.optString("switch")) { on -> act("internet", JSONObject().put("on", on)) }
        root.addView(internetSwitch, full())
        root.addView(text(inet.optString("hint"), 13f, c("text_3")), full())
        // Relay calls
        val relay = s.getJSONObject("relay")
        root.addView(section(relay.optString("section")))
        relaySwitch = switch(relay.optString("title")) { on -> act("relay", JSONObject().put("on", on)) }
        root.addView(relaySwitch, full())
        relayHint = text("", 13f, c("text_3"))
        root.addView(relayHint, full())
        relayLine = text("", 14f, c("text_2")).apply {
            setPadding(0, pad / 4, 0, 0)
            setOnClickListener { relayCapDialog() }
        }
        root.addView(relayLine, full())
        // Peers
        val peers = s.getJSONObject("peers")
        root.addView(section(peers.optString("title")))
        peersHint = text("", 13f, c("text_3"))
        root.addView(peersHint, full())
        peerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peerBox, full())
        root.addView(button(peers.optString("button")) { addPeerDialog() }, full().apply { topMargin = pad / 3 })
        // Pair a device
        val pr = s.getJSONObject("pairing")
        root.addView(section(pr.optString("title")))
        root.addView(text(pr.optString("hint"), 13f, c("text_3")), full())
        quietLine = text("", 14f, c("text_3")).apply { setPadding(0, pad / 4, 0, 0) }
        root.addView(quietLine, full())
        val pairButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pairButtons.addView(button(w("PAIR_SHOW")) { act("pair_offer") }, weight())
        pairButtons.addView(button(w("PAIR_ENTER")) { enterPairCodeDialog() }, weight())
        root.addView(pairButtons, full().apply { topMargin = pad / 3 })
        offerCard = card().apply { visibility = View.GONE }
        offerCode = text("", 34f, c("text"), bold = true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        offerExpiry = text("", 13f, c("text_2"))
        offerText = text("", 11f, c("text_3")).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad / 3, 0, 0)
        }
        offerCard.addView(offerCode)
        offerCard.addView(offerExpiry)
        offerCard.addView(text(w("PAIR_CODE_HOWTO"), 13f, c("text_2")).apply { setPadding(0, pad / 4, 0, 0) })
        offerCard.addView(offerText)
        offerCard.addView(text(w("PAIR_TEXT_WARNING"), 12f, c("text_3")))
        val offerButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        offerButtons.addView(button(w("PAIR_COPY")) { act("copy_pair_text") }, weight())
        offerButtons.addView(button(w("PAIR_STOP")) { act("pair_cancel") }, weight())
        offerCard.addView(offerButtons)
        root.addView(offerCard, full().apply { topMargin = pad / 3 })
        pairedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairedBox, full())
        // Updates
        val upd = s.getJSONObject("update")
        root.addView(section(upd.optString("title")))
        val urow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urow.addView(button(upd.optString("button")) { act("check_updates") }, weight())
        updateGet = button(upd.optString("get")) { openUrl(state.optJSONObject("update")?.optString("url").orEmpty()) }
        urow.addView(updateGet, weight())
        root.addView(urow, full())
        updateLine = text("", 14f, c("text_2"))
        root.addView(updateLine, full())
        built = true
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun text(t: String, size: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(col)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String) = text(title, 17f, c("text"), bold = true).apply {
        setPadding(0, pad, 0, pad / 4)
        layoutParams = full()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 16f
        setTextColor(c("text"))
        setOnCheckedChangeListener { _, on -> if (!painting) onChange(on) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
        background = GradientDrawable().apply {
            cornerRadius = 16 * resources.displayMetrics.density
            setColor(c("card"))
            setStroke((1 * resources.displayMetrics.density).toInt(), c("card_edge"))
        }
    }

    // ---- painting every state ----

    private fun render(s: JSONObject) {
        if (!built) build(s)
        state = s
        words = s.optJSONObject("words") ?: words
        painting = true
        try {
            master.isChecked = s.getJSONObject("network").optBoolean("on")
            val st = s.getJSONObject("status")
            statusLine.text = st.optString("line")
            statusDot.setTextColor(color(st.optString("color")))
            statusDetail.text = st.optString("detail")
            nodeLine.text = st.optString("node_line")
            versionLine.text = s.optString("version_line")
            connectHint.text = s.getJSONObject("connect").optString("hint")
            val radios = s.optJSONObject("radios")
            radioTitle.visibility = if (radios == null) View.GONE else View.VISIBLE
            radioLine.visibility = radioTitle.visibility
            if (radios != null) {
                radioLine.text = radios.optString("line")
                radioLine.setTextColor(color(radios.optString("color")))
            }
            val inet = s.getJSONObject("internet")
            wanLine.text = inet.optString("line")
            wanLine.setTextColor(color(inet.optString("color")))
            internetSwitch.isChecked = inet.optBoolean("on")
            val relay = s.getJSONObject("relay")
            relaySwitch.isChecked = relay.optBoolean("on")
            relayHint.text = relay.optString("hint")
            relayLine.text = relay.optString("line")
            relayLine.setTextColor(color(relay.optString("color")))
            val peers = s.getJSONObject("peers")
            peersHint.text = peers.optString("hint")
            paintPeers(peers.optJSONArray("items") ?: JSONArray())
            paintPairing(s.getJSONObject("pairing"))
            val upd = s.getJSONObject("update")
            updateLine.text = upd.optString("line")
            updateLine.setTextColor(color(upd.optString("color")))
            updateGet.visibility = if (upd.optString("url").isEmpty()) View.GONE else View.VISIBLE
        } finally {
            painting = false
        }
        val toasts = s.optJSONArray("toasts") ?: JSONArray()
        for (i in 0 until toasts.length()) toast(toasts.optString(i))
    }

    private fun paintPeers(items: JSONArray) {
        peerBox.removeAllViews()
        for (i in 0 until items.length()) {
            val p = items.getJSONObject(i)
            peerBox.addView(text(p.optString("label"), 15f, color(p.optString("color"))).apply {
                setPadding(0, 12, 0, 12)
                setOnClickListener { removePeer(p) }
            })
        }
    }

    private fun paintPairing(pr: JSONObject) {
        quietLine.text = pr.optString("quiet_line")
        quietLine.setTextColor(color(pr.optString("quiet_color")))
        val o = pr.optJSONObject("offer")
        if (o != null) {
            offerCode.text = o.optString("code")
            offerExpiry.text = o.optString("expiry")
            offerText.text = o.optString("payload")
            offerCard.visibility = View.VISIBLE
        } else {
            offerCard.visibility = View.GONE
        }
        pairedBox.removeAllViews()
        val paired = pr.optJSONArray("paired") ?: JSONArray()
        if (paired.length() == 0) {
            pairedBox.addView(text(pr.optString("none"), 15f, c("text_3")).apply { setPadding(0, 12, 0, 12) })
        }
        for (i in 0 until paired.length()) {
            val p = paired.getJSONObject(i)
            pairedBox.addView(text(p.optString("line"), 15f, color(p.optString("color"))).apply {
                setPadding(0, 12, 0, 12)
                setOnClickListener { removePaired(p) }
            })
        }
    }

    // ---- actions: the core decides; this applies the Android effects ----

    private fun act(action: String, args: JSONObject = JSONObject(), after: ((JSONObject) -> Unit)? = null) {
        acts.execute {
            val r = try { NodeCore.act(this, action, args) } catch (t: Throwable) { JSONObject().put("toast", t.toString()) }
            ui.post {
                applyResult(r)
                after?.invoke(r)
                load(refresh = false)
            }
        }
    }

    private fun applyResult(r: JSONObject) {
        val effects = r.optJSONArray("effects") ?: JSONArray()
        for (i in 0 until effects.length()) {
            when (effects.optString(i)) {
                "start_node" -> askPermissionsThen { OmniDaemonService.start(this); askBatteryOnce() }
                "stop_node" -> OmniDaemonService.stop(this)
                "settings_changed" -> OmniDaemonService.kick(this)
            }
        }
        val copy = r.optString("copy")
        if (copy.isNotEmpty()) copyText(copy, r.optBoolean("sensitive"))
        val url = r.optString("url")
        if (url.isNotEmpty() && r.optString("toast").isNotEmpty()) updateGet.visibility = View.VISIBLE
        toast(r.optString("toast"))
    }

    private fun toast(msg: String) {
        if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun copyText(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(w("TITLE"), text)
        // Keeps the token and pairing text out of the keyboard's clipboard preview on Android 13+.
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun openUrl(url: String) {
        if (url.isEmpty()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun inputDialog(title: String, message: String, hint: String, ok: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val p = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { setPadding(p, p / 2, p, 0); addView(input, full()) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setNegativeButton(w("CANCEL"), null)
            .setPositiveButton(ok) { _, _ -> onOk(input.text.toString()) }
            .show()
    }

    private fun confirm(title: String, message: String, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(w("KEEP"), null)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .show()
    }

    private fun addPeerDialog() = inputDialog(w("ADD_PEER"), w("ADD_PEER_HOWTO"), w("ADD_PEER_EXAMPLE"), w("ADD")) {
        act("add_peer", JSONObject().put("text", it))
    }

    private fun removePeer(p: JSONObject) = confirm(p.optString("remove_title"), p.optString("remove_body"), w("PAIR_REMOVE")) {
        act("remove_peer", JSONObject().put("key", p.optString("key")))
    }

    private fun enterPairCodeDialog() {
        if (!state.getJSONObject("network").optBoolean("on")) { toast(w("NETWORK_FIRST")); return }
        inputDialog(w("PAIR_ENTER"), w("PAIR_ENTER_HOWTO"), w("PAIR_EXAMPLE"), w("PAIR")) {
            toast(w("PAIR_LOOKING"))
            act("pair_accept", JSONObject().put("text", it))
        }
    }

    private fun removePaired(p: JSONObject) = confirm(w("PAIR_REMOVE"), p.optString("confirm"), w("PAIR_REMOVE")) {
        act("pair_remove", JSONObject().put("pubkey", p.optString("pubkey")))
    }

    private fun relayCapDialog() {
        val relay = state.optJSONObject("relay") ?: return
        val caps = relay.optJSONArray("caps") ?: return
        val labels = Array(caps.length()) { caps.getJSONObject(it).optString("label") }
        val mbs = IntArray(caps.length()) { caps.getJSONObject(it).optInt("mb") }
        val current = mbs.indexOf(relay.optInt("cap_mb")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(relay.optString("cap_title"))
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                act("relay_cap", JSONObject().put("mb", mbs[which]))
            }
            .setNegativeButton(w("CANCEL"), null)
            .show()
    }

    // ---- Connect apps: the token, only after the phone's own lock (fingerprint, face, PIN) ----

    private fun connectApps() = act("token") { r -> if (r.optBoolean("ok")) unlockThenShow(r) }

    private fun unlockThenShow(r: JSONObject) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) {
            AlertDialog.Builder(this)
                .setTitle(w("NO_LOCK_TITLE"))
                .setMessage(w("NO_LOCK_BODY"))
                .setNegativeButton(w("CANCEL"), null)
                .setPositiveButton(w("SHOW")) { _, _ -> showToken(r) }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                BiometricPrompt.Builder(this)
                    .setTitle(r.optString("unlock_title"))
                    .setSubtitle(r.optString("unlock_subtitle"))
                    .setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                    .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            showToken(r)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            // Cancelled or locked out: stay on the screen, never crash.
                        }
                    })
            } catch (e: Exception) {
                unlockWithScreenLock(km, r)
            }
        } else {
            unlockWithScreenLock(km, r)
        }
    }

    private var pendingToken: JSONObject? = null

    /** Fallback: the phone's own PIN/pattern screen. If even that is unavailable, show after the warning above. */
    private fun unlockWithScreenLock(km: KeyguardManager, r: JSONObject) {
        try {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent(r.optString("unlock_title"), r.optString("unlock_subtitle"))
            if (i == null) showToken(r) else { pendingToken = r; startActivityForResult(i, REQ_UNLOCK) }
        } catch (e: Exception) {
            showToken(r)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK && resultCode == RESULT_OK) pendingToken?.let { showToken(it) }
        pendingToken = null
    }

    private fun showToken(r: JSONObject) {
        val p = (16 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0) }
        body.addView(TextView(this).apply { text = r.optString("howto"); textSize = 14f })
        body.addView(TextView(this).apply {
            text = r.optString("token")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, p, 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(r.optString("title"))
            .setView(body)
            .setNegativeButton(w("CLOSE"), null)
            .setPositiveButton(r.optString("copy_label")) { _, _ -> act("copy_token") }
            .show()
    }

    // ---- Android permissions and battery ----

    private var afterPermissions: (() -> Unit)? = null

    private fun askPermissionsThen(next: () -> Unit) {
        val ask = ArrayList(NodeEnv.missingRadioPermissions(this))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask += Manifest.permission.POST_NOTIFICATIONS
        if (ask.isNotEmpty()) {
            afterPermissions = next
            requestPermissions(ask.toTypedArray(), REQ_START)
        } else {
            next()
        }
    }

    private fun askRadioPermissionsIfNeeded() {
        if (state.optJSONObject("radios")?.optBoolean("ask_permission") != true) return
        val missing = NodeEnv.missingRadioPermissions(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_RADIOS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The node runs either way; without the permission the status just isn't in the shade.
        if (requestCode == REQ_RADIOS) { OmniDaemonService.radiosChanged(this); return }
        afterPermissions?.invoke()
        afterPermissions = null
    }

    /** Asked once, in plain words, and only after the person turned the node on. */
    private fun askBatteryOnce() {
        if (NodePrefs.askedBattery(this)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        NodePrefs.setAskedBattery(this)
        AlertDialog.Builder(this)
            .setTitle(w("BATTERY_TITLE"))
            .setMessage(w("BATTERY_BODY"))
            .setNegativeButton(w("NOT_NOW"), null)
            .setPositiveButton(w("ALLOW")) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .show()
    }

    companion object {
        private const val REQ_START = 1
        private const val REQ_UNLOCK = 7
        private const val REQ_RADIOS = 8
    }
}
