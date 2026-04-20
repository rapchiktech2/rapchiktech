package com.rapchiktech.katmoviehd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KatMovieHDProvider : MainAPI() {

    override var mainUrl         = SiteConfig.KATMOVIEHD_URL
    override var name            = "KatMovieHD"
    override val hasMainPage     = true
    override var lang            = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes  = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/"                       to "Latest Updates",
        "$mainUrl/category/hollywood/"    to "HollyWood",
        "$mainUrl/category/k-drama/"      to "K-Drama & More",
        "$mainUrl/category/anime/"        to "Anime",
        "$mainUrl/category/tv-shows/"     to "TV Shows",
        "$mainUrl/category/animated/"     to "Animated",
        "$mainUrl/category/dub-movie/"    to "Dub Movie",
        "$mainUrl/category/netflix/"      to "NetFlix",
        "$mainUrl/category/disney/"       to "Disney+",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val home = doc.select("article, .post-item, .item, .blog-post")
                      .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href   = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title  = selectFirst("h2, h3, h4, .entry-title, .post-title, .title")
                         ?.text()?.trim()
                     ?: anchor.attr("title").trim()
        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val type = when {
            href.contains("anime", ignoreCase = true)   -> TvType.Anime
            href.contains("drama", ignoreCase = true)   -> TvType.AsianDrama
            href.contains("tv-show", ignoreCase = true) -> TvType.TvSeries
            else                                        -> TvType.Movie
        }
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article, .post-item, .item, .blog-post")
                  .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc    = app.get(url).document
        val title  = doc.selectFirst("h1.entry-title, h1.post-title, h1")
                        ?.text()?.trim() ?: return null
        val poster = doc.selectFirst(
            ".post-thumbnail img, .wp-post-image, .entry-content img"
        )?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot   = doc.selectFirst("div.entry-content > p, .description, .synopsis")
                        ?.text()?.trim()
        val tags   = doc.select("a[rel=tag], .tags a, .post-categories a")
                        .map { it.text().trim() }
        val episodes = detectEpisodes(doc)

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
            val tvType = when {
                tags.any { it.contains("anime", true) }  -> TvType.Anime
                tags.any { it.contains("drama", true) }  -> TvType.AsianDrama
                else                                      -> TvType.TvSeries
            }
            newTvSeriesLoadResponse(title, url, tvType, eps) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
            }
        }
    }

    private fun detectEpisodes(doc: Document): List<Pair<String, String>> {
        return doc.select("div.entry-content a[href]").filter {
            val txt  = it.text().trim()
            val href = it.attr("href")
            (txt.contains(Regex("(?i)\\bepisode\\b|\\bep\\b\\s*\\d|e\\d{2,}")) ||
             href.contains(Regex("(?i)episode|ep\\d|e\\d{2}"))) &&
            !href.endsWith(".jpg") && !href.endsWith(".png")
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
            val src = it.attr("src").trim()
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        val hostRegex = Regex(
            "(?i)(drive\\.google|mega\\.nz|gdtot|gofile|pixeldrain|mediafire|" +
            "mixdrop|streamtape|doodstream|dood\\.re|filemoon|voe\\.sx|" +
            "upstream|filelions|hubcloud|dailymotion|ok\\.ru)"
        )
        doc.select("a[href]")
            .filter { hostRegex.containsMatchIn(it.attr("href")) }
            .forEach {
                loadExtractor(it.attr("href"), data, subtitleCallback, callback)
                found = true
            }

        doc.select("a[href]")
            .filter { it.attr("href").contains(Regex("(?i)\\.mp4|\\.mkv|\\.m3u8")) }
            .forEach {
                callback(newExtractorLink(name, it.text().ifBlank { name }, it.attr("href"), type = INFER_TYPE))
                found = true
            }

        val scripts = doc.select("script").joinToString("\n") { it.data() }
        Regex("""(?i)(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8|mkv)[^"']*)["']""")
            .findAll(scripts).forEach {
                callback(newExtractorLink(name, name, it.groupValues[1], type = INFER_TYPE))
                found = true
            }

        return found
    }
}
