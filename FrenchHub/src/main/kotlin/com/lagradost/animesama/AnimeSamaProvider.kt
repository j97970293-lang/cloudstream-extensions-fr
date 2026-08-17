package com.lagradost.frenchhub.animesama

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
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
 * Réécrit pour fonctionner comme French-Manga :
 * - Les panneaux panneauxAnime("Saison X", "slug") définissent les SAISONS RÉELLES
 *   (indexées +1) au lieu d'un aplatissement en season=1 (Wistoria S1 vs S2 distincts).
 * - Épisodes VF et VOSTFR séparés dans DubStatus.Dubbed / DubStatus.Subbed.
 * - Les liens sont émis avec le label de la langue ([VF] / [VOSTFR] / [VO]) et
 *   chaque embed est résolu par le chargeur d'extracteurs de CloudStream.
 *
 * Fonctionnement du site :
 * - Recherche : POST /template-php/defaut/fetch.php avec body query=<titre>
 *   → HTML contenant des liens a (class asn-search-result, titre .asn-search-result-title)
 * - Fiche anime : /catalogue/<slug>/ avec panneauAnime("Nom", "saison1/vf") dans le
 *   script de #sousBlocMiddle ; /vostfr dans le slug indique une version VO.
 * - Épisodes : GET /catalogue/<slug>/<saison>/<lang>/episodes.js?filever=N
 *   → var eps1 = [...]; var eps2 = [...]; ... chaque var = un lecteur embed
 */
class AnimeSamaProvider : MainAPI() {
    override var mainUrl = "https://anime-sama.to"
    override var name = "Anime Sama"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "#containerAjoutsAnimes a" to "Derniers épisodes ajoutés",
        "#containerSorties a" to "Derniers contenus sortis",
        "#containerClassiques a" to "Les classiques",
        "#containerPepites a" to "Découvrez des pépites",
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf("User-Agent" to userAgent)

    private val searchResultRegex = Regex(
        """<a[^>]+href="([^"]+)"[^>]*class="asn-search-result"[^>]*>.*?<img[^>]+src="([^"]+)"[^>]*>.*?<h3[^>]*>([^<]+)""",
        setOf(DOT_MATCHES_ALL, IGNORE_CASE)
    )

    private val panelRegex = Regex("""panneauAnime\("([^"]+)",\s*"([^"]+)"\);""")

    private val episodesScriptRegex = Regex(
        """<script[^>]*src=['"]([^'"]*episodes\.js\?filever=\d+)['"][^>]*>""",
        IGNORE_CASE
    )

    private val episodesRegex = Regex("""var\s+eps(\d+)\s*=\s*\[([\s\S]*?)\];""")

