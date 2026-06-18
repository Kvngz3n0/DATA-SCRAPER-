package com.example.mangascraper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.UUID

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

data class CustomSourceDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val itemSelector: String,
    val titleSelector: String,
    val coverSelector: String = "img",
    val hrefSelector: String = "a[href]"
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

class CustomSource(private val definition: CustomSourceDefinition) : SourceExtension {
    override val id: String = definition.id
    override val name: String = definition.name
    override val baseUrl: String = definition.baseUrl
    override val supportsNsfw: Boolean = true

    override suspend fun search(query: String, scraper: ScraperService): List<MangaItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (definition.baseUrl.contains("{query}")) {
            definition.baseUrl.replace("{query}", encodedQuery)
        } else {
            definition.baseUrl
        }

        val html = scraper.fetchHtml(url)
        return Jsoup.parse(html)
            .select(definition.itemSelector)
            .mapNotNull { element ->
                val href = element.selectFirst(definition.hrefSelector)?.attr("abs:href")
                    ?: element.attr("abs:href")
                val title = element.selectFirst(definition.titleSelector)?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val cover = element.selectFirst(definition.coverSelector)?.attr("abs:src") ?: ""
                MangaItem(
                    title = title,
                    url = href,
                    coverUrl = cover,
                    sourceName = definition.name
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun latest(scraper: ScraperService): List<MangaItem> = emptyList()
    override suspend fun chapters(mangaUrl: String, scraper: ScraperService): List<ChapterItem> = emptyList()
    override suspend fun pages(chapterUrl: String, scraper: ScraperService): List<String> = emptyList()
}

class SourceRegistry {
    companion object {
        private const val PREFS_NAME = "source_registry_prefs"
        private const val KEY_CUSTOM_SOURCES = "custom_sources"

        fun builtIn(): List<SourceExtension> = listOf(
            MangaFireSource(),
            MangaDexSource(),
            MangabuddySource()
        )

        fun all(context: Context): List<SourceExtension> =
            builtIn() + loadCustomDefinitions(context).map { CustomSource(it) }

        fun addCustomSource(context: Context, definition: CustomSourceDefinition) {
            val definitions = loadCustomDefinitions(context).toMutableList()
            definitions.removeAll { it.id == definition.id }
            definitions.add(definition)
            saveCustomDefinitions(context, definitions)
        }

        fun removeCustomSource(context: Context, sourceId: String) {
            val definitions = loadCustomDefinitions(context).filterNot { it.id == sourceId }
            saveCustomDefinitions(context, definitions)
        }

        private fun saveCustomDefinitions(context: Context, definitions: List<CustomSourceDefinition>) {
            val json = JSONArray()
            definitions.forEach { definition ->
                json.put(
                    JSONObject().apply {
                        put("id", definition.id)
                        put("name", definition.name)
                        put("baseUrl", definition.baseUrl)
                        put("itemSelector", definition.itemSelector)
                        put("titleSelector", definition.titleSelector)
                        put("coverSelector", definition.coverSelector)
                        put("hrefSelector", definition.hrefSelector)
                    }
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_SOURCES, json.toString())
                .apply()
        }

        private fun loadCustomDefinitions(context: Context): List<CustomSourceDefinition> {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_SOURCES, null)
                ?: return emptyList()

            val json = JSONArray(raw)
            return (0 until json.length()).mapNotNull { index ->
                json.optJSONObject(index)?.let { obj ->
                    CustomSourceDefinition(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Custom Source"),
                        baseUrl = obj.optString("baseUrl", ""),
                        itemSelector = obj.optString("itemSelector", "a[href]"),
                        titleSelector = obj.optString("titleSelector", "a[href]"),
                        coverSelector = obj.optString("coverSelector", "img"),
                        hrefSelector = obj.optString("hrefSelector", "a[href]")
                    )
                }
            }
        }
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