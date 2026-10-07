package com.example.f95updater

import android.content.Context
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
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class LogLevel(val tag: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

/**
 * Lightweight rolling-file logger plus in-memory ring buffer.
 * - INFO default; DEBUG suppressed unless [verbose] is true.
 * - Disk usage capped: two 1.5 MB files rotated (~3 MB total).
 * - Ring buffer ~1500 lines for embedding in crash reports.
 * - All disk writes happen on a background dispatcher.
 */
object AppLog {
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val RING_LINES = 1500

    @Volatile var verbose: Boolean = true

    private lateinit var dir: File
    private val logFile: File get() = File(dir, "log.txt")
    private val rotFile: File get() = File(dir, "log.1.txt")

    private val ring = ArrayDeque<String>()
    private val ringLock = Any()
    private val dfLine = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        // Start fresh on every app launch — keeps logs short and relevant to the current session.
        runCatching {
            if (logFile.exists()) logFile.delete()
            if (rotFile.exists()) rotFile.delete()
            synchronized(ringLock) { ring.clear() }
        }
    }

    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO,  tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg, t)

    fun log(level: LogLevel, tag: String, msg: String, t: Throwable? = null) {
        if (level == LogLevel.DEBUG && !verbose) return
        val line = buildString {
            append(dfLine.format(Date()))
            append(' '); append(level.tag); append(' ')
            append(tag.padEnd(14).take(14))
            append(' ')
            append(msg)
            if (t != null) {
                append('\n')
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                append(sw.toString().prependIndent("  "))
            }
        }
        synchronized(ringLock) {
            if (ring.size >= RING_LINES) ring.pollFirst()
            ring.addLast(line)
        }
        // Fire-and-forget write
        DiskWriter.enqueue(this, line)
    }

    /** Snapshot of in-memory ring buffer (for embedding in crash reports). */
    fun snapshotRing(): List<String> = synchronized(ringLock) { ring.toList() }

    /** Append a single line to logFile, rotating if it would exceed cap. */
    @Synchronized
    internal fun writeToDisk(line: String) {
        if (!::dir.isInitialized) return
        val f = logFile
        try {
            if (f.exists() && f.length() + line.length + 1 > MAX_FILE_BYTES) {
                if (rotFile.exists()) rotFile.delete()
                f.renameTo(rotFile)
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) { /* ignore — logging must not throw */ }
    }

    /** Upload all log files to the configured crash-upload endpoint. Returns (ok, fail). */
    suspend fun upload(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext 0 to 0
        val config = AppConfigStore.current(context)
        val base = config.crashUploadBaseUrl ?: return@withContext 0 to 1
        val sas = config.crashUploadAuthQuery ?: ""
        if (base.isBlank()) return@withContext 0 to 1

        // Flush current file to ensure it's complete on disk
        var ok = 0; var fail = 0
        val ts = System.currentTimeMillis()
        val files = buildList {
            logFile.takeIf { it.exists() && it.length() > 0 }?.let {
                add(Triple(it, "log-${ts}-current.txt", "text/plain; charset=utf-8"))
            }
            rotFile.takeIf { it.exists() && it.length() > 0 }?.let {
                add(Triple(it, "log-${ts}-prev.txt", "text/plain; charset=utf-8"))
            }
            ScreenshotDiagnostics.files(context).forEachIndexed { idx, file ->
                add(Triple(file, "screenshot-${ts}-${idx + 1}-${file.name}", "image/png"))
            }
        }

        for ((file, name, contentType) in files) {
            val target = if (sas.isNotBlank()) "$base/$name?$sas" else "$base/$name"
            val body = file.readBytes().toRequestBody(contentType.toMediaType())
            val req = Request.Builder()
                .url(target)
                .header("x-ms-blob-type", "BlockBlob")
                .header("x-ms-version", "2021-08-06")
                .header("Content-Type", contentType)
                .put(body)
                .build()
            val success = runCatching {
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (success) {
                ok++
                runCatching { file.delete() }
            } else fail++
        }
        if (ok > 0) synchronized(ringLock) { ring.clear() }
        ok to fail
    }

    /** Save all in-memory + on-disk log content to the user's Documents folder.
     *  Always works regardless of whether an upload endpoint is configured. */
    suspend fun saveLocally(context: Context): String = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext "Logger not initialized"
        val ts = System.currentTimeMillis()
        val parts = mutableListOf<String>()
        synchronized(ringLock) {
            if (ring.isNotEmpty()) {
                parts.add("=== in-memory ring buffer (${ring.size} lines) ===")
                parts.addAll(ring)
            }
        }
        runCatching {
            if (logFile.exists()) {
                parts.add("\n=== log.txt ===")
                parts.add(logFile.readText())
            }
            if (rotFile.exists()) {
                parts.add("\n=== log.1.txt (rotated) ===")
                parts.add(rotFile.readText())
            }
        }
        val name = "applog-${ts}.txt"
        runCatching {
            AppConfigStore.writeLogToDocuments(context, name, parts.joinToString("\n"))
        }.getOrElse { "save failed: ${it.message}" }
    }

    fun diskBytes(): Long {
        if (!::dir.isInitialized) return 0L
        return (if (logFile.exists()) logFile.length() else 0L) +
               (if (rotFile.exists()) rotFile.length() else 0L)
    }

    fun clear() {
        if (!::dir.isInitialized) return
        runCatching { logFile.delete() }
        runCatching { rotFile.delete() }
        synchronized(ringLock) { ring.clear() }
    }
}

/** Simple background writer using a single-threaded executor to keep order. */
private object DiskWriter {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog-writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun enqueue(target: AppLog, line: String) {
        executor.execute { target.writeToDisk(line) }
    }
}