    private val stringLiteralRegex = Regex("""["']([^"']+)["']""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = runCatching { app.get(mainUrl, headers = browserHeaders).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = doc.select(request.data).mapNotNull { anchor ->
            val title = anchor.selectFirst(".card-title")?.text()?.trim()
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val href = fixUrl(anchor.attr("href"))
            val poster = fixUrlNull(anchor.selectFirst("img")?.attr("src"))
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = poster
                        addDubStatus(dubExist = true, subExist = true)
                    }
        }.distinctBy { it.name }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // La recherche anime-sama fonctionne avec le titre seul OU le titre
        // suivi de la saison : « Wistoria Saison 1 », « Wistoria Saison 2 »,
        // « Wistoria » retournent des fiches différentes. On cherche les deux
        // variantes comme le fait French-Manga (discoverSeasons fallback).
        val results = linkedMapOf<String, SearchResponse>()
        listOf(query, "$query Saison 1", "$query Saison 2").forEach { candidate ->
            runCatching {
                val response = app.post(
                    "$mainUrl/template-php/defaut/fetch.php",
                    data = mapOf("query" to candidate),
                    headers = browserHeaders,
                    referer = "$mainUrl/",
                    timeout = 10L
                )
                val doc = response.document
                doc.select("a").forEach { anchor ->
                    val title = anchor.selectFirst(".asn-search-result-title")?.text()?.trim()
                        ?: return@forEach
                    val href = fixUrl(anchor.attr("href"))
                    if (title.isBlank() || href.isBlank()) return@forEach
                    val poster = fixUrlNull(anchor.selectFirst("img")?.attr("src"))
                    results[title] = newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = poster
                        addDubStatus(dubExist = true, subExist = true)
                    }
                }
            }
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query).take(20)

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = browserHeaders, timeout = 12L).document
        val title = doc.selectFirst("#titreOeuvre")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("""<title[^>]*>([^<]+)""").find(doc.html())?.groupValues?.getOrNull(1)
                ?.substringBefore("| Anime-Sama")?.trim()?.ifBlank { "Anime" } ?: "Anime"

        // 1. Panneaux = saisons réelles, exactement comme le provider CuxPlug :
        //    panneauAnime("Saison 1", "saison1/vf") → season 1, etc.
        val rawSeasonScript = doc.selectFirst("#sousBlocMiddle script")?.toString() ?: ""
        val panneaux = panelRegex.findAll(rawSeasonScript).map {
            Panel(it.groupValues[1].trim(), it.groupValues[2].trim().trimEnd('/'))
        }.toList()

        if (panneaux.isEmpty()) {
            throw ErrorLoadingException("Aucune saison ou langue disponible sur Anime-Sama")
        }

        val seasonData = panneaux.mapIndexed { index, panel ->
            SeasonData(index + 1, panel.name)
        }
        val seasonByName = panneaux.map { it.name }.toList()

        // 2. Pour chaque panneau, extraire les liens VF (doublés) et VO (sous-titrés).
        //    Un panneau dont le slug finit par /vf est déjà doublé ; sinon on
        //    tente la page /vostfr puis la page vf comme fallback.
        val seasonPages = coroutineScope {
            panneaux.mapIndexed { index, panel ->
                async {
                    runCatching {
                        val pageUrl = "$url/${panel.path}"
                        val isVFPage = pageUrl.removeSuffix("/").endsWith("/vf")
                        val vfLinks = if (isVFPage) {
                            extractStreamLinks(pageUrl)
                        } else {
                            extractStreamLinks(pageUrl.replaceFirst("$url/${panel.path}", "$url/${panel.path}/vf"))
                                .takeIf { it.isNotEmpty() }
                                ?: extractStreamLinks(pageUrl)
                        }
                        val voLinks = if (!isVFPage) extractStreamLinks(pageUrl) else emptyMap()
                        val maxEpisodes = maxOf(
                            vfLinks.values.maxOfOrNull { it.size } ?: 0,
                            voLinks.values.maxOfOrNull { it.size } ?: 0,
                        )
                        SeasonPage(index + 1, pageUrl, maxEpisodes, vfLinks, voLinks)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        if (seasonPages.isEmpty()) {
            throw ErrorLoadingException("Aucune saison exploitable sur Anime-Sama")
        }

        val isMovie = seasonPages.size == 1 &&
            seasonPages.first().maxEpisodes == 1 &&
            "film" in panneaux.first().name.lowercase()

        val (dubbedEpisodes, subbedEpisodes) = buildSeasonEpisodes(seasonPages, title, isMovie, seasonByName)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = doc.selectFirst("#coverOeuvre")?.attr("src")
                ?: doc.selectFirst("img[src*='thumb'], img")?.attr("src")
                ?.let { fixUrl(it) }
            plot = doc.selectFirst("p.text-sm.text-gray-400.mt-2")?.text()
            tags = doc.selectFirst("a.text-sm.text-gray-300.mt-2")?.text()
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            addSeasonNames(seasonData)
            if (dubbedEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbedEpisodes)
            if (subbedEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbedEpisodes)
            if (dubbedEpisodes.isEmpty() && subbedEpisodes.isEmpty()) {
                throw ErrorLoadingException("Aucun épisode disponible sur Anime-Sama")
            }
        }
    }

    private fun buildSeasonEpisodes(
        seasons: List<SeasonPage>,
        title: String,
        isMovie: Boolean,
        seasonByName: List<String>
    ): Pair<List<Episode>, List<Episode>> {
        val dubbed = mutableListOf<Episode>()
        val subbed = mutableListOf<Episode>()
        seasons.forEachIndexed { seasonIndex, seasonPage ->
            val seasonLabel = seasonPage.number
            val seasonName = seasonByName.getOrNull(seasonIndex)?.trim() ?: ""
            for (ep in 1..seasonPage.maxEpisodes) {
                val name = when {
                    isMovie -> title
                    "film" in seasonName.lowercase() -> "$title - Film"
                    "saison" in seasonName.lowercase() -> "$title - Saison $seasonLabel Épisode ${ep.toString().padStart(2, '0')}"
                    else -> "$title $seasonName - Épisode $ep"
                }
                val vfData = seasonPage.vfLinks.values.mapIndexedNotNull { index, links ->
                    links.getOrNull(ep - 1)?.takeIf { it.isNotBlank() }
                }.joinToString(" ")
                val voData = seasonPage.voLinks.values.mapIndexedNotNull { index, links ->
                    links.getOrNull(ep - 1)?.takeIf { it.isNotBlank() }
                }.joinToString(" ")
                if (vfData.isNotBlank()) {
                    dubbed += newEpisode(vfData) {
                        this.name = name
                        this.season = seasonLabel
                        this.episode = ep
                    }
                }
                if (voData.isNotBlank()) {
                    subbed += newEpisode(voData) {
                        this.name = name
                        this.season = seasonLabel
                        this.episode = ep
                    }
                }
            }
        }
        return dubbed to subbed
    }

    private suspend fun extractStreamLinks(pageUrl: String): Map<String, List<String>> {
        val response = runCatching {
            app.get(pageUrl, headers = browserHeaders, referer = "$mainUrl/", timeout = 12L)
        }.getOrNull() ?: return emptyMap()
        val doc = response.document
        val script = doc.selectFirst("#sousBlocMiddle script")?.toString() ?: return emptyMap()
        val episodeScriptPath = episodesScriptRegex.find(script)?.groupValues?.getOrNull(1) ?: return emptyMap()
        val episodeScript = runCatching {
            app.get("$pageUrl/$episodeScriptPath", headers = browserHeaders, referer = pageUrl, timeout = 12L).text
        }.getOrNull() ?: return emptyMap()
        val urls = episodesRegex.findAll(episodeScript).flatMap { match ->
            stringLiteralRegex.findAll(match.groupValues[2])
                .map { it.groupValues[1] }
                .filter { it.startsWith("http", true) || it.startsWith("/") }
                .map { fixUrl(it) }
        }.distinct().toList()
        if (urls.isEmpty()) return emptyMap()
        return urls.groupBy { url ->
            when {
                url.contains("sibnet.ru") -> "Sibnet"
                url.contains("embed4me.com") -> "Embed4Me"
                url.contains("vidmoly") -> "Vidmoly"
                url.contains("oneupload") -> "Oneupload"
                url.contains("sendvid") -> "Sendvid"
                url.contains("vk.com") -> "Vk"
                else -> "Autre"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = liens séparés par des espaces (VO ou VF) + un préfixe de langue
        // éventuel « [VF] ... ». Le catalogue encode la langue dans le nom des
        // épisodes : ici on émet chaque embed en laissant le nom du serveur.
        val links = data.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (links.isEmpty()) return false
        var emitted = false
        for (link in links) {
            var resolved = false
            for (candidate in linkCandidates(link)) {
                if (loadExtractor(candidate, subtitleCallback) { extracted ->
                        emitted = true
                        callback(extracted)
                    }) {
                    resolved = true
                    break
                }
            }
            if (!resolved) {
                // Fallback : émettre le lien embed directement pour que le
                // lecteur apparaisse même si aucun extracteur interne ne le résout.
                callback(
                    ExtractorLink(
                        source = "Anime-Sama",
                        name = "Anime-Sama (embed)",
                        url = link,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to userAgent,
                        ),
                        type = ExtractorLinkType.M3U8,
                    )
                )
                emitted = true
            }
        }
        return emitted
    }

    private fun linkCandidates(url: String): List<String> {
        val lower = url.lowercase()
        return buildList {
            add(url)
            if ("vidmoly" in lower) {
                add(url.replace("vidmoly.to", "vidmoly.me"))
                add(url.replace("vidmoly.biz", "vidmoly.me"))
            }
        }.distinct()
    }

    private data class Panel(val name: String, val path: String)

    private data class SeasonPage(
        val number: Int,
        val pageUrl: String,
        val maxEpisodes: Int,
        val vfLinks: Map<String, List<String>>,
        val voLinks: Map<String, List<String>>,
    )
}
