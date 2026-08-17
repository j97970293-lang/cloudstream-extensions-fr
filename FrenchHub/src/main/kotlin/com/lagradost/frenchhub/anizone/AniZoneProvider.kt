package com.lagradost.frenchhub.anizone

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direction v23 : AniZone est intégré uniquement comme source anime complémentaire.
 * Le catalogue, les saisons et le matching restent centralisés dans FrenchHub/TMDB ;
 * ce provider résout ensuite les fiches AniZone et transmet le manifest HLS multi-audio
 * ainsi que les sous-titres français ou anglais déclarés sur la page d’épisode.
 */
class AniZoneProvider : MainAPI() {
    override var mainUrl = "https://anizone.to"
    override var name = "AniZone"
    override var lang = "en"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private data class LivewireSession(val cookie: String, val csrf: String, val snapshot: String)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val session = openSession() ?: return emptyList()
        val response = app.post(
            "$mainUrl/livewire/update",
            headers = headers + mapOf(
                "Content-Type" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/anime",
                "Cookie" to session.cookie,
            ),
            json = mapOf(
                "_token" to session.csrf,
                "components" to listOf(
                    mapOf(
                        "snapshot" to session.snapshot,
                        "updates" to mapOf("search" to query),
                        "calls" to emptyList<Any>(),
                    ),
                ),
            ),
        )
        val html = runCatching {
            JSONObject(response.text)
                .optJSONArray("components")
                ?.optJSONObject(0)
                ?.optJSONObject("effects")
                ?.optString("html")
                .orEmpty()
        }.getOrDefault("")
        return parseSearchItems(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("Titre AniZone introuvable") }
        val season = Regex("""(?i)(?:season|saison)\s*(\d+)""")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val episodes = doc.select("li[x-data] a[href]").mapNotNull { element ->
            val episodeUrl = element.attr("abs:href").ifBlank { element.attr("href") }
            if (episodeUrl.isBlank()) return@mapNotNull null
            val number = Regex("""/(\d+)(?:[/?#].*)?$""")
                .find(episodeUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(absoluteUrl(episodeUrl)) {
                name = element.selectFirst("h3")?.text()?.substringAfter(":")?.trim()
                    ?.takeIf(String::isNotBlank) ?: "Épisode $number"
                this.season = season
                this.episode = number
                posterUrl = element.selectFirst("img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode AniZone trouvé")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = doc.selectFirst("main img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            plot = doc.selectFirst(".sr-only + div")?.text()?.takeIf(String::isNotBlank)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data, headers = headers + mapOf("Referer" to "$mainUrl/")).text
        val players = decodePlayerPayloads(html)
        var emitted = false
        players.forEachIndexed { index, player ->
            player.optJSONArray("subtitles")?.emitSupportedSubtitles(subtitleCallback)
            val stream = player.optString("src").takeIf { it.startsWith("http") } ?: return@forEachIndexed
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name — multi-audio${if (players.size > 1) " ${index + 1}" else ""}",
                    url = stream,
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                },
            )
            emitted = true
        }
        return emitted
    }

    private suspend fun openSession(): LivewireSession? {
        val response = app.get("$mainUrl/anime", headers = headers)
        val html = response.text
        val csrf = Regex("""data-csrf="([^"]+)""").find(html)?.groupValues?.getOrNull(1) ?: return null
        val snapshot = Regex("""wire:snapshot="([^"]+)""").findAll(html)
            .map { decodeHtml(it.groupValues[1]) }
            .firstOrNull { it.contains("pages.anime-index") } ?: return null
        val cookie = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return LivewireSession(cookie, csrf, snapshot)
    }

    private fun parseSearchItems(html: String): List<SearchResponse> {
        val raw = Regex("""items:\s*JSON\.parse\('((?:\\.|[^'])*)'\)""")
            .find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        // Le fragment Livewire contient une chaîne JavaScript encodée une fois
        // de plus (\\u0022, \\\/, \\&). La convertir manuellement laissait un
        // antislash invalide devant « & » et faisait taire AniZone. On décode
        // d'abord la chaîne externe, puis le JSON interne.
        val entries = runCatching { JSONArray(decodeJavascriptString(raw)) }.getOrNull() ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("main_title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
            newAnimeSearchResponse(title, absoluteUrl(url), TvType.Anime) {
                posterUrl = item.optString("cover").takeIf(String::isNotBlank)
            }
        }.distinctBy { it.url }
    }

    private fun decodePlayerPayloads(html: String): List<JSONObject> {
        return Regex("""JSON\.parse\('((?:\\.|[^'])*)'\)""").findAll(html)
            .mapNotNull { match -> runCatching { JSONObject(decodeJavascriptString(match.groupValues[1])) }.getOrNull() }
            .filter { it.optString("src").startsWith("http") }
            .distinctBy { it.optString("src") }
            .toList()
    }

    private fun JSONArray.emitSupportedSubtitles(callback: (SubtitleFile) -> Unit) {
        (0 until length()).forEach { index ->
            val subtitle = optJSONObject(index) ?: return@forEach
            val language = subtitle.optString("language").lowercase()
            val title = subtitle.optString("title")
            val file = subtitle.optString("file").takeIf { it.startsWith("http") } ?: return@forEach
            val supported = language in setOf("fr", "en") || title.contains("french", true) || title.contains("english", true)
            if (supported) callback(SubtitleFile("${if (language == "fr") "FR" else "EN"} — $title", file))
        }
    }

    /** Décode la chaîne JavaScript externe, sans altérer le JSON interne. */
    private fun decodeJavascriptString(value: String): String =
        JSONObject("""{"payload":"${value.replace("\"", "\\\"")}"}""").optString("payload")

    private fun decodeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl/${url.trimStart('/')}"
}
