package com.lagradost.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

/**
 * Source MovieBox, reprise à l'identique de l'implémentation invokeMoviebox de
 * CineStream (SaurabhKaperwan/CSX) : API h5-api.aoneroom.com avec jeton x-user,
 * recherche par sujet, endpoints /subject/download et /subject/play. Seule
 * différence : les lecteurs dont la langue n'est pas française (VF, VOSTFR,
 * VFF, Multi) sont écartés, y compris les sous-titres non français.
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

    private val frenchLanguages = setOf("VF", "VOSTFR", "VFF", "MULTI", "FRENCH")

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

        val searchObj = runCatching {
            JSONObject(
                app.post(
                    "$baseUrl/wefeed-h5api-bff/subject/search",
                    headers = headers,
                    json = mapOf(
                        "keyword" to title,
                        "page" to 1,
                        "perPage" to 24,
                        "subjectType" to subjectType,
                    ),
                ).text,
            )
        }.getOrNull() ?: return false

        val items = unwrap(searchObj).optJSONArray("items") ?: return false
        val titleMatchRegex = Regex(
            "^${Regex.escape(title)}(?:\\s+\\[([^\\]]+)])?$",
            RegexOption.IGNORE_CASE,
        )
        // Comportement CineStream : un sujet par identifiant, sa langue étant
        // celle du tag entre crochets de son titre (ex : « Oppenheimer [VF] »).
        val subjectsById = mutableMapOf<String, String>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf(String::isNotBlank) ?: continue
            val cleanTitle = item.optString("title", "").replace(seasonSuffixRegex, "")
            val language = titleMatchRegex.find(cleanTitle)?.groupValues?.getOrNull(1) ?: "Original"
            subjectsById.putIfAbsent(id, language)
        }

        // Les seuls contenus conservés sont ceux en AUDIO FRANÇAIS : VF, VOSTFR,
        // VFF ou Multi. Tout le reste (Original, Anglais, etc.) est écarté.
        val frenchSubjects = subjectsById.filter { (_, language) ->
            language.uppercase() in frenchLanguages
        }

        if (frenchSubjects.isEmpty()) return false

        frenchSubjects.forEach { (subjectId, language) ->
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
                    unwrap(JSONObject(app.get("$baseUrl/wefeed-h5api-bff/subject/download?$params", headers = playHeaders).text))
                }.getOrNull() ?: JSONObject()
                val playObj = runCatching {
                    unwrap(JSONObject(app.get("$baseUrl/wefeed-h5api-bff/subject/play?$params", headers = playHeaders).text))
                }.getOrNull() ?: JSONObject()

                val displayName = "MovieBox [$language]"
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

    /** Récupère le jeton d'API MovieBox via le header x-user du point d'entrée des paquets. */
    private suspend fun fetchToken(): String? = runCatching {
        val xUser = app.get("$baseUrl/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox")
            .headers.get("x-user")
        JSONObject(xUser ?: return null).optString("token").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun baseHeaders(token: String): Map<String, String> = mapOf(
        "X-Client-Info" to """{"timezone":"Africa/Nairobi"}""",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept" to "application/json",
        "Referer" to baseUrl,
        "Host" to "h5-api.aoneroom.com",
        "Connection" to "keep-alive",
        "Authorization" to "Bearer $token",
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
    )

    private fun unwrap(json: JSONObject): JSONObject {
        val data = json.optJSONObject("data") ?: return json
        return data.optJSONObject("data") ?: data
    }

    /** Accepte le français sous toutes ses formes usuelles dans les sous-titres. */
    private fun isFrenchLanguage(language: String): Boolean {
        val normalized = language.uppercase()
        return "FR" in normalized || "FRENCH" in normalized || "VF" in normalized ||
            "VOSTFR" in normalized || "MULTI" in normalized
    }
}
