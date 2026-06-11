package com.example.f95updater

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager

object InstalledAppsScanner {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun lastUsedMap(context: Context): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 5L * 365 * 24 * 60 * 60 * 1000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, start, end) ?: return emptyMap()
        val map = HashMap<String, Long>()
        for (s in stats) {
            val prev = map[s.packageName] ?: 0L
            if (s.lastTimeUsed > prev) map[s.packageName] = s.lastTimeUsed
        }
        return map
    }

    /** Returns triple (apk, data, cache) bytes for the package, or (apkFromFile, 0, 0) on failure. */
    private fun storageStats(
        context: Context,
        ssm: StorageStatsManager?,
        pi: PackageInfo,
        apkFromFile: Long,
    ): Triple<Long, Long, Long> {
        if (ssm != null && hasUsageAccess(context)) {
            return runCatching {
                val uuid = StorageManager.UUID_DEFAULT
                val user = android.os.Process.myUserHandle()
                val s = ssm.queryStatsForPackage(uuid, pi.packageName, user)
                Triple(s.appBytes, s.dataBytes, s.cacheBytes)
            }.getOrDefault(Triple(apkFromFile, 0L, 0L))
        }
        return Triple(apkFromFile, 0L, 0L)
    }

    fun scan(context: Context, includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        val flags = PackageManager.GET_META_DATA
        val packages: List<PackageInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }
        val usage = lastUsedMap(context)
        // Resolve every package's LAUNCHER-activity label in one PM query so we can
        // show the home-screen name when it differs from the app's manifest label.
        val launcherLabels: Map<String, String> = runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
            }
            resolveInfos.associate { ri ->
                ri.activityInfo.packageName to ri.loadLabel(pm).toString()
            }
        }.getOrDefault(emptyMap())
        return packages.asSequence()
            .filter { includeSystem || (it.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) }
            .filter { it.applicationInfo != null }
            .map { pi ->
                val ai = pi.applicationInfo!!
                val label = ai.loadLabel(pm).toString()
                val launcher = launcherLabels[pi.packageName]?.takeIf { it.isNotBlank() && it != label }
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
                val apkFromFile = runCatching {
                    var total = 0L
                    ai.sourceDir?.let { total += java.io.File(it).length() }
                    ai.splitSourceDirs?.forEach { total += java.io.File(it).length() }
                    total
                }.getOrDefault(0L)
                val (appB, dataB, cacheB) = storageStats(context, ssm, pi, apkFromFile)
                InstalledApp(
                    packageName = pi.packageName,
                    label = label,
                    launcherLabel = launcher,
                    versionName = pi.versionName ?: "",
                    versionCode = vCode,
                    firstInstallTime = pi.firstInstallTime,
                    lastUpdateTime = pi.lastUpdateTime,
                    lastUsedTime = usage[pi.packageName] ?: 0L,
                    apkSize = appB,
                    dataSize = dataB,
                    cacheSize = cacheB,
                )
            }
            .filterNot { InstalledAppIgnoreRules.shouldIgnore(it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
