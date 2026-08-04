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

    suspend fun downloadSingleMedia(
        mediaUrl: String,
        mediaType: MediaType,
        preferredQuality: String,
        destinationTreeUri: Uri?,
        premiumModeEnabled: Boolean,
        premiumDomains: List<String>,
        progress: (String) -> Unit
    ): List<MediaResult> = withContext(Dispatchers.IO) {
        val normalizedUrl = buildUrl(mediaUrl) ?: return@withContext emptyList()
        val destinationRootFolder = destinationTreeUri?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)
        }
        scraper.configurePremiumMode(premiumModeEnabled, premiumDomains)

        val candidateUrls = mutableListOf<String>()
        if (isUnsupportedStreamingHost(normalizedUrl)) {
            progress(
                "Unsupported host for direct download. Use the YT-DLP mode for streaming service URLs."
            )
            return@withContext emptyList()
        }

        if (looksLikeContentMedia(normalizedUrl, mediaType)) {
            candidateUrls += normalizedUrl
        } else {
            try {
                val html = scraper.fetchHtml(normalizedUrl)
                val document = Jsoup.parse(html, normalizedUrl)
                candidateUrls += extractMediaLinks(document, setOf(mediaType), premiumModeEnabled, premiumDomains).map { it.first }
            } catch (e: Exception) {
                progress("Failed to resolve media URL: ${e.message}")
            }
        }

        val selectedUrl = MediaQualitySelector.pickPreferredMediaUrl(candidateUrls, mediaType, preferredQuality)
            ?: return@withContext emptyList()

        progress("Downloading ${mediaType.label}: $selectedUrl")
        val bytes = scraper.fetchBytes(selectedUrl)
        if (bytes.isEmpty()) {
            progress("Downloaded empty payload from $selectedUrl")
            return@withContext emptyList()
        }

        val filename = chooseFilename(selectedUrl, mediaType)
        val saved = if (destinationRootFolder != null && destinationRootFolder.exists()) {
            saveToDocumentFolder(destinationRootFolder, mediaType, filename, bytes)
        } else {
            saveToFileSystem(mediaType, filename, bytes)
        }

        if (saved == null) {
            progress("Failed to save ${mediaType.label} file")
            return@withContext emptyList()
        }

        val destinationPath = destinationRootFolder?.uri?.toString() ?: File(context.getExternalFilesDir(null), "media").absolutePath
        progress("Saved ${mediaType.label} as $saved under $destinationPath")
        listOf(
            MediaResult(
                url = selectedUrl,
                type = mediaType,
                filename = saved,
                sizeKb = bytes.size / 1024,
                sourcePage = normalizedUrl
            )
        )
    }

    suspend fun downloadYtDlpMedia(
        mediaUrl: String,
        mediaType: MediaType,
        preferredQuality: String,
        destinationTreeUri: Uri?,
        premiumModeEnabled: Boolean,
        premiumDomains: List<String>,
        progress: (String) -> Unit
    ): List<MediaResult> = withContext(Dispatchers.IO) {
        val normalizedUrl = buildUrl(mediaUrl) ?: return@withContext emptyList()
        val destinationRootFolder = destinationTreeUri?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)
        }

        val ytDlpCommand = findYtDlpCommand(progress) ?: return@withContext emptyList()
        val tempDir = File(context.cacheDir, "yt_dlp_temp_${System.nanoTime()}")
        tempDir.mkdirs()
        val outputTemplate = File(tempDir, "download.%(ext)s").absolutePath
        val format = buildYtDlpFormat(mediaType, preferredQuality)

        progress("Running yt-dlp with format $format")
        val command = ytDlpCommand + listOf("-f", format, "-o", outputTemplate, normalizedUrl)
        val exitCode = runCommand(command, progress)
        if (exitCode != 0) {
            progress("yt-dlp failed with exit code $exitCode")
            tempDir.deleteRecursively()
            return@withContext emptyList()
        }

        val downloadedFile = tempDir.listFiles()?.firstOrNull { it.isFile }
        if (downloadedFile == null) {
            progress("yt-dlp did not produce a downloadable file")
            tempDir.deleteRecursively()
            return@withContext emptyList()
        }

        val fileBytes = downloadedFile.readBytes()
        val savedName = downloadedFile.name
        val saved = if (destinationRootFolder != null && destinationRootFolder.exists()) {
            saveToDocumentFolder(destinationRootFolder, mediaType, savedName, fileBytes)
        } else {
            saveToFileSystem(mediaType, chooseFilename(downloadedFile.absolutePath, mediaType), fileBytes)
        }
        tempDir.deleteRecursively()

        if (saved == null) {
            progress("Failed to save yt-dlp output file")
            return@withContext emptyList()
        }

        val destinationPath = destinationRootFolder?.uri?.toString() ?: File(context.getExternalFilesDir(null), "media").absolutePath
        progress("Saved ${mediaType.label} as $saved under $destinationPath")
        listOf(
            MediaResult(
                url = normalizedUrl,
                type = mediaType,
                filename = saved,
                sizeKb = fileBytes.size / 1024,
                sourcePage = normalizedUrl
            )
        )
    }

    private fun findYtDlpCommand(progress: (String) -> Unit): List<String>? {
        val candidates = listOf(
            listOf("yt-dlp"),
            listOf("python3", "-m", "yt_dlp"),
            listOf("python", "-m", "yt_dlp")
        )

        candidates.forEach { candidate ->
            if (checkCommandAvailable(candidate, progress)) return candidate
        }

        progress("yt-dlp is not installed or not accessible on this device.")
        if (tryInstallYtDlpAutomatically(progress)) {
            candidates.forEach { candidate ->
                if (checkCommandAvailable(candidate, progress)) return candidate
            }
        }

        progress("yt-dlp could not be installed automatically.")
        progress("Please install yt-dlp on the device or use the direct media download mode for standard file URLs.")
        return null
    }

    private fun checkCommandAvailable(command: List<String>, progress: (String) -> Unit): Boolean {
        return try {
            val process = ProcessBuilder(command + listOf("--version"))
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { progress(it) }
            }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun tryInstallYtDlpAutomatically(progress: (String) -> Unit): Boolean {
        val installCandidates = listOf(
            listOf("python3", "-m", "pip", "install", "--user", "yt-dlp"),
            listOf("python", "-m", "pip", "install", "--user", "yt-dlp"),
            listOf("pip3", "install", "--user", "yt-dlp"),
            listOf("pip", "install", "--user", "yt-dlp"),
            listOf("python3", "-m", "pip", "install", "yt-dlp"),
            listOf("python", "-m", "pip", "install", "yt-dlp")
        )

        installCandidates.forEach { installCommand ->
            try {
                progress("Attempting to install yt-dlp using: ${installCommand.joinToString(" ")}")
                val installResult = runCommand(installCommand, progress)
                if (installResult == 0) {
                    progress("Successfully installed yt-dlp.")
                    return true
                }
            } catch (e: Exception) {
                progress("Automatic install failed: ${e.message}")
            }
        }

        progress("Automatic yt-dlp installation failed."
            + " This device may not have a supported Python or pip environment.")
        progress("Install yt-dlp manually, or run the app on a device with Python/pip available.")
        return false
    }

    private fun buildYtDlpFormat(mediaType: MediaType, preferredQuality: String): String {
        return if (mediaType == MediaType.AUDIO) {
            when (preferredQuality.lowercase()) {
                "192kbps" -> "bestaudio[abr<=192]/bestaudio"
                "128kbps" -> "bestaudio[abr<=128]/bestaudio"
                "64kbps" -> "bestaudio[abr<=64]/bestaudio"
                else -> "bestaudio/best"
            }
        } else {
            when (preferredQuality.lowercase()) {
                "bestvideo+bestaudio" -> "bestvideo+bestaudio/best"
                "1080p" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]"
                "720p" -> "bestvideo[height<=720]+bestaudio/best[height<=720]"
                "480p" -> "bestvideo[height<=480]+bestaudio/best[height<=480]"
                "360p" -> "bestvideo[height<=360]+bestaudio/best[height<=360]"
                else -> "bestvideo+bestaudio/best"
            }
        }
    }

    private fun runCommand(command: List<String>, progress: (String) -> Unit): Int {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { progress(it) }
            }
            process.waitFor()
        } catch (e: Exception) {
            progress("Failed to execute yt-dlp: ${e.message}")
            -1
        }
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
                "[data-media]",
                "[srcset]",
                "[data-srcset]"
            )
            document.select(imageSelectors.joinToString(", ")).forEach { element ->
                val candidate = when {
                    element.hasAttr("src") -> element.attr("abs:src")
                    element.hasAttr("data-src") -> element.attr("abs:data-src")
                    element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
                    element.hasAttr("data-original") -> element.attr("abs:data-original")
                    element.hasAttr("data-media") -> element.attr("abs:data-media")
                    element.hasAttr("srcset") -> element.attr("srcset")
                    element.hasAttr("data-srcset") -> element.attr("data-srcset")
                    element.hasAttr("content") -> element.attr("abs:content")
                    else -> null
                }
                candidate?.let { value ->
                    val normalizedCandidates = extractCandidatesFromAttribute(value)
                    normalizedCandidates.forEach { candidateUrl ->
                        if (looksLikeContentMedia(candidateUrl, MediaType.IMAGES)) {
                            links.add(candidateUrl to MediaType.IMAGES)
                        }
                    }
                }
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
                candidate?.let { value ->
                    extractCandidatesFromAttribute(value).forEach { candidateUrl ->
                        val type = when {
                            typeMatches(candidateUrl, MediaType.VIDEOS) -> MediaType.VIDEOS
                            typeMatches(candidateUrl, MediaType.AUDIO) -> MediaType.AUDIO
                            typeMatches(candidateUrl, MediaType.IMAGES) -> MediaType.IMAGES
                            else -> null
                        }
                        if (type != null && looksLikeContentMedia(candidateUrl, type)) {
                            links.add(candidateUrl to type)
                        }
                    }
                }
            }

            document.select("script").forEach { script ->
                val scriptText = script.data()
                val mediaRegex = Regex("https?://[^\\s\"'<>]+\\.(jpg|jpeg|png|gif|webp|avif|mp4|webm|mov|mkv|m4v|ogg|wav|mp3)", RegexOption.IGNORE_CASE)
                mediaRegex.findAll(scriptText).forEach { match ->
                    val candidateUrl = match.value
                    val type = when {
                        typeMatches(candidateUrl, MediaType.VIDEOS) -> MediaType.VIDEOS
                        typeMatches(candidateUrl, MediaType.AUDIO) -> MediaType.AUDIO
                        typeMatches(candidateUrl, MediaType.IMAGES) -> MediaType.IMAGES
                        else -> null
                    }
                    if (type != null && looksLikeContentMedia(candidateUrl, type)) {
                        links.add(candidateUrl to type)
                    }
                }
            }
        }

        return links.distinctBy { it.first }
    }

    private fun extractCandidatesFromAttribute(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split(Regex("[,\\s]+"))
            .mapNotNull { segment ->
                val cleaned = segment.trim().removeSuffix(",")
                if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) cleaned else null
            }
    }

    private fun looksLikeContentMedia(url: String, type: MediaType): Boolean {
        val lower = url.lowercase()
        val nonContentKeywords = listOf("logo", "favicon", "icon", "avatar", "badge", "sprite", "placeholder", "spinner", "loading", "default", "thumb", "thumbnail")
        if (nonContentKeywords.any { lower.contains(it) }) return false
        if (lower.contains("/static/") || lower.contains("/assets/")) return false
        return typeMatches(url, type)
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

    private fun isUnsupportedStreamingHost(url: String): Boolean {
        return try {
            val host = URL(url).host.lowercase()
            listOf("youtube.com", "youtu.be", "vimeo.com", "twitch.tv", "soundcloud.com", "dailymotion.com")
                .any { host == it || host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }
}

object MediaQualitySelector {
    fun pickPreferredMediaUrl(candidates: List<String>, type: MediaType, preferredQuality: String): String? {
        val uniqueCandidates = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (uniqueCandidates.isEmpty()) return null

        val normalizedPreferred = preferredQuality.trim().lowercase()
        if (normalizedPreferred.isBlank() || normalizedPreferred == "best" || normalizedPreferred == "highest" || normalizedPreferred == "auto") {
            return uniqueCandidates.maxByOrNull { qualityScore(it, type) }
        }

        val exactMatches = uniqueCandidates.filter { candidate ->
            val lower = candidate.lowercase()
            lower.contains(normalizedPreferred) || lower.contains(normalizedPreferred.replace("kbps", ""))
        }
        return exactMatches.firstOrNull() ?: uniqueCandidates.maxByOrNull { qualityScore(it, type) }
    }

    private fun qualityScore(candidate: String, type: MediaType): Int {
        val lower = candidate.lowercase()
        return when (type) {
            MediaType.VIDEOS -> {
                listOf(
                    "2160p" to 2160,
                    "1440p" to 1440,
                    "1080p" to 1080,
                    "720p" to 720,
                    "480p" to 480,
                    "360p" to 360,
                    "240p" to 240,
                    "144p" to 144,
                    "4k" to 2160,
                    "uhd" to 2160,
                    "fullhd" to 1080,
                    "hd" to 720
                ).firstOrNull { (token, _) -> lower.contains(token) }?.second ?: 0
            }
            MediaType.AUDIO -> {
                listOf(
                    "320kbps" to 320,
                    "256kbps" to 256,
                    "192kbps" to 192,
                    "160kbps" to 160,
                    "128kbps" to 128,
                    "96kbps" to 96,
                    "64kbps" to 64
                ).firstOrNull { (token, _) -> lower.contains(token) }?.second ?: 0
            }
            else -> 0
        }
    }
}
