package com.example.mangascraper

import org.jsoup.Jsoup
import java.net.URLEncoder

// Lightweight manga model used by the UI.
data class MangaItem(
    val title: String,
    val url: String,
    val coverUrl: String,
    val sourceName: String,
    val isNsfw: Boolean = false
)

data class ChapterItem(
    val title: String,
    val url: String,
    val number: Double
)

data class SourcePreferences(
    val enabled: Boolean = true,
    val showNsfw: Boolean = false,
    val customUserAgent: String? = null,
    val proxyUrl: String? = null
)

interface SourceExtension {
    val id: String
    val name: String
    val baseUrl: String
    val supportsNsfw: Boolean

    suspend fun search(query: String, scraper: ScraperService): List<MangaItem>
    suspend fun latest(scraper: ScraperService): List<MangaItem>
    suspend fun chapters(mangaUrl: String, scraper: ScraperService): List<ChapterItem>
    suspend fun pages(chapterUrl: String, scraper: ScraperService): List<String>
}

class SourceRegistry {
    companion object {
        fun all(): List<SourceExtension> = listOf(
            MangaFireSource(),
            MangaDexSource(),
            MangabuddySource()
        )
    }
}

class MangaFireSource : SourceExtension {
    override val id = "mangafire"
    override val name = "MangaFire"
    override val baseUrl = "https://mangafire.to"
    override val supportsNsfw = false

    override suspend fun search(query: String, scraper: ScraperService): List<MangaItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = scraper.fetchHtml("$baseUrl/search/?search=$q")
        return Jsoup.parse(html)
            .select("a[href].item-title, a[href].series-title, a[href].search-result")
            .mapNotNull { el ->
                val href = el.attr("abs:href")
                val title = el.text().trim().ifBlank { null } ?: return@mapNotNull null
                val cover = el.selectFirst("img")?.attr("abs:src")
                MangaItem(
                    title = title,
                    url = href,
                    coverUrl = cover ?: "",
                    sourceName = name
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun latest(scraper: ScraperService): List<MangaItem> = emptyList()
    override suspend fun chapters(mangaUrl: String, scraper: ScraperService): List<ChapterItem> = emptyList()
    override suspend fun pages(chapterUrl: String, scraper: ScraperService): List<String> = emptyList()
}

class MangaDexSource : SourceExtension {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"
    override val supportsNsfw = false

    override suspend fun search(query: String, scraper: ScraperService): List<MangaItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = scraper.fetchHtml("$baseUrl/title/$q")
        return Jsoup.parse(html)
            .select("a[href*=/title/]")
            .mapNotNull { el ->
                val href = el.attr("abs:href")
                val title = el.text().trim().ifBlank { null } ?: return@mapNotNull null
                MangaItem(
                    title = title,
                    url = href,
                    coverUrl = el.selectFirst("img")?.attr("abs:src") ?: "",
                    sourceName = name
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun latest(scraper: ScraperService): List<MangaItem> = emptyList()
    override suspend fun chapters(mangaUrl: String, scraper: ScraperService): List<ChapterItem> = emptyList()
    override suspend fun pages(chapterUrl: String, scraper: ScraperService): List<String> = emptyList()
}

class MangabuddySource : SourceExtension {
    override val id = "mangabuddy"
    override val name = "MangaBuddy"
    override val baseUrl = "https://mangabuddy.com"
    override val supportsNsfw = false

    override suspend fun search(query: String, scraper: ScraperService): List<MangaItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = scraper.fetchHtml("$baseUrl/?s=$q")
        return Jsoup.parse(html)
            .select("a[href*=/manga/]")
            .mapNotNull { el ->
                val href = el.attr("abs:href")
                val title = el.text().trim().ifBlank { null } ?: return@mapNotNull null
                MangaItem(
                    title = title,
                    url = href,
                    coverUrl = el.selectFirst("img")?.attr("abs:src") ?: "",
                    sourceName = name
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun latest(scraper: ScraperService): List<MangaItem> = emptyList()
    override suspend fun chapters(mangaUrl: String, scraper: ScraperService): List<ChapterItem> = emptyList()
    override suspend fun pages(chapterUrl: String, scraper: ScraperService): List<String> = emptyList()
}