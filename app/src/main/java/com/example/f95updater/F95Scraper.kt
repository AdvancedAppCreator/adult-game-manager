package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class F95Scraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val cookie: String? = null,
) {
    data class Result(val title: String, val parsedVersion: String?)

    suspend fun fetch(url: String): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (AdultGameManager/0.2)")
            if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${'$'}{resp.code}")
                val body = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(body)
                val title = doc.title().ifBlank {
                    doc.selectFirst("h1.p-title-value")?.text().orEmpty()
                }
                Result(title = title, parsedVersion = VersionCompare.extract(title))
            }
        }
    }
}
