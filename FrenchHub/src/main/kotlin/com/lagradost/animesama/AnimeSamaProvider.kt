package com.lagradost.frenchhub.animesama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Anime-Sama (anime-sama.to) — catalogue d'anime VF/VOSTFR communautaire.
 *
 * Fonctionnement du site :
 * - Recherche : POST /template-php/defaut/fetch.php avec body query=<titre>
 *   → HTML contenant des <a class="asn-search-result"> (href, img, titre)
 * - Fiche anime : /catalogue/<slug>/ avec fonctions JS panneauAnime("Nom", "saison1/vf")
 * - Langues disponibles : HEAD probe sur /catalogue/<slug>/<saison>/<lang>/
 * - Épisodes : GET /catalogue/<slug>/<saison>/<lang>/episodes.js
 *   → var eps1 = [...]; var eps2 = [...]; ... chaque var = un provider de lecture
 *   → chaque URL embed devient un ExtractorLink : le lecteur s'affiche pour chaque provider
 */
class AnimeSamaProvider : MainAPI() {
    override var mainUrl = "https://anime-sama.to"
    override var name = "Anime Sama"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "catalogue" to "Tous les animes"
    )

    data class AnimePanel(
        val name: String,
        val path: String,
        val languages: List<String>,
    )

    data class AnimeSamaLoadData(
        val url: String,
        val title: String,
        val panels: List<AnimePanel>,
        val episode: Int,
    )

    private val searchResultRegex = Regex(
        """<a[^>]+href="([^"]+)"[^>]*class="asn-search-result"[^>]*>.*?<img[^>]+src="([^"]+)"[^>]*>.*?<h3[^>]*>([^<]+)""",
        setOf(DOT_MATCHES_ALL, IGNORE_CASE)
    )

    private val panelRegex = Regex("""panneauAnime\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")

    private val episodesRegex = Regex("""var\s+eps(\d+)\s*=\s*\[([\s\S]*?)\];""")

    private val stringLiteralRegex = Regex("""["']([^"']+)["']""")

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data}").document
        val items = doc.select("a[href*='/catalogue/'], a.anime-card, .list a").mapNotNull { anchor ->
            val href = fixUrl(anchor.attr("href"))
            val title = anchor.attr("title").ifBlank { anchor.text().trim() }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(anchor.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(true)
            }
        }.distinctBy { it.name }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/template-php/defaut/fetch.php",
            data = mapOf("query" to query),
        )
        val html = response.text
        return searchResultRegex.findAll(html).mapNotNull { match ->
            val href = fixUrl(match.groupValues[1])
            val poster = fixUrlNull(match.groupValues[2])
            val title = match.groupValues[3].trim()
            if (title.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(true)
            }
        }.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val title = Regex("""<title[^>]*>([^<]+)""").find(html)?.groupValues?.getOrNull(1)
            ?.substringBefore("| Anime-Sama")?.trim()?.ifBlank { "Anime" } ?: "Anime"

        val panels: List<AnimePanel> = coroutineScope {
            val jobs: List<kotlinx.coroutines.Deferred<AnimePanel?>> =
                panelRegex.findAll(html).toList().map { match ->
                    async { panelFromMatch(url, match) }
                }
            jobs.awaitAll().filterNotNull()
        }

        if (panels.isEmpty()) {
            throw ErrorLoadingException("Aucune saison ou langue disponible sur Anime-Sama")
        }

        val maxEpisode = panels.maxOf { panel ->
            loadEpisodeUrls(panel, 1).size
        }.coerceAtLeast(1)

        val isMovie = panels.size == 1 && "film" in panels.first().name.lowercase()
        val hasVf = panels.any { "vf" in it.languages }

        val episodeList = if (isMovie) {
            listOf(
                newEpisode(
                    AnimeSamaLoadData(
                        url = url,
                        title = title,
                        panels = panels,
                        episode = 1,
                    ).toJson()
                ) {
                    name = "Film"
                    season = 1
                    episode = 1
                }
            )
        } else {
            (1..maxEpisode).map { episode ->
                newEpisode(
                    AnimeSamaLoadData(
                        url = url,
                        title = title,
                        panels = panels,
                        episode = episode,
                    ).toJson()
                ) {
                    name = "Épisode $episode"
                    season = 1
                    this.episode = episode
                }
            }
        }

        val poster = runCatching {
            app.get(url).document
                .selectFirst("img[src*='thumb'], img.lazyload, img")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
        }.getOrNull()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            tags = listOf("VF", "VOSTFR").filter {
                it == "VF" && hasVf || it == "VOSTFR"
            }.ifEmpty { listOf("VF") }
            addEpisodes(DubStatus.Dubbed, episodeList)
        }
    }

    private suspend fun panelFromMatch(baseUrl: String, match: MatchResult): AnimePanel? {
        val name = match.groupValues[1].trim()
        val path = match.groupValues[2].trim().trimEnd('/')
        if (path.isBlank()) return null
        val languages = detectLanguages(baseUrl, path)
        if (languages.isEmpty()) return null
        return AnimePanel(name = name, path = path, languages = languages)
    }

    /** Probe les langues disponibles pour un panneau (vf, vostfr, va, vcn). */
    private suspend fun detectLanguages(baseUrl: String, panelPath: String): List<String> {
        val candidates = listOf("vf", "vostfr", "va", "vcn")
        val base = baseUrl.trimEnd('/')
        return candidates.mapNotNull { language ->
            val probeUrl = "$base/$panelPath/$language/"
            runCatching {
                val response = app.head(probeUrl)
                if (response.isSuccessful || response.code in 200..399) language else null
            }.getOrNull()
        }
    }

    /** Charge les URLs embed de l'épisode donné pour ce panneau (toutes langues confondues). */
    private suspend fun loadEpisodeUrls(panel: AnimePanel, episode: Int): List<String> {
        val base = mainUrl.trimEnd('/')
        val urls = mutableListOf<String>()
        for (language in panel.languages) {
            val scriptUrl = "$base/${panel.path}/$language/episodes.js"
            val script = runCatching { app.get(scriptUrl).text }.getOrNull() ?: continue
            urls += episodesRegex.findAll(script).flatMap { match ->
                stringLiteralRegex.findAll(match.groupValues[2])
                    .map { it.groupValues[1] }
                    .filter { it.startsWith("http") || it.startsWith("/") }
                    .map { fixUrl(it) }
            }.toList()
        }
        return urls.distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<AnimeSamaLoadData>(data) ?: return false
        var emitted = false

        for (panel in payload.panels) {
            val langLabel = panel.languages.firstOrNull()?.uppercase() ?: ""
            val urls = loadEpisodeUrls(panel, payload.episode)
            for ((index, url) in urls.withIndex()) {
                val sourceName = "Anime-Sama ${panel.name} $langLabel".trim()
                val linkName = "$sourceName (${index + 1})"

                val emit: (ExtractorLink) -> Unit = { link ->
                    emitted = true
                    callback(
                        @Suppress("DEPRECATION_ERROR")
                        ExtractorLink(
                            source = link.source.ifBlank { "Anime-Sama" },
                            name = linkName,
                            url = link.url,
                            referer = link.referer.ifBlank { "https://anime-sama.to/" },
                            quality = link.quality,
                            headers = link.headers.ifEmpty { mapOf("Referer" to "https://anime-sama.to/") },
                            type = link.type,
                        )
                    )
                }

                // Vidmoly est souvent rebrandé : tester les variantes connues.
                val candidates = buildList {
                    add(url)
                    val lower = url.lowercase()
                    if ("vidmoly" in lower) {
                        add(url.replace("vidmoly.to", "vidmoly.me"))
                        add(url.replace("vidmoly.biz", "vidmoly.me"))
                    }
                }

                var resolved = false
                for (candidate in candidates.distinct()) {
                    if (loadExtractor(candidate, subtitleCallback, emit)) {
                        resolved = true
                        break
                    }
                }

                if (!resolved) {
                    // Fallback : émettre le lien embed directement pour que le lecteur
                    // apparaisse même si aucun extracteur interne ne le résout.
                    callback(
                        ExtractorLink(
                            source = "Anime-Sama",
                            name = "$linkName (embed)",
                            url = url,
                            referer = "https://anime-sama.to/",
                            quality = Qualities.Unknown.value,
                            headers = mapOf(
                                "Referer" to "https://anime-sama.to/",
                                "User-Agent" to userAgent,
                            ),
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                    emitted = true
                }
            }
        }
        return emitted
    }
}
