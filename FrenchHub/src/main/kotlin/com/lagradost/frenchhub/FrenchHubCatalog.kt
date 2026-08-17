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
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.frenchhub.frenchmanga.FrenchMangaProvider
import com.lagradost.nikola.NikolaFrenchStreamProvider
import com.lagradost.frenchhub.movix.MovixProvider
import com.lagradost.moviebox.MovieBoxProvider
import com.lagradost.frenchhub.animesama.AnimeSamaProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale

internal data class FrenchHubMediaData(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val originalTitle: String? = null,
    val imdbId: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
)

class FrenchHubCatalog : MainAPI() {
    private data class Entry(val key: String, val label: String, val api: MainAPI)

    private val frenchStream = NikolaFrenchStreamProvider()
    private val movix = MovixProvider()
    private val frenchManga = FrenchMangaProvider()
    private val movieBox = MovieBoxProvider()
    private val animeSama = AnimeSamaProvider()

    private val providers = listOf(
        // Provider Nikola (Nikola17/cloudstream-frenchstream) : recherche directe
        // sur french-stream.one + matching TMDB robuste, éprouvé sur de nombreuses
        // fiches. Les lecteurs sont chargés en parallèle avec les autres sources.
        // FS Mirror et les autres miroirs Datalife Engine du même site font partie
        // de ce même provider (bascule automatique de miroir si le domaine principal
        // tombe : french-stream.one, french-stream.pink, fstream.info).
        Entry("frenchstream", "French-Stream", frenchStream),
        Entry("movix", "Movix", movix),
        Entry("frenchmanga", "French-Manga", frenchManga),
        Entry("moviebox", "MovieBox", movieBox),
        Entry("animesama", "Anime-Sama", animeSama),
    )

