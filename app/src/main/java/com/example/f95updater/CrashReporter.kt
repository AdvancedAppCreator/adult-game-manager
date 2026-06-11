package com.example.f95updater

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Captures uncaught exceptions, writes them to local files, and uploads them
 * only when a diagnostics upload endpoint is explicitly configured.
 *
 * On every app start, queued crash files are retried.
 * A manual "Send crash logs" menu action can also force a flush.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_DIR = "crashes"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR).apply { mkdirs() }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        val dfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        pw.println("=== Adult Game Manager crash report ===")
        pw.println("time:       ${dfIso.format(Date())}")
        pw.println("app:        ${context.packageName}  v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        pw.println("debug:      ${BuildConfig.DEBUG}")
        pw.println("device:     ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("android:    SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        pw.println("thread:     ${thread.name} (id=${thread.id})")
        pw.println()
        pw.println("--- stack ---")
        throwable.printStackTrace(pw)
        pw.println()
        pw.println("--- recent log (in-memory ring) ---")
        runCatching { AppLog.snapshotRing().takeLast(500).forEach { pw.println(it) } }
        pw.flush()
        val name = "crash-${System.currentTimeMillis()}.txt"
        File(crashDir(context), name).writeText(sw.toString())
    }

    /**
     * Logs a "soft" failure (caught exception we want recorded) with optional context tag.
     * Safe to call from anywhere; writes to the same queue as fatal crashes.
     */
    fun logCaught(context: Context, tag: String, throwable: Throwable) {
        runCatching {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            val dfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            pw.println("=== Adult Game Manager non-fatal report ===")
            pw.println("time:    ${dfIso.format(Date())}")
            pw.println("app:     ${context.packageName}  v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            pw.println("tag:     $tag")
            pw.println("device:  ${Build.MANUFACTURER} ${Build.MODEL}  SDK ${Build.VERSION.SDK_INT}")
            pw.println()
            pw.println("--- stack ---")
            throwable.printStackTrace(pw)
            pw.println()
            pw.println("--- recent log (in-memory ring) ---")
            runCatching { AppLog.snapshotRing().takeLast(300).forEach { pw.println(it) } }
            pw.flush()
            val name = "soft-${System.currentTimeMillis()}.txt"
            File(crashDir(context), name).writeText(sw.toString())
        }
    }

    /** Returns (uploaded, failed). Deletes successfully uploaded files. */
    suspend fun flush(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val dir = crashDir(context)
        val files = dir.listFiles { f -> f.isFile && (f.extension == "txt") } ?: emptyArray()
        if (files.isEmpty()) return@withContext 0 to 0
        val config = AppConfigStore.current(context)
        val base = config.crashUploadBaseUrl ?: return@withContext 0 to files.size
        val sas = config.crashUploadAuthQuery ?: ""
        if (base.isBlank()) return@withContext 0 to files.size
        var ok = 0
        var fail = 0
        for (f in files) {
            val target = if (sas.isNotBlank()) "$base/${f.name}?$sas" else "$base/${f.name}"
            val req = Request.Builder()
                .url(target)
                .header("x-ms-blob-type", "BlockBlob")
                .header("x-ms-version", "2021-08-06")
                .header("Content-Type", "text/plain; charset=utf-8")
                .put(f.readText().toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .build()
            val success = runCatching {
                client.newCall(req).execute().use { resp -> resp.isSuccessful }
            }.getOrDefault(false)
            if (success) { f.delete(); ok++ } else fail++
        }
        ok to fail
    }

    /** Save all pending crash reports to the user's Documents folder. Always available. */
    suspend fun saveAllLocally(context: Context): Pair<Int, String> = withContext(Dispatchers.IO) {
        val dir = crashDir(context)
        val files = dir.listFiles { f -> f.isFile && (f.extension == "txt") } ?: emptyArray()
        if (files.isEmpty()) return@withContext 0 to "No crash reports pending"
        val ts = System.currentTimeMillis()
        val bundled = buildString {
            files.forEachIndexed { idx, f ->
                if (idx > 0) append("\n\n")
                append("=== ").append(f.name).append(" ===\n")
                append(f.readText())
            }
        }
        val location = runCatching {
            AppConfigStore.writeLogToDocuments(context, "crashes-${ts}.txt", bundled)
        }.getOrElse { "save failed: ${it.message}" }
        files.size to location
    }

    fun pendingCount(context: Context): Int =
        crashDir(context).listFiles { f -> f.isFile }?.size ?: 0
}
