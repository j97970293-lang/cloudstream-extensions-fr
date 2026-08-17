package com.lagradost.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nikola.FrenchStreamTmdbClient
import com.lagradost.nikola.FrenchStreamMetadata
import org.json.JSONObject
import java.text.Normalizer

/**
 * Source MovieBox, reprise du modèle invokeMoviebox de CineStream (CSX) avec
 * les correctifs nécessaires à la v17 :
 *
 * 1. RECHERCHE SANS subjectType — l'API h5-api.aoneroom.com renvoie
 *    systématiquement totalCount=0/items vides avec subjectType=1 (films)
 *    et renvoie même les counts Movies à zéro. On interroge donc sans
 *    subjectType et on filtre/rank côté client (comme CSX fait le matching
 *    au niveau des items).
 * 2. MATCHING TMDB — comme Nikola/FrenchStream : on cherche le titre TMDB
 *    (français) et le titre original, on normalise (NFD sans accents) et on
 *    matche le titre MovieBox le plus proche en tenant compte des suffixes
 *    de langue [VF]/[VOSTFR]/[Multi] et des « S1-S8 ».
 * 3. RECHERCHE GÉNÉRIQUE DE SECOURS — si aucun item ne correspond au titre,
 *    on relance une recherche courte (1-2 mots clés) et on réapplique le
 *    matching : l'API ne propose pas de recherche exacte fiable et indexe
 *    parfois les fiches sous des titres très différents.
 * 4. FILTRE LANGUES — seuls les lecteurs dont la langue est française
 *    (VF, VOSTFR, VFF, Multi) ou indéterminée (Original) sont conservés.
 *
 * Note géographique : les endpoints /subject/download et /subject/play
 * retournent « invalid region » (403) depuis l'Europe. L'API est géoblocquée
 * et ne dessert que certaines régions (Afrique notamment). Aucune correction
 * côté extension ne peut contourner ce blocage serveur.
 */
