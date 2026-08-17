package com.lagradost.nuviobridge

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal data class NuvioBridgeTmdbCard(
    val id: Int,
    val type: String,
    val title: String,
    val posterPath: String? = null,
    val year: Int? = null,
    val score: Double? = null,
)

internal object NuvioBridgeTmdb {
    private const val API_URL = "https://api.themoviedb.org/3"
    private const val API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val IMAGE_URL = "https://image.tmdb.org/t/p"

    suspend fun catalog(path: String, page: Int): List<NuvioBridgeTmdbCard> {
        val json = get(path, mapOf("page" to page.toString())) ?: return emptyList()
        val hint = path.substringBefore('/').takeIf { it == "movie" || it == "tv" }
        return json.optJSONArray("results")?.toCards(hint).orEmpty()
    }

    suspend fun search(query: String): List<NuvioBridgeTmdbCard> {
        if (query.isBlank()) return emptyList()
        val json = get(
            "search/multi",
            mapOf(
                "query" to query,
                "include_adult" to "false",
                "page" to "1"
            )
        ) ?: return emptyList()
        return json.optJSONArray("results")?.toCards(null).orEmpty()
            .filter { it.type == "movie" || it.type == "tv" }
    }

    suspend fun details(type: String, id: Int): JSONObject? {
        return get(
            "$type/$id",
            mapOf(
                "append_to_response" to "external_ids,credits,videos,images,content_ratings,recommendations",
                "include_image_language" to "fr,en,null"
            )
        )
    }

    suspend fun season(id: Int, season: Int): JSONObject? {
        return get("tv/$id/season/$season", emptyMap())
    }

    fun image(path: String?, size: String = "w780"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$IMAGE_URL/$size$path"
    }

    fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()

    fun externalId(details: JSONObject, key: String): String? {
        val nested = details.optJSONObject("external_ids")?.optString(key).orEmpty()
        val direct = details.optString(key)
        return (nested.ifBlank { direct }).takeIf { it.isNotBlank() }
    }

    private suspend fun get(path: String, extra: Map<String, String>): JSONObject? {
        val params = linkedMapOf(
            "api_key" to API_KEY,
            "language" to "fr-FR"
        )
        params.putAll(extra)
        val query = params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        return runCatching {
            JSONObject(app.get("$API_URL/${path.trimStart('/')}?$query", timeout = 15L).text)
        }.getOrNull()
    }

    private fun JSONArray.toCards(typeHint: String?): List<NuvioBridgeTmdbCard> {
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val type = item.optString("media_type").ifBlank { typeHint.orEmpty() }
            if (type != "movie" && type != "tv") return@mapNotNull null
            val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title = if (type == "movie") {
                item.optString("title").ifBlank { item.optString("original_title") }
            } else {
                item.optString("name").ifBlank { item.optString("original_name") }
            }
            if (title.isBlank()) return@mapNotNull null
            val date = if (type == "movie") item.optString("release_date") else item.optString("first_air_date")
            NuvioBridgeTmdbCard(
                id = id,
                type = type,
                title = title,
                posterPath = item.optString("poster_path").takeIf { it.isNotBlank() }
                    ?: item.optString("poster").takeIf { it.isNotBlank() },
                year = year(date).takeIf { it != 0 },
                score = item.optDouble("vote_average").takeIf { it > 0.0 }
            )
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
