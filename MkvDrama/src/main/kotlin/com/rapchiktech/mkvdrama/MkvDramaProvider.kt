package com.rapchiktech.mkvdrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MkvDramaProvider : MainAPI() {

    override var mainUrl         = SiteConfig.MKVDRAMA_URL
    override var name            = "MkvDrama"
    override val hasMainPage     = true
    override var lang            = "en"
    override val hasDownloadSupport = true
    override val supportedTypes  = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/"                          to "Latest Updates",
        "$mainUrl/category/korean-drama/"    to "Korean Drama",
        "$mainUrl/category/chinese-drama/"   to "Chinese Drama",
        "$mainUrl/category/thai-drama/"      to "Thai Drama",
        "$mainUrl/category/japanese-drama/"  to "Japanese Drama",
        "$mainUrl/category/movies/"          to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val home = doc.select("article, .post-item, .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href   = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title  = selectFirst("h2, h3, .entry-title, .title")?.text()?.trim()
                     ?: anchor.attr("title").trim()
        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article, .post-item, .item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url).document
        val title  = doc.selectFirst("h1.entry-title, h1.post-title, h1")
                        ?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".post-thumbnail img, .wp-post-image, .entry-content img")
                        ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot   = doc.selectFirst("div.entry-content p, .synopsis")?.text()?.trim()
        val tags   = doc.select("a[rel=tag], .post-categories a").map { it.text() }
        val episodes = findEpisodeLinks(doc)

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
            }
        } else {
            val eps = episodes.mapIndexed { i, ep ->
                Episode(ep.second, name = ep.first, episode = i + 1)
            }
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, eps) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
            }
        }
    }

    private fun findEpisodeLinks(doc: Document): List<Pair<String, String>> {
        return doc.select("div.entry-content a[href]").filter {
            it.text().contains(Regex("(?i)episode|\\bep\\b|e\\d{2}")) ||
            it.attr("href").contains(Regex("(?i)episode|ep\\d|e\\d{2}"))
        }.map { Pair(it.text().trim().ifBlank { "Episode" }, it.attr("href")) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        doc.select("iframe[src]").forEach {
            loadExtractor(it.attr("src"), data, subtitleCallback, callback)
            found = true
        }

        val hostRegex = Regex("(?i)(drive\\.google|mega\\.nz|streamtape|doodstream|filemoon|voe\\.sx|gdtot|gofile|pixeldrain|mixdrop)")
        doc.select("a[href]").filter { hostRegex.containsMatchIn(it.attr("href")) }.forEach {
            loadExtractor(it.attr("href"), data, subtitleCallback, callback)
            found = true
        }

        doc.select("a[href]").filter {
            it.attr("href").contains(Regex("(?i)\\.mp4|\\.mkv|\\.m3u8"))
        }.forEach {
            callback(newExtractorLink(name, it.text().ifBlank { name }, it.attr("href"), type = INFER_TYPE))
            found = true
        }

        return found
    }
}