class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://h5-api.aoneroom.com"
    override var name = "MovieBox"
    override var lang = "fr"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val baseUrl get() = mainUrl.trimEnd('/')
    private val seasonSuffixRegex = Regex("""\sS\d+(?:-S?\d+)*$""", RegexOption.IGNORE_CASE)

    /** Langues françaises reconnues dans les tags de titres MovieBox. */
    private val frenchLanguages = setOf("VF", "VOSTFR", "VFF", "MULTI", "VERSION FRANÇAISE", "FRENCH")

    /** Langues explicitement NON françaises : leurs lecteurs sont supprimés. */
    private val nonFrenchLanguages = setOf("HINDI", "VERSION ANGLAISE", "ENGLISH", "ANGLO")

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data : « moviebox://{tmdbId}::{type}::{title} » (type = movie|tv)
        val parts = data.removePrefix("moviebox://").split("::")
        val type = parts.getOrNull(1) ?: return false
        val title = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: return false
        val season = parts.getOrNull(3)?.toIntOrNull()
        val episode = parts.getOrNull(4)?.toIntOrNull()
        val isSeries = type == "tv"

        val token = fetchToken() ?: return false
        val headers = baseHeaders(token)

        // Identifiant commun TMDB/TMBV : le titre TMDB est résolu par son ID
        // (comme Nikola), avec le titre français ET le titre original afin de
        // couvrir les fiches indexées sous le titre anglais alors que le
        // catalogue TMDB demande le titre français (et inversement).
        val tmdbQuery = runCatching {
            FrenchStreamTmdbClient.find(title, null, isSeries)
        }.getOrNull() ?: runCatching {
            FrenchStreamTmdbClient.details(title, null, isSeries)
        }.getOrNull()
        val originalTitle = tmdbQuery?.let {
            (it.optString("original_title").ifBlank { it.optString("original_name") })
                .takeIf(String::isNotBlank)
        }
        val titleEn = tmdbQuery?.let {
            (it.optString("title").ifBlank { it.optString("name") }).takeIf(String::isNotBlank)
        }
        val tmdbTitles = linkedSetOf<String>().apply {
            originalTitle?.takeIf(String::isNotBlank)?.let { add(it) }
            titleEn?.takeIf(String::isNotBlank)?.let { add(it) }
            add(title)
        }

        // Variantes de langue recherchées pour chaque titre TMDB.
        val searchQueries = linkedSetOf<String>()
        tmdbTitles.forEach { tmdbTitle ->
            searchQueries.add(tmdbTitle)
            searchQueries.add("$tmdbTitle Version française")
            searchQueries.add("$tmdbTitle VF")
            searchQueries.add("$tmdbTitle VOSTFR")
        }

        val subjectType = if (season != null) 2 else 1

        // 1. Recherche primaire par titre TMDB et ses variantes de langue.
        val subjectsById = mutableMapOf<String, String>()
        searchQueries.forEach { keyword ->
            fetchSubjects(keyword, subjectType, headers)?.let { subjectsById.putAll(it) }
        }

        // 2. Secours : recherche sur le premier mot du titre puis recherche
        // générique (l'API indexe parfois sous un titre très différent ou ne
        // répond que partiellement).
        if (subjectsById.isEmpty()) {
            val firstWord = title.split(Regex("""\s+""")).firstOrNull()?.takeIf { it.length > 2 }
            if (firstWord != null && firstWord != title) {
                fetchSubjects(firstWord, null, headers)?.let { subjectsById.putAll(it) }
            }
        }
        if (subjectsById.isEmpty()) {
            val shortKeyword = title.split(Regex("""\s+"""))
                .filter { it.length > 2 }
                .take(2)
                .joinToString(" ")
            if (shortKeyword.isNotBlank() && shortKeyword != title) {
                fetchSubjects(shortKeyword, null, headers)?.let { subjectsById.putAll(it) }
            }
        }

        if (subjectsById.isEmpty()) return false

        // Les contenus explicitement non français sont écartés ; tout le reste
        // (VF, VOSTFR, VFF, Multi, Original…) est conservé.
        val keptSubjects = subjectsById.filter { (_, language) ->
            language.uppercase() !in nonFrenchLanguages
        }
        if (keptSubjects.isEmpty()) return false

        keptSubjects.forEach { (subjectId, language) ->
            runCatching {
                val detailObj = runCatching {
                    JSONObject(
                        app.get(
                            "https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
                        ).text,
                    )
                }.getOrNull() ?: return@forEach

                val detailPath = detailObj
                    .optJSONObject("data")
                    ?.optJSONArray("items")
                    ?.optJSONObject(0)
                    ?.optJSONObject("subject")
                    ?.optString("detailPath", "")
                    ?.takeIf(String::isNotBlank) ?: return@forEach

                val params = buildString {
                    append("subjectId=$subjectId")
                    if (season != null) append("&se=$season&ep=$episode")
                    append("&detailPath=$detailPath")
                }
                val referer = "https://fmoviesunblocked.net"
                val playHeaders = headers + mapOf(
                    "Referer" to "$referer/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                    "Origin" to referer,
                )

                val downloadObj = runCatching {
                    JSONObject(
                        app.get("$baseUrl/wefeed-h5api-bff/subject/download?$params", headers = playHeaders)
                            .text,
                    ).optJSONObject("data") ?: JSONObject()
                }.getOrNull() ?: JSONObject()
                val playObj = runCatching {
                    JSONObject(
                        app.get("$baseUrl/wefeed-h5api-bff/subject/play?$params", headers = playHeaders)
                            .text,
                    ).optJSONObject("data") ?: JSONObject()
                }.getOrNull() ?: JSONObject()

                val displayName = buildName(language)
                val emittedUrls = mutableSetOf<String>()
                val emittedSources = mutableSetOf<String>()

                downloadObj.optJSONArray("downloads")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { download ->
                        val url = download.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (download.optBoolean("vipLocked", false)) return@forEach
                        val resolution = download.optInt("resolution")
                        val sourceKey = sourceKey(url)
                        if (emittedUrls.add(url) && emittedSources.add(sourceKey)) {
                            callback.invoke(
                                newExtractorLink(
                                    displayName,
                                    "${languageLabel(language)} ${qualityLabel(resolution)}".trim(),
                                    url,
                                ) {
                                    this.headers = mapOf(
                                        "Referer" to "$referer/",
                                        "Origin" to referer,
                                    )
                                    this.quality = resolution
                                },
                            )
                        }
                    }
                }

                playObj.optJSONArray("streams")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { stream ->
                        val url = stream.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (stream.optBoolean("vipLocked", false)) return@forEach
                        val resolution = stream.optString("resolutions").toIntOrNull()
                            ?: stream.optInt("resolution", 0)
                        val sourceKey = sourceKey(url)
                        if (emittedUrls.add(url) && emittedSources.add(sourceKey)) {
                            callback.invoke(
                                newExtractorLink(
                                    displayName,
                                    "${languageLabel(language)} ${qualityLabel(resolution)}".trim(),
                                    url,
                                ) {
                                    this.headers = mapOf(
                                        "Referer" to "$referer/",
                                        "Origin" to referer,
                                    )
                                    this.quality = resolution
                                },
                            )
                        }
                    }
                }

                playObj.optJSONArray("dash")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { dash ->
                        val url = dash.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (dash.optBoolean("vipLocked", false)) return@forEach
                        if (emittedUrls.add(url)) {
                            callback.invoke(
                                newExtractorLink(
                                    displayName,
                                    "${languageLabel(language)} (Auto)".trim(),
                                    url,
                                ) {
                                    this.headers = mapOf(
                                        "Referer" to "$referer/",
                                        "Origin" to referer,
                                    )
                                },
                            )
                        }
                    }
                }

                // Sous-titres : seuls ceux en français sont conservés.
                downloadObj.optJSONArray("captions")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { caption ->
                        val url = caption.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        val languageName = caption.optString("lanName").ifBlank { caption.optString("lan") }
                        if (languageName.isNotBlank() && isFrenchLanguage(languageName)) {
                            subtitleCallback(SubtitleFile(languageName, url))
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * Interroge la recherche MovieBox SANS subjectType (l'API renvoie des
     * résultats vides ou incohérents avec subjectType=1 pour les films) et
     * retourne les items dont le titre matche le mot-clé demandé selon le
     * même principe de normalisation que Nikola (NFD, sans accents).
     */
    private suspend fun fetchSubjects(
        keyword: String,
        subjectType: Int?,
        headers: Map<String, String>,
    ): Map<String, String>? {
        val body = linkedMapOf<String, Any>(
            "keyword" to keyword,
            "page" to 1,
            "perPage" to 24,
        )
        subjectType?.let { body["subjectType"] = it }
        val searchObj = runCatching {
            JSONObject(
                app.post(
                    "$baseUrl/wefeed-h5api-bff/subject/search",
                    headers = headers,
                    json = body,
                ).text,
            )
        }.getOrNull() ?: return null

        val data = searchObj.optJSONObject("data")?.let { it.optJSONObject("data") ?: it } ?: return null
        val items = data.optJSONArray("items") ?: return null
        if (items.length() == 0) return null

        // Matching identique à Nikola : normalisation NFD + sans accents,
        // après retrait du suffixe de saison « S1-S8 » et du tag de langue.
        val keywordKey = FrenchStreamMetadata.normalizeTitle(keyword)
            .lowercase()
            .let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").replace(Regex("""[^a-z0-9]+"""), "") }

        val result = mutableMapOf<String, String>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf(String::isNotBlank) ?: continue
            val rawTitle = item.optString("title", "")
            val cleanTitle = FrenchStreamMetadata.normalizeTitle(rawTitle).replace(seasonSuffixRegex, "")
            val bracketLanguage = Regex("\\[([^\\]]+)]").find(cleanTitle)?.groupValues?.getOrNull(1)
            val titleKey = Normalizer.normalize(cleanTitle.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .replace(Regex("""[^a-z0-9]+"""), "")

            // Correspondance directe ou partielle : le titre MovieBox doit
            // commencer par le mot-clé ou contenir tous ses mots principaux.
            val matches = titleKey == keywordKey ||
                (keywordKey.isNotEmpty() && (titleKey.startsWith(keywordKey) || keywordKey in titleKey)) ||
                (keywordKey.split(Regex("""[a-z0-9]+""")).filter { it.length >= 4 }
                    .all { it in titleKey } && keywordKey.split(Regex("""[a-z0-9]+""")).filter { it.length >= 4 }.isNotEmpty())

            if (!matches) continue

            val language = bracketLanguage ?: when {
                cleanTitle.contains("version française", true) -> "Version française"
                cleanTitle.contains("vostfr", true) -> "VOSTFR"
                cleanTitle.contains("vf", true) -> "VF"
                else -> "Original"
            }
            result.putIfAbsent(id, language)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /** Le nom de la source du lecteur (affiché en premier dans l'UI). */
    private fun buildName(language: String): String = "MovieBox"

    /** Libellé de la langue en clair pour le nom du lecteur. */
    private fun languageLabel(language: String): String = when (language.uppercase()) {
        "VERSION FRANÇAISE" -> "VF"
        "ORIGINAL" -> "Original"
        else -> language.uppercase()
    }

    /**
     * Clé de dédoublonnage du fichier source : les fiches MovieBox dupliquées
     * (« VF », « Version française », original…) renvoient les mêmes vidéos
     * depuis des CDN différents (URLs distinctes). On ne conserve qu'un seul
     * lecteur par couple hôte + chemin du fichier.
     */
    private fun sourceKey(url: String): String = runCatching {
        val host = Regex("""https?://([^/]+)/""").find(url)?.groupValues?.getOrNull(1) ?: ""
        val path = url.substringAfter("?").let { rest ->
            if (rest == url) url.substringAfterLast('/') else ""
        }
        "$host/$path"
    }.getOrDefault(url)

    /** Libellé humain de la résolution (480 → « 480p », 0 → vide). */
    private fun qualityLabel(resolution: Int): String = when (resolution) {
        0 -> ""
        in 1..359 -> "${resolution}p"
        in 360..719 -> "${resolution}p"
        in 720..1079 -> "${resolution}p"
        else -> "${resolution}p"
    }

    /** Récupère le jeton d'API MovieBox via le header x-user du point d'entrée des paquets. */
    private suspend fun fetchToken(): String? = runCatching {
        val xUser = app.get("$baseUrl/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox")
            .headers.get("x-user")
        JSONObject(xUser ?: return null).optString("token").takeIf(String::isNotBlank)
    }.getOrNull()

    /**
     * Headers de base — sans Host personnalisé : le point de terminaison
     * h5.aoneroom.com rejette (404) les requêtes portant ce Host alternatif.
     */
    private fun baseHeaders(token: String): Map<String, String> = mapOf(
        "X-Client-Info" to """{"timezone":"Africa/Nairobi"}""",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept" to "application/json",
        "Referer" to baseUrl,
        "Connection" to "keep-alive",
        "Authorization" to "Bearer $token",
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
    )

    /** Accepte le français sous toutes ses formes usuelles dans les sous-titres. */
    private fun isFrenchLanguage(language: String): Boolean {
        val normalized = language.uppercase()
        return "FR" in normalized || "FRENCH" in normalized || "VF" in normalized ||
            "VOSTFR" in normalized || "MULTI" in normalized
    }
}
