package com.luumlabs.lumen

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.PersistableBundle
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Checks official stable releases; APK identity and signature are verified before installation. */
object Updates {
    data class Product(val name: String, val repo: String, val pkg: String)
    val products = listOf(Product("Lumen home screen", "luumlabs/lumen-tv", "com.luumlabs.lumen"),
        Product("MiracastReceiver casting", "weekdayjast/MiracastReceiver", "com.weekd.miracastreceiver"))
    private const val MIRACAST_CERT = "ea7f4568203d294bd37acac966d6d707f79147549d24c2244f58a6fbc27a6291"
    val executor = Executors.newSingleThreadExecutor()
    private const val CHANNEL = "software_updates"
    fun prefs(c: Context) = c.getSharedPreferences("software_updates", Context.MODE_PRIVATE)

    /** Whether one of our install sessions for [p] is still open (waiting to be confirmed on the TV). */
    private fun pending(c: Context, p: Product): Boolean =
        runCatching { c.packageManager.packageInstaller.mySessions.any { it.appPackageName == p.pkg } }.getOrDefault(false)

    /** Gives up our open install sessions for [p]: a confirmation that went away would otherwise block every later update. */
    private fun abandonPending(c: Context, p: Product) {
        val installer = c.packageManager.packageInstaller
        runCatching { installer.mySessions.filter { it.appPackageName == p.pkg }.forEach { installer.abandonSession(it.sessionId) } }
        prefs(c).edit().remove(p.pkg + ".session").remove(p.pkg + ".confirmation").apply()
    }

