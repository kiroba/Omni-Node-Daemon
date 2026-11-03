package com.omniverse.node

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app update (Android only). Which release is newest, and every word about it, comes from the shared
 * core through omni_node_entry.apk_update_json (newest release with an .apk asset vs. BuildConfig's
 * versionName). This file does the Android part: download the APK into the app cache, check it is an
 * Omni Node signed with the same key as this one, and hand it to the system installer through a
 * FileProvider. Nothing installs without the person tapping Install in Android's own screen.
 *
 * Every function here blocks except [installIntent]: call them off the main thread.
 */
object AppUpdate {
    private const val DIR = "updates"

    /** {ok, newer, latest, tag, page_url, apk_url, apk_name, size, line} or {ok: false, error, line}. Never throws. */
    fun check(c: Context): JSONObject = try {
        NodeCore.apkUpdate(c, BuildConfig.VERSION_NAME)
    } catch (t: Throwable) {
        JSONObject().put("ok", false).put("newer", false).put("error", t.javaClass.simpleName).put("line", t.javaClass.simpleName)
    }

    /** Old downloads are useless once the app is updated (or the person said no): free the space. */
    fun clearCache(c: Context) {
        try { File(c.cacheDir, DIR).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    /**
     * Downloads [url] to cache/updates/omni-node-[version].apk. [progress] gets (bytes so far, total or -1).
     * Returns the file, or throws an IOException whose message is shown as-is. [cancelled] stops it.
     */
    fun download(c: Context, url: String, version: String, expectedSize: Long,
                 cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val dir = File(c.cacheDir, DIR).apply { mkdirs() }
        clearCache(c)
        val safe = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val part = File(dir, "omni-node-$safe.apk.part")
        val out = File(dir, "omni-node-$safe.apk")
        var conn: HttpURLConnection? = null
        try {
            // GitHub answers with a redirect to its download host (https -> https, followed automatically).
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniNode/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: -1L
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw java.io.InterruptedIOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) { lastReport = done; progress(done, total) }
                    }
                }
            }
            progress(done, total)
            if (expectedSize > 0 && done != expectedSize) throw java.io.IOException("incomplete ($done of $expectedSize bytes)")
            if (!part.renameTo(out)) throw java.io.IOException("can't save the file")
            return out
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    enum class Verdict { OK, NOT_OMNI_NODE, NOT_NEWER, OTHER_KEY }

    /**
     * Reads the downloaded APK before Android does, so the person gets a plain reason instead of the
     * installer's "App not installed": wrong package, not newer, or signed with another key (an older
     * per-build debug key; Android then needs one uninstall).
     */
    fun verify(c: Context, apk: File): Verdict {
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags or PackageManager.GET_SIGNATURES)
            ?: return Verdict.NOT_OMNI_NODE
        if (archive.packageName != c.packageName) return Verdict.NOT_OMNI_NODE
        val installed = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(c.packageName, flags or PackageManager.GET_SIGNATURES)
        } catch (_: Exception) {
            null
        }
        if (installed != null && versionCode(archive) <= versionCode(installed)) return Verdict.NOT_NEWER
        val mine = installed?.let { signers(it) }.orEmpty()
        val theirs = signers(archive)
        // Only refuse when both sides are readable and share no certificate; unreadable -> let Android decide.
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) return Verdict.OTHER_KEY
        return Verdict.OK
    }

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun signers(p: PackageInfo): Set<String> {
        val certs = try {
            if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
                val si = p.signingInfo!!
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION") p.signatures
            }
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return certs.filterNotNull().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Android 8+: has the person allowed Omni Node to install updates ("Install unknown apps")? */
    fun canInstall(c: Context): Boolean = try { c.packageManager.canRequestPackageInstalls() } catch (_: Exception) { false }

    /** The screen where the person allows it, opened on Omni Node's own switch. */
    fun allowInstallsIntent(c: Context) =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    /** The system installer for [apk], through our FileProvider (cache/updates only). */
    fun installIntent(c: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
