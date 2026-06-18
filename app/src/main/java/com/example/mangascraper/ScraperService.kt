package com.example.mangascraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class ScraperService(
    private val cacheDir: File? = null,
    private val proxyUrl: String? = null,
    private val userAgents: List<String> = DEFAULT_USER_AGENTS
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(cacheDir?.let { Cache(it, 50L * 1024L * 1024L) })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .proxy(buildProxy(proxyUrl))
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", pickRandomUserAgent())
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", original.url.host)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        fetchWithRetry(url) { response -> response.body?.string() ?: "" }
    }

    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        fetchWithRetry(url) { response -> response.body?.bytes() ?: ByteArray(0) }
    }

    suspend fun extractLinks(html: String): List<String> = withContext(Dispatchers.Default) {
        Jsoup.parse(html)
            .select("a[href]")
            .mapNotNull { it.attr("abs:href") }
            .filter { it.isNotBlank() && it.startsWith("http") }
            .distinct()
    }

    suspend fun extractImageUrls(html: String): List<String> = withContext(Dispatchers.Default) {
        Jsoup.parse(html)
            .select("img[src], source[src], meta[property=og:image], meta[name=og:image]")
            .mapNotNull { element ->
                when {
                    element.hasAttr("src") -> element.attr("abs:src")
                    element.hasAttr("content") -> element.attr("abs:content")
                    else -> null
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun setProxy(url: String?) {
        // The client is immutable here; callers can create a new service instance with the proxy.
    }

    private fun buildProxy(proxyUrl: String?): Proxy? {
        if (proxyUrl.isNullOrBlank()) return null
        return try {
            val host = proxyUrl.substringAfter("//").substringBefore(":")
            val port = proxyUrl.substringAfter(":").substringBefore("/").toInt()
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } catch (_: Exception) {
            null
        }
    }

    private fun pickRandomUserAgent(): String = userAgents.random()

    private inline fun <T> fetchWithRetry(
        url: String,
        crossinline parser: (Response) -> T
    ): T {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (attempt == 2) {
                            throw IllegalStateException("HTTP ${response.code} for $url")
                        }
                    } else {
                        return parser(response)
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Failed to fetch $url")
    }

    companion object {
        private val DEFAULT_USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 10; Mobile; rv:125.0) Gecko/20100101 Firefox/125.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Safari/537.36"
        )
    }
}
