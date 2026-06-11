package com.example.f95updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK installer using the modern PackageInstaller session API.
 *
 * Flow:
 * 1. [installApk] creates a session, streams the APK, and commits with a status PendingIntent.
 * 2. The system shows the install confirmation UI to the user.
 * 3. On completion (success or failure), the PendingIntent fires and our dynamically-registered
 *    [BroadcastReceiver] delivers the result via [onResult].
 */
object ApkInstaller {

    private const val ACTION_INSTALL_STATUS = "com.example.f95updater.INSTALL_STATUS"

    /** Callback with (packageName, success, message). */
    var onResult: ((String?, Boolean, String) -> Unit)? = null

    private var receiver: BroadcastReceiver? = null

    /**
     * Install an APK file using the PackageInstaller session API.
     * Must be called from a coroutine (performs IO). The result arrives asynchronously
     * via [onResult].
     */
    suspend fun installApk(context: Context, apk: File, expectedPkg: String?) {
        withContext(Dispatchers.IO) {
            AppLog.i("ApkInstall", "Starting session install: file=${apk.name} pkg=$expectedPkg size=${apk.length()}")
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setSize(apk.length())
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                // Stream APK bytes into the session
                session.openWrite("apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { inp -> inp.copyTo(out) }
                    session.fsync(out)
                }
                // Register receiver for install status
                registerReceiver(context)
                // Commit with a PendingIntent that triggers our receiver
                val intent = Intent(ACTION_INSTALL_STATUS).apply {
                    setPackage(context.packageName)
                    putExtra("expected_pkg", expectedPkg)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
                AppLog.i("ApkInstall", "Session $sessionId committed, awaiting user confirmation")
            }
        }
    }

    private fun registerReceiver(context: Context) {
        // Unregister any stale receiver first
        unregisterReceiver(context)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val pkg = intent.getStringExtra("expected_pkg")
                AppLog.i("ApkInstall", "Session result: status=$status msg=$msg pkg=$pkg")
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        onResult?.invoke(pkg, true, "Install complete.")
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // The system needs user confirmation — launch the confirmation intent
                        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(confirmIntent)
                        }
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        onResult?.invoke(pkg, false, "Install cancelled by user.")
                    }
                    else -> {
                        onResult?.invoke(pkg, false, "Install failed: $msg (status=$status)")
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregisterReceiver(context: Context) {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
        }
    }

    // --- Legacy helpers kept for the URI-based fallback path ---

    /** Build an Intent that opens the system installer for [apk]. */
    fun buildIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** As above but for an APK referenced by a Uri (from extraction).
     *  If [uri] is a file:// URI we re-wrap via FileProvider. */
    fun buildIntentForUri(context: Context, uri: Uri): Intent? {
        val safe = when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                runCatching {
                    FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", File(path),
                    )
                }.getOrNull() ?: return null
            }
            "content" -> uri
            else -> return null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(safe, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
