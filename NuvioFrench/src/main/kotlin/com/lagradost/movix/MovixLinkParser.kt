package com.lagradost.nuviofrench.movix

import org.json.JSONArray
import org.json.JSONObject

internal object MovixLinkParser {

    /** Lien avec sa langue d'origine (clé du groupe de lecteurs de l'API Movix). */
    data class LabeledLink(val url: String, val language: String)

    private fun languageForGroup(group: String): String = when (group) {
        "VFQ", "VFF", "VF", "vf" -> "VF"
        "VOSTFR", "vostfr" -> "VOSTFR"
        "vo" -> "VO"
        else -> "VO"
    }
    private val preferredGroups = listOf(
        "VFQ",
        "VFF",
        "VF",
        "VOSTFR",
        "VOENG",
        "Default",
        "vf",
        "vostfr"
    )

    fun fstreamMovie(root: JSONObject): List<String> {
        return linksFromGroups(
            root.optJSONObject("players"),
            listOf("VFQ", "VFF", "VOSTFR", "Default")
        )
    }

    fun fstreamTv(root: JSONObject, episode: Int): List<String> {
        val languages = root.optJSONObject("episodes")
            ?.optJSONObject(episode.toString())
            ?.optJSONObject("languages")
        return linksFromGroups(languages)
    }

    /**
     * Variante étiquetée : chaque lien est associé à la langue de son groupe
     * de lecteurs (VFQ/VFF=VF, VOSTFR=VOSTFR, Default/autre=VO). Utilisé par
     * MovixProvider pour afficher [VF] ou [VOSTFR] dans le nom du lecteur.
     */
    fun fstreamMovieLabeled(root: JSONObject): List<LabeledLink> {
        return labeledFromGroups(
            root.optJSONObject("players"),
            listOf("VFQ", "VFF", "VOSTFR", "Default")
        )
    }

    fun fstreamTvLabeled(root: JSONObject, episode: Int): List<LabeledLink> {
        val languages = root.optJSONObject("episodes")
            ?.optJSONObject(episode.toString())
            ?.optJSONObject("languages")
        return labeledFromGroups(languages)
    }

    private fun labeledFromGroups(
        groups: JSONObject?,
        requestedGroups: List<String>? = null,
    ): List<LabeledLink> {
        if (groups == null) return emptyList()

        val available = groups.keys().asSequence().toList()
        val groupNames = requestedGroups ?: (preferredGroups.filter { it in available } + available.filterNot { it in preferredGroups })

        return groupNames.map { group ->
            linksFromArray(groups.optJSONArray(group)).map { LabeledLink(it, languageForGroup(group)) }
        }.flatten().distinctBy { it.url }
    }

    fun customMovie(root: JSONObject): List<String> {
        return linksFromArray(root.optJSONObject("data")?.optJSONArray("links"))
    }

    fun frembed(root: JSONObject): List<String> {
        return linksFromArray(root.optJSONObject("result")?.optJSONArray("items"))
    }

    fun imdb(root: JSONObject): List<String> {
        return linksFromArray(root.optJSONArray("player_links")) +
            linksFromArray(root.optJSONArray("players")) +
            listOfNotNull(root.optString("iframe_src").takeIf { it.isNotBlank() })
    }

    fun j1f(root: JSONObject): List<String> {
        return linksFromGroups(root.optJSONObject("players"), listOf("vf", "vostfr", "vo"))
    }

    fun wiflixMovie(root: JSONObject): List<String> {
        return linksFromGroups(root.optJSONObject("players"), listOf("vf", "vostfr"))
    }

    fun wiflixTv(root: JSONObject, episode: Int): List<String> {
        val groups = root.optJSONObject("episodes")?.optJSONObject(episode.toString())
        return linksFromGroups(groups, listOf("vf", "vostfr"))
    }

    private fun linksFromGroups(
        groups: JSONObject?,
        requestedGroups: List<String>? = null
    ): List<String> {
        if (groups == null) return emptyList()

        val available = groups.keys().asSequence().toList()
        val groupNames = requestedGroups ?: (
            preferredGroups.filter { it in available } + available.filterNot { it in preferredGroups }
        )

        return groupNames
            .flatMap { linksFromArray(groups.optJSONArray(it)) }
            .distinct()
    }

    private fun linksFromArray(items: JSONArray?): List<String> {
        if (items == null) return emptyList()

        return (0 until items.length())
            .mapNotNull { index ->
                when (val item = items.opt(index)) {
                    is String -> item
                    is JSONObject -> item.optString("url").ifBlank {
                        item.optString("link").ifBlank { item.optString("decoded_url") }
                    }
                    else -> null
                }?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            .distinct()
    }
}
