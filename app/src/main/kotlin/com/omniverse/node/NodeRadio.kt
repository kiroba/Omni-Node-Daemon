package com.omniverse.node

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import java.net.NetworkInterface
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The phone's radios, owned by Omni Node so every Omni app shares them (moved from the superapp's
 * OmniRadioDiscovery, same wire format so Node and older superapp builds still see each other).
 *
 * - BLE: advertises UUID 0xFFA0 with the first 16 bytes of the node key, scans for the same.
 *   The advertisement is connectable (daemon 8.33+): a GATT service (0xFFA1 / 0xFFA2) serves this
 *   node's signed introduction - minted by the daemon (GET /api/ble/introduction), because the
 *   node key never leaves it - and a newly seen peer is connected to once to read its blob,
 *   which is handed to the daemon (POST /api/ble/met) for verification. BLE still gives no IP;
 *   the blob's endpoints are where to dial when an IP path exists. Every stage of that exchange
 *   counts its outcome into the status file (ble_intro), so a failed introduction shows where
 *   it stopped instead of vanishing.
 * - Wi-Fi Aware: publish + subscribe "omniverse", info {"h": key, "p": port}; on Android 10+ a
 *   PSK data path gives the peer's IPv6 address, which is handed to the daemon.
 * Every radio peer with an address is re-posted to loopback POST /api/sync/peers every 60 s (the daemon
 * forgets radio peers after 120 s), with the local token. The fresh BLE sightings go to the daemon too
 * (POST /api/ble/nearby, daemon 8.32+): the daemon's BLE registry is what node-health "ble" reads, and
 * a sighting stays a hint there as well - it never makes a peer. Added peers (the Add peer list) are the
 * shared core's job now (omni_node_core duties()), the same code as on desktop.
 * Status goes to files/radio_status.json for the screen (it runs in another process).
 */
@SuppressLint("MissingPermission")
class NodeRadio(private val context: Context) {

