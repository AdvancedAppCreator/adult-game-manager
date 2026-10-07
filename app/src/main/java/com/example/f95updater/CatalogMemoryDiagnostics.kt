package com.example.f95updater

import android.os.Debug
import java.util.Locale

internal object CatalogMemoryDiagnostics {
    private const val BYTES_PER_MIB = 1024.0 * 1024.0

    fun log(
        phase: String,
        startedAtMs: Long? = null,
        detail: String = "",
    ) {
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val elapsed = startedAtMs?.let { " elapsedMs=${System.currentTimeMillis() - it}" }.orEmpty()
        val processMemory = runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            " pss=${info.totalPss}KB dalvikPss=${info.dalvikPss}KB" +
                " nativePss=${info.nativePss}KB otherPss=${info.otherPss}KB"
        }.getOrDefault(" pss=unavailable")
        val suffix = detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        AppLog.i(
            "CatalogMemory",
            "phase=$phase$elapsed heapUsed=${mib(heapUsed)}MiB" +
                " heapCommitted=${mib(runtime.totalMemory())}MiB" +
                " heapMax=${mib(runtime.maxMemory())}MiB$processMemory" +
                " thread='${Thread.currentThread().name}'$suffix",
        )
    }

    private fun mib(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / BYTES_PER_MIB)
}
