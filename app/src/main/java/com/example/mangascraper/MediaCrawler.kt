package com.example.mangascraper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLEncoder

enum class MediaType(val label: String, val extensions: List<String>) {
    IMAGES("Images", listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg")),
    VIDEOS("Videos", listOf(".mp4", ".webm", ".mov", ".mkv")),
    AUDIO("Audio", listOf(".mp3", ".ogg", ".wav", ".m4a")),
    DOCUMENTS("Documents", listOf(".pdf", ".epub", ".docx", ".txt", ".md")),
    ARCHIVES("Archives", listOf(".zip", ".rar", ".7z", ".tar", ".gz")),
    EBOOKS("eBooks", listOf(".mobi", ".azw3", ".fb2"))
}

data class MediaResult(
    val url: String,
    val type: MediaType,
    val filename: String,
    val sizeKb: Int,
    val sourcePage: String
)

class MediaCrawler(private val context: Context, private val scraper: ScraperService) {
    suspend fun crawl(
        startUrl: String,
        selectedTypes: Set<MediaType>,
        minSizeKb: Int,
        maxDepth: Int,
        maxPages: Int,
        sameDomain: Boolean,
        progress: (String) -> Unit
    ): List<MediaResult> = withContext(Dispatchers.IO) {
        val normalizedUrl = buildUrl(startUrl) ?: return@withContext emptyList()
        val visited = mutableSetOf<String>()
        val results = mutableListOf<MediaResult>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(normalizedUrl to 0)
        val rootDomain = URL(normalizedUrl).host
        val types = if (selectedTypes.isEmpty()) MediaType.values().toSet() else selectedTypes
        val destinationRoot = File(context.getExternalFilesDir(null), "media").apply { mkdirs() }

        while (queue.isNotEmpty() && visited.size < maxPages) {
            val (url, depth) = queue.removeFirst()
            if (visited.contains(url) || depth > maxDepth) continue

            progress("Fetching $url")
            try {
                val html = scraper.fetchHtml(url)
                val document = Jsoup.parse(html, url)
                visited.add(url)
                val mediaLinks = extractMediaLinks(document, types)

                for ((link, type) in mediaLinks) {
                    if (results.count { it.type == type } >= maxPages) break
                    try {
                        progress("Downloading ${type.label}: $link")
                        val bytes = scraper.fetchBytes(link)
                        if (bytes.size / 1024 < minSizeKb) {
                            progress("Skipped ${link}: below size threshold")
                            continue
                        }
                        val filename = chooseFilename(link, type)
                        val folder = File(destinationRoot, type.label)
                        folder.mkdirs()
                        val file = File(folder, filename)
                        file.writeBytes(bytes)
                        results += MediaResult(
                            url = link,
                            type = type,
                            filename = file.name,
                            sizeKb = bytes.size / 1024,
                            sourcePage = url
                        )
                    } catch (e: Exception) {
                        progress("Download failed: ${e.message}")
                    }
                }

                if (depth < maxDepth) {
                    extractLinks(document, url, sameDomain, rootDomain).forEach { nextUrl ->
                        if (nextUrl !in visited) queue.add(nextUrl to depth + 1)
                    }
                }
            } catch (e: Exception) {
                progress("Failed to crawl $url: ${e.message}")
            }
        }

        progress("Crawl finished. Saved files under ${destinationRoot.absolutePath}")
        return@withContext results
    }

    private fun buildUrl(input: String): String? {
        return try {
            var fixed = input.trim()
            if (!fixed.startsWith("http://") && !fixed.startsWith("https://")) {
                fixed = "https://$fixed"
            }
            URL(fixed).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun chooseFilename(url: String, type: MediaType): String {
        val path = URL(url).path
        val raw = path.substringAfterLast('/').takeIf { it.isNotBlank() && it.contains('.') } ?: "download"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun extractMediaLinks(document: org.jsoup.nodes.Document, types: Set<MediaType>): List<Pair<String, MediaType>> {
        val links = mutableListOf<Pair<String, MediaType>>()
        val base = document.baseUri()

        if (types.contains(MediaType.IMAGES)) {
            document.select("img[src], source[src], meta[property=og:image], meta[name=og:image]").forEach { element ->
                val candidate = when {
                    element.hasAttr("src") -> element.attr("abs:src")
                    element.hasAttr("content") -> element.attr("abs:content")
                    else -> null
                }
                candidate?.takeIf { it.isNotBlank() }?.let { links.add(it to MediaType.IMAGES) }
            }
        }

        if (types.contains(MediaType.VIDEOS) || types.contains(MediaType.AUDIO)) {
            document.select("video[src], audio[src], source[src]").forEach { element ->
                val src = element.attr("abs:src")
                if (src.isNotBlank()) {
                    val type = when {
                        types.contains(MediaType.VIDEOS) && typeMatches(src, MediaType.VIDEOS) -> MediaType.VIDEOS
                        types.contains(MediaType.AUDIO) && typeMatches(src, MediaType.AUDIO) -> MediaType.AUDIO
                        else -> null
                    }
                    if (type != null) links.add(src to type)
                }
            }
        }

        val fileTypes = types.filter { it in setOf(MediaType.DOCUMENTS, MediaType.ARCHIVES, MediaType.EBOOKS) }
        if (fileTypes.isNotEmpty()) {
            document.select("a[href]").forEach { element ->
                val href = element.attr("abs:href")
                if (href.isBlank()) return@forEach
                fileTypes.forEach { type ->
                    if (typeMatches(href, type)) {
                        links.add(href to type)
                    }
                }
            }
        }

        return links.distinctBy { it.first }
    }

    private fun extractLinks(document: org.jsoup.nodes.Document, baseUrl: String, sameDomain: Boolean, rootDomain: String): List<String> {
        return document.select("a[href]")
            .map { it.attr("abs:href") }
            .mapNotNull { link ->
                link.takeIf { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
            }
            .filter { if (!sameDomain) true else URL(it).host == rootDomain }
            .distinct()
    }

    private fun typeMatches(url: String, type: MediaType): Boolean {
        val lowercase = url.lowercase()
        return type.extensions.any { lowercase.endsWith(it) }
    }
}
