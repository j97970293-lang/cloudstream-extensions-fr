package com.lagradost.frenchhub

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.frenchhub.dotriv.DoTriv
import com.lagradost.frenchhub.frenchanime.FrenchAnime
import com.lagradost.frenchhub.frenchmanga.FrenchMangaProvider
import com.lagradost.frenchhub.frenchstream.FrenchStreamProvider
import com.lagradost.frenchhub.frembed.Frembed
import com.lagradost.frenchhub.fsmirror.FsMirrorLol
import com.lagradost.frenchhub.fstv.FSTVProvider
import com.lagradost.frenchhub.jourfilm.JourFilm
import com.lagradost.frenchhub.movix.MovixProvider
import com.lagradost.frenchhub.wiflix.WiflixProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap

class FrenchHubCatalog : MainAPI() {
    private data class Entry(val key: String, val label: String, val api: MainAPI)
    private data class ProviderData(val key: String, val data: String)
    private data class Route(
        val kind: String,
        val key: String,
        val value: String? = null,
        val title: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val bundle: List<ProviderData> = emptyList()
    )

    private val entries = listOf(
        Entry("frenchstream", "French-Stream", FrenchStreamProvider()),
        Entry("movix", "Movix", MovixProvider()),
        Entry("fstv", "FSTV", FSTVProvider()),
        Entry("frenchmanga", "French-Manga", FrenchMangaProvider()),
        Entry("wiflix", "Wiflix", WiflixProvider()),
        Entry("frembed", "Frembed", Frembed()),
        Entry("frenchanime", "French Anime", FrenchAnime()),
        Entry("fsmirror", "FS Mirror", FsMirrorLol()),
        Entry("jourfilm", "JourFilm", JourFilm()),
        Entry("dotriv", "DoTriv", DoTriv())
    )

    companion object {
        private const val ROUTE_BASE = "https://frenchhub.local"
        private const val LEGACY_TITLE_PREFIX = "frenchhub://"
        private const val LEGACY_DATA_PREFIX = "frenchhub-data://"
    }

