package com.omniverse.node

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The phone's radios, owned by Omni Node so every Omni app shares them (moved from the superapp's
 * OmniRadioDiscovery, same wire format so Node and older superapp builds still see each other).
 *
 * - BLE: advertises UUID 0xFFA0 with the first 16 bytes of the node key, scans for the same.
 *   Proximity only (BLE gives no IP), shown as "nearby" in the status.
 * - Wi-Fi Aware: publish + subscribe "omniverse", info {"h": key, "p": port}; on Android 10+ a
 *   PSK data path gives the peer's IPv6 address, which is handed to the daemon.
 * - Added peers (the Add peer list) are handed to the daemon too.
 * Every peer with an address is re-posted to loopback POST /api/sync/peers every 60 s (the daemon
 * forgets radio peers after 120 s), with the local token.
 * Status goes to files/radio_status.json for the screen (it runs in another process).
 */
@SuppressLint("MissingPermission")
class NodeRadio(private val context: Context) {

    companion object {
        private val SERVICE_UUID = ParcelUuid(UUID.fromString("0000FFA0-0000-1000-8000-00805F9B34FB"))
        private const val AWARE_SERVICE = "omniverse"
        private const val AWARE_PSK = "omniverse-mesh-dev"
        private const val REFRESH_MS = 60_000L
        private const val BLE_FRESH_MS = 90_000L

        fun statusFile(c: Context) = File(c.filesDir, "radio_status.json")

        /** Runtime permissions the radios need on this Android version. */
        fun permissions(): Array<String> {
            val p = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 31) {
                p += listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE",
                    "android.permission.BLUETOOTH_CONNECT")
            }
            if (Build.VERSION.SDK_INT >= 33) p += "android.permission.NEARBY_WIFI_DEVICES"
            else p += "android.permission.ACCESS_FINE_LOCATION"
            if (Build.VERSION.SDK_INT < 31) p += "android.permission.ACCESS_FINE_LOCATION"
            return p.distinct().toTypedArray()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var running = false
    private var nodeKey = ""
    private var port = 5005
    private val errors = ArrayList<String>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var bleAdvertising = false
    private var bleScanning = false
    private val bleSeen = ConcurrentHashMap<String, Long>()       // key prefix -> last seen ms

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var awareState = "idle"
    private val networkCallbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private val requestedPeers = HashSet<PeerHandle>()
    private val peerInfo = HashMap<PeerHandle, Pair<String, Int?>>()
    private var messageId = 1

    /** host -> (port, transport, key) of every peer with an address we keep telling the daemon about. */
    private val addressed = ConcurrentHashMap<String, Triple<Int, String, String>>()
    private val handedIn = ConcurrentHashMap<String, String>()     // "host:port" -> "ok" / error

    fun start(key: String, p: Int = 5005) {
        if (running) return
        nodeKey = key.lowercase()
        port = p
        running = true
        safe("ble") { startBle() }
        safe("wifi_aware") { startAware() }
        main.post(refresh)
    }

    fun stop() {
        running = false
        main.removeCallbacks(refresh)
        safe("ble stop") { stopBle() }
        safe("wifi_aware stop") { stopAware() }
        writeStatus()
        io.shutdown()
    }

    /** Called when the Add peer list changes: hand the list in now. */
    fun peersChanged() = main.post { main.removeCallbacks(refresh); refresh.run() }

    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            val manual = PeerList.read(context)
            io.execute {
                for (m in manual) post(m.host, m.port, "manual", "")
                for ((host, v) in addressed) post(host, v.first, v.second, v.third)
                writeStatus()
            }
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun post(host: String, p: Int, transport: String, key: String) {
        val token = NodeStatus.localToken(context) ?: return
        val id = "$host:$p"
        handedIn[id] = try {
            val c = URL("http://127.0.0.1:5000/api/sync/peers").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 4000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Omni-Local-Token", token)
            val body = JSONObject().put("host", host).put("port", p).put("transport", transport)
            if (key.isNotEmpty()) body.put("node_hash", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 200) "ok" else "daemon answered $code: " +
                (c.errorStream?.bufferedReader()?.readText()?.take(120) ?: "")
        } catch (e: Exception) {
            "not handed in (${e.javaClass.simpleName})"
        }
    }

