package com.lagradost.frenchhub

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
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.frenchhub.dotriv.DoTriv
import com.lagradost.frenchhub.animesama.AnimeSamaProvider
import com.lagradost.frenchhub.frenchanime.FrenchAnime
import com.lagradost.frenchhub.frenchmanga.FrenchMangaProvider
import com.lagradost.frenchhub.frenchstream.FrenchStreamProvider
import com.lagradost.frenchhub.frembed.Frembed
import com.lagradost.frenchhub.fsmirror.FsMirrorLol
import com.lagradost.frenchhub.jourfilm.JourFilm
import com.lagradost.frenchhub.movix.MovixProvider
import com.lagradost.frenchhub.wiflix.WiflixProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

internal data class FrenchHubMediaData(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val imdbId: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
)

class FrenchHubCatalog : MainAPI() {
    private data class Entry(val key: String, val label: String, val api: MainAPI)

    private val providers = listOf(
        Entry("frenchstream", "French-Stream", FrenchStreamProvider()),
        Entry("movix", "Movix", MovixProvider()),
        Entry("frenchmanga", "French-Manga", FrenchMangaProvider()),
        Entry("wiflix", "Wiflix", WiflixProvider()),
        Entry("frembed", "Frembed", Frembed()),
        Entry("frenchanime", "French Anime", FrenchAnime()),
        Entry("fsmirror", "FS Mirror", FsMirrorLol()),
        Entry("jourfilm", "JourFilm", JourFilm()),
        Entry("dotriv", "DoTriv", DoTriv()),
        Entry("animesama", "Anime Sama", AnimeSamaProvider()),
    )

    private val providerByKey = providers.associateBy { it.key }
    private val movix = providerByKey.getValue("movix").api as MovixProvider

    /**
     * This is intentionally a non-network URL. CloudStream uses mainUrl to decide
     * which MainAPI owns a result before it calls load(). The actual network calls
     * happen only against TMDB or the streaming providers below.
     */
    override var mainUrl = "https://frenchhub.local"
    override var name = "FrenchHub"
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
        val items = FrenchHubTmdb.catalog(request.data, page).map { card -> card.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return FrenchHubTmdb.search(query)
            .distinctBy { "${it.type}:${it.id}" }
            .map { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query).take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.removePrefix(mainUrl).trim('/').split('/')
        if (parts.size < 3 || parts[0] != "catalog") {
            throw ErrorLoadingException("URL FrenchHub invalide : le catalogue doit utiliser une fiche TMDB")
        }
        val type = parts[1].takeIf { it == "movie" || it == "tv" }
            ?: throw ErrorLoadingException("Type TMDB invalide")
        val tmdbId = parts[2].toIntOrNull()
            ?: throw ErrorLoadingException("ID TMDB invalide")
        val details = FrenchHubTmdb.details(type, tmdbId)
            ?: throw ErrorLoadingException("Fiche TMDB indisponible")

        return if (type == "movie") {
            loadMovie(tmdbId, details)
        } else {
            loadSeries(tmdbId, details)
        }
    }

    private suspend fun loadMovie(id: Int, details: JSONObject): MovieLoadResponse {
        val title = details.optString("title").ifBlank { details.optString("original_title") }
        val imdbId = FrenchHubTmdb.externalId(details, "imdb_id")
        val data = FrenchHubMediaData(
            tmdbId = id,
            type = "movie",
            title = title,
            imdbId = imdbId,
            year = FrenchHubTmdb.year(details.optString("release_date")),
        ).toJson()
        return newMovieLoadResponse(title, catalogUrl("movie", id), TvType.Movie, data) {
            posterUrl = FrenchHubTmdb.image(details.optString("poster_path"))
            backgroundPosterUrl = FrenchHubTmdb.image(details.optString("backdrop_path"), "original")
            plot = details.optString("overview").takeIf { it.isNotBlank() }
            year = FrenchHubTmdb.year(details.optString("release_date"))
            tags = jsonNames(details.optJSONArray("genres"))
            score = details.optDouble("vote_average").takeIf { it > 0.0 }?.let { com.lagradost.cloudstream3.Score.from10(it) }
            duration = details.optInt("runtime").takeIf { it > 0 }
            addImdbId(imdbId)
            addTMDbId(id.toString())
        }
    }

