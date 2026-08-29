package com.defe.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * DeFE — downloadeverythingfromeverywhere.com
 *
 * The site is a Next.js PWA that meta-searches ~24 download sites from a
 * single "slave" scraper and shows direct file links. Reverse-engineered API:
 *
 *  Catalog/search : TMDB v3 with the site's public bearer token (from its JS
 *                   bundle) — /search/multi, /trending, /movie and /tv/{id}.
 *  Anime          : api.tenrai.org/v1 (Jikan shapes) — /anime?q=, /anime/{id},
 *                   /anime/{id}/episodes.
 *  Links          : POST https://slave.downloadeverythingfromeverywhere.com/
 *                   headers: Content-Type: application/json,
 *                            Accept: application/x-ndjson, X-Defe-Manual: 1
 *                   body: {mode:"movie"|"episode", title, year, season?,
 *                          episode?, tmdb_id?|mal_id?, imdb_id?}
 *                   → NDJSON events; {t:"hit", site, links:[{url, tags[], name}]}
 *  Only directly-downloadable URLs are emitted (mkv/mp4/avi/webm/m3u8 plus
 *  fzmovies /res/ and highxhd new_download.php endpoints). Telegram/page
 *  links are dropped — they are not playable in-app.
 */
class DeFEProvider : MainAPI() {
    override var mainUrl = "https://downloadeverythingfromeverywhere.com"
    override var name = "DeFE"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val slave = "https://slave.downloadeverythingfromeverywhere.com"
    private val tmdb = "https://api.themoviedb.org/3"
    private val tmdbToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1YjEwYWNhZDFhNjY3ZTQwMDEyMGVjMTc1ZDBjZTFmZCIsIm5iZiI6MTcyNDk1Mjg3MC45NDA4NDcsInN1YiI6IjY2ZDBhOTgyODMwMjliNzQyNzE4Y2QzMiIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXIiOjF9.W3r7L0KdqKWPG7EA-2T29OPcqW_qpJjKL5Yhrjc" // site's public client token
    private val tenrai = "https://api.tenrai.org/v1"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // section data = "tmdb:<path>" or "tenrai:<path>"
    override val mainPage = mainPageOf(
        "tmdb:/trending/movie/week" to "Trending Movies",
        "tmdb:/trending/tv/week" to "Trending TV",
        "tmdb:/movie/popular" to "Popular Movies",
        "tmdb:/tv/popular" to "Popular TV",
        "tmdb:/movie/top_rated" to "Top Movies",
        "tmdb:/tv/top_rated" to "Top TV",
        "tmdb:/movie/now_playing" to "In Theaters",
        "tmdb:/tv/on_the_air" to "Airing TV",
        "tenrai:/seasons/now" to "Anime This Season",
    )

    private fun tmdbHeaders() = mapOf(
        "Authorization" to "Bearer $tmdbToken",
        "Accept" to "application/json",
        "User-Agent" to userAgent,
    )

    private fun headers(ref: String? = null): Map<String, String> =
        buildMap {
            put("User-Agent", userAgent)
            put("Accept", "application/json")
            if (ref != null) put("Referer", ref)
        }