    private fun addAddressed(host: String, p: Int, transport: String, key: String) {
        val isNew = addressed.put(host, Triple(p, transport, key)) == null
        if (isNew) io.execute { post(host, p, transport, key); writeStatus() }
    }

    // ---- status for the screen ----

    @Synchronized
    private fun writeStatus() {
        try {
            val now = System.currentTimeMillis()
            val nearby = bleSeen.filterValues { now - it < BLE_FRESH_MS }.size
            val o = JSONObject()
                .put("running", running)
                .put("ble", when {
                    bleAdvertising && bleScanning -> "on"
                    bleScanning -> "scanning only"
                    else -> errors.lastOrNull { it.startsWith("ble") }?.removePrefix("ble: ") ?: "off"
                })
                .put("ble_nearby", nearby)
                .put("wifi_aware", awareState)
                .put("missing_permissions", JSONArray(missing().toList()))
                .put("peers", JSONArray().apply {
                    handedIn.forEach { (k, v) -> put(JSONObject().put("peer", k).put("state", v)) }
                })
                .put("errors", JSONArray(synchronized(errors) { errors.takeLast(5) }))
            val f = statusFile(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    private fun missing(): List<String> = permissions().filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { err("$what: ${t.message}") }
    }

    private fun err(msg: String) {
        synchronized(errors) {
            errors.add(msg)
            while (errors.size > 20) errors.removeAt(0)
        }
    }

    // ---- BLE ----

    private fun keyPrefix(): ByteArray {
        val clean = nodeKey.filter { it.isDigit() || it in 'a'..'f' }
        val out = ByteArray(16)
        var i = 0
        while (i < 16 && i * 2 + 1 < clean.length) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte(); i++
        }
        return out
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { bleAdvertising = true; writeStatus() }
        override fun onStartFailure(errorCode: Int) { bleAdvertising = false; err("ble: advertise failed ($errorCode)") }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) = seen(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>?) { results?.forEach { seen(it) } }
        override fun onScanFailed(errorCode: Int) { bleScanning = false; err("ble: scan failed ($errorCode)") }
    }

    private fun seen(r: ScanResult?) {
        val data = r?.scanRecord?.getServiceData(SERVICE_UUID) ?: return
        val hex = data.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (nodeKey.startsWith(hex)) return
        bleSeen[hex] = System.currentTimeMillis()
    }

    private fun startBle() {
        val need = if (Build.VERSION.SDK_INT >= 31)
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE")
        else listOf("android.permission.ACCESS_FINE_LOCATION")
        if (need.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            err("ble: needs Nearby devices permission"); return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { err("ble: Bluetooth is off"); return }
        advertiser = adapter.bluetoothLeAdvertiser
        advertiser?.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false).build(),
            AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addServiceUuid(SERVICE_UUID).addServiceData(SERVICE_UUID, keyPrefix()).build(),
            advertiseCallback
        ) ?: err("ble: advertising not supported on this phone")
        scanner = adapter.bluetoothLeScanner
        scanner?.let {
            it.startScan(listOf(ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback)
            bleScanning = true
        }
    }

