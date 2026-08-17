package com.lagradost.nuviobridge

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale

internal data class NuvioBridgeMediaData(
    val tmdbId: String,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
)

internal data class BridgeResolveRequest(
    val tmdbId: String,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
)

internal data class BridgeSubtitle(val url: String, val language: String = "Unknown")

internal data class BridgeStream(
    val url: String,
    val name: String = "Nuvio",
    val quality: String? = null,
    val language: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<BridgeSubtitle> = emptyList(),
)

internal data class BridgeResolveResponse(
    val streams: List<BridgeStream> = emptyList(),
    val errors: List<String> = emptyList(),
)

class NuvioBridgeCatalog : MainAPI() {
    override var mainUrl = "https://nuviobridge.local"
    override var name = "NuvioBridge"
    override var lang = "fr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val providerType = com.lagradost.cloudstream3.ProviderType.MetaProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "trending/all/day" to "Tendances",
        "movie/popular" to "Films populaires",
        "tv/popular" to "Séries populaires",
        "movie/top_rated" to "Films les mieux notés",
        "tv/top_rated" to "Séries les mieux notées",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = NuvioBridgeTmdb.catalog(request.data, page).map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> = NuvioBridgeTmdb.search(query)
        .distinctBy { "${it.type}:${it.id}" }
        .map { it.toSearchResponse() }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query).take(40)

    override suspend fun load(url: String): LoadResponse {
        val parts = url.removePrefix(mainUrl).trim('/').split('/')
        if (parts.size < 3 || parts[0] != "catalog") {
            throw ErrorLoadingException("URL NuvioBridge invalide")
        }
        val type = parts[1].takeIf { it == "movie" || it == "tv" }
            ?: throw ErrorLoadingException("Type TMDB invalide")
        val id = parts[2].toIntOrNull() ?: throw ErrorLoadingException("ID TMDB invalide")
        val details = NuvioBridgeTmdb.details(type, id)
            ?: throw ErrorLoadingException("Fiche TMDB indisponible")
        return if (type == "movie") loadMovie(id, details) else loadSeries(id, details)
    }

    private suspend fun loadMovie(id: Int, details: JSONObject): MovieLoadResponse {
        val title = details.optString("title").ifBlank { details.optString("original_title") }
        val data = NuvioBridgeMediaData(id.toString(), "movie").toJson()
        return newMovieLoadResponse(title, catalogUrl("movie", id), TvType.Movie, data) {
            posterUrl = NuvioBridgeTmdb.image(details.optString("poster_path"))
            backgroundPosterUrl = NuvioBridgeTmdb.image(details.optString("backdrop_path"), "original")
            plot = details.optString("overview").takeIf { it.isNotBlank() }
            year = NuvioBridgeTmdb.year(details.optString("release_date"))
            tags = jsonNames(details.optJSONArray("genres"))
            score = details.optDouble("vote_average").takeIf { it > 0.0 }
                ?.let { com.lagradost.cloudstream3.Score.from10(it) }
            duration = details.optInt("runtime").takeIf { it > 0 }
        }
    }

    private suspend fun loadSeries(id: Int, details: JSONObject): LoadResponse {
        val title = details.optString("name").ifBlank { details.optString("original_name") }
        val seasons = details.optJSONArray("seasons")?.toJsonObjects()
            ?.mapNotNull { it.optInt("season_number").takeIf { number -> number > 0 } }.orEmpty()
        val episodes = coroutineScope {
            seasons.chunked(4).flatMap { batch ->
                batch.map { season -> async { loadSeasonEpisodes(id, season) } }.awaitAll().flatten()
            }
        }.sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
        val isAnime = details.optString("original_language") in setOf("ja", "zh", "ko") &&
            jsonNames(details.optJSONArray("genres")).any { it.equals("Animation", true) }
        val catalogUrl = catalogUrl("tv", id)
        return if (isAnime) {
            newAnimeLoadResponse(title, catalogUrl, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                applySeriesMetadata(details)
            }
        } else {
            newTvSeriesLoadResponse(title, catalogUrl, TvType.TvSeries, episodes) {
                applySeriesMetadata(details)
            }
        }
    }

    private suspend fun loadSeasonEpisodes(id: Int, season: Int): List<Episode> {
        val seasonJson = NuvioBridgeTmdb.season(id, season) ?: return emptyList()
        return seasonJson.optJSONArray("episodes")?.toJsonObjects()?.mapNotNull { item ->
            val episode = item.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
            val data = NuvioBridgeMediaData(id.toString(), "tv", season, episode).toJson()
            newEpisode(data) {
                name = item.optString("name").ifBlank { "Épisode $episode" }
                this.season = season
                this.episode = episode
                description = item.optString("overview").takeIf { it.isNotBlank() }
                posterUrl = NuvioBridgeTmdb.image(item.optString("still_path"))
                score = item.optDouble("vote_average").takeIf { it > 0.0 }
                    ?.let { com.lagradost.cloudstream3.Score.from10(it) }
                date = parseDate(item.optString("air_date"))
            }
        }.orEmpty()
    }

    private fun TvSeriesLoadResponse.applySeriesMetadata(details: JSONObject) {
        posterUrl = NuvioBridgeTmdb.image(details.optString("poster_path"))
        backgroundPosterUrl = NuvioBridgeTmdb.image(details.optString("backdrop_path"), "original")
        plot = details.optString("overview").takeIf { it.isNotBlank() }
        year = NuvioBridgeTmdb.year(details.optString("first_air_date"))
        tags = jsonNames(details.optJSONArray("genres"))
        score = details.optDouble("vote_average").takeIf { it > 0.0 }
            ?.let { com.lagradost.cloudstream3.Score.from10(it) }
    }

    private fun AnimeLoadResponse.applySeriesMetadata(details: JSONObject) {
        posterUrl = NuvioBridgeTmdb.image(details.optString("poster_path"))
        backgroundPosterUrl = NuvioBridgeTmdb.image(details.optString("backdrop_path"), "original")
        plot = details.optString("overview").takeIf { it.isNotBlank() }
        year = NuvioBridgeTmdb.year(details.optString("first_air_date"))
        tags = jsonNames(details.optJSONArray("genres"))
        score = details.optDouble("vote_average").takeIf { it > 0.0 }
            ?.let { com.lagradost.cloudstream3.Score.from10(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = tryParseJson<NuvioBridgeMediaData>(data) ?: return false
        val response = withTimeoutOrNull(55_000L) {
            runCatching {
                app.post(
                    url = "${NuvioBridgeSettings.bridgeUrl()}/resolve",
                    data = mapOf(
                        "payload" to BridgeResolveRequest(
                            media.tmdbId,
                            media.mediaType,
                            media.season,
                            media.episode,
                        ).toJson(),
                    ),
                    headers = mapOf("Accept" to "application/json"),
                    timeout = 50L,
                ).text
            }.getOrNull()?.let { tryParseJson<BridgeResolveResponse>(it) }
        } ?: return false
        val seenLinks = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubtitles = Collections.synchronizedSet(mutableSetOf<String>())
        response.streams.forEach { stream ->
            if (!stream.url.startsWith("http") || !seenLinks.add(stream.url)) return@forEach
            stream.subtitles.forEach { subtitle ->
                if (subtitle.url.startsWith("http") && seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(SubtitleFile(subtitle.language, subtitle.url))
                }
            }
            val language = stream.language?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            val name = listOfNotNull(language, stream.name.takeIf { it.isNotBlank() }).joinToString(" · ")
            val type = if (stream.url.substringBefore('?').contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                ExtractorLink(
                    source = "NuvioBridge",
                    name = name.ifBlank { "NuvioBridge" },
                    url = stream.url,
                    referer = stream.headers["Referer"] ?: stream.headers["referer"] ?: "",
                    quality = quality(stream.quality),
                    headers = stream.headers,
                    type = type,
                ),
            )
        }
        return response.streams.isNotEmpty()
    }

    private fun NuvioBridgeTmdbCard.toSearchResponse(): SearchResponse {
        val url = catalogUrl(type, id)
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                posterUrl = NuvioBridgeTmdb.image(posterPath)
                year = this@toSearchResponse.year
                this.id = this@toSearchResponse.id
                score = this@toSearchResponse.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                posterUrl = NuvioBridgeTmdb.image(posterPath)
                year = this@toSearchResponse.year
                this.id = this@toSearchResponse.id
                score = this@toSearchResponse.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        }
    }

    private fun quality(raw: String?): Int = Regex("""(2160|1440|1080|720|480|360)p?""", RegexOption.IGNORE_CASE)
        .find(raw.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun jsonNames(array: org.json.JSONArray?): List<String> =
        array?.toJsonObjects()?.mapNotNull { it.optString("name").takeIf(String::isNotBlank) }.orEmpty()

    private fun org.json.JSONArray.toJsonObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.time }.getOrNull()
    }

    private fun catalogUrl(type: String, id: Int): String = "$mainUrl/catalog/$type/$id"
}
