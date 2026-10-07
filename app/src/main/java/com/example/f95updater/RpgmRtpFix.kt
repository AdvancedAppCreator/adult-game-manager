package com.example.f95updater

import java.io.File

/**
 * RPG Maker RGSS (XP/VX/VX Ace) games run through RGSSx01.dll and read a `Game.ini` whose
 * `[Game]` section may carry an `RTP=RPGVXAce` line. That line makes RGSS look up the shared
 * Run-Time Package via the registry and abort with "RPGVXAce RTP is required to run this game."
 * when it isn't registered — even when the game ships all of its own assets (a packed
 * `Game.rgss3a`, or local `Data/`+`Graphics/`+`Audio/`). Under Winlator the RTP usually isn't
 * registered, so these self-contained games fail on a runtime they never actually need.
 *
 * This helper blanks a non-empty `RTP=` line to `RTP=` **only when the game is self-contained**,
 * so genuinely RTP-dependent games are left untouched (Winlator's registered RTP key handles
 * those). The original is backed up to `Game.ini.agmbak` once, and byte content outside the RTP
 * line is preserved exactly (ISO-8859-1 round-trip keeps Shift-JIS titles intact). Idempotent.
 */
object RpgmRtpFix {
    private val rgssArchiveExtensions = setOf("rgss3a", "rgss2a", "rgssad")

    // Matches an `RTP=<value>` line (any case, optional surrounding spaces) whose value has at
    // least one non-space char — i.e. a line that still demands an RTP.
    private val rtpHasValueRe = Regex("(?im)^[ \\t]*RTP[ \\t]*=[ \\t]*\\S")

    // Captures the `RTP=` key portion; the value (up to but excluding the line ending) is dropped
    // on replace so line endings (CRLF/LF) are preserved untouched.
    private val rtpLineRe = Regex("(?im)^([ \\t]*RTP[ \\t]*=)[^\\r\\n]*")

    /** True if [iniText] still has an `RTP=` line with a non-empty value. */
    fun needsBlanking(iniText: String): Boolean = rtpHasValueRe.containsMatchIn(iniText)

    /** Returns [iniText] with any `RTP=` line's value emptied. Idempotent; preserves line endings. */
    fun blankRtp(iniText: String): String = rtpLineRe.replace(iniText) { it.groupValues[1] }

    /**
     * A folder is a self-contained RGSS game if it packs its assets in an RGSS archive, or ships
     * the unencrypted `Data/`+`Graphics/` asset tree locally.
     */
    fun isSelfContainedRgss(dir: File): Boolean {
        val entries = dir.listFiles() ?: return false
        if (entries.any { it.isFile && it.extension.lowercase() in rgssArchiveExtensions }) return true
        val dirNames = entries.filter { it.isDirectory }.map { it.name.lowercase() }.toSet()
        return "data" in dirNames && "graphics" in dirNames
    }

    /**
     * Best-effort: for [storagePath]'s game folder (and immediate subfolders, since some repacks
     * nest the game one level down), blank a stray `RTP=` in a self-contained RGSS game's
     * `Game.ini`. Returns true if any file was rewritten. Never throws.
     */
    fun applyIfNeeded(storagePath: String?): Boolean {
        val cleaned = storagePath?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: return false
        val start = runCatching { File(cleaned) }.getOrNull() ?: return false
        val root = runCatching { if (start.isFile) start.parentFile else start }.getOrNull() ?: return false
        val candidates = buildList {
            add(root)
            runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrNull()?.let { addAll(it) }
        }
        var wrote = false
        for (dir in candidates) {
            val ini = runCatching { File(dir, "Game.ini").takeIf { it.isFile } }.getOrNull() ?: continue
            if (!runCatching { isSelfContainedRgss(dir) }.getOrDefault(false)) continue
            // ISO-8859-1 is a lossless byte<->char mapping: the ASCII RTP line is edited while any
            // Shift-JIS Title bytes survive the round-trip verbatim.
            val text = runCatching { ini.readText(Charsets.ISO_8859_1) }.getOrNull() ?: continue
            if (!needsBlanking(text)) continue
            val backup = File(dir, "Game.ini.agmbak")
            if (!backup.exists()) runCatching { ini.copyTo(backup, overwrite = false) }
            val fixed = blankRtp(text)
            runCatching { ini.writeText(fixed, Charsets.ISO_8859_1) }
                .onSuccess {
                    wrote = true
                    AppLog.i("RpgmRtp", "Blanked stray RTP= in ${ini.absolutePath}")
                }
                .onFailure { AppLog.w("RpgmRtp", "Failed to rewrite ${ini.absolutePath}: ${it.message}", it) }
        }
        return wrote
    }
}