    // ------------------------------------------------------------------ //
    // TMDB / tenrai models
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TmdbList(
        val results: List<TmdbItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TmdbItem(
        val id: Int? = null,
        val media_type: String? = null,
        val title: String? = null,
        val name: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val poster_path: String? = null,
    ) {
        val displayName: String? get() = title ?: name
        val year: Int? get() = (release_date ?: first_air_date)?.take(4)?.toIntOrNull()
        val isMovie: Boolean get() = media_type == null || media_type == "movie"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TmdbDetail(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        val tagline: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
        val poster_path: String? = null,
        val genres: List<IdName>? = null,
        val external_ids: ExternalIds? = null,
        val seasons: List<Season>? = null,
        val number_of_episodes: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ExternalIds(val imdb_id: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class IdName(val id: Int? = null, val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Season(
        val season_number: Int? = null,
        val episode_count: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SeasonDetail(
        val episodes: List<Ep>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Ep(
        val episode_number: Int? = null,
        val name: String? = null,
    )

    // tenrai (Jikan shapes)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JikanList(val data: List<JikanAnime>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JikanAnime(
        val mal_id: Int? = null,
        val title: String? = null,
        val title_english: String? = null,
        val images: Images? = null,
        val type: String? = null,
        val episodes: Int? = null,
        val year: Int? = null,
        val aired: Aired? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Images(val jpg: Jpg? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Jpg(val large_image_url: String? = null, val image_url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Aired(val from: String? = null, val prop: AiredProp? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AiredProp(val from: FromProp? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class FromProp(val year: Int? = null)

    // ------------------------------------------------------------------ //
    // Catalog
    // ------------------------------------------------------------------ //

    private fun tmdbSearchResponse(it: TmdbItem): SearchResponse? {
        val id = it.id ?: return null
        val title = it.displayName ?: return null
        val poster = it.poster_path?.let { p -> "https://image.tmdb.org/t/p/w342$p" }
        return if (it.isMovie) {
            newMovieSearchResponse(title, "$mainUrl/m/$id") {
                this.posterUrl = poster
                this.year = it.year
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/s/$id") {
                this.posterUrl = poster
                this.year = it.year
            }
        }
    }

    private fun jikanSearchResponse(a: JikanAnime): SearchResponse? {
        val id = a.mal_id ?: return null
        val title = a.title_english ?: a.title ?: return null
        return newTvSeriesSearchResponse(title, "$mainUrl/a/$id") {
            this.posterUrl = a.images?.jpg?.large_image_url ?: a.images?.jpg?.image_url
            this.year = a.year ?: a.aired?.prop?.from?.year ?: a.aired?.from?.take(4)?.toIntOrNull()
        }
    }

    private suspend inline fun <reified T : Any> getJson(
        url: String,
        hdrs: Map<String, String>,
    ): T? = runCatching { parseJson<T>(app.get(url, headers = hdrs).text) }.getOrNull()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, path) = request.data.split(":", limit = 2)
        val items: List<SearchResponse> = when (kind) {
            "tenrai" -> {
                val res = getJson<JikanList>("$tenrai$path?page=$page", headers()) ?: JikanList()
                res.data.orEmpty().mapNotNull { jikanSearchResponse(it) }
            }
            else -> {
                val res = getJson<TmdbList>("$tmdb$path?page=$page", tmdbHeaders()) ?: TmdbList()
                res.results.orEmpty()
                    .filter { it.media_type != "person" }
                    .mapNotNull { tmdbSearchResponse(it) }
            }
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.size >= 18)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        getJson<TmdbList>("$tmdb/search/multi?query=$q&include_adult=false", tmdbHeaders())
            ?.results.orEmpty()
            .filter { (it.media_type == "movie" || it.media_type == "tv") && it.poster_path != null }
            .mapNotNullTo(out) { tmdbSearchResponse(it) }
        if (out.isEmpty()) {
            getJson<JikanList>("$tenrai/anime?q=$q&limit=24&sfw=true", headers())
                ?.data.orEmpty()
                .mapNotNullTo(out) { jikanSearchResponse(it) }
        }
        return out
    }

    // ------------------------------------------------------------------ //
    // Detail
    // ------------------------------------------------------------------ //

    /** what loadLinks needs to query the slave */
    private data class Payload(
        val mode: String, // "movie" | "episode"
        val title: String,
        val year: Int?,
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val malId: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val display: String? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        val kind = url.substringAfter("$mainUrl/").take(1)
        val id = url.substringAfterLast('/').toIntOrNull()
            ?: throw ErrorLoadingException("bad id")

        when (kind) {
            "m" -> {
                val d = getJson<TmdbDetail>("$tmdb/movie/$id?append_to_response=external_ids", tmdbHeaders())
                    ?: throw ErrorLoadingException("TMDB movie not found")
                val title = d.title ?: "Movie $id"
                val payload = Payload(
                    mode = "movie", title = title, year = d.release_date?.take(4)?.toIntOrNull(),
                    tmdbId = d.id, imdbId = d.external_ids?.imdb_id,
                ).toJson()
                return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                    this.posterUrl = d.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    this.year = d.release_date?.take(4)?.toIntOrNull()
                    this.plot = d.overview
                    this.tags = d.genres?.mapNotNull { it.name }.orEmpty()
                }
            }
            "s" -> {
                val d = getJson<TmdbDetail>("$tmdb/tv/$id?append_to_response=external_ids", tmdbHeaders())
                    ?: throw ErrorLoadingException("TMDB tv not found")
                val title = d.name ?: "Series $id"
                val imdb = d.external_ids?.imdb_id
                val tmdbId = d.id
                val year = d.first_air_date?.take(4)?.toIntOrNull()
                val seasons = d.seasons.orEmpty().filter { (it.season_number ?: 0) > 0 }
                val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
                for (s in seasons) {
                    val sn = s.season_number ?: continue
                    val sd = getJson<SeasonDetail>("$tmdb/tv/$id/season/$sn", tmdbHeaders())
                    val eps = sd?.episodes.orEmpty()
                    if (eps.isEmpty()) {
                        // fall back to declared count
                        repeat(s.episode_count ?: 0) { i ->
                            episodes += newEpisode(
                                Payload("episode", title, year, tmdbId, imdb, null, sn, i + 1, "S%02dE%02d".format(sn, i + 1)).toJson(),
                            ) { this.season = sn; this.episode = i + 1 }
                        }
                    } else {
                        for (e in eps) {
                            val en = e.episode_number ?: continue
                            episodes += newEpisode(
                                Payload("episode", title, year, tmdbId, imdb, null, sn, en, "S%02dE%02d".format(sn, en)).toJson(),
                            ) {
                                this.name = e.name?.takeIf { it.isNotBlank() && !it.startsWith("Episode ") }
                                this.season = sn
                                this.episode = en
                            }
                        }
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = d.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    this.year = year
                    this.plot = d.overview
                    this.tags = d.genres?.mapNotNull { it.name }.orEmpty()
                }
            }
            else -> { // anime (tenrai/mal)
                val d = getJson<JikanDetail>("$tenrai/anime/$id", headers())
                    ?: throw ErrorLoadingException("anime not found")
                val a = d.data ?: throw ErrorLoadingException("anime not found")
                val title = a.title_english ?: a.title ?: "Anime $id"
                val year = a.year ?: a.aired?.prop?.from?.year
                val total = a.episodes ?: 0
                val eps = ArrayList<com.lagradost.cloudstream3.Episode>()
                val list = getJson<JikanEpList>("$tenrai/anime/$id/episodes", headers())?.data.orEmpty()
                val n = list.size.let { if (total > 0) it.coerceAtMost(total) else it }
                for (i in 0 until n) {
                    val en = list[i].mal_id ?: (i + 1)
                    eps += newEpisode(
                        Payload("episode", title, year, null, null, id, 1, en, "E%02d".format(en)).toJson(),
                    ) {
                        this.name = list[i].title?.takeIf { it.isNotBlank() }
                        this.season = 1
                        this.episode = en
                    }
                }
                if (eps.isEmpty() && total > 0) {
                    for (i in 1..total) {
                        eps += newEpisode(
                            Payload("episode", title, year, null, null, id, 1, i, "E%02d".format(i)).toJson(),
                        ) { this.season = 1; this.episode = i }
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = a.images?.jpg?.large_image_url ?: a.images?.jpg?.image_url
                    this.year = year
                    this.plot = a.synopsis
                    this.tags = a.genres.orEmpty().mapNotNull { it.name }
                }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JikanDetail(val data: JikanFull? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JikanFull(
        val mal_id: Int? = null,
        val title: String? = null,
        val title_english: String? = null,
        val images: Images? = null,
        val type: String? = null,
        val episodes: Int? = null,
        val year: Int? = null,
        val aired: Aired? = null,
        val synopsis: String? = null,
        val genres: List<IdName>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JikanEpList(val data: List<JikanEp>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JikanEp(val mal_id: Int? = null, val title: String? = null)

    // ------------------------------------------------------------------ //
    // Links — the slave meta-search
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class HitEvent(
        val t: String? = null,
        val site: String? = null,
        val links: List<HitLink>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class HitLink(
        val url: String? = null,
        val name: String? = null,
        val tags: List<String>? = null,
    )

    private fun qualityOf(tags: List<String>): Int = when {
        tags.any { it.equals("2160p", true) || it.equals("4K", true) } -> Qualities.P2160.value
        tags.any { it.equals("1080p", true) } -> Qualities.P1080.value
        tags.any { it.equals("720p", true) } -> Qualities.P720.value
        tags.any { it.equals("480p", true) } -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun isDirectFile(u: String): Boolean {
        val clean = u.substringBefore("#").lowercase()
        val path = clean.substringBefore("?")
        if (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") ||
            path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".ts")
        ) return true
        // fzmovies CDN
        if (Regex("""/res/[0-9a-f]/[0-9a-f]/""").containsMatchIn(clean)) return true
        // highxhd direct download endpoint
        if (path.contains("new_download.php")) return true
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = runCatching { parseJson<Payload>(data) }.getOrNull() ?: return false
        val body = buildMap<String, Any?> {
            put("mode", p.mode)
            put("title", p.title)
            p.year?.let { put("year", it) }
            p.season?.let { put("season", it) }
            p.episode?.let { put("episode", it) }
            if (p.malId != null) {
                put("tmdb_id", null)
                put("mal_id", p.malId)
            } else {
                put("tmdb_id", p.tmdbId)
            }
            put("imdb_id", p.imdbId)
        }
        val res = runCatching {
            app.post(
                slave,
                json = body,
                headers = mapOf(
                    "Accept" to "application/x-ndjson",
                    "X-Defe-Manual" to "1",
                    "User-Agent" to userAgent,
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                ),
                timeout = 150_000L,
            ).text
        }.getOrNull() ?: return false
        if (res.isBlank() || res.contains("\"t\":\"busy\"")) return false

        var emitted = false
        for (line in res.lineSequence()) {
            if (line.isBlank()) continue
            val ev = runCatching { parseJson<HitEvent>(line) }.getOrNull() ?: continue
            if (ev.t != "hit") continue
            val site = ev.site ?: "?"
            for (l in ev.links.orEmpty()) {
                val u = l.url ?: continue
                if (!u.startsWith("http") || !isDirectFile(u)) continue
                val tags = l.tags.orEmpty()
                val label = tags.filterNot { it.equals("Movie", true) || it.equals("Episode", true) }
                    .joinToString(" ").take(56)
                callback(
                    newExtractorLink(
                        source = "$name • $site",
                        name = label.ifBlank { l.name?.take(56) ?: "Download" },
                        url = u,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = qualityOf(tags)
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "$mainUrl/",
                        )
                    },
                )
                emitted = true
            }
        }
        return emitted
    }
}
