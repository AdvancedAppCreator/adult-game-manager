package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppBrowserPolicyTest {
    @Test
    fun androidDownloadManagerRemainsDefaultBackend() {
        assertEquals(
            BrowserDownloadBackend.AndroidDownloadManager,
            BrowserSettings().downloadBackend,
        )
    }

    @Test
    fun emptyAllowlistBlocksEverything() {
        assertFalse(isPopupHostAllowed("ads.example.com", emptyList()))
        assertFalse(isPopupHostAllowed("f95zone.to", emptyList()))
    }

    @Test
    fun exactHostMatches() {
        assertTrue(isPopupHostAllowed("f95zone.to", listOf("f95zone.to")))
        assertFalse(isPopupHostAllowed("evil-f95zone.to", listOf("f95zone.to")))
    }

    @Test
    fun subdomainSuffixMatches() {
        val allow = listOf("f95zone.to")
        assertTrue(isPopupHostAllowed("www.f95zone.to", allow))
        assertTrue(isPopupHostAllowed("cdn.assets.f95zone.to", allow))
        // A host that merely ends with the string but not on a dot boundary must NOT match.
        assertFalse(isPopupHostAllowed("notf95zone.to", allow))
    }

    @Test
    fun matchIsCaseInsensitiveAndTrimsWildcardPrefix() {
        assertTrue(isPopupHostAllowed("WWW.Example.COM", listOf("*.example.com")))
        assertTrue(isPopupHostAllowed("example.com", listOf("  Example.com  ")))
    }

    @Test
    fun blankHostNeverMatches() {
        assertFalse(isPopupHostAllowed("", listOf("example.com")))
    }

    @Test
    fun blocklistTakesPrecedenceOverAllowlist() {
        assertEquals(
            PopupHostPolicy.Blocked,
            popupHostPolicy(
                "files.example.com",
                allowlist = listOf("example.com"),
                blocklist = listOf("files.example.com"),
            ),
        )
    }

    @Test
    fun normalizesHostsAndUrls() {
        assertEquals("files.example.com", normalizeBrowserHost(" HTTPS://Files.Example.com/path "))
        assertEquals("example.com", normalizeBrowserHost("*.Example.com."))
        assertEquals(null, normalizeBrowserHost("not a host"))
    }

    @Test
    fun effectiveAllowlistSupportsRemovingConfiguredHosts() {
        val settings = BrowserSettings(
            allowedPopupHosts = setOf("added.example"),
            blockedPopupHosts = setOf("blocked.example"),
            disabledConfiguredPopupHosts = setOf("removed.example"),
        )
        assertEquals(
            setOf("kept.example", "added.example"),
            effectivePopupAllowlist(
                listOf("kept.example", "removed.example", "blocked.example"),
                settings,
            ),
        )
    }

    @Test
    fun downloadNameClampPreservesShortExtension() {
        val long = "a".repeat(300) + ".apk"
        val clamped = BrowserDownloads.limitFileName(long, maxChars = 180)
        assertTrue(clamped.length <= 180)
        assertTrue(clamped.endsWith(".apk"))
    }

    @Test
    fun downloadNameClampLeavesShortNamesUnchanged() {
        assertEquals("game.apk", BrowserDownloads.limitFileName("game.apk", maxChars = 180))
    }

    @Test
    fun downloadNameClampHandlesNoExtension() {
        val long = "b".repeat(250)
        val clamped = BrowserDownloads.limitFileName(long, maxChars = 180)
        assertEquals(180, clamped.length)
    }

    @Test
    fun browserUserAgentRemovesOnlyWebViewMarker() {
        val webView =
            "Mozilla/5.0 (Linux; Android 16; SM-F966U Build/BP2A; wv) " +
                "AppleWebKit/537.36 Version/4.0 Chrome/138.0.7204.157 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; SM-F966U Build/BP2A) " +
                "AppleWebKit/537.36 Version/4.0 Chrome/138.0.7204.157 Mobile Safari/537.36",
            browserCompatibleUserAgent(webView),
        )
    }

    @Test
    fun browserUserAgentLeavesChromeIdentityUnchanged() {
        val chrome =
            "Mozilla/5.0 (Linux; Android 16; SM-F966U) AppleWebKit/537.36 " +
                "Chrome/138.0.7204.157 Mobile Safari/537.36"

        assertEquals(chrome, browserCompatibleUserAgent(chrome))
    }

    @Test
    fun defaultPopupAllowlistIncludesCatalogAndDownloadHosts() {
        val defaults = AppConfig().effectiveBrowserPopupAllowlist
        assertTrue("f95zone.to" in defaults)
        assertTrue("kimochi.info" in defaults)
        assertTrue("otomi-games.com" in defaults)
        assertTrue("dikgames.com" in defaults)
        assertTrue("drive.google.com" in defaults)
        assertTrue("drive.usercontent.google.com" in defaults)
        assertTrue("mega.nz" in defaults)
        assertTrue("workupload.com" in defaults)
        assertTrue("pixeldrain.com" in defaults)
        assertTrue("gofile.io" in defaults)
        assertTrue("mediafire.com" in defaults)
    }

    @Test
    fun configuredPopupHostsAreMergedWithDefaults() {
        assertTrue("f95zone.to" in AppConfig(browserPopupAllowlist = listOf("example.com")).effectiveBrowserPopupAllowlist)
        assertTrue("example.com" in AppConfig(browserPopupAllowlist = listOf("example.com")).effectiveBrowserPopupAllowlist)
    }

    @Test
    fun knownAdNetworkSubresourcesAreBlocked() {
        assertTrue(
            isBrowserSubresourceBlocked(
                "https://pl29317436.profitablecpmratenetwork.com/ad.js",
                isForMainFrame = false,
            ),
        )
        assertTrue(
            isBrowserSubresourceBlocked(
                "https://cdn.redgarto.com/sb/interstitial/ad.js",
                isForMainFrame = false,
            ),
        )
    }

    @Test
    fun adNetworkTopLevelNavigationIsNotBlocked() {
        assertFalse(
            isBrowserSubresourceBlocked(
                "https://profitablecpmratenetwork.com/",
                isForMainFrame = true,
            ),
        )
    }

    @Test
    fun adNetworkSuffixRequiresDotBoundary() {
        assertFalse(
            isBrowserSubresourceBlocked(
                "https://notprofitablecpmratenetwork.com/ad.js",
                isForMainFrame = false,
            ),
        )
        assertFalse(isBrowserSubresourceBlocked("not a url", isForMainFrame = false))
    }

    @Test
    fun kimochiBlocksRotatingThirdPartySubresources() {
        assertTrue(
            isBrowserSubresourceBlocked(
                "https://random-ad-domain.example/injected.js",
                isForMainFrame = false,
                referrer = "https://kimochi.info/game/example/",
            ),
        )
        assertFalse(
            isBrowserSubresourceBlocked(
                "https://cdn.jsdelivr.net/library.js",
                isForMainFrame = false,
                referrer = "https://kimochi.info/game/example/",
            ),
        )
        assertFalse(
            isBrowserSubresourceBlocked(
                "https://drive.google.com/file/example",
                isForMainFrame = true,
                referrer = "https://kimochi.info/download/example/",
            ),
        )
    }
}
