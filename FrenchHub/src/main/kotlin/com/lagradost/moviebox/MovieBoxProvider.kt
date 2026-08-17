package com.lagradost.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Source MovieBox (api.h5.aoneroom.com), inspirée de l'implémentation
 * invokeMoviebox de CineStream (SaurabhKaperwan/CSX). Seuls les contenus en
 * AUDIO FRANÇAIS sont conservés : les résultats dont le suffixe de langue est
 * différent de « French » ([French] / [Multi] avec piste VF) sont ignorés.
 */
class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://h5-api.aoneroom.com"
    override var name = "MovieBox"
    override var lang = "fr"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val baseUrl get() = mainUrl.trimEnd('/')

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data : « moviebox://{type}::{title} » (type = movie|tv)
        val payload = data.removePrefix("moviebox://")
        val parts = payload.split("::")
        val type = parts.getOrNull(0) ?: return false
        val title = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return false
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val token = fetchToken() ?: return false
        val headers = baseHeaders(token)
        val subjectType = if (season != null) 2 else 1

        val searchJson = runCatching {
            JSONObject(
                app.post(
                    "$baseUrl/wefeed-h5api-bff/subject/search",
                    headers = headers,
                    json = mapOf("keyword" to title, "page" to 1, "perPage" to 24, "subjectType" to subjectType),
                ).text,
            )
        }.getOrNull() ?: return false

        val items = unwrap(searchJson).optJSONArray("items") ?: return false

        // Le titre des résultats MovieBox porte la langue en suffixe
        // crochets : « Le Cas Oppenheimer [French] ». On ne garde que
        // l'audio français.
        val titleRegex = Regex("^${Regex.escape(title)}(?:\\s+\\[([^\\]]+)])?$", RegexOption.IGNORE_CASE)
        val frenchSubjects = items.toJsonObjects()
            .mapNotNull { item ->
                val subjectId = item.optString("subjectId").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val rawTitle = item.optString("title").replace(Regex("""\s*S\d+(?:-S?\d+)*$"""), "").trim()
                val language = titleRegex.find(rawTitle)?.groupValues?.getOrNull(1) ?: "Original"
                if (language.equals("French", ignoreCase = true) ||
                    language.equals("Multi", ignoreCase = true) ||
                    language.equals("VF", ignoreCase = true)
                ) subjectId to language else null
            }
            .distinctBy { it.first }

        if (frenchSubjects.isEmpty()) return false

        frenchSubjects.forEach { (subjectId, language) ->
            runCatching {
                val detailJson = runCatching {
                    JSONObject(
                        app.get(
                            "https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
                        ).text,
                    )
                }.getOrNull() ?: return@forEach

                val detailPath = detailJson
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
                val referer = "https://fmoviesunblocked.net/"
                val playHeaders = headers + mapOf(
                    "Referer" to referer,
                    "Origin" to referer.trimEnd('/'),
                )

                val downloadData = runCatching {
                    unwrap(JSONObject(app.get("$baseUrl/wefeed-h5api-bff/subject/download?$params", headers = playHeaders).text))
                }.getOrNull() ?: JSONObject()
                val playData = runCatching {
                    unwrap(JSONObject(app.get("$baseUrl/wefeed-h5api-bff/subject/play?$params", headers = playHeaders).text))
                }.getOrNull() ?: JSONObject()

                val name = "MovieBox [${language.ifBlank { "French" }}]"
                val addedResolutions = mutableSetOf<Int>()

                downloadData.optJSONArray("downloads")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { download ->
                        val url = download.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (download.optBoolean("vipLocked")) return@forEach
                        val resolution = download.optInt("resolution")
                        if (addedResolutions.add(resolution)) {
                            callback(extractorLink(name, url, referer, resolution))
                        }
                    }
                }

                playData.optJSONArray("streams")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { stream ->
                        val url = stream.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (stream.optBoolean("vipLocked")) return@forEach
                        val resolution = stream.optString("resolutions").toIntOrNull()
                            ?: stream.optInt("resolution", 0)
                        if (addedResolutions.add(resolution)) {
                            callback(extractorLink(name, url, referer, resolution))
                        }
                    }
                }

                playData.optJSONArray("dash")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { dash ->
                        val url = dash.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        if (dash.optBoolean("vipLocked")) return@forEach
                        callback(
                            @Suppress("DEPRECATION_ERROR")
                            ExtractorLink(
                                "MovieBox",
                                "$name (Auto)",
                                url,
                                referer.trimEnd('/'),
                                Qualities.Unknown.value,
                                ExtractorLinkType.M3U8,
                                headers = mapOf("Referer" to referer, "Origin" to referer.trimEnd('/')),
                            ),
                        )
                    }
                }

                downloadData.optJSONArray("captions")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.forEach { caption ->
                        val url = caption.optString("url").takeIf(String::isNotBlank) ?: return@forEach
                        val langName = caption.optString("lanName").ifBlank { caption.optString("lan") }
                        if (langName.isNotBlank()) {
                            subtitleCallback(
                                SubtitleFile(langName, url),
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private fun extractorLink(name: String, url: String, referer: String, resolution: Int): ExtractorLink {
        @Suppress("DEPRECATION_ERROR")
        return ExtractorLink(
            "MovieBox",
            name,
            url,
            referer.trimEnd('/'),
            when {
                resolution >= 2160 -> Qualities.P2160.value
                resolution >= 1080 -> Qualities.P1080.value
                resolution >= 720 -> Qualities.P720.value
                resolution >= 480 -> Qualities.P480.value
                else -> Qualities.Unknown.value
            },
            if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            headers = mapOf("Referer" to referer, "Origin" to referer.trimEnd('/')),
        )
    }

    /** Récupère le jeton d'API MovieBox via le header x-user du point d'entrée des paquets. */
    private suspend fun fetchToken(): String? {
        val token = runCatching {
            val response = app.get("$baseUrl/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox")
            response.headers.get("x-user")?.let { JSONObject(it).optString("token") }?.takeIf(String::isNotBlank)
        }.getOrNull()
        return token
    }

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

    private fun org.json.JSONArray.toJsonObjects(): List<JSONObject> {
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
