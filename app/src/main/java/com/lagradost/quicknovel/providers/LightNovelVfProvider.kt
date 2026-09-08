package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import org.jsoup.nodes.Element
import java.net.URLEncoder

class LightNovelVfProvider : MainAPI() {
    override val name = "LightNovelVF"
    override val mainUrl = "https://www.lightnovelvf.com"
    override val lang = "fr"
    override val iconId = R.drawable.icon_lightnovelvf
    override val hasMainPage = true
    override val rateLimitTime = 500L

    override val orderBys = listOf(
        "Derniers ajouts" to "update",
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val sort = if (orderBy == "update") "&sort=update&sort_dir=desc" else ""
        val url = "$mainUrl/novels-list?page=$page$sort"
        val doc = app.get(url).document
        val novels = doc.select(NOVEL_LINK_SELECTOR).mapNotNull(::cardFromElement).distinctBy { it.url }
        return HeadMainPageResponse(url, novels)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/novels-list?search=$encoded"
        val doc = app.get(url).document
        return doc.select(NOVEL_LINK_SELECTOR).mapNotNull(::cardFromElement).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val cover = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.let { fixUrlNull(it) }
        val synopsis = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
        val chapters = doc.select("a.chapter_link").mapNotNull(::chapterFromElement)
            .sortedBy { it.url.substringAfterLast("/").toIntOrNull() ?: 0 }
        return newStreamResponse(title, url, chapters) {
            posterUrl = cover
            this.synopsis = synopsis
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val doc = app.get(url).document
        return doc.selectFirst(".lnv-reader-content")?.html()
    }

    private fun cardFromElement(element: Element): SearchResponse? {
        val href = element.attr("href")
        if (!NOVEL_REGEX.matches(href)) return null
        val title = element.selectFirst("h3")?.text()
            ?: element.attr("aria-label")
            ?: element.selectFirst("img")?.attr("alt")
            ?: return null
        val cover = element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src")
        return newSearchResponse(title.trim(), href) {
            posterUrl = fixUrlNull(cover)
        }
    }

    private fun chapterFromElement(element: Element): ChapterData? {
        val num = element.attr("data-num")
        val href = element.attr("href")
        if (num.isBlank() || href.isBlank()) return null
        return newChapterData("Chapitre $num", href)
    }

    companion object {
        private val NOVEL_REGEX = Regex("/novel/[^/]+")
        private const val NOVEL_LINK_SELECTOR = "a[href^=\"/novel/\"]"
    }
}
