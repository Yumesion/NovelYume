package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JgardenProvider : MainAPI() {
    override val name = "J-Garden"
    override val mainUrl = "https://j-garden.fr"
    override val lang = "fr"
    override val iconId = R.drawable.icon_jgarden
    override val hasMainPage = true
    override val rateLimitTime = 500L

    override val mainCategories = listOf(
        "Light Novels" to "/jg-ln/",
        "Autres LNs" to "/jg-autres-lns/",
        "Web Novels" to "/jg-web-novel/",
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val path = mainCategory ?: "/jg-ln/"
        val url = mainUrl + path
        val doc = app.get(url).document
        val novels = doc.select(CARD_SELECTOR).mapNotNull(::cardFromElement).distinctBy { it.url }
        return HeadMainPageResponse(url, novels)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/wp-json/wp/v2/search?search=$encoded&subtype=page&per_page=20"
        val data = app.get(url).parsed<List<SearchResultJson>>()
        return data.mapNotNull { r ->
            val title = r.title ?: return@mapNotNull null
            val link = r.url ?: return@mapNotNull null
            val slug = link.trimEnd('/').substringAfterLast('/')
            if (slug in NAV_SLUGS) return@mapNotNull null
            newSearchResponse(title, link)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val cover = doc.select("img[src]").firstOrNull {
            val s = it.attr("src")
            s.contains("/wp-content/uploads/") && !s.contains("logo", ignoreCase = true)
        }?.attr("src")
        val author = doc.select("p").firstOrNull { it.text().contains("Auteur") }?.text()
            ?.substringAfter("Auteur :")?.trim()
        val chapters = doc.select(CHAPTER_SELECTOR).mapNotNull(::chapterFromElement)
        return newStreamResponse(title, url, chapters) {
            posterUrl = fixUrlNull(cover)
            this.author = author
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val doc = app.get(url).document
        val content = doc.selectFirst(".elementor-widget-text-editor .elementor-widget-container")
        content?.selectFirst("h1")?.remove()
        return content?.html()
    }

    private fun cardFromElement(element: Element): SearchResponse? {
        val href = fixUrl(element.attr("href"))
        if (!href.startsWith("$mainUrl/")) return null
        val slug = href.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank() || slug in NAV_SLUGS) return null
        val img = element.selectFirst("img") ?: return null
        val cover = img.attr("data-src").ifBlank { img.attr("src") }
        if (cover.isBlank() || cover.contains("logo", ignoreCase = true)) return null
        val title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        return newSearchResponse(title, href) {
            posterUrl = fixUrlNull(cover)
        }
    }

    private fun chapterFromElement(element: Element): ChapterData? {
        val href = element.absUrl("href")
        val text = element.text().trim()
        if (href.isBlank() || text.isBlank()) return null
        return newChapterData(text, href)
    }

    companion object {
        private const val CARD_SELECTOR = "a[href]:has(img)"
        private const val CHAPTER_SELECTOR =
            "a[href*='chapitre'], a[href*='chapter'], a[href*='postface'], a[href*='epilogue'], a[href*='prologue']"
        private val NAV_SLUGS = setOf(
            "a-propos", "actualites", "faq-jgarden", "recrutement", "jg-ln", "jg-autres-lns",
            "jg-manga", "jg-web-novel", "orv", "series-abandonnees", "series-en-pause",
            "series-en-terminees", "free", "feed", "comments",
        )

        data class SearchResultJson(
            @JsonProperty("title") val title: String?,
            @JsonProperty("url") val url: String?,
        )
    }
}
