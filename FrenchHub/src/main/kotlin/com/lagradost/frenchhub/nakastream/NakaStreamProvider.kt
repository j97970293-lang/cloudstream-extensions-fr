package com.lagradost.frenchhub.nakastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Port Kotlin du provider Luna NakaStream. Les flux sont fournis par l'API du
 * site avec leurs en-têtes d'origine et sont généralement des manifestes HLS.
 */
class NakaStreamProvider : MainAPI() {
    override var mainUrl = DEFAULT_URL
    override var name = "NakaStream"
    override var lang = "fr"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl get() = "${mainUrl.trimEnd('/')}/api/v1"
    private fun headers(referer: String = "${mainUrl.trimEnd('/')}/") = mapOf(
        "Accept" to "application/json",
        "Origin" to mainUrl.trimEnd('/'),
        "Referer" to referer,
        "User-Agent" to USER_AGENT,
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val root = runCatching {
            JSONObject(app.get("$apiUrl/browse/catalog?page=1&limit=30&sort=recent&search=$encoded", headers = headers()).text)
        }.getOrNull() ?: return emptyList()
        return root.optJSONArray("data").toObjects().mapNotNull { item ->
            val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title = item.optString("title").ifBlank { item.optString("originalTitle") }
            if (title.isBlank()) return@mapNotNull null
            val type = item.optString("mediaType").lowercase()
            val url = "$mainUrl/$type/$id-${slugify(title)}"
            val poster = item.optString("posterPath").takeIf { it.isNotBlank() }?.let { path ->
                if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w500$path"
            }
            if (type == "movie") {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    year = item.optString("releaseDate").take(4).toIntOrNull()
                    this.id = item.optInt("tmdbId").takeIf { value -> value > 0 } ?: id
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    year = item.optString("releaseDate").take(4).toIntOrNull()
                    this.id = item.optInt("tmdbId").takeIf { value -> value > 0 } ?: id
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parsed = parseUrl(url) ?: return null
        val item = catalogItem(parsed.id) ?: return null
        val title = item.optString("title").ifBlank { item.optString("originalTitle") }
        if (title.isBlank()) return null
        val poster = item.optString("posterPath").takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w500$path"
        }
        val background = item.optString("backdropPath").takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/original$path"
        }
        val year = item.optString("releaseDate").take(4).toIntOrNull()
        val plot = item.optString("overview").takeIf { it.isNotBlank() }
        if (parsed.type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, NakaPayload(parsed.id, "movie", fullId = parsed.fullId).toString()) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                duration = item.optInt("runtime").takeIf { it > 0 }
            }
        }
        val episodes = loadEpisodes(parsed.id, parsed.fullId, item.optInt("numberOfSeasons").coerceAtLeast(1))
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = background
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = NakaPayload.from(data) ?: return false
        val request = buildString {
            append("$apiUrl/streaming/sources/${payload.id}?type=${payload.type}")
            if (payload.type == "tv") append("&season=${payload.season}&episode=${payload.episode}")
        }
        val referer = "$mainUrl/${payload.type}/${payload.fullId}"
        val root = runCatching { JSONObject(app.get(request, headers = headers(referer)).text) }.getOrNull() ?: return false
        val emitted = linkedSetOf<String>()
        var hasLink = false
        root.optJSONArray("sources").toObjects().forEachIndexed { index, source ->
            val rawUrl = source.optString("url").takeIf { it.startsWith("http") }
                ?: source.optString("url").takeIf { it.startsWith("/") }?.let { "$mainUrl$it" }
                ?: return@forEachIndexed
            if (!emitted.add(rawUrl)) return@forEachIndexed
            val qualityText = source.optString("maxQuality").ifBlank { source.optString("quality") }
            val label = buildString {
                append("NakaStream")
                source.optString("type").takeIf { it.isNotBlank() && !it.equals("encoded", true) }?.let { append(" — $it") }
                qualityText.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                if (index > 0) append(" #${index + 1}")
            }
            val isHls = rawUrl.contains(".m3u8", ignoreCase = true) || source.optString("format").equals("hls", true)
            callback(newExtractorLink(name, label, rawUrl, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                headers = headers(referer)
                quality = qualityFrom(qualityText)
            })
            source.optJSONArray("subtitles").toObjects().forEach { subtitle ->
                val subtitleUrl = subtitle.optString("url").let { value ->
                    if (value.startsWith("/")) "$mainUrl$value" else value
                }
                if (subtitleUrl.startsWith("http")) {
                    val language = subtitle.optString("lang").ifBlank { subtitle.optString("label") }.ifBlank { "Sous-titres" }
                    subtitleCallback(SubtitleFile("NakaStream — $language", subtitleUrl))
                }
            }
            hasLink = true
        }
        return hasLink
    }

    private suspend fun catalogItem(id: Int): JSONObject? {
        val root = runCatching { JSONObject(app.get("$apiUrl/browse/catalog?page=1&limit=3&search=&id=$id", headers = headers()).text) }.getOrNull()
            ?: return null
        return root.optJSONArray("data").toObjects().firstOrNull { it.optInt("id") == id }
            ?: root.optJSONArray("data").toObjects().firstOrNull()
    }

    private suspend fun loadEpisodes(id: Int, fullId: String, seasons: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (season in 1..seasons.coerceAtMost(40)) {
            val root = runCatching { JSONObject(app.get("$apiUrl/browse/$id/season/$season", headers = headers()).text) }.getOrNull()
                ?: continue
            root.optJSONArray("episodes").toObjects().forEach { episode ->
                val number = episode.optInt("episode_number").takeIf { it > 0 } ?: return@forEach
                episodes += newEpisode(NakaPayload(id, "tv", season, number, fullId).toString()) {
                    name = episode.optString("name").ifBlank { "Épisode $number" }
                    this.season = season
                    this.episode = number
                    description = episode.optString("overview").takeIf { it.isNotBlank() }
                    posterUrl = episode.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                }
            }
        }
        return episodes.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })
    }

    private fun parseUrl(url: String): ParsedUrl? {
        val match = Regex("""/(movie|tv)/(\d+(?:-[^/?#]+)?)""").find(url) ?: return null
        val fullId = match.groupValues[2]
        return ParsedUrl(match.groupValues[1], fullId.substringBefore('-').toIntOrNull() ?: return null, fullId)
    }

    private fun slugify(value: String): String = value.lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun qualityFrom(value: String): Int = when {
        "2160" in value || "4k" in value.lowercase() -> Qualities.P2160.value
        "1080" in value -> Qualities.P1080.value
        "720" in value -> Qualities.P720.value
        "480" in value -> Qualities.P480.value
        "360" in value -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun JSONArray?.toObjects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private data class ParsedUrl(val type: String, val id: Int, val fullId: String)
    private data class NakaPayload(
        val id: Int,
        val type: String,
        val season: Int = 1,
        val episode: Int = 1,
        val fullId: String = id.toString(),
    ) {
        override fun toString() = "$id::$type::$season::$episode::$fullId"
        companion object {
            fun from(raw: String): NakaPayload? {
                val parts = raw.split("::")
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val type = parts.getOrNull(1)?.takeIf { it == "movie" || it == "tv" } ?: return null
                return NakaPayload(
                    id = id,
                    type = type,
                    season = parts.getOrNull(2)?.toIntOrNull() ?: 1,
                    episode = parts.getOrNull(3)?.toIntOrNull() ?: 1,
                    fullId = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: id.toString(),
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_URL = "https://nakastream.tv"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