    override var name = "FrenchHub"
    override var mainUrl = "https://github.com/j97970293-lang/cloudstream-extensions-fr"
    override var lang = "fr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val mainPage = mainPageOf("all" to "Catalogue français")
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.Live
    )

    private fun enabledEntries(): List<Entry> = entries.filter { FrenchHubSettings.isEnabled(it.key) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = coroutineScope {
            enabledEntries().map { entry ->
                async {
                    runCatching {
                        if (!entry.api.hasMainPage) return@runCatching emptyList<SearchResponse>()
                        val section = entry.api.mainPage.firstOrNull { it.data.isNotBlank() }
                            ?: return@runCatching emptyList<SearchResponse>()
                        val response = entry.api.getMainPage(
                            page,
                            MainPageRequest(section.name, section.data, section.horizontalImages)
                        ) ?: return@runCatching emptyList()
                        response.items.flatMap { it.list }.map { wrapSearch(entry, it) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        return newHomePageResponse(request.name, deduplicate(results), results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            enabledEntries().map { entry ->
                async {
                    runCatching {
                        entry.api.search(query).orEmpty().map { wrapSearch(entry, it) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        return deduplicate(results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val results = coroutineScope {
            enabledEntries().map { entry ->
                async {
                    runCatching {
                        (entry.api.quickSearch(query) ?: entry.api.search(query).orEmpty())
                            .map { wrapSearch(entry, it) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        return deduplicate(results).take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val route = decodeTitleRoute(url) ?: throw ErrorLoadingException("URL FrenchHub invalide")
        val entry = entries.firstOrNull { it.key == route.key && FrenchHubSettings.isEnabled(it.key) }
            ?: throw ErrorLoadingException("Provider FrenchHub désactivé ou introuvable")
        val response = entry.api.load(route.value.orEmpty())
            ?: throw ErrorLoadingException("La fiche ${entry.label} n’a pas répondu")
        return wrapLoad(entry, route.value.orEmpty(), response)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val route = decodeDataRoute(data) ?: return false
        val routes = if (route.bundle.isNotEmpty()) {
            route.bundle
        } else {
            listOfNotNull(route.value?.let { ProviderData(route.key, it) })
        }
        val activeEntries = enabledEntries().associateBy { it.key }
        val sources = Collections.synchronizedMap(LinkedHashMap<String, ExtractorLink>())
        val subtitles = Collections.synchronizedMap(LinkedHashMap<String, SubtitleFile>())

        val results = coroutineScope {
            routes.mapNotNull { providerData ->
                val entry = activeEntries[providerData.key] ?: return@mapNotNull null
                async {
                    withTimeoutOrNull(15_000L) {
                        runCatching {
                            entry.api.loadLinks(
                                providerData.data,
                                isCasting,
                                { subtitle -> subtitles.putIfAbsent(subtitle.url, subtitle) },
                                { link -> sources.putIfAbsent(link.url, link) }
                            )
                        }.getOrDefault(false)
                    } ?: false
                }
            }.awaitAll()
        }
        synchronized(subtitles) { subtitles.values.toList() }.forEach(subtitleCallback)
        synchronized(sources) { sources.values.toList() }.forEach(callback)
        return results.any { it }
    }

    private suspend fun wrapLoad(
        entry: Entry,
        originalUrl: String,
        response: LoadResponse
    ): LoadResponse {
        val companions = loadCompanionResponses(entry, response)
        response.url = encodeTitleRoute(entry.key, originalUrl)
        response.apiName = name

        when (response) {
            is MovieLoadResponse -> {
                val bundle = mutableListOf(ProviderData(entry.key, response.dataUrl))
                companions.forEach { (companionEntry, companionResponse) ->
                    if (companionResponse is MovieLoadResponse) {
                        bundle += ProviderData(companionEntry.key, companionResponse.dataUrl)
                    }
                }
                response.dataUrl = encodeDataRoute(
                    kind = "movie",
                    key = entry.key,
                    bundle = bundle,
                    title = response.name
                )
            }
            is LiveStreamLoadResponse -> {
                response.dataUrl = encodeDataRoute(
                    kind = "live",
                    key = entry.key,
                    bundle = listOf(ProviderData(entry.key, response.dataUrl)),
                    title = response.name
                )
            }
            is TvSeriesLoadResponse -> {
                response.episodes = response.episodes.map { episode ->
                    val bundle = episodeBundle(entry, episode, companions)
                    episode.copy(
                        data = encodeDataRoute(
                            kind = "episode",
                            key = entry.key,
                            bundle = bundle,
                            title = response.name,
                            season = episode.season,
                            episode = episode.episode
                        )
                    )
                }
            }
            is AnimeLoadResponse -> {
                response.episodes = response.episodes.mapValues { (_, episodes) ->
                    episodes.map { episode ->
                        val bundle = episodeBundle(entry, episode, companions)
                        episode.copy(
                            data = encodeDataRoute(
                                kind = "episode",
                                key = entry.key,
                                bundle = bundle,
                                title = response.name,
                                season = episode.season,
                                episode = episode.episode
                            )
                        )
                    }
                }.toMutableMap()
            }
        }
        return response
    }

    private suspend fun loadCompanionResponses(
        primaryEntry: Entry,
        primaryResponse: LoadResponse
    ): List<Pair<Entry, LoadResponse>> {
        val canAggregate = primaryResponse is MovieLoadResponse ||
            primaryResponse is TvSeriesLoadResponse ||
            primaryResponse is AnimeLoadResponse
        if (!canAggregate) return emptyList()

        return coroutineScope {
            enabledEntries()
                .filterNot { it.key == primaryEntry.key }
                .map { entry ->
                    async {
                        withTimeoutOrNull(8_000L) {
                            runCatching {
                                val candidate = entry.api.search(primaryResponse.name).orEmpty()
                                    .firstOrNull { similarTitle(it.name, primaryResponse.name) }
                                    ?: return@runCatching null
                                entry.api.load(candidate.url)?.let { entry to it }
                            }.getOrNull()
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun episodeBundle(
        primaryEntry: Entry,
        primaryEpisode: Episode,
        companions: List<Pair<Entry, LoadResponse>>
    ): List<ProviderData> {
        val bundle = mutableListOf(ProviderData(primaryEntry.key, primaryEpisode.data))
        companions.forEach { (entry, response) ->
            findMatchingEpisode(response, primaryEpisode)?.let { episode ->
                bundle += ProviderData(entry.key, episode.data)
            }
        }
        return bundle
    }

    private fun findMatchingEpisode(response: LoadResponse, target: Episode): Episode? {
        val episodes = when (response) {
            is TvSeriesLoadResponse -> response.episodes
            is AnimeLoadResponse -> response.episodes.values.flatten()
            else -> emptyList()
        }
        return episodes.firstOrNull { episode ->
            (episode.season ?: 1) == (target.season ?: 1) &&
                episode.episode == target.episode
        }
    }

    private fun similarTitle(left: String, right: String): Boolean {
        val normalize = { value: String ->
            value.lowercase()
                .replace(Regex("[^a-z0-9à-ÿ]+"), " ")
                .trim()
        }
        val a = normalize(left)
        val b = normalize(right)
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun wrapSearch(entry: Entry, response: SearchResponse): SearchResponse {
        val url = encodeTitleRoute(entry.key, response.url)
        return when (response) {
            is LiveSearchResponse -> newLiveSearchResponse(response.name, url) {
                posterUrl = response.posterUrl
                id = response.id
                quality = response.quality
                posterHeaders = response.posterHeaders
            }
            is AnimeSearchResponse -> newAnimeSearchResponse(
                response.name,
                url,
                response.type ?: TvType.Anime,
                fix = false
            ) {
                posterUrl = response.posterUrl
                year = response.year
                dubStatus = response.dubStatus
                otherName = response.otherName
                episodes = response.episodes.toMutableMap()
                id = response.id
                quality = response.quality
                posterHeaders = response.posterHeaders
            }
            is TvSeriesSearchResponse -> newTvSeriesSearchResponse(
                response.name,
                url,
                response.type ?: TvType.TvSeries,
                fix = false
            ) {
                posterUrl = response.posterUrl
                id = response.id
                quality = response.quality
                posterHeaders = response.posterHeaders
            }.copy(episodes = response.episodes)
            is MovieSearchResponse -> newMovieSearchResponse(
                response.name,
                url,
                response.type ?: TvType.Movie,
                fix = false
            ) {
                posterUrl = response.posterUrl
                year = response.year
                id = response.id
                quality = response.quality
                posterHeaders = response.posterHeaders
            }
            else -> newMovieSearchResponse(
                response.name,
                url,
                response.type ?: TvType.Movie,
                fix = false
            ) {
                posterUrl = response.posterUrl
                id = response.id
                quality = response.quality
                posterHeaders = response.posterHeaders
            }
        }
    }

    private fun deduplicate(items: List<SearchResponse>): List<SearchResponse> {
        return items.distinctBy { "${it.name.trim().lowercase()}|${it.type}" }
    }

    private fun encodeTitleRoute(key: String, value: String): String =
        "$ROUTE_BASE/title/$key?${encodeQuery(mapOf("url" to value))}"

    private fun encodeDataRoute(
        kind: String,
        key: String,
        bundle: List<ProviderData>,
        title: String,
        season: Int? = null,
        episode: Int? = null
    ): String {
        val params = mutableMapOf<String, String?>(
            "bundle" to encodeBundle(bundle),
            "title" to title,
            "season" to season?.toString(),
            "episode" to episode?.toString()
        )
        return "$ROUTE_BASE/$kind/$key?${encodeQuery(params)}"
    }

    private fun decodeTitleRoute(value: String): Route? {
        if (value.startsWith("$ROUTE_BASE/title/")) {
            val key = value.removePrefix("$ROUTE_BASE/title/").substringBefore('?')
            val params = parseQuery(value)
            return params["url"]?.let { Route("title", key, value = it) }
        }
        if (value.startsWith(LEGACY_TITLE_PREFIX)) {
            val after = value.removePrefix(LEGACY_TITLE_PREFIX)
            val key = after.substringBefore('?')
            val params = parseQuery("?${after.substringAfter('?', "")}")
            return params["url"]?.let { Route("title", key, value = it) }
        }
        return null
    }

    private fun decodeDataRoute(value: String): Route? {
        val route = if (value.startsWith(ROUTE_BASE)) {
            val path = value.removePrefix("$ROUTE_BASE/").substringBefore('?').split('/')
            if (path.size < 2) return null
            val params = parseQuery(value)
            val bundle = params["bundle"]?.let(::decodeBundle).orEmpty()
            Route(
                kind = path[0],
                key = path[1],
                value = params["data"],
                title = params["title"],
                season = params["season"]?.toIntOrNull(),
                episode = params["episode"]?.toIntOrNull(),
                bundle = bundle
            )
        } else if (value.startsWith(LEGACY_DATA_PREFIX)) {
            val after = value.removePrefix(LEGACY_DATA_PREFIX)
            val key = after.substringBefore('?')
            val params = parseQuery("?${after.substringAfter('?', "")}")
            Route(
                kind = "legacy",
                key = key,
                value = params["data"],
                bundle = listOfNotNull(params["data"]?.let { ProviderData(key, it) })
            )
        } else {
            return null
        }
        return route.takeIf { it.bundle.isNotEmpty() || !it.value.isNullOrBlank() }
    }

    private fun encodeBundle(bundle: List<ProviderData>): String {
        return bundle.joinToString("|") { providerData ->
            "${URLEncoder.encode(providerData.key, Charsets.UTF_8.name())}:" +
                URLEncoder.encode(providerData.data, Charsets.UTF_8.name())
        }
    }

    private fun decodeBundle(value: String): List<ProviderData> {
        return value.split('|').mapNotNull { part ->
            val separator = part.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val key = runCatching {
                URLDecoder.decode(part.substring(0, separator), Charsets.UTF_8.name())
            }.getOrNull() ?: return@mapNotNull null
            val data = runCatching {
                URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8.name())
            }.getOrNull() ?: return@mapNotNull null
            ProviderData(key, data)
        }
    }

    private fun encodeQuery(params: Map<String, String?>): String {
        return params.filterValues { !it.isNullOrBlank() }.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
    }

    private fun parseQuery(value: String): Map<String, String> {
        val query = value.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = part.substring(0, separator)
            val decoded = runCatching {
                URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8.name())
            }.getOrNull() ?: return@mapNotNull null
            key to decoded
        }.toMap()
    }
}
