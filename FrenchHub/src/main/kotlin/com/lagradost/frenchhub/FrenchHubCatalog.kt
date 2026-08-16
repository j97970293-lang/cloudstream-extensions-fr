package com.lagradost.frenchhub

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.mainPageOf
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
import java.net.URLDecoder
import java.net.URLEncoder

class FrenchHubCatalog : MainAPI() {
    private data class Entry(val key: String, val label: String, val api: MainAPI)
    private data class Route(val key: String, val value: String)

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

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse {
        val route = decodeRoute(url) ?: throw ErrorLoadingException("URL FrenchHub invalide")
        val entry = entries.firstOrNull { it.key == route.key && FrenchHubSettings.isEnabled(it.key) }
            ?: throw ErrorLoadingException("Provider FrenchHub désactivé ou introuvable")
        val response = entry.api.load(route.value)
            ?: throw ErrorLoadingException("La fiche ${entry.label} n’a pas répondu")
        return wrapLoad(entry, route.value, response)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val route = decodeData(data) ?: return false
        val entry = entries.firstOrNull { it.key == route.key && FrenchHubSettings.isEnabled(it.key) }
            ?: return false
        return runCatching {
            entry.api.loadLinks(route.value, isCasting, subtitleCallback, callback)
        }.getOrDefault(false)
    }

    private fun wrapSearch(entry: Entry, response: SearchResponse): SearchResponse {
        val url = encodeRoute(entry.key, response.url)
        return when (response) {
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

    private fun wrapLoad(
        entry: Entry,
        originalUrl: String,
        response: com.lagradost.cloudstream3.LoadResponse
    ): com.lagradost.cloudstream3.LoadResponse {
        response.url = encodeRoute(entry.key, originalUrl)
        response.apiName = name
        when (response) {
            is MovieLoadResponse -> response.dataUrl = encodeData(entry.key, response.dataUrl)
            is com.lagradost.cloudstream3.LiveStreamLoadResponse -> response.dataUrl = encodeData(entry.key, response.dataUrl)
            is TvSeriesLoadResponse -> response.episodes = response.episodes.map {
                it.copy(data = encodeData(entry.key, it.data))
            }
            is com.lagradost.cloudstream3.AnimeLoadResponse -> response.episodes = response.episodes.mapValues { (_, episodes) ->
                episodes.map { it.copy(data = encodeData(entry.key, it.data)) }
            }.toMutableMap()
        }
        return response
    }

    private fun deduplicate(items: List<SearchResponse>): List<SearchResponse> {
        return items.distinctBy { "${it.name.trim().lowercase()}|${it.type}" }
    }

    private fun encodeRoute(key: String, value: String): String =
        "frenchhub://$key?url=${URLEncoder.encode(value, Charsets.UTF_8.name())}"

    private fun encodeData(key: String, value: String): String =
        "frenchhub-data://$key?data=${URLEncoder.encode(value, Charsets.UTF_8.name())}"

    private fun decodeRoute(value: String): Route? = decode(value, "frenchhub://", "url=")

    private fun decodeData(value: String): Route? = decode(value, "frenchhub-data://", "data=")

    private fun decode(value: String, prefix: String, parameter: String): Route? {
        if (!value.startsWith(prefix)) return null
        val key = value.removePrefix(prefix).substringBefore('?')
        val encoded = value.substringAfter(parameter, "")
        if (key.isBlank() || encoded.isBlank()) return null
        return runCatching { Route(key, URLDecoder.decode(encoded, Charsets.UTF_8.name())) }.getOrNull()
    }
}
