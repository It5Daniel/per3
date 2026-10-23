package com.likdev256

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Site 2: FarsiPlex (farsiplex.com)
 * Handles movies and TV series from FarsiPlex.
 */
class Site2FarsiPlex(override val api: MainAPI) : SiteHandler {
    override val siteUrl = "https://farsiplex.com"
    override val siteName = "FarsiPlex"
    
    override val mainPages = listOf(
        "$siteUrl/movie/" to "Movies - $siteName",
        "$siteUrl/tvshow/" to "Series - $siteName",
    )
    
    override fun getHomeSelector(url: String): String = "article.item"
    
    override fun parseHomeItem(element: Element): SearchResponse? {
        val title = element.selectFirst("div.data h3 a")?.text()?.trim() ?: return null
        val href = element.selectFirst("div.data h3 a")?.attr("href")?.let { fixUrl(it) } ?: return null
        val posterUrl = element.selectFirst("div.poster img")?.attr("src")?.trim()?.let { fixUrlNull(it) }
        val type = if (element.hasClass("tvshows")) TvType.TvSeries else TvType.Movie
        return with(api) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }
    
    fun parseSearchItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("div.details div.title a")
        val title = titleElement?.text()?.trim() ?: return null
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(element.selectFirst("div.image img")?.attr("src"))
        val type = if (element.hasClass("tvshows")) TvType.TvSeries else TvType.Movie
        return with(api) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            app.get("$siteUrl/?s=${query.replace(" ", "+")}")
                .document.select("div.result-item")
                .mapNotNull { parseSearchItem(it) }
        } catch (e: Exception) { emptyList() }
    }
    
    override suspend fun load(url: String, document: Document): LoadResponse? {
        val isTvSeries = url.contains("/tvshow/")
        val isMovie = url.contains("/movie/")
        val isEpisode = url.contains("/episode/")

        return with(api) {
            when {
                isTvSeries -> {
                    val title = document.selectFirst("div.data h1")?.text()?.trim() ?: return null
                    val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
                    val plot = document.selectFirst("div.contenido p")?.text()?.trim()
                    val episodes = mutableListOf<Episode>()

                    document.select("#seasons .se-c").forEach { seasonElement ->
                        val seasonNumber = seasonElement.selectFirst(".se-t")?.text()?.toIntOrNull() ?: return@forEach
                        seasonElement.select("ul.episodios li").forEach { episodeElement ->
                            val epNumber = episodeElement.selectFirst(".numerando")?.text()
                                ?.substringAfter("-")?.trim()?.toIntOrNull() ?: return@forEach
                            val epTitle = episodeElement.selectFirst(".episodiotitle a")?.text() ?: return@forEach
                            val epLink = fixUrl(episodeElement.selectFirst(".episodiotitle a")?.attr("href") ?: return@forEach)

                            episodes.add(newEpisode(epLink) {
                                name = epTitle
                                season = seasonNumber
                                episode = epNumber
                            })
                        }
                    }

                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                }
                isMovie -> {
                    val title = document.selectFirst("div.data h1")?.text()?.trim() ?: return null
                    val poster = fixUrlNull(document.selectFirst("div.playbox img.cover")?.attr("src"))
                    val plot = document.selectFirst("div#info div.wp-content p")?.text()?.trim()

                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                }
                isEpisode -> {
                    val title = document.selectFirst("div#info h2")?.text()?.trim() ?: return null
                    val poster = fixUrlNull(document.selectFirst("#fakeplayer .playbox img.cover")?.attr("src"))
                    val plot = document.selectFirst("div#info div.wp-content p")?.text()?.trim()

                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                }
                else -> {
                    val title = document.selectFirst("div.data h2")?.text()?.trim() ?: return null
                    val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
                    val plot = document.selectFirst("div#info div.wp-content p")?.text()?.trim()

                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.plot = plot
                    }
                }
            }
        }
    }
    
    // 0: keep every available resolution selectable. 1080: offer only 1080p or higher.
    private val minimumQuality = 0

    private data class FarsiPlexVideo(
        val url: String,
        val resolution: Int,
        val referer: String
    )

    private fun detectResolution(source: String): Int {
        return Regex("""(?<!\d)(2160|1440|1080|720|480|360)p?(?!\d)""", RegexOption.IGNORE_CASE)
            .find(source.substringBefore("?"))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun decodeSourceUrl(url: String): String =
        url.replace("\\/", "/").replace("&amp;", "&")

    // FarsiPlex's JWPlayer script exposes independently signed "file" and "file2" URLs.
    // Use the primary URL, with file2 as a fallback, without fabricating resolutions.
    private fun extractSignedVideo(html: String, playerUrl: String): FarsiPlexVideo? {
        val pattern = Regex("""["']file2?["']\s*:\s*["']([^"']+)["']""")
        val matches = pattern.findAll(html)
            .map { decodeSourceUrl(it.groupValues[1]) }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .filter { it.substringBefore("?").let { path -> path.endsWith(".mp4", true) || path.endsWith(".m3u8", true) } }
            .toList()
        val videoUrl = matches.firstOrNull() ?: return null
        return FarsiPlexVideo(videoUrl, detectResolution(videoUrl), siteUrl)
    }

    override suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try { app.get(data).document } catch (_: Exception) { return false }

        // Real episode HTML: <form id="watch-17338" ...></form>
        // There is no hidden input[name=id], which the old extractor incorrectly expected.
        val form = document.selectFirst("form[id^=watch-]")
        val postId = form?.attr("id")?.substringAfter("watch-")
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: form?.selectFirst("input[name=id]")?.attr("value")
            ?: document.selectFirst(".dooplay_player_option[data-post]")?.attr("data-post")
            ?: Regex("""\bpostid-(\d+)\b""").find(document.body().className())
                ?.groupValues?.get(1)
            ?: return false

        val preferredType = if (data.contains("/movie/")) "movie" else "tv"

        // Some items have no player-option nodes until the user clicks Play.
        // The DooPlayer API still exposes each numbered source directly.
        val options = linkedSetOf<Pair<String, Int>>()
        document.select(".dooplay_player_option[data-nume]").forEach { element ->
            val index = element.attr("data-nume").toIntOrNull()
            val type = element.attr("data-type").ifBlank { preferredType }
            if (index != null && index in 1..20) options.add(type to index)
        }
        for (index in 1..8) options.add(preferredType to index)

        val videos = coroutineScope {
            options.map { (type, index) ->
                async {
                    try {
                        val apiUrl = "$siteUrl/wp-json/dooplayer/v2/$postId/$type/$index"
                        val json = app.get(apiUrl, referer = data).text
                        val embedUrl = org.json.JSONObject(json).optString("embed_url").trim()
                        if (!embedUrl.startsWith("https://") && !embedUrl.startsWith("http://")) {
                            null
                        } else {
                            val playerHtml = app.get(embedUrl, referer = data).text
                            extractSignedVideo(playerHtml, embedUrl)
                        }
                    } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }
        }

        // Deduplicate links, favour 1080p. The player can display every found quality.
        val unique = videos.distinctBy { it.url }.filter {
            minimumQuality == 0 || it.resolution >= minimumQuality
        }.sortedByDescending { it.resolution }

        for (video in unique) {
            val label = if (video.resolution > 0) video.resolution.toString() + "p" else "unknown quality"
            callback.invoke(
                newExtractorLink(
                    source = siteName,
                    name = siteName + " " + label,
                    url = video.url
                ).apply {
                    quality = if (video.resolution > 0) video.resolution else Qualities.Unknown.value
                    referer = video.referer
                }
            )
        }
        return unique.isNotEmpty()
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$siteUrl$url"
    }
    
    private fun fixUrlNull(url: String?): String? {
        return url?.let { if (it.startsWith("http")) it else "$siteUrl$it" }
    }
}