    private suspend fun loadSeries(id: Int, details: JSONObject): LoadResponse {
        val title = details.optString("name").ifBlank { details.optString("original_name") }
        val imdbId = FrenchHubTmdb.externalId(details, "imdb_id")
        val seasonNumbers = details.optJSONArray("seasons")
            ?.toJsonObjects()
            ?.mapNotNull { it.optInt("season_number").takeIf { number -> number > 0 } }
            .orEmpty()
        val episodes = coroutineScope {
            seasonNumbers.chunked(4).flatMap { batch ->
                batch.map { season ->
                    async { loadSeasonEpisodes(id, season, title, imdbId) }
                }.awaitAll().flatten()
            }
        }.sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val isAnime = details.optString("original_language") in setOf("ja", "zh", "ko") &&
            jsonNames(details.optJSONArray("genres")).any { it.equals("Animation", true) }
        val url = catalogUrl("tv", id)
        return if (isAnime) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                applySeriesMetadata(details, id, imdbId)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                applySeriesMetadata(details, id, imdbId)
            }
        }
    }

    private suspend fun loadSeasonEpisodes(
        id: Int,
        season: Int,
        title: String,
        imdbId: String?,
    ): List<Episode> {
        val json = FrenchHubTmdb.season(id, season) ?: return emptyList()
        return json.optJSONArray("episodes")?.toJsonObjects()?.mapNotNull { item ->
            val number = item.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
            newEpisode(
                FrenchHubMediaData(
                    tmdbId = id,
                    type = "tv",
                    title = title,
                    imdbId = imdbId,
                    season = season,
                    episode = number,
                    firstAired = item.optString("air_date").takeIf { it.isNotBlank() },
                )
            ) {
                name = item.optString("name").ifBlank { "Épisode $number" }
                this.season = season
                this.episode = number
                description = item.optString("overview").takeIf { it.isNotBlank() }
                posterUrl = FrenchHubTmdb.image(item.optString("still_path"))
                score = item.optDouble("vote_average").takeIf { it > 0.0 }?.let { com.lagradost.cloudstream3.Score.from10(it) }
                date = parseDate(item.optString("air_date"))
            }
        }.orEmpty()
    }

    private fun TvSeriesLoadResponse.applySeriesMetadata(details: JSONObject, id: Int, imdbId: String?) {
        posterUrl = FrenchHubTmdb.image(details.optString("poster_path"))
        backgroundPosterUrl = FrenchHubTmdb.image(details.optString("backdrop_path"), "original")
        plot = details.optString("overview").takeIf { it.isNotBlank() }
        year = FrenchHubTmdb.year(details.optString("first_air_date"))
        tags = jsonNames(details.optJSONArray("genres"))
        score = details.optDouble("vote_average").takeIf { it > 0.0 }?.let { com.lagradost.cloudstream3.Score.from10(it) }
        addImdbId(imdbId)
        addTMDbId(id.toString())
    }

    private fun AnimeLoadResponse.applySeriesMetadata(details: JSONObject, id: Int, imdbId: String?) {
        posterUrl = FrenchHubTmdb.image(details.optString("poster_path"))
        backgroundPosterUrl = FrenchHubTmdb.image(details.optString("backdrop_path"), "original")
        plot = details.optString("overview").takeIf { it.isNotBlank() }
        year = FrenchHubTmdb.year(details.optString("first_air_date"))
        tags = jsonNames(details.optJSONArray("genres"))
        score = details.optDouble("vote_average").takeIf { it > 0.0 }?.let { com.lagradost.cloudstream3.Score.from10(it) }
        addImdbId(imdbId)
        addTMDbId(id.toString())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = tryParseJson<FrenchHubMediaData>(data) ?: return false
        val links = Collections.synchronizedMap(LinkedHashMap<String, ExtractorLink>())
        val subtitles = Collections.synchronizedMap(LinkedHashMap<String, SubtitleFile>())
        val active = providers.filter { FrenchHubSettings.isEnabled(it.key) }

        val results = coroutineScope {
            active.mapNotNull { entry ->
                async {
                    withTimeoutOrNull(18_000L) {
                        runCatching {
                            val providerData = directProviderData(entry, media)
                                ?: searchProviderData(entry, media)
                                ?: return@runCatching false
                            entry.api.loadLinks(
                                providerData,
                                isCasting,
                                { subtitle -> subtitles.putIfAbsent(subtitle.url, subtitle) },
                                { link -> links.putIfAbsent(link.url, link) },
                            )
                        }.getOrDefault(false)
                    } ?: false
                }
            }.awaitAll()
        }

        synchronized(subtitles) { subtitles.values.toList() }.forEach(subtitleCallback)
        synchronized(links) { links.values.toList() }.forEach(callback)
        return results.any { it } && links.isNotEmpty()
    }

    private fun directProviderData(entry: Entry, media: FrenchHubMediaData): String? {
        return when (entry.key) {
            "frembed" -> Frembed.VideoLinkData(
                tmdbId = media.tmdbId,
                type = if (media.type == "movie") "movie" else "tv",
                season = media.season,
                episode = media.episode,
            ).toJson()
            "movix" -> {
                val base = movix.mainUrl.trimEnd('/')
                if (media.type == "movie") {
                    "$base/movie/${media.tmdbId}"
                } else if (media.season != null && media.episode != null) {
                    "$base/tv/${media.tmdbId}/${media.season}/${media.episode}"
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private suspend fun searchProviderData(entry: Entry, media: FrenchHubMediaData): String? {
        if (entry.api.supportedTypes.none { type ->
                if (media.type == "movie") type == TvType.Movie else type == TvType.TvSeries || type == TvType.Anime
            }) return null

        val candidate = entry.api.search(media.title).orEmpty()
            .filter { result ->
                if (media.type == "movie") result.type == TvType.Movie
                else result.type == TvType.TvSeries || result.type == TvType.Anime
            }
            .firstOrNull { result -> similarTitle(result.name, media.title) }
            ?: return null
        val loaded = entry.api.load(candidate.url) ?: return null
        return when (loaded) {
            is MovieLoadResponse -> if (media.type == "movie") loaded.dataUrl else null
            is TvSeriesLoadResponse -> loaded.episodes.firstOrNull { episode ->
                episode.season == media.season && episode.episode == media.episode
            }?.data
            is AnimeLoadResponse -> loaded.episodes.values.flatten().firstOrNull { episode ->
                episode.season == media.season && episode.episode == media.episode
            }?.data
            else -> null
        }
    }

    private fun FrenchHubTmdbCard.toSearchResponse(): SearchResponse {
        val url = catalogUrl(type = if (type == "tv") "tv" else "movie", id = id)
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries, fix = false) {
                posterUrl = FrenchHubTmdb.image(posterPath)
                year = this@toSearchResponse.year
                this.id = this@toSearchResponse.id
                score = this@toSearchResponse.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie, fix = false) {
                posterUrl = FrenchHubTmdb.image(posterPath)
                year = this@toSearchResponse.year
                this.id = this@toSearchResponse.id
                score = this@toSearchResponse.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
            }
        }
    }

    private fun similarTitle(left: String, right: String): Boolean {
        val normalize = { value: String ->
            value.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9à-ÿ]+"), " ")
                .trim()
        }
        val a = normalize(left)
        val b = normalize(right)
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun jsonNames(array: org.json.JSONArray?): List<String> {
        return array?.toJsonObjects()?.mapNotNull { it.optString("name").takeIf(String::isNotBlank) }.orEmpty()
    }

    private fun org.json.JSONArray.toJsonObjects(): List<JSONObject> {
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.time }.getOrNull()
    }

    private fun catalogUrl(type: String, id: Int?): String = "$mainUrl/catalog/$type/${id ?: -1}"
}