    private fun stopBle() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Throwable) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        advertiser = null; scanner = null; bleAdvertising = false; bleScanning = false
    }

    // ---- Wi-Fi Aware ----

    private fun localInfo() = JSONObject().put("h", nodeKey).put("p", port).toString().toByteArray()

    private fun parseInfo(b: ByteArray?): Pair<String, Int?>? = try {
        val o = JSONObject(String(b ?: return null))
        val h = o.optString("h", "")
        if (h.isEmpty()) null else Pair(h, if (o.has("p")) o.optInt("p") else null)
    } catch (_: Exception) { null }

    private fun startAware() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            awareState = "not on this phone"; return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.NEARBY_WIFI_DEVICES"
        else "android.permission.ACCESS_FINE_LOCATION"
        if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            awareState = "needs Nearby devices permission"; return
        }
        val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (mgr == null || !mgr.isAvailable) { awareState = "unavailable (Wi-Fi off?)"; return }
        awareState = "starting"
        mgr.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                if (!running) { session.close(); return }
                awareSession = session
                safe("wifi_aware publish") { publish(session) }
                safe("wifi_aware subscribe") { subscribe(session) }
            }
            override fun onAttachFailed() { awareState = "attach failed" }
        }, main)
    }

    private fun publish(session: WifiAwareSession) {
        session.publish(PublishConfig.Builder().setServiceName(AWARE_SERVICE).setServiceSpecificInfo(localInfo()).build(),
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(s: PublishDiscoverySession) { publishSession = s; awareState = "on" }
                override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                    parseInfo(message)?.let { peerInfo[peer] = it }
                    publishSession?.let { dataPath(it, peer) }
                }
                override fun onSessionTerminated() { publishSession = null }
            }, main)
    }

    private fun subscribe(session: WifiAwareSession) {
        session.subscribe(SubscribeConfig.Builder().setServiceName(AWARE_SERVICE).build(),
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(s: SubscribeDiscoverySession) { subscribeSession = s; awareState = "on" }
                override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, mf: MutableList<ByteArray>?) {
                    val i = parseInfo(info) ?: return
                    if (i.first == nodeKey) return
                    peerInfo[peer] = i
                    val s = subscribeSession ?: return
                    safe("wifi_aware sendMessage") { s.sendMessage(peer, messageId++, localInfo()) }
                    dataPath(s, peer)
                }
                override fun onSessionTerminated() { subscribeSession = null }
            }, main)
    }

    @SuppressLint("NewApi")
    private fun dataPath(session: DiscoverySession, peer: PeerHandle) {
        if (Build.VERSION.SDK_INT < 29 || !requestedPeers.add(peer)) return
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(WifiAwareNetworkSpecifier.Builder(session, peer).setPskPassphrase(AWARE_PSK).build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val addr = info.peerIpv6Addr ?: return
                    val known = peerInfo[peer] ?: return
                    val p = if (info.port > 0) info.port else (known.second ?: 5005)
                    addAddressed(addr.hostAddress ?: return, p, "wifi_aware", known.first)
                }
                override fun onUnavailable() { requestedPeers.remove(peer); err("wifi_aware: data path unavailable") }
                override fun onLost(network: Network) { requestedPeers.remove(peer) }
            }
            networkCallbacks.add(cb)
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).requestNetwork(req, cb)
        } catch (t: Throwable) {
            requestedPeers.remove(peer); err("wifi_aware data path: ${t.message}")
        }
    }

    private fun stopAware() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallbacks.forEach { try { cm?.unregisterNetworkCallback(it) } catch (_: Throwable) {} }
        networkCallbacks.clear(); requestedPeers.clear(); peerInfo.clear()
        try { publishSession?.close() } catch (_: Throwable) {}
        try { subscribeSession?.close() } catch (_: Throwable) {}
        try { awareSession?.close() } catch (_: Throwable) {}
        publishSession = null; subscribeSession = null; awareSession = null; awareState = "off"
    }
}

/** The Add peer list: one "host[:port]" per line in files/peers.txt (shared by both processes). */
object PeerList {
    data class Peer(val host: String, val port: Int) {
        override fun toString() = if (port == 5005) host else (if (host.contains(':')) "[$host]:$port" else "$host:$port")
    }

    private fun file(c: Context) = File(c.filesDir, "peers.txt")

    fun read(c: Context): List<Peer> = try {
        file(c).readLines().mapNotNull { parse(it) }
    } catch (_: Exception) { emptyList() }

    fun write(c: Context, peers: List<Peer>) = file(c).writeText(peers.distinct().joinToString("\n") { it.toString() })

    /** "1.2.3.4", "1.2.3.4:5006", "host.example.com", "[2001:db8::1]:5005", "2001:db8::1". Null if malformed. */
    fun parse(raw: String): Peer? {
        val s = raw.trim().removePrefix("http://").removeSuffix("/")
        if (s.isEmpty()) return null
        var host = s
        var port = 5005
        if (s.startsWith("[")) {
            val end = s.indexOf(']'); if (end < 0) return null
            host = s.substring(1, end)
            val rest = s.substring(end + 1)
            if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull() ?: return null
        } else if (s.count { it == ':' } == 1) {
            host = s.substringBefore(':'); port = s.substringAfter(':').toIntOrNull() ?: return null
        }
        if (port !in 1..65535) return null
        if (!Regex("[A-Za-z0-9.\\-:%_]{1,255}").matches(host)) return null
        return Peer(host, port)
    }
}
