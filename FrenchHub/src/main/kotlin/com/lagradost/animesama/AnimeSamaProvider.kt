package com.lagradost.frenchhub.animesama

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nikola.FrenchStreamTmdbClient
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Source Anime-Sama (anime-sama.to), réécrite sur le modèle qui fonctionne dans
 * les extensions Nuvio (nuvio-french-providers / gowaru-nuvio-providers).
 *
 * Différence clé par rapport à la v17 : les épisodes ne sont plus cherchés en
 * parsant la page HTML (le tag <script src="episodes.js"> a un contenu inline
 * vide, ce qui cassait l'ancienne extraction). Les épisodes sont lus
 * DIRECTEMENT depuis le fichier JavaScript du site :
 *
 *   https://anime-sama.to/catalogue/{slug}/saison{N}/{lang}/episodes.js
 *
 * avec des variantes de slugs construites depuis les titres TMDB
 * (français + anglais + original + « Saison N »), exactement comme Nuvio :
 * slug TMDB → slug-saison-N → slug-N → titres alternatifs → recherche du site.
 * Les langues VF et VOSTFR sont cherchées pour chaque slug ; la VF est
 * prioritaire dans la liste des lecteurs.
 *
 * Format du payload passé par le catalogue (directProviderData « animesama ») :
 *   animesama://{tmdbId}::{type}::{season}::{episode}::{titre}
 */
class AnimeSamaProvider : MainAPI() {
    override var mainUrl = "https://anime-sama.to"
    override var name = "Anime Sama"
    override var lang = "fr"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val browserHeaders = mapOf("User-Agent" to userAgent)

    /** Var → liste des URLs embed, exactement comme parseUrls de Nuvio. */
    private val varRegex = Regex("""var\s+([a-z0-9]+)\s*=\s*\[([\s\S]*?)\s*\];""")
    private val quotedRegex = Regex("""['"]([^'"]+)['"]""")

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    /**
     * Charge un film ou une série : les lecteurs sont émis ici via loadLinks ;
     * la fiche est construite à partir de TMDB (titre, affiche, résumé).
     */
    override suspend fun load(url: String): LoadResponse {
        val parts = url.removePrefix("animesama://").split("::")
        val tmdbId = parts.getOrNull(0)?.toIntOrNull()
            ?: throw ErrorLoadingException("Identifiant TMDB manquant pour Anime-Sama")
        val type = parts.getOrNull(1) ?: "tv"
        // Anime-Sama est un site exclusivement dédié aux animés : aucune fiche
        // film ne doit être traitée (les entrées « Anime-Sama (embed) » vides
        // sur les films venaient de là).
        if (type != "tv") throw ErrorLoadingException("Anime-Sama ne traite que les séries/animes")
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()
        val title = parts.getOrNull(4)?.takeIf(String::isNotBlank) ?: "Anime"

        val tmdb = FrenchStreamTmdbClient.details(title, null, type == "tv")
        val isSeries = type == "tv"

        // La réponse TMDB arrive en language=fr-FR : title/name = titre
        // français, original_title/original_name = titre original (souvent
        // anglais), poster = affiche localisée ou originale.
        val titleFr = tmdb?.let {
            (it.optString("name").ifBlank { it.optString("title") }).takeIf(String::isNotBlank)
        } ?: title
        val titleOriginal = tmdb?.let {
            (it.optString("original_name").ifBlank { it.optString("original_title") })
                .takeIf(String::isNotBlank)
        }
        // Nuvio cherche aussi le titre anglais « officiel » : la recherche TMDB
        // (find, language=fr-FR) retourne souvent le titre original dans le
        // champ title quand aucune traduction française n'existe — on l'utilise
        // comme variante complémentaire.
        val titleEn = FrenchStreamTmdbClient.find(title, null, isSeries)?.let {
            (it.optString("name").ifBlank { it.optString("title") }).takeIf(String::isNotBlank)
        }?.takeIf { it != titleFr && it != titleOriginal }

        // Les saisons réelles sont celles détectées par TMDB (title + « Saison N »),
        // pas les panneaux HTML : le catalogue TMDB fait foi, comme Nuvio.
        val seasonCount = tmdb?.let { details ->
            details.optJSONArray("seasons")?.let { seasons ->
                (0 until seasons.length()).mapNotNull { i ->
                    seasons.optJSONObject(i)?.optInt("season_number", 0)?.takeIf { n -> n > 0 }
                }.maxOrNull()?.takeIf { max -> max > 0 } ?: 1
            } ?: 1
        } ?: 1

        val dubbedEpisodes = mutableListOf<Episode>()
        val subbedEpisodes = mutableListOf<Episode>()
        var foundAny = false

        for (s in 1..seasonCount) {
            val seasonData = runCatching {
                AnimeSamaSeasonData(tmdbId, titleFr, titleEn.orEmpty(), titleOriginal.orEmpty(), isSeries, s)
            }.getOrNull() ?: continue
            val seasonEpisodes = seasonData.resolveLinks(episode)
            if (seasonEpisodes.vf.isEmpty() && seasonEpisodes.vostfr.isEmpty()) continue
            foundAny = true

            for (ep in 1..maxOf(seasonEpisodes.total, 1)) {
                val vfData = seasonEpisodes.vf.getOrNull(ep - 1)?.joinToString(" ").orEmpty()
                val vostfrData = seasonEpisodes.vostfr.getOrNull(ep - 1)?.joinToString(" ").orEmpty()
                val epName = if (seasonCount == 1 && !isSeries) {
                    titleFr
                } else {
                    "$titleFr - Saison $s Épisode ${ep.toString().padStart(2, '0')}"
                }
                if (vfData.isNotBlank()) {
                    dubbedEpisodes += newEpisode(vfData) {
                        this.name = "$epName [VF]"
                        this.season = s
                        this.episode = ep
                    }
                }
                if (vostfrData.isNotBlank()) {
                    subbedEpisodes += newEpisode(vostfrData) {
                        this.name = "$epName [VOSTFR]"
                        this.season = s
                        this.episode = ep
                    }
                }
            }
        }

        if (!foundAny && dubbedEpisodes.isEmpty() && subbedEpisodes.isEmpty()) {
            throw ErrorLoadingException("Aucun épisode trouvé sur Anime-Sama")
        }

        return newAnimeLoadResponse(titleFr, url, TvType.Anime) {
            this.posterUrl = FrenchStreamTmdbClient.image(
                tmdb?.optString("poster_path")
                    ?: FrenchStreamTmdbClient.find(title, null, isSeries)?.optString("poster_path"),
            )
            this.plot = tmdb?.optString("overview")
            // addDubStatus n'est disponible que sur AnimeSearchResponse ; la
            // présence de VF/VOSTFR est exprimée par les sections Dubbed/Subbed.
            if (dubbedEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbedEpisodes)
            if (subbedEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbedEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data : « {url}::{epIndex}::{lang} » — les liens sont indexés dans
        // load() et stockés ici par épisode ; on émet chaque embed via les
        // extracteurs CloudStream (sibnet, vidmoly, sendvid, voe, …).
        val parts = data.split("::")
        val links = parts.getOrNull(0)?.split(Regex("\\s+"))?.filter(String::isNotBlank).orEmpty()
        if (links.isEmpty()) return false
        var emitted = false
        for (link in links) {
            for (candidate in linkCandidates(link)) {
                if (loadExtractor(candidate, subtitleCallback) { extracted ->
                        emitted = true
                        callback(extracted)
                    }) {
                    break
                }
            }
        }
        // Aucun extracteur n'a résolu l'embed : ne pas émettre de lecteur vide
        // (l'ancien fallback « Anime-Sama (embed) » apparaissait même sur les
        // films non-anime et affichait un lecteur non fonctionnel).
        return emitted
    }

    private fun linkCandidates(url: String): List<String> = buildList {
        add(url)
        val lower = url.lowercase()
        if ("vidmoly" in lower) {
            for (tld in listOf("me", "net", "to", "ru", "biz")) {
                add(url.replace(Regex("""vidmoly\.[a-z]+"""), "vidmoly.$tld"))
            }
        }
        if ("streamtape" in lower || "stape" in lower) {
            add(url.replace(Regex("""(stream)?tape\.[a-z]+"""), "streamtape.com"))
        }
    }.distinct()

    /** Titres à essayer comme slug, dans l'ordre Nuvio : FR, EN, original, variantes Saison. */
    private fun candidateTitles(titleFr: String, titleEn: String, titleOriginal: String, season: Int): List<String> {
        val seen = linkedSetOf<String>()
        val add = { t: String -> if (t.isNotBlank()) seen.add(t) }
        add(titleFr)
        add(titleEn)
        titleOriginal?.takeIf { it != titleEn && it != titleFr }?.let(add)
        if (season > 0) {
            add("$titleFr Saison $season")
            add("$titleEn Season $season")
            add("$titleEn S$season")
        }
        return seen.toList()
    }

    /** slug friendly identique à toSlug de Nuvio (NFD sans accents, tirets). */
    private fun toSlug(title: String): String = Normalizer
        .normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[':!.,?()\[\]/–—"]"""), " ")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .replace(Regex("""-+"""), "-")
        .trim('-')

    /** Clé de comparaison normalisée (pour le matching de la recherche). */
    private fun titleKey(title: String): String = Normalizer
        .normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), "")

    private inner class AnimeSamaSeasonData(
        private val tmdbId: Int,
        private val titleFr: String,
        private val titleEn: String,
        private val titleOriginal: String,
        private val isSeries: Boolean,
        private val season: Int,
    ) {
        /** Ordre des essais : slug TMDB, root sans saison, slug-saison-N, slug-N,
         *  titres alternatifs TMDB, puis recherche du site — comme Nuvio. */
        suspend fun resolveLinks(episode: Int?): SeasonEpisodes {
            val languages = listOf("vostfr", "vf")
            val slugs = linkedSetOf<String>()
            for (title in candidateTitles(titleFr, titleEn, titleOriginal, season)) {
                slugs.add(toSlug(title))
                if (season > 1) slugs.add("${toSlug(title)}-saison-$season")
                if (season > 1) slugs.add("${toSlug(title)}-$season")
            }
            if (slugs.size > 5) slugs.take(5).also { slugs.clear(); slugs.addAll(it) }

            // 1. Slugs directs : chaque (slug, lang) → episodes.js.
            for (slug in slugs) {
                val vf = linkedMapOf<String, List<String>>()
                val vostfr = linkedMapOf<String, List<String>>()
                for (lang in languages) {
                    val parsed = fetchEpisodeVars(slug, season, lang)
                    if (parsed.isNotEmpty()) {
                        val streams = parseStreams(parsed, episode)
                        if (lang == "vf") vf.putAll(streams) else vostfr.putAll(streams)
                    }
                }
                // Root path (sans préfixe de saison) — utile si tout est dans un seul fichier.
                for (lang in languages) {
                    val parsed = fetchRootVars(slug, lang)
                    if (parsed.isNotEmpty()) {
                        val streams = parseStreams(parsed, episode)
                        if (lang == "vf") vf.putAll(streams) else vostfr.putAll(streams)
                    }
                }
                if (vf.isNotEmpty() || vostfr.isNotEmpty()) {
                    return SeasonEpisodes(vf.values.toList(), vostfr.values.toList())
                }
            }

            // 2. Recherche du site : fetch.php retourne les slugs RÉELS des fiches
            // trouvées — le matching est exact, bien meilleur que des slugs TMDB
            // approximatifs. Pour chaque fiche trouvée on lit la page HTML et on
            // charge episodes.js directement (le tag a un contenu inline vide,
            // le fichier JS porte les vraies URLs).
            for (title in candidateTitles(titleFr, titleEn, titleOriginal, 0).take(2)) {
                val found = runCatching { searchSiteSlugs(title) }.getOrNull().orEmpty()
                for (slug in found) {
                    val vf = linkedMapOf<String, List<String>>()
                    val vostfr = linkedMapOf<String, List<String>>()
                    for (lang in languages) {
                        val parsed = fetchHtmlEpisodeVars(slug, lang)
                        if (parsed.isNotEmpty()) {
                            val streams = parseStreams(parsed, episode)
                            if (lang == "vf") vf.putAll(streams) else vostfr.putAll(streams)
                        }
                    }
                    if (vf.isNotEmpty() || vostfr.isNotEmpty()) {
                        return SeasonEpisodes(vf.values.toList(), vostfr.values.toList())
                    }
                }
            }
            return SeasonEpisodes(emptyList(), emptyList())
        }

        /**
         * Lit la page HTML de la fiche, repère la référence episodes.js dans le
         * bloc des panneaux (le tag a un contenu inline vide, c'est le fichier
         * JS qui porte les URLs) et charge ce fichier directement.
         */
        private suspend fun fetchHtmlEpisodeVars(slug: String, lang: String): List<Pair<String, List<String>>> {
            // La page du panneau (ex. /catalogue/{slug}/saison1/vf/) porte la
            // langue dans son chemin et son script episodes.js contient les
            // URLs de cette langue seulement. La racine /catalogue/{slug}/ est
            // essayée en secours (fiche mono-saison souvent hébergée à la racine).
            val pageUrls = buildList {
                add("$mainUrl/catalogue/$slug/saison$season/$lang/")
                add("$mainUrl/catalogue/$slug/saison$season/$lang")
                add("$mainUrl/catalogue/$slug/$lang/")
                add("$mainUrl/catalogue/$slug/")
            }
            val episodeJsRef = Regex("""episodes\.js\??(?:filever=\d+)?""")
            for (pageUrl in pageUrls) {
                val episodeScript = runCatching {
                    val doc = app.get(pageUrl, headers = browserHeaders).document
                    // Le tag script peut être dans #sousBlocMiddle ou ailleurs :
                    // chercher d'abord le sélecteur précis, puis toute la page.
                    val attr = doc.selectFirst("#sousBlocMiddle script[src*='episodes.js']")
                        ?.attr("src")?.takeIf(String::isNotBlank)
                        ?: episodeJsRef.find(doc.html())?.let { match ->
                            val snippet = match.value
                            if (snippet.startsWith("http")) snippet
                            else {
                                val pagePath = java.net.URI(pageUrl).path
                                val dir = pagePath.substringBeforeLast('/')
                                val file = snippet.substringAfterLast('/').substringBefore('?')
                                "$dir/$file"
                            }
                        }
                    attr
                }.getOrNull()
                if (episodeScript.isNullOrBlank()) continue
                val scriptUrl = if (episodeScript.startsWith("http")) episodeScript
                else "$mainUrl$episodeScript"
                val js = runCatching {
                    app.get(scriptUrl, headers = browserHeaders).text
                }.getOrNull().orEmpty()
                if (js.isBlank()) continue
                val vars = varRegex.findAll(js).mapNotNull { match ->
                    val varName = match.groupValues.getOrNull(1).orEmpty().ifBlank { return@mapNotNull null }
                    val urls = quotedRegex.findAll(match.groupValues.getOrNull(2).orEmpty())
                        .map { it.groupValues.getOrNull(1).orEmpty() }
                        .filter { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) }
                        .toList()
                    if (urls.isEmpty()) null else varName to urls
                }.toList()
                if (vars.isNotEmpty()) return vars
            }
            return emptyList()
        }

        private fun parseStreams(vars: List<Pair<String, List<String>>>, episode: Int?): Map<String, List<String>> {
            val result = linkedMapOf<String, List<String>>()
            vars.forEachIndexed { index, (varName, urls) ->
                val player = playerFor(varName, urls.firstOrNull().orEmpty())
                val picked = when {
                    episode == null -> urls.firstOrNull()?.takeIf(String::isNotBlank)?.let { listOf(it) } ?: emptyList()
                    episode in 1..urls.size -> listOf(urls[episode - 1])
                    else -> emptyList()
                }
                if (picked.isNotEmpty()) {
                    result["$player|${picked.joinToString(" ")}"] = picked
                }
            }
            return result
        }

        private fun playerFor(varName: String, url: String): String = when {
            "sibnet" in url -> "Sibnet"
            "vidmoly" in url -> "Vidmoly"
            "sendvid" in url -> "Sendvid"
            "voe" in url -> "Voe"
            "streamtape" in url || "stape" in url -> "Streamtape"
            "dood" in url -> "Doodstream"
            "uqload" in url || "oneupload" in url -> "Uqload"
            else -> varName.replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun fetchEpisodeVars(slug: String, season: Int, lang: String): List<Pair<String, List<String>>> {
        val path = if (season > 0) "saison$season" else ""
        val url = "$mainUrl/catalogue/$slug/${if (path.isNotBlank()) "$path/" else ""}$lang/episodes.js"
        return runCatching {
            val text = app.get(url, headers = browserHeaders, referer = "$mainUrl/", timeout = 10L).text
            parseVars(text)
        }.getOrNull() ?: emptyList()
    }

    private suspend fun fetchRootVars(slug: String, lang: String): List<Pair<String, List<String>>> {
        val url = "$mainUrl/catalogue/$slug/$lang/episodes.js"
        return runCatching {
            val text = app.get(url, headers = browserHeaders, referer = "$mainUrl/", timeout = 10L).text
            parseVars(text)
        }.getOrNull() ?: emptyList()
    }

    private fun parseVars(text: String): List<Pair<String, List<String>>> =
        varRegex.findAll(text).mapNotNull { match ->
            val varName = match.groupValues[1]
            val urls = quotedRegex.findAll(match.groupValues[2])
                .map { it.groupValues[1] }
                .filter { it.startsWith("http", true) || it.startsWith("//") }
                .map { if (it.startsWith("//")) "https:$it" else it }
                .distinct()
                .toList()
            if (urls.isEmpty()) null else varName to urls
        }.toList()

    /** Recherche fetch.php avec scoring NFD, retourne les 2 meilleurs slugs. */
    private suspend fun searchSiteSlugs(query: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = runCatching {
            app.post(
                "$mainUrl/template-php/defaut/fetch.php",
                data = mapOf("query" to query),
                headers = browserHeaders,
                referer = "$mainUrl/",
                timeout = 10L,
            ).text
        }.getOrNull() ?: return emptyList()

        val results = mutableListOf<Pair<String, Int>>()
        val seen = mutableSetOf<String>()
        Regex("""href="([^"]*/catalogue/([^/]+)/?)"[^>]*>[\s\S]*?asn-search-result-title[^>]*>([^<]+)""").findAll(html).forEach { match ->
            val slug = match.groupValues[2]
            if (slug in seen) return@forEach
            seen.add(slug)
            val title = match.groupValues[3].trim()
            val score = scoreSearchResult(title, query)
            if (score > 0) results += slug to score
        }
        // Les anchors peuvent être dans l'autre ordre (title avant href) : scan plus laxiste.
        if (results.isEmpty()) {
            Regex("""asn-search-result-title[^>]*>([^<]+)[\s\S]{0,300}?href="[^"]*/catalogue/([^/]+)/""" , IGNORE_CASE).findAll(html).forEach { match ->
                val title = match.groupValues[1].trim()
                val slug = match.groupValues[2]
                if (slug in seen) return@forEach
                seen.add(slug)
                val score = scoreSearchResult(title, query)
                if (score > 0) results += slug to score
            }
        }
        return results.sortedByDescending { it.second }.take(2).map { it.first }
    }

    /** Scoring identique à Nuvio (NFD, mots > 2 lettres). */
    private fun scoreSearchResult(resultTitle: String, query: String): Int {
        val q = titleKey(query)
        val t = titleKey(resultTitle)
        if (q.isEmpty() || t.isEmpty()) return 0
        var score = 0
        when {
            t == q -> return 100
            t.contains(q) -> score += 60
            q.contains(t) -> score += 50
        }
        val qWords = q.split(Regex("""[a-z0-9]+""")).filter { it.length > 2 }
        val tWords = t.split(Regex("""[a-z0-9]+""")).filter { it.length > 2 }
        score += qWords.count { it in tWords } * 15
        return score
    }

    private data class SeasonEpisodes(
        val vf: List<List<String>>,
        val vostfr: List<List<String>>,
    ) {
        val total: Int get() = maxOf(
            vf.maxOfOrNull { it.size } ?: 0,
            vostfr.maxOfOrNull { it.size } ?: 0,
        )
    }
}