    /** Shows the TV's install confirmation for [p] again, if it is still waiting for one. */
    private fun reopenConfirmation(c: Context, p: Product): Boolean {
        val uri = prefs(c).getString(p.pkg + ".confirmation", null) ?: return false
        return runCatching { c.startActivity(Intent.parseUri(uri, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
    fun installed(c: Context, p: Product) = runCatching { c.packageManager.getPackageInfo(p.pkg, 0).versionName ?: "0.0" }.getOrNull()
    fun schedule(c: Context) {
        val scheduler = c.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(24001) != null) return
        scheduler.schedule(JobInfo.Builder(24001, ComponentName(c, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(6 * 60 * 60 * 1000L).build())
    }
    private fun connection(address: String): HttpURLConnection {
        require(address.startsWith("https://"))
        return (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "Lumen-Updater")
        }
    }
    fun check(c: Context): List<String> = products.map { p ->
        try {
            val con = connection("https://api.github.com/repos/${p.repo}/releases/latest")
            val release = try { con.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { con.disconnect() }
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
            val version = release.getString("tag_name")
            val current = installed(c, p)
            val update = current == null || UpdatePolicy.newer(version, current)
            val assets = release.getJSONArray("assets")
            val home = c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.packageName), 0).isNotEmpty()
            val items = (0 until assets.length()).map { assets.getJSONObject(it) }
            val asset = if (p.pkg == c.packageName) {
                // New releases carry the version in their filename; preserve legacy release support.
                val preferred = ReleaseAssetNames.select(version, !home, items.map { it.getString("name") })
                items.firstOrNull { it.getString("name") == preferred }
            } else {
                items.firstOrNull { it.getString("name").endsWith("-universal.apk") }
            } ?: error("No compatible package in this release")
            val url = asset.getString("browser_download_url")
            check(url.startsWith("https://github.com/${p.repo}/releases/download/"))
            val status = when { current == null -> "Not installed · $version available"; update -> "$current → $version"; else -> "$current · up to date (stable $version)" }
            // Updated some other way (adb, a newer APK): a session of ours left waiting has nothing left to install.
            if (!update && pending(c, p)) abandonPending(c, p)
            prefs(c).edit().putString(p.pkg + ".status", status).putBoolean(p.pkg + ".available", update)
                .putString(p.pkg + ".url", url).putString(p.pkg + ".version", version)
                .putString(p.pkg + ".digest", asset.optString("digest"))
                .putLong(p.pkg + ".size", asset.getLong("size")).apply()
            if (update && current != null) {
                val key = p.pkg + ".notified"
                if (prefs(c).getString(key, "") != version) {
                    notify(c, p, "New version $version available — open the update center")
                    prefs(c).edit().putString(key, version).apply()
                }
            }
            "${p.name}：$status"
        } catch (e: Exception) {
            val status = "Check failed: ${e.message ?: "Network unavailable"}, try again later"
            prefs(c).edit().putString(p.pkg + ".status", status).putBoolean(p.pkg + ".available", false).apply()
            "${p.name}：$status"
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    /**
     * Downloads, verifies and installs [p]. [interactive] is false for the background job: it never
     * puts the confirmation back on screen, nor drops an install the viewer may be confirming.
     */
    fun install(c: Context, p: Product, interactive: Boolean = true) {
        check(c.packageManager.canRequestPackageInstalls()) { "Allow Lumen to install unknown apps first" }
        val sp = prefs(c)
        if (pending(c, p)) {
            check(interactive) { "An install is waiting for confirmation; open Software Updates to continue it" }
            // Still waiting on the TV's confirmation: show it again rather than download anew.
            if (reopenConfirmation(c, p)) return
            // Nothing left to confirm (the screen was dismissed, or the TV dropped it): start over.
            abandonPending(c, p)
        }
        check(sp.getBoolean(p.pkg + ".available", false)) { "Check for updates first" }
        val file = File(c.cacheDir, p.pkg + "-update.apk")
        try {
            val url = sp.getString(p.pkg + ".url", "")!!
            check(url.startsWith("https://github.com/${p.repo}/releases/download/"))
            val size = sp.getLong(p.pkg + ".size", 0)
            check(size in 1..250_000_000L) { "Unexpected package size" }
            val con = connection(url)
            try {
                con.inputStream.use { input -> file.outputStream().use { out ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                        check(total <= size) { "Download is larger than the release metadata" }; out.write(buffer, 0, n) }
                    check(total == size) { "Download incomplete" }
                } }
            } finally { con.disconnect() }
            val digest = sp.getString(p.pkg + ".digest", "").orEmpty()
            if (digest.startsWith("sha256:")) {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val bytes = ByteArray(65536)
                    while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) } }
                check(hex(md.digest()) == digest.removePrefix("sha256:")) { "Package verification failed" }
            }
            val pm = c.packageManager
            val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Can't read the package")
            check(archive.packageName == p.pkg) { "Package identity mismatch" }
            check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Unsupported TV system version" }
            val old = runCatching { pm.getPackageInfo(p.pkg, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            if (old != null) check(archive.longVersionCode > old.longVersionCode) { "No newer version, nothing to install" }
            // Android 9 and 10 can return a package on disk without its signingInfo; there, and only
            // there, the package is read again with GET_SIGNATURES (from Tigris 2.0.8, #39). Newer
            // Android never falls back to the legacy certificates.
            fun certs(info: android.content.pm.PackageInfo?): Set<String> {
                @Suppress("DEPRECATION")
                val signers = info?.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
                    ?: info?.signatures?.takeIf { it.isNotEmpty() }
                return signers?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
            }
            val legacyAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            @Suppress("DEPRECATION")
            val signatures = UpdateSignerPolicy.withLegacyFallback(certs(archive), legacyAllowed) {
                val legacy = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
                // A second reading that disagrees on which package this is is never trusted.
                if (legacy?.packageName == archive.packageName) certs(legacy) else emptySet()
            }
            check(signatures.isNotEmpty()) { "Package signature missing" }
            if (old != null) {
                @Suppress("DEPRECATION")
                val installedSignatures = UpdateSignerPolicy.withLegacyFallback(certs(old), legacyAllowed) {
                    certs(runCatching { pm.getPackageInfo(p.pkg, PackageManager.GET_SIGNATURES) }.getOrNull())
                }
                check(UpdateSignerPolicy.sameSigner(signatures, installedSignatures)) {
                    "Different signature: can't upgrade keeping settings; the old version was not uninstalled"
                }
            } else check(UpdateSignerPolicy.matchesPinned(signatures, p.pkg == products[1].pkg, MIRACAST_CERT)) { "Untrusted casting signature" }
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(p.pkg)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            sp.edit().putInt(p.pkg + ".session", id).apply()
            try {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val intent = Intent(c, UpdateResult::class.java).putExtra("product", p.pkg)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(c, id, intent, flags)
                    session.commit(pending.intentSender)
                }
                sp.edit().putString(p.pkg + ".status", "Install submitted; you may need to confirm on the TV").apply()
            } catch (e: Exception) { installer.abandonSession(id); sp.edit().remove(p.pkg + ".session").apply(); throw e }
        } finally { file.delete() }
    }
    fun notify(c: Context, p: Product, message: String, action: Intent? = null) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Software update", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = action ?: Intent(c, UpdateActivity::class.java)
        val pending = PendingIntent.getActivity(c, p.pkg.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(p.pkg.hashCode(), Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_toggles)
            .setContentTitle(p.name).setContentText(message).setContentIntent(pending).setAutoCancel(true).build())
    }
}

class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updates.executor.execute {
            try {
                Updates.check(this)
                if (Updates.prefs(this).getBoolean("auto", false)) for (p in Updates.products) {
                    if (Updates.installed(this, p) != null && Updates.prefs(this).getBoolean(p.pkg + ".available", false)) {
                        runCatching { Updates.install(this, p, interactive = false) }.onFailure { Updates.notify(this, p, it.message ?: "Update failed") }
                    }
                }
            } finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}

class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Updates.products.find { it.pkg == intent.getStringExtra("product") } ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Kept so a later "Install / update" can show it again instead of finding the session stuck.
            Updates.prefs(context).edit().putString(p.pkg + ".confirmation", confirmation.toUri(0)).apply()
            Updates.notify(context, p, "Please confirm the update install", confirmation)
            runCatching { context.startActivity(confirmation) }
        } else {
            val message = if (status == PackageInstaller.STATUS_SUCCESS) "Installed successfully" else "Install incomplete: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            Updates.prefs(context).edit().remove(p.pkg + ".session").remove(p.pkg + ".confirmation")
                .putString(p.pkg + ".status", message).putBoolean(p.pkg + ".available", false).apply()
            Updates.notify(context, p, message)
        }
    }
}
