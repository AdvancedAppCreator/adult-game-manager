package com.example.f95updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Heuristics for classifying installed Android packages as "definitely not a game".
 *
 * The rules favor false negatives (i.e., "maybe a game") over false positives — if we
 * aren't sure, the app stays visible. The user can always hide it manually.
 *
 * Signals used:
 *  - Package prefix (com.google, com.samsung, etc. — official OEM/system apps)
 *  - Installer (com.android.vending = Play Store installs are rarely sideloaded adult games)
 *  - Hardcoded "known non-games" list (popular browsers, social apps, payment apps)
 */
object NonGamesDetector {

    /** Package prefixes that are essentially never managed games. */
    private val systemPrefixes = listOf(
        "com.android.",
        "com.google.android.",
        "com.samsung.android.",
        "com.samsung.knox.",
        "com.samsung.sec.",
        "com.samsung.smt.",
        "com.sec.",
        "com.microsoft.",
        "com.miui.",
        "com.xiaomi.",
        "com.oneplus.",
        "com.huawei.",
        "com.oppo.",
        "com.vivo.",
        "com.motorola.",
        "com.lge.",
        "com.sonyericsson.",
        "com.htc.",
        "com.qualcomm.",
        "com.mediatek.",
        "com.qti.",
        "android.",
        // JoiPlay runtime plugins — engines, not games
        "cyou.joiplay.runtime.",
    )

    /** Known consumer apps that are never managed games. */
    private val knownNonGames = setOf(
        // Browsers (incl. ad-block variants)
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.brave.browser", "com.opera.browser", "com.duckduckgo.mobile.android",
        "com.UCMobile.intl", "org.adblockplus.browser", "com.hsv.freeadblockerbrowser",
        // Social / messaging
        "com.whatsapp", "com.facebook.katana", "com.facebook.orca",
        "com.instagram.android", "com.twitter.android", "com.snapchat.android",
        "com.tinder", "com.discord", "org.telegram.messenger", "com.viber.voip",
        "com.skype.raider", "com.zhiliaoapp.musically", "com.reddit.frontpage",
        // Payment / finance
        "com.paypal.android.p2pmobile", "com.squareup.cash", "com.venmo",
        "com.coinbase.android", "com.robinhood.android",
        // Productivity / Office
        "com.microsoft.office.excel", "com.microsoft.office.word",
        "com.microsoft.office.powerpoint", "com.microsoft.office.outlook",
        "com.microsoft.office.officehubrow", "com.google.android.apps.docs",
        "com.google.android.apps.sheets", "com.google.android.apps.slides",
        "com.maxistar.textpad",
        // Storage / cloud / file managers
        "com.dropbox.android", "mega.privacy.android.app", "com.dubox.drive",
        "com.rs.explorer.filemanager", "com.lb.app_manager",
        "com.devone.filetreesize", "com.panaustik.memmap",
        "com.mobile_infographics_tools.mydrive", "ru.zdevs.zarchiver",
        "com.rarlab.rar",
        // Media / players
        "org.videolan.vlc",
        // Tools / utilities
        "com.shamanland.privatescreenshots", "com.dv.adm",
        // Streaming / shopping
        "com.netflix.mediaclient", "com.amazon.mShop.android.shopping",
        "com.ebay.mobile",
        // AI / assistants
        "com.openai.chatgpt", "com.google.android.apps.bard",
        // Travel
        "com.dayuse_hotels.dayuseus", "com.airbnb.android",
        "com.my6.android", "com.tripadvisor.tripadvisor",
        // Email
        "com.google.android.gm", "com.microsoft.office.outlook",
        // Lottery / gambling utilities
        "com.walottery.mobileapp",
        // Flash/SWF players (utilities, not games — Ruffle plugin already covered by prefix)
        "com.nyamixSWFNative", "com.issess.flashplayer", "com.webgenie.swf.play",
        // Game-library / launcher apps (not games themselves)
        "com.xiaoji.egggame",
        // Companion apps to our app
        "cyou.joiplay.joiplay", "com.example.f95updater",
        "com.advancedappcreator.f95updater",
    )

    /** Substrings in package names that strongly suggest "not a game". */
    private val nonGameSubstrings = listOf(
        ".browser", ".keyboard", ".launcher", ".camera", ".wallpaper",
        ".calculator", ".clock", ".calendar", ".gallery", ".weather",
        ".voicerecorder", ".notes",
    )

    /** Installers that strongly suggest "store app, not a sideloaded adult game APK". */
    private val storeInstallers = setOf(
        "com.android.vending",       // Play Store
        "com.google.android.feedback",
        "com.amazon.venezia",        // Amazon Appstore
        "com.huawei.appmarket",
        "com.sec.android.app.samsungapps",
        "com.xiaomi.market",
        "com.aurora.store",          // Aurora (Play Store mirror)
    )

    /** Quick package-name-only check (no PackageManager access — safe for batch use). */
    fun isLikelyNonGameByPackage(packageName: String): Boolean {
        if (packageName in knownNonGames) return true
        if (systemPrefixes.any { packageName.startsWith(it) }) return true
        if (nonGameSubstrings.any { packageName.contains(it) }) return true
        return false
    }

    /** Deeper check using PackageManager: considers installer + package name. */
    fun isLikelyNonGame(context: Context, packageName: String): Boolean {
        if (isLikelyNonGameByPackage(packageName)) return true
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        if (installer != null && installer in storeInstallers) return true
        return false
    }
}
