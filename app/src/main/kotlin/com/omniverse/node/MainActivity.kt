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
        setContentView(root)
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
            } else {
                io.execute {
                    val s = NodeStatus.fetch(this@MainActivity)
                    ui.post { show(s) }
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
                })
        } else {
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Connect apps", "Unlock to see your node token")
            if (i == null) showToken(token) else startActivityForResult(i, REQ_UNLOCK)
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
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            afterNotifications = next
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            next()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The node runs either way; without the permission the status just isn't in the shade.
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

    companion object { private const val REQ_UNLOCK = 7 }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
