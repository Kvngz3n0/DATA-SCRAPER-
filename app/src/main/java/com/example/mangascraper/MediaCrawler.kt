package com.example.mangascraper

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
        destinationTreeUri: Uri?,
        premiumModeEnabled: Boolean,
        premiumDomains: List<String>,
        progress: (String) -> Unit
    ): List<MediaResult> = withContext(Dispatchers.IO) {
        val normalizedUrl = buildUrl(startUrl) ?: return@withContext emptyList()
        val visited = mutableSetOf<String>()
        val results = mutableListOf<MediaResult>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(normalizedUrl to 0)
        val rootDomain = URL(normalizedUrl).host
        val types = if (selectedTypes.isEmpty()) MediaType.values().toSet() else selectedTypes

        val destinationRootFolder = destinationTreeUri?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)
        }
        scraper.configurePremiumMode(premiumModeEnabled, premiumDomains)

        while (queue.isNotEmpty() && visited.size < maxPages) {
            val (url, depth) = queue.removeFirst()
            if (visited.contains(url) || depth > maxDepth) continue

            progress("Fetching $url")
            try {
                val html = scraper.fetchHtml(url)
                val document = Jsoup.parse(html, url)
                visited.add(url)
                val mediaLinks = extractMediaLinks(document, types, premiumModeEnabled, premiumDomains)

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
                        val saved = if (destinationRootFolder != null && destinationRootFolder.exists()) {
                            saveToDocumentFolder(destinationRootFolder, type, filename, bytes)
                        } else {
                            saveToFileSystem(type, filename, bytes)
                        }

                        if (saved != null) {
                            results += MediaResult(
                                url = link,
                                type = type,
                                filename = saved,
                                sizeKb = bytes.size / 1024,
                                sourcePage = url
                            )
                        }
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

        val destinationPath = destinationRootFolder?.uri?.toString() ?: File(context.getExternalFilesDir(null), "media").absolutePath
        progress("Crawl finished. Saved files under $destinationPath")
        return@withContext results
    }

    private fun saveToDocumentFolder(root: DocumentFile, type: MediaType, filename: String, bytes: ByteArray): String? {
        val typeDirectory = root.findFile(type.label) as? DocumentFile ?: root.createDirectory(type.label)
        if (typeDirectory == null || !typeDirectory.isDirectory) {
            return null
        }
        val mimeType = when (type) {
            MediaType.IMAGES -> "image/*"
            MediaType.VIDEOS -> "video/*"
            MediaType.AUDIO -> "audio/*"
            MediaType.DOCUMENTS -> "application/pdf"
            MediaType.ARCHIVES -> "application/zip"
            MediaType.EBOOKS -> "application/epub+zip"
        }
        val file = typeDirectory.createFile(mimeType, filename) ?: return null
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
        return file.name ?: filename
    }

    private fun saveToFileSystem(type: MediaType, filename: String, bytes: ByteArray): String {
        val destinationRoot = File(context.getExternalFilesDir(null), "media")
        val folder = File(destinationRoot, type.label)
        folder.mkdirs()
        val file = File(folder, filename)
        file.writeBytes(bytes)
        return file.name
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

    private fun extractMediaLinks(
        document: org.jsoup.nodes.Document,
        types: Set<MediaType>,
        premiumModeEnabled: Boolean,
        premiumDomains: List<String>
    ): List<Pair<String, MediaType>> {
        val links = mutableListOf<Pair<String, MediaType>>()
        val base = document.baseUri()

        if (types.contains(MediaType.IMAGES)) {
            val imageSelectors = listOf(
                "img[src]",
                "img[data-src]",
                "img[data-lazy-src]",
                "img[data-original]",
                "source[src]",
                "meta[property=og:image]",
                "meta[name=og:image]",
                "[data-src]",
                "[data-media]"
            )
            document.select(imageSelectors.joinToString(", ")).forEach { element ->
                val candidate = when {
                    element.hasAttr("src") -> element.attr("abs:src")
                    element.hasAttr("data-src") -> element.attr("abs:data-src")
                    element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
                    element.hasAttr("data-original") -> element.attr("abs:data-original")
                    element.hasAttr("data-media") -> element.attr("abs:data-media")
                    element.hasAttr("content") -> element.attr("abs:content")
                    else -> null
                }
                candidate?.takeIf { it.isNotBlank() }?.let { links.add(it to MediaType.IMAGES) }
            }
        }

        if (types.contains(MediaType.VIDEOS) || types.contains(MediaType.AUDIO)) {
            val mediaSelectors = listOf(
                "video[src]",
                "video[data-src]",
                "audio[src]",
                "audio[data-src]",
                "source[src]",
                "source[data-src]",
                "[data-video]",
                "[data-poster]"
            )
            document.select(mediaSelectors.joinToString(", ")).forEach { element ->
                val src = when {
                    element.hasAttr("src") -> element.attr("abs:src")
                    element.hasAttr("data-src") -> element.attr("abs:data-src")
                    element.hasAttr("data-video") -> element.attr("abs:data-video")
                    element.hasAttr("data-poster") -> element.attr("abs:data-poster")
                    else -> ""
                }
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

        if (premiumModeEnabled) {
            document.select("script, [data-url], [data-href]").forEach { element ->
                val candidate = when {
                    element.hasAttr("data-url") -> element.attr("abs:data-url")
                    element.hasAttr("data-href") -> element.attr("abs:data-href")
                    element.hasAttr("src") -> element.attr("abs:src")
                    else -> null
                }
                candidate?.takeIf { it.isNotBlank() && (typeMatches(it, MediaType.IMAGES) || typeMatches(it, MediaType.VIDEOS) || typeMatches(it, MediaType.AUDIO)) }?.let { links.add(it to MediaType.IMAGES) }
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
