package com.omniverse.node

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** One screen, one switch, one status line. */
class MainActivity : Activity() {
    private lateinit var master: Switch
    private lateinit var statusLine: TextView
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
        statusLine = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#03DAC6"))
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(statusLine)
        nodeLine = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#808080"))
            setOnClickListener { copyNodeId() }
        }
        root.addView(nodeLine)
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
        if (s.nodeId.isNotEmpty()) nodeId = s.nodeId
        nodeLine.text = if (nodeId.isEmpty()) "" else "Your node ${NodeStatus(s.line, nodeId).tag}  ·  tap to copy full key" +
            (if (s.version.isNotEmpty()) "\nDaemon v${s.version}" else "")
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

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
