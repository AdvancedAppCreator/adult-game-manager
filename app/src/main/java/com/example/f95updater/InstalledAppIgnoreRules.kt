package com.example.f95updater

object InstalledAppIgnoreRules {
    private val exactPackages = setOf(
        "com.advancedappcreator.f95updater",
        "com.dv.adm",
        "com.cxinventor.file.explorer",
        "com.google.android.apps.googlevoice",
        "com.google.android.apps.translate",
        "com.google.android.music",
        "com.issess.flashplayer",
        "com.lb.app_manager",
        "com.maxistar.textpad",
        "com.microsoft.emmx",
        "com.microsoft.office.excel",
        "com.microsoft.office.officehubrow",
        "com.microsoft.office.word",
        "com.mobile_infographics_tools.mydrive",
        "com.my6.android",
        "com.nyamixSWFNative",
        "com.openai.chatgpt",
        "com.panaustik.memmap",
        "com.rarlab.rar",
        "com.rs.explorer.filemanager",
        "com.shamanland.privatescreenshots",
        "com.squareup.cash",
        "com.vibo.jsontool",
        "com.walottery.mobileapp",
        "com.webgenie.swf.play",
        "cyou.joiplay.joiplay",
        "mega.privacy.android.app",
        "org.adblockplus.browser",
        "org.telegram.messenger",
        "org.videolan.vlc",
        "ru.zdevs.zarchiver",
    )

    private val packagePrefixes = listOf(
        "com.facebook.",
        "com.google.android.contactkeys",
        "com.hsv.",
        "com.samsung.",
        "cyou.joiplay.runtime.",
    )

    private val exactLabels = setOf(
        "adm",
        "app manager",
        "backup",
        "calendar",
        "cash app",
        "chatgpt",
        "dayuse",
        "disk usage",
        "drives",
        "edge",
        "excel",
        "f95",
        "f95 updater",
        "facebook",
        "filetreesize",
        "gamehub",
        "gaminik",
        "joiplay",
        "json & xml tool",
        "m365 copilot",
        "mega",
        "messenger",
        "motel 6 my6",
        "paypal",
        "private screenshots",
        "rar",
        "reipatcher",
        "reminder",
        "samsung notes",
        "simple text editor",
        "snapchat",
        "swf native",
        "swf player free",
        "tcndex",
        "telegram",
        "terabox",
        "translate",
        "0x52_urm",
        "videos",
        "vlc",
        "voice",
        "webgenie swf player",
        "washington's lottery",
        "whatsapp",
        "word",
        "zarchiver",
    )

    private val labelFragments = listOf(
        "adblocker browser",
        "adblock browser",
        "browser",
        "file manager",
        "file explorer",
        "godot 3 plugin for joiplay",
        "godot 4 plugin for joiplay",
        "language pack",
        "plugin for joiplay",
        "ren'py ",
        "resource espa",
        "rpg maker plugin for joiplay",
        "samsungtts",
        "swf player",
        "tts ",
        "0x52_urm",
    )

    private val wholeLabelSuffixes = setOf(
        "browser",
        "file explorer",
        "file manager",
        "swf player",
    )

    fun shouldIgnore(app: InstalledApp): Boolean {
        val label = app.label.trim().lowercase()
        val launcher = app.launcherLabel?.trim()?.lowercase()
        val labels = listOfNotNull(label, launcher)
        if ((app.source == AppSource.JoiPlay || app.source == AppSource.Managed) &&
            labels.any { it.all(Char::isDigit) }
        ) {
            return true
        }
        if (app.packageName in exactPackages) return true
        if (packagePrefixes.any { app.packageName.startsWith(it) }) return true
        if (labels.any { it in exactLabels }) return true
        return labels.any { labelText ->
            labelFragments.any { fragment ->
                if (fragment in wholeLabelSuffixes) {
                    labelText == fragment || labelText.endsWith(" $fragment")
                } else {
                    fragment in labelText
                }
            }
        }
    }

    fun shouldSkipCatalogMatching(app: InstalledApp, hasExistingMapping: Boolean): Boolean =
        !hasExistingMapping && shouldIgnore(app)
}
