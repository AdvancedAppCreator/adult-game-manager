package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URI
import java.util.concurrent.TimeUnit

data class ExternalMirrorResult(
    val title: String,
    val mirrorUrl: String,
    val officialUrl: String?,
    val threadId: Int?,
    val version: String?,
    val sourceHost: String = "",
)

/**
 * Looks up F95Zone thread URLs by name using DuckDuckGo's HTML search endpoint
 * (Google aggressively blocks scrapers; DDG is tolerant when called with a real
 * User-Agent). Returns a normalized canonical thread URL, or null if no match.
 */
class WebSearcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val ua = "Mozilla/5.0 (Linux; Android 14; SM-S918U) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val threadIdRe = Regex("""f95zone\.to/threads/(?:[^/]*\.)?(\d+)/?""")
    private val f95HostRe = Regex("""(^|\.)f95zone\.""", RegexOption.IGNORE_CASE)
    private val titleVersionRe = Regex("""\[(?:v|ep\.?|episode|ch\.?|chapter)?\s*([^\]]+)]""", RegexOption.IGNORE_CASE)

    /** Returns canonical https://f95zone.to/threads/<id>/ or null. */
    suspend fun findF95Thread(label: String): String? = withContext(Dispatchers.IO) {
        val query = "site:f95zone.to/threads \"$label\""
        val url = "https://html.duckduckgo.com/html/?q=" +
            URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val html = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(html)
                // DDG result anchors carry class="result__a" and href is /l/?uddg=<encoded>
                for (a in doc.select("a.result__a, a.result__url, .result__title a")) {
                    val raw = a.attr("href")
                    val resolved = resolveDdgRedirect(raw)
                    val m = threadIdRe.find(resolved) ?: continue
                    // Skip results that don't share at least one significant word with the label.
                    if (!isLabelMatch(label, resolved)) continue
                    return@withContext "https://f95zone.to/threads/${m.groupValues[1]}/"
                }
                null
            }
        }.getOrNull()
    }

    suspend fun searchExternalMirrors(query: String, limit: Int = 8): List<ExternalMirrorResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val results = linkedMapOf<String, ExternalMirrorResult>()
        val variants = mirrorQueryVariants(q)
        AppLog.i("ExternalMirror", "Search start query='$q' variants=${variants.joinToString(prefix = "[", postfix = "]") { "'$it'" }}")
        runCatching {
            webF95Search(q, variants, limit).forEach { result ->
                if (results.size < limit) results.putIfAbsent(result.mirrorUrl, result)
            }
            for (variant in variants) {
                if (results.size >= limit) break
                val url = "https://f95zone.to.it/?s=" + URLEncoder.encode(variant, "UTF-8")
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppLog.w("ExternalMirror", "Search HTTP ${resp.code} for variant='$variant'")
                        return@use
                    }
                    val doc = Jsoup.parse(resp.body?.string().orEmpty(), "https://f95zone.to.it/")
                    val candidates = doc.select("h2 a, h3 a, article a[href]")
                        .asSequence()
                        .mapNotNull { a ->
                            val href = a.absUrl("href").takeIf { it.startsWith("https://f95zone.to.it/") } ?: return@mapNotNull null
                            val title = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            title to href
                        }
                        .distinctBy { it.second }
                        .toList()
                    val matched = candidates.filter { (title, href) -> isLabelMatch(variant, "$title $href") }
                    AppLog.i(
                        "ExternalMirror",
                        "Search variant='$variant' candidates=${candidates.size} matched=${matched.size} " +
                            matched.take(4).joinToString(prefix = "[", postfix = "]") { "'${it.first}'" }
                    )
                    for ((title, href) in matched) {
                        if (results.size >= limit) break
                        results.getOrPut(href) { mirrorResult(title, href) }
                    }
                }
            }
        }.onFailure {
            AppLog.w("ExternalMirror", "Search failed query='$q': ${it.message}", it)
        }
        AppLog.i("ExternalMirror", "Search done query='$q' results=${results.size}")
        results.values.toList()
    }

    private fun webF95Search(query: String, variants: List<String>, limit: Int): List<ExternalMirrorResult> {
        val titleQuery = variants.lastOrNull().takeUnless { it.isNullOrBlank() } ?: query
        val searchQuery = "\"$titleQuery\" f95zone"
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(searchQuery, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val results = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w("ExternalMirror", "Web search HTTP ${resp.code} for query='$searchQuery'")
                    return emptyList()
                }
                val doc = Jsoup.parse(resp.body?.string().orEmpty())
                doc.select("a.result__a, .result__title a")
                    .mapNotNull { a ->
                        val href = resolveDdgRedirect(a.attr("href"))
                        val title = a.text().trim()
                        val host = hostOf(href)
                        if (href.isBlank() || title.isBlank() || !f95HostRe.containsMatchIn(host)) return@mapNotNull null
                        SearchHit(title, href, host)
                    }
                    .distinctBy { it.url }
            }
        }.getOrElse {
            AppLog.w("ExternalMirror", "Web search failed query='$searchQuery': ${it.message}", it)
            emptyList()
        }
        val ranked = results
            .filter { isTitleMatch(query, it.title) || isLabelMatch(query, "${it.title} ${it.url}") }
            .sortedWith(
                compareByDescending<SearchHit> { if (it.host.equals("f95zone.to", ignoreCase = true) && isTitleMatch(query, it.title)) 1 else 0 }
                    .thenByDescending { if (f95HostRe.containsMatchIn(it.host)) 1 else 0 }
                    .thenByDescending { if (isTitleMatch(query, it.title)) 1 else 0 }
            )
            .take(limit)
            .map { hit ->
                val tid = threadIdRe.find(hit.url)?.groupValues?.get(1)?.toIntOrNull()
                ExternalMirrorResult(
                    title = hit.title,
                    mirrorUrl = tid?.let { "https://f95zone.to/threads/$it/" } ?: hit.url,
                    officialUrl = tid?.let { "https://f95zone.to/threads/$it/" },
                    threadId = tid,
                    version = titleVersionRe.findAll(hit.title).lastOrNull()?.groupValues?.getOrNull(1)?.trim(),
                    sourceHost = hit.host,
                )
            }
        AppLog.i(
            "ExternalMirror",
            "Web search query='$searchQuery' candidates=${results.size} matched=${ranked.size} " +
                ranked.take(4).joinToString(prefix = "[", postfix = "]") { "'${it.title}' from ${it.sourceHost}" }
        )
        return ranked
    }

    private fun mirrorResult(title: String, mirrorUrl: String): ExternalMirrorResult {
        val officialRaw = runCatching {
            val req = Request.Builder()
                .url(mirrorUrl)
                .header("User-Agent", ua)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string().orEmpty()
                chooseOfficialThreadLink(html, title, mirrorUrl)
            }
        }.getOrNull()
        val tid = threadIdRe.find(officialRaw.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        AppLog.i(
            "ExternalMirror",
            "Result title='$title' mirror='$mirrorUrl' official=${officialRaw ?: "none"}"
        )
        return ExternalMirrorResult(
            title = title,
            mirrorUrl = mirrorUrl,
            officialUrl = tid?.let { "https://f95zone.to/threads/$it/" },
            threadId = tid,
            version = titleVersionRe.findAll(title).lastOrNull()?.groupValues?.getOrNull(1)?.trim(),
            sourceHost = hostOf(mirrorUrl),
        )
    }

    private data class SearchHit(
        val title: String,
        val url: String,
        val host: String,
    )

    private fun resolveDdgRedirect(raw: String): String {
        if (raw.startsWith("//duckduckgo.com/l/") || raw.startsWith("/l/")) {
            val q = raw.substringAfter("uddg=", "")
            if (q.isNotEmpty()) return URLDecoder.decode(q.substringBefore('&'), "UTF-8")
        }
        if (raw.startsWith("https://duckduckgo.com/l/")) {
            val q = raw.substringAfter("uddg=", "")
            if (q.isNotEmpty()) return URLDecoder.decode(q.substringBefore('&'), "UTF-8")
        }
        return raw
    }

    private fun isLabelMatch(label: String, url: String): Boolean {
        val urlLower = url.lowercase()
        val urlNorm = urlLower.filter { it.isLetterOrDigit() }
        val tokens = significantTokens(label)
        if (tokens.isEmpty()) return true
        return tokens.all { token ->
            val norm = token.filter { it.isLetterOrDigit() }
            urlLower.contains(token) || (norm.length >= 3 && urlNorm.contains(norm))
        }
    }

    private fun isTitleMatch(query: String, resultTitle: String): Boolean {
        val queryTokens = significantTokens(query)
        if (queryTokens.isEmpty()) return false
        val resultNorm = resultTitle
            .replace(Regex("\\[[^\\]]*]"), " ")
            .filter { it.isLetterOrDigit() }
            .lowercase()
        return queryTokens.all { token ->
            val norm = token.filter { it.isLetterOrDigit() }
            norm.length >= 3 && resultNorm.contains(norm)
        }
    }

    private fun chooseOfficialThreadLink(html: String, title: String, mirrorUrl: String): String? {
        val links = threadIdRe.findAll(html)
            .map { it.value }
            .distinct()
            .toList()
        if (links.isEmpty()) return null

        val titleCore = title.replace(Regex("\\[[^\\]]*]"), " ").trim()
        val mirrorSlug = mirrorUrl.substringAfter("f95zone.to.it/", "").substringBefore('/').replace('-', ' ')
        val probes = listOf(titleCore, mirrorSlug)
        val chosen = links.firstOrNull { link -> probes.any { isLabelMatch(it, link) } }
        if (chosen == null) {
            AppLog.w(
                "ExternalMirror",
                "No matching official thread for title='$title' mirror='$mirrorUrl' links=${links.take(6)}"
            )
        }
        return chosen
    }

    private fun significantTokens(text: String): List<String> {
        return text
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { token ->
                token.length >= 3 &&
                    token !in setOf("the", "and", "for", "with", "from", "game", "games", "final", "android", "win", "mac") &&
                    !token.matches(Regex("(?i)^(?:v\\d.*|ep\\d+|episode\\d+|ch\\d+|chapter\\d+|pc)$"))
            }
    }

    private fun mirrorQueryVariants(query: String): List<String> {
        val variants = linkedSetOf<String>()
        val trimmed = query.trim()
        variants += trimmed
        val spaced = trimmed
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z])([A-Z][a-z])"), "$1 $2")
            .replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
        if (spaced.isNotBlank()) variants += spaced
        val titleOnly = spaced
            .split(Regex("\\s+"))
            .filterNot { token ->
                token.equals("pc", ignoreCase = true) ||
                    token.equals("android", ignoreCase = true) ||
                    token.equals("win", ignoreCase = true) ||
                    token.equals("mac", ignoreCase = true) ||
                    token.matches(Regex("(?i)^(?:v\\d.*|ep\\d+|episode\\d+|ch\\d+|chapter\\d+)$"))
            }
            .joinToString(" ")
            .trim()
        if (titleOnly.isNotBlank()) variants += titleOnly
        return variants.toList()
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")
}