    companion object {
        private val SERVICE_UUID = ParcelUuid(UUID.fromString("0000FFA0-0000-1000-8000-00805F9B34FB"))
        private val INTRO_SERVICE_UUID = UUID.fromString("0000FFA1-0000-1000-8000-00805F9B34FB")
        private val INTRO_CHAR_UUID = UUID.fromString("0000FFA2-0000-1000-8000-00805F9B34FB")
        private const val AWARE_SERVICE = "omniverse"
        private const val AWARE_PSK = "omniverse-mesh-dev"
        private const val REFRESH_MS = 60_000L
        private const val BLE_FRESH_MS = 90_000L
        private const val INTRO_RETRY_MS = 300_000L
        private const val GATT_TIMEOUT_MS = 12_000L

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

    private var gattServer: BluetoothGattServer? = null
    @Volatile private var introBlob: ByteArray? = null            // our signed introduction, served over GATT
    private val introTried = ConcurrentHashMap<String, Long>()    // key prefix -> last GATT attempt ms
    @Volatile private var activeGatt: BluetoothGatt? = null       // one client exchange at a time
    @Volatile private var metPosted = 0                           // introductions the daemon accepted

    // Introduction-path observability (radio_status.json "ble_intro"): every stage of the GATT
    // exchange counts its outcomes and remembers the most recent one, so a failed introduction
    // is visible on the node screen instead of vanishing - the hand-in used to swallow every
    // failure, which is how the first field test (Nov 12) failed without leaving a trace.
    @Volatile private var introAttempts = 0
    @Volatile private var introConnected = 0
    @Volatile private var introReadOk = 0
    @Volatile private var introServed = 0
    @Volatile private var introServedEmpty = 0
    @Volatile private var introLast: JSONObject? = null

    /** Stamps the most recent introduction outcome (stage + short detail) for the status file. */
    private fun introNote(stage: String, detail: String) {
        introLast = JSONObject()
            .put("stage", stage)
            .put("detail", detail.take(120))
            .put("at_ms", System.currentTimeMillis())
    }

    /** introNote for conditions that repeat every tick (blob fetch, closed gates): record on change only. */
    private fun introNoteOnce(stage: String, detail: String) {
        val last = introLast
        if (last != null && last.optString("stage") == stage && last.optString("detail") == detail) return
        introNote(stage, detail)
    }

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

    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            io.execute {
                for ((host, v) in addressed) post(host, v.first, v.second, v.third)
                postBleNearby()
                refreshIntroBlob()
                writeStatus()
            }
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun post(host: String, p: Int, transport: String, key: String) {
        val token = NodePrefs.localToken(context) ?: return
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

    /** Hands the fresh BLE sightings to the daemon (POST /api/ble/nearby, daemon 8.32+), in the
     *  registry's wire format: the key prefixes heard on service 0xFFA0, at most 64 per report.
     *  Fire-and-forget - an older daemon answers 404 and the node screen keeps its own count. */
    private fun postBleNearby() {
        val now = System.currentTimeMillis()
        val fresh = bleSeen.filterValues { now - it < BLE_FRESH_MS }.keys.sorted()
        if (fresh.isEmpty()) return
        val token = NodePrefs.localToken(context) ?: return
        try {
            val c = URL("http://127.0.0.1:5000/api/ble/nearby").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 4000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Omni-Local-Token", token)
            val body = JSONObject().put("seen", JSONArray(fresh.take(64)))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode
        } catch (_: Exception) {
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
                .put("ble_met", metPosted)
                .put("ble_intro", JSONObject()
                    .put("attempts", introAttempts)
                    .put("connected", introConnected)
                    .put("read_ok", introReadOk)
                    .put("posted", metPosted)
                    .put("served", introServed)
                    .put("served_empty", introServedEmpty)
                    .put("last", introLast ?: JSONObject.NULL))
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
        maybeIntro(r.device, hex)
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
                .setConnectable(true).build(),
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
        safe("ble gatt") { startGattServer() }
    }

    private fun stopBle() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Throwable) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        closeActiveGatt()
        try { gattServer?.close() } catch (_: Throwable) {}
        gattServer = null
        advertiser = null; scanner = null; bleAdvertising = false; bleScanning = false
    }

    // ---- BLE introductions (GATT): serve our signed blob, read the peer's ----

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            val blob = introBlob
            if (blob == null || characteristic.uuid != INTRO_CHAR_UUID) {
                if (blob == null) {
                    introServedEmpty++
                    introNoteOnce("served_empty", "a peer read our introduction before the first blob fetch succeeded")
                }
                try { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null) } catch (_: Throwable) {}
                return
            }
            if (offset == 0) introServed++
            val slice = if (offset >= blob.size) ByteArray(0) else blob.copyOfRange(offset, blob.size)
            try { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice) } catch (_: Throwable) {}
        }
    }

    private fun startGattServer() {
        if (!hasConnectPermission()) { err("ble: introductions need the Nearby devices (connect) permission"); return }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (mgr == null) { introNoteOnce("gatt_server_unavailable", "no BluetoothManager"); return }
        val server = mgr.openGattServer(context, gattServerCallback)
        if (server == null) { introNoteOnce("gatt_server_unavailable", "openGattServer returned null"); return }
        val ch = BluetoothGattCharacteristic(INTRO_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val svc = BluetoothGattService(INTRO_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        server.addService(svc)
        gattServer = server
    }

    /** Fetches this node's signed introduction from the daemon (GET /api/ble/introduction) and
     *  rebuilds the blob the GATT server hands out: short keys, our current LAN endpoints merged
     *  in (unsigned dial hints - the daemon signs the identity, not the endpoints). An older
     *  daemon answers 404; then there is simply nothing to serve. */
    private fun refreshIntroBlob() {
        val token = NodePrefs.localToken(context) ?: return
        try {
            val c = URL("http://127.0.0.1:5000/api/ble/introduction").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 4000
            c.setRequestProperty("X-Omni-Local-Token", token)
            val fetchCode = c.responseCode
            if (fetchCode != 200) { introNoteOnce("blob_fetch_http_$fetchCode", "GET /api/ble/introduction"); return }
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            introBlob = JSONObject()
                .put("h", o.getString("node_id"))
                .put("m", o.optString("mesh_id", ""))
                .put("n", o.optString("name", ""))
                .put("t", o.getLong("ts"))
                .put("s", o.getString("sig"))
                .put("e", localEndpoints())
                .toString().toByteArray()
        } catch (e: Exception) {
            introNoteOnce("blob_fetch_exception", e.javaClass.simpleName)
        }
    }

    /** This phone's current private IPv4 addresses with the P2P port: where a node that just
     *  met us over BLE can dial us once an IP path exists (same LAN, hotspot, venue network). */
    private fun localEndpoints(): JSONArray {
        val out = JSONArray()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in ifaces.toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    val h = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || !addr.isSiteLocalAddress || h.contains(":")) continue
                    out.put(JSONObject().put("h", h).put("p", port))
                    if (out.length() >= 3) return out
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** One GATT exchange per newly seen peer (retried at most every INTRO_RETRY_MS): connect,
     *  read the introduction blob, hand it to the daemon. Serialized - one exchange at a time,
     *  with a watchdog so a stuck connection never blocks the next peer for long. */
    private fun maybeIntro(device: BluetoothDevice?, prefix: String) {
        if (device == null || !running || activeGatt != null) return
        if (!hasConnectPermission()) {
            introNoteOnce("no_connect_permission", "BLUETOOTH_CONNECT not granted; introductions cannot start")
            return
        }
        val now = System.currentTimeMillis()
        val last = introTried[prefix]
        if (last != null && now - last < INTRO_RETRY_MS) return
        introTried[prefix] = now
        introAttempts++
        try {
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) { introNote("connect_failed", "connectGatt returned null"); return }
            activeGatt = gatt
            main.postDelayed({ if (activeGatt === gatt) closeActiveGatt() }, GATT_TIMEOUT_MS)
        } catch (t: Throwable) {
            introNote("connect_failed", "connectGatt threw ${t.javaClass.simpleName}")
            err("ble gatt: ${t.message}")
        }
    }

    private fun closeActiveGatt() {
        val g = activeGatt ?: return
        activeGatt = null
        try { g.disconnect() } catch (_: Throwable) {}
        try { g.close() } catch (_: Throwable) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== activeGatt) { try { gatt.close() } catch (_: Throwable) {}; return }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                introNote("connect_failed", "gatt status $status")
                err("ble intro: connect failed (gatt status $status)")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    introConnected++
                    if (!gatt.requestMtu(256)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> closeActiveGatt()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt === activeGatt) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== activeGatt) return
            val ch = gatt.getService(INTRO_SERVICE_UUID)?.getCharacteristic(INTRO_CHAR_UUID)
            if (ch == null) {
                introNote("service_missing", "discovery status $status")
                err("ble intro: peer has no introduction service (discovery status $status)")
                gatt.disconnect()
            } else if (!gatt.readCharacteristic(ch)) {
                introNote("read_failed", "read could not start")
                gatt.disconnect()
            }
        }

        @Deprecated("Legacy callback - the value arrives via the characteristic pre-API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== activeGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val v = characteristic.value
                if (v != null) {
                    introReadOk++
                    introNote("read_ok", "${v.size} bytes")
                    io.execute { handInIntro(v) }
                } else {
                    introNote("blob_empty", "read succeeded but returned no value")
                }
            } else {
                introNote("read_failed", "gatt status $status")
            }
            gatt.disconnect()
        }
    }

    /** Hands a peer's introduction blob (read over GATT) to the daemon's POST /api/ble/met.
     *  Nothing here trusts the blob - the daemon verifies the signature and keeps the meeting. */
    private fun handInIntro(raw: ByteArray) {
        val token = NodePrefs.localToken(context) ?: return
        try {
            val b = JSONObject(String(raw))
            val eps = JSONArray()
            val src = b.optJSONArray("e") ?: JSONArray()
            for (i in 0 until src.length()) {
                val e = src.optJSONObject(i) ?: continue
                eps.put(JSONObject().put("host", e.optString("h")).put("port", e.optInt("p", port)))
            }
            val body = JSONObject()
                .put("node_id", b.getString("h"))
                .put("mesh_id", b.optString("m", ""))
                .put("name", b.optString("n", ""))
                .put("endpoints", eps)
                .put("ts", b.getLong("t"))
                .put("sig", b.getString("s"))
            val c = URL("http://127.0.0.1:5000/api/ble/met").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 4000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Omni-Local-Token", token)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 200) {
                metPosted++
                introNote("posted", "daemon accepted the introduction")
                writeStatus()
            } else {
                val why = try { c.errorStream?.bufferedReader()?.readText()?.take(120) ?: "" } catch (_: Exception) { "" }
                introNote("met_http_$code", why)
                err("ble intro: daemon answered $code: $why")
            }
        } catch (e: Exception) {
            introNote("met_exception", e.javaClass.simpleName)
            err("ble intro: hand-in failed (${e.javaClass.simpleName})")
        }
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
