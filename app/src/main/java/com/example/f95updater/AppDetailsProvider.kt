package com.example.f95updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** On-demand details fetched only when a row is expanded. */
data class AppDetails(
    val installerPackage: String?,
    val uid: Int,
    val minSdk: Int?,
    val targetSdk: Int,
    val processName: String,
    val sourceDir: String?,
    val splitCount: Int,
    val nativeLibDir: String?,
    val dataDir: String?,
    val externalCacheBytes: Long,
    val externalDataBytes: Long,
)

object AppDetailsProvider {
    fun get(context: Context, packageName: String): AppDetails? {
        val pm = context.packageManager
        val pi = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return null
        val ai = pi.applicationInfo ?: return null
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        val splits = ai.splitSourceDirs?.size ?: 0
        val externalCache = sumDir(context.externalCacheDir?.parentFile?.parent?.let { File(it, "$packageName/cache") })
        val externalData = sumDir(context.getExternalFilesDir(null)?.parentFile?.parent?.let { File(it, "$packageName") })
        return AppDetails(
            installerPackage = installer,
            uid = ai.uid,
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion,
            processName = ai.processName ?: packageName,
            sourceDir = ai.sourceDir,
            splitCount = splits,
            nativeLibDir = ai.nativeLibraryDir,
            dataDir = ai.dataDir,
            externalCacheBytes = externalCache,
            externalDataBytes = externalData,
        )
    }

    private fun sumDir(root: File?): Long {
        if (root == null || !root.exists()) return 0L
        var total = 0L
        runCatching {
            root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        return total
    }
}
