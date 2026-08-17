package com.lagradost.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Source MovieBox, reprise à l'identique de l'implémentation invokeMoviebox de
 * CineStream (SaurabhKaperwan/CSX) : API h5-api.aoneroom.com avec jeton x-user,
 * recherche par sujet, endpoints /subject/download et /subject/play.
 *
 * Différences avec CSX d'origine :
 * - Tous les sujets trouvés sont conservés au niveau du matching (y compris
 *   ceux sans tag de langue), c'est-à-dire « tout MovieBox ».
 * - Les lecteurs dont la langue est CONNUE et non française (Hindi, Anglais,
 *   Version anglaise…) sont écartés ; ceux sans tag (Original) sont conservés
 *   car leur piste audio est indéterminée. Le tag entre crochets du titre
 *   ([VF], [VOSTFR], [VFF], [Multi], [Version française]) détermine la langue
 *   et est reporté dans le nom du lecteur.
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
        // data : « moviebox://{type}::{title} » (type = movie|tv)
        val parts = data.removePrefix("moviebox://").split("::")
        val type = parts.getOrNull(0) ?: return false
        val title = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return false
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val token = fetchToken() ?: return false
        val headers = baseHeaders(token)
        val subjectType = if (season != null) 2 else 1

        // MovieBox indexe parfois la piste française sous un titre séparé,
        // notamment « Titre [Version française] ». On interroge donc le titre
        // TMDB et ses variantes de langue, puis on déduplique par subjectId.
        val searchQueries = linkedSetOf(title, "$title Version française", "$title VF", "$title VOSTFR")
        val subjectsById = mutableMapOf<String, String>()
        searchQueries.forEach { keyword ->
            val searchObj = runCatching {
                JSONObject(
                    app.post(
                        "$baseUrl/wefeed-h5api-bff/subject/search",
                        headers = headers,
                        json = mapOf(
                            "keyword" to keyword,
                            "page" to 1,
                            "perPage" to 24,
                            "subjectType" to subjectType,
                        ),
                    ).text,
                )
            }.getOrNull() ?: return@forEach
            val items = searchObj.optJSONObject("data")?.optJSONArray("items") ?: return@forEach
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("subjectId").takeIf(String::isNotBlank) ?: continue
                val cleanTitle = item.optString("title", "").replace(seasonSuffixRegex, "")
                val bracketLanguage = Regex("\\[([^\\]]+)]").find(cleanTitle)?.groupValues?.getOrNull(1)
                val language = bracketLanguage ?: when {
                    cleanTitle.contains("version française", true) -> "Version française"
                    cleanTitle.contains("vostfr", true) -> "VOSTFR"
                    cleanTitle.contains("vf", true) -> "VF"
                    cleanTitle.contains("english", true) || cleanTitle.contains("hindi", true) -> "Original"
                    else -> "Original"
                }
                subjectsById[id] = language
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
                    "Referer" to "$referer/",
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
                val addedQualities = mutableSetOf<Int>()

                downloadObj.optJSONArray("downloads")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { download ->
                        val url = download.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (download.optBoolean("vipLocked", false)) return@forEach
                        val resolution = download.optInt("resolution")
                        if (addedQualities.add(resolution)) {
                            callback.invoke(
                                newExtractorLink(displayName, displayName, url) {
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
                        if (addedQualities.add(resolution)) {
                            callback.invoke(
                                newExtractorLink(displayName, displayName, url) {
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
                        callback.invoke(
                            newExtractorLink(
                                displayName,
                                "$displayName (Auto)",
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

    /** Construit le nom affiché du lecteur, en français lisible. */
    private fun buildName(language: String): String {
        val tag = when (language.uppercase()) {
            "VERSION FRANÇAISE" -> "VF"
            "ORIGINAL" -> "Original"
            else -> language.uppercase()
        }
        return "MovieBox [$tag]"
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