    private val providerByKey = providers.associateBy { it.key }

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
            originalTitle = details.optString("original_title").takeIf { it.isNotBlank() && it != title },
            imdbId = imdbId,
            year = FrenchHubTmdb.year(details.optString("release_date")),
        ).toJson()
        val response = newMovieLoadResponse(title, catalogUrl("movie", id), TvType.Movie, data) {
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
        response.applyTmdbExtras(details)
        return response
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
                    async {
                        loadSeasonEpisodes(
                            id,
                            season,
                            title,
                            imdbId,
                            details.optString("original_name").takeIf { it.isNotBlank() && it != title },
                        )
                    }
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
        originalTitle: String? = null,
    ): List<Episode> {
        val json = FrenchHubTmdb.season(id, season) ?: return emptyList()
        return json.optJSONArray("episodes")?.toJsonObjects()?.mapNotNull { item ->
            val number = item.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
            val data = FrenchHubMediaData(
                tmdbId = id,
                type = "tv",
                title = title,
                originalTitle = originalTitle,
                imdbId = imdbId,
                season = season,
                episode = number,
                firstAired = item.optString("air_date").takeIf { it.isNotBlank() },
            ).toJson()
            newEpisode(data) {
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

    private suspend fun TvSeriesLoadResponse.applySeriesMetadata(details: JSONObject, id: Int, imdbId: String?) {
        posterUrl = FrenchHubTmdb.image(details.optString("poster_path"))
        backgroundPosterUrl = FrenchHubTmdb.image(details.optString("backdrop_path"), "original")
        plot = details.optString("overview").takeIf { it.isNotBlank() }
        year = FrenchHubTmdb.year(details.optString("first_air_date"))
        tags = jsonNames(details.optJSONArray("genres"))
        score = details.optDouble("vote_average").takeIf { it > 0.0 }?.let { com.lagradost.cloudstream3.Score.from10(it) }
        addImdbId(imdbId)
        addTMDbId(id.toString())
        applyTmdbExtras(details)
    }

    private suspend fun AnimeLoadResponse.applySeriesMetadata(details: JSONObject, id: Int, imdbId: String?) {
        posterUrl = FrenchHubTmdb.image(details.optString("poster_path"))
        backgroundPosterUrl = FrenchHubTmdb.image(details.optString("backdrop_path"), "original")
        plot = details.optString("overview").takeIf { it.isNotBlank() }
        year = FrenchHubTmdb.year(details.optString("first_air_date"))
        tags = jsonNames(details.optJSONArray("genres"))
        score = details.optDouble("vote_average").takeIf { it > 0.0 }?.let { com.lagradost.cloudstream3.Score.from10(it) }
        addImdbId(imdbId)
        addTMDbId(id.toString())
        applyTmdbExtras(details)
    }

    /**
     * Complète la fiche avec le casting (15 acteurs principaux de TMDB) et la
     * bande-annonce officielle (vidéo YouTube « Trailer » ou « Teaser »).
     * La fiche TMDB est déjà demandée avec credits et videos via
     * FrenchHubTmdb.details (append_to_response).
     */
    private suspend fun LoadResponse.applyTmdbExtras(details: JSONObject) {
        details.optJSONObject("credits")?.optJSONArray("cast")?.let { cast ->
            val actors = (0 until cast.length())
                .mapNotNull { cast.optJSONObject(it) }
                .take(15)
                .mapNotNull { actor ->
                    val name = actor.optString("name").ifBlank { actor.optString("original_name") }
                    if (name.isBlank()) return@mapNotNull null
                    val character = actor.optString("character").ifBlank { actor.optString("known_for_department") }
                    com.lagradost.cloudstream3.Actor(
                        name,
                        FrenchHubTmdb.image(actor.optString("profile_path"), "w185"),
                    ) to character
                }
            if (actors.isNotEmpty()) addActors(actors)
        }
        details.optJSONObject("videos")?.optJSONArray("results")?.let { videos ->
            val trailer = (0 until videos.length())
                .mapNotNull { videos.optJSONObject(it) }
                .firstOrNull { video ->
                    video.optString("site", "").equals("YouTube", true) &&
                        video.optString("type", "").let { type -> type.equals("Trailer", true) || type.equals("Teaser", true) }
                }
            trailer?.optString("key")?.takeIf { it.isNotBlank() }?.let { key ->
                addTrailer("https://www.youtube.com/watch?v=$key")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = tryParseJson<FrenchHubMediaData>(data) ?: return false
        // Les domaines sont modifiables depuis le menu de l'extension ; on les
        // réapplique ici afin qu'un changement soit pris en compte sans recréer
        // l'objet provider.
        frenchStream.mainUrl = FrenchHubSettings.domain("frenchstream")
        movix.mainUrl = FrenchHubSettings.domain("movix")
        frenchManga.mainUrl = FrenchHubSettings.domain("frenchmanga")
        movieBox.mainUrl = FrenchHubSettings.domain("moviebox")
        animeSama.mainUrl = FrenchHubSettings.domain("animesama")
        // Les sous-providers (FStream, Movix, Wiflix, …) partagent souvent les mêmes
        // liens finaux (vidzy, uqload, …). Il faut conserver UN lecteur par
        // (provider, URL) et non par URL seule, sinon le premier provider à émettre
        // masque tous les lecteurs des autres sources.
        val seenLinks = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubtitles = Collections.synchronizedSet(mutableSetOf<String>())
        // Si tous les providers ont été désactivés (menu Providers), aucun lecteur
        // ne pourrait s'afficher : dans ce cas, réactiver la configuration par
        // défaut afin de ne jamais laisser l'utilisateur sans aucun lecteur.
        var active = providers.filter { FrenchHubSettings.isEnabled(it.key) }
        if (active.isEmpty()) {
            providers.forEach { FrenchHubSettings.setEnabled(it.key, true) }
            active = providers
        }

        // Chargement non bloquant : chaque provider tourne en parallèle et émet
        // ses lecteurs et sous-titres AUSSI TÔT qu'ils sont disponibles.
        // CloudStream rafraîchit la liste des lecteurs en temps réel dès que le
        // callback est appelé — comme le font CineStream ou StreamPlay.
        supervisorScope {
            active.forEach { entry ->
                launch {
                    runCatching {
                        val providerData = directProviderData(entry, media)
                            ?: searchProviderData(entry, media)
                            ?: return@launch
                        withTimeoutOrNull(30_000L) {
                            entry.api.loadLinks(
                                providerData,
                                isCasting,
                                { subtitle ->
                                    if (seenSubtitles.add(subtitle.url)) subtitleCallback(subtitle)
                                },
                                { link ->
                                    if (seenLinks.add("${entry.key}|${link.url}")) callback(link)
                                },
                            )
                        }
                    }
                }
            }
        }
        // loadLinks retourne true pour garder la fiche ouverte et laisser les
        // lecteurs arriver au fur et à mesure ; le retour exact n'est plus
        // bloquant (CloudStream gère l'arrêt quand la coroutine parente se termine).
        return true
    }

    private fun directProviderData(entry: Entry, media: FrenchHubMediaData): String? {
        return when (entry.key) {
            "moviebox" -> {
                val subjectTitle = media.title
                val parts = buildString {
                    append("moviebox://")
                    append(media.tmdbId)
                    append("::")
                    append(if (media.type == "movie") "movie" else "tv")
                    append("::")
                    append(subjectTitle)
                    if (media.season != null && media.episode != null) {
                        append("::")
                        append(media.season)
                        append("::")
                        append(media.episode)
                    }
                }
                parts
            }
            "animesama" -> {
                if (media.tmdbId == null) null
                else {
                    val parts = buildString {
                        append("animesama://")
                        append(media.tmdbId)
                        append("::")
                        append(if (media.type == "movie") "movie" else "tv")
                        append("::")
                        append(media.season ?: 1)
                        append("::")
                        append(media.episode ?: 1)
                        append("::")
                        append(media.title)
                    }
                    parts
                }
            }
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

        var results = entry.api.search(media.title).orEmpty()
            .filter { result ->
                if (media.type == "movie") result.type == TvType.Movie
                else result.type == TvType.TvSeries || result.type == TvType.Anime
            }
        // Les cartes Nikola sont enrichies avec leur ID TMDB (enrichCards) :
        // une correspondance exacte sur l'ID TMDB est le match parfait, sans
        // ambiguïté possible sur le titre ou l'année.
        val candidate = results.firstOrNull { result -> result.id == media.tmdbId }
            ?: run {
                // Matching multi-critères (titre français, titre original,
                // mots clés, similarTitle en dernier recours) : les sites
                // renomment parfois les fiches (« Le Cas Oppenheimer » ↔
                // « Oppenheimer »), la comparaison exacte rate trop de
                // correspondances valides.
                tmdbMatch(results, media.title, media.originalTitle.orEmpty(), media.year)
                    ?: results.firstOrNull { result -> similarTitle(result.name, media.title) }
            }
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

    /**
     * Clé de comparaison de titre (inspirée de Nikola/cloudstream-frenchstream et
     * de CineStream) : minuscules, accents décomposés (NFD) et retirés, puis seuls
     * les caractères alphanumériques conservés. « Oppenheimer » et
     * « Le Cas Oppenheimer » deviennent donc respectivement « oppenheimer » et
     * « lecasoppenheimer », ce qui permet une comparaison robuste.
     */
    private fun titleKey(value: String): String {
        return java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "")
    }

    /**
     * Comparaison de titres tolérante : égalité exacte des clés, inclusion
     * réciproque des mots principaux (>= 4 caractères), et correspondance
     * d'au moins la moitié des mots principaux. Permet de matcher des fiches
     * dont le site renomme le contenu (ex. « Le Cas Oppenheimer » ↔ « Oppenheimer »).
     */
    private fun similarTitle(left: String, right: String): Boolean {
        val a = titleKey(left)
        val b = titleKey(right)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        // Correspondance des mots principaux : tous les mots >= 4 lettres d'un
        // titre doivent être présents dans l'autre.
        val words = { value: String -> value.split(Regex("""[a-z0-9]+"""")).filter { it.length >= 4 } }
        val wa = words(a)
        val wb = words(b)
        if (wa.isNotEmpty() && wa.all { it in b }) return true
        if (wb.isNotEmpty() && wb.all { it in a }) return true
        if (wa.isNotEmpty() && wb.isNotEmpty()) {
            val common = wa.count { it in wb }
            return common * 2 >= wa.size + wb.size && common >= 2
        }
        return false
    }

    /**
     * Cherche la fiche TMDB la plus proche d'un résultat de site : compare le
     * titre français, le titre original et les titres alternatifs, en bonus la
     * proximité d'année (écart <= 2 ans). C'est le même principe que
     * Nikola (tmdbResult) et CineStream (Cinemeta aliases).
     */
    private fun tmdbMatch(
        results: List<com.lagradost.cloudstream3.SearchResponse>,
        title: String,
        originalTitle: String,
        year: Int?,
    ): com.lagradost.cloudstream3.SearchResponse? {
        val targetKeys = listOf(titleKey(title), titleKey(originalTitle))
            .filter(String::isNotEmpty)
        val targetWords = targetKeys.flatMap { titleKey(it).split(Regex("""[a-z0-9]+""")).filter { w -> w.length >= 4 } }

        fun score(name: String): Int {
            val key = titleKey(name)
            if (targetKeys.any { it == key }) return 100
            if (targetKeys.any { it.contains(key) || key.contains(it) }) return 60
            val words = key.split(Regex("""[a-z0-9]+""")).filter { it.length >= 4 }
            if (words.isNotEmpty() && words.all { it in targetKeys.joinToString("") }) return 50
            if (targetWords.isNotEmpty() && words.any { it in targetWords } && words.size >= 2) {
                return words.count { it in targetWords } * 20
            }
            return 0
        }

        return results.mapNotNull { result ->
            val nameScore = score(result.name)
            val titleNameScore = result.name.filter { it.isLetterOrDigit() }.length
            result to nameScore
        }.filter { (_, s) -> s >= 60 }
            .maxByOrNull { (result, s) -> s * 10 + result.name.length }
            ?.first
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
