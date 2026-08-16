package com.lagradost.frenchhub.movix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

internal object MovixPackedPlayerParser {
    private val mediaRegex = Regex(
        """https?://[^\s"'\\]+\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun extractMediaUrls(html: String): List<String> {
        return Jsoup.parse(html)
            .select("script")
            .mapNotNull { script ->
                val packed = script.data().ifBlank { script.html() }
                JsUnpacker(packed).takeIf { it.detect() }?.unpack()
            }
            .flatMap { unpacked -> mediaRegex.findAll(unpacked).map { it.value }.toList() }
            .distinct()
    }
}

internal object MovixExtractorPipeline {
    suspend fun <T> load(
        links: List<String>,
        loader: suspend (String, (T) -> Unit) -> Boolean,
        callback: (T) -> Unit
    ): Boolean {
        var emitted = false

        links.distinct().forEach { link ->
            runCatching {
                loader(link) { item ->
                    emitted = true
                    callback(item)
                }
            }
        }

        return emitted
    }
}

internal abstract class MovixPackedPlayerExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer ?: "https://movix.show/",
            headers = mapOf("User-Agent" to USER_AGENT)
        )
        val streamHeaders = mapOf(
            "Referer" to url,
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        MovixPackedPlayerParser.extractMediaUrls(response.text).forEach { streamUrl ->
            val type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, name, streamUrl, type) {
                    this.referer = url
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

internal class MovixVidzyExtractor : MovixPackedPlayerExtractor() {
    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.cc"
}

internal class MovixUqloadExtractor : MovixPackedPlayerExtractor() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.is"
}

/**
 * Attrape-tout pour les liens embed retournés par l'API Movix mais non couverts
 * par un ExtractorApi dédié (kakaflix, bysebuho, embedseek, neocine, vidara,
 * serix.upns, luluvdo, morencius, firestream, jessicayeahcatch, playmogo,
 * vidsonic, fsvid, netu, upns, etc.). Sans lui, loadExtractor ignore ces liens
 * silencieusement et l'utilisateur ne voit qu'un seul lecteur.
 *
 * CloudStream fait correspondre un lien à un extractor via le préfixe de
 * [mainUrl], une seule instance couvrant plusieurs domaines n'est donc pas
 * possible : ce sont les instances de [MovixHostEmbedExtractor] ci-dessous
 * qui couvrent chaque hôte d'embed connu.
 *
 * La page embed est récupérée, le JavaScript est déballé (packed/js_eval) et les
 * URLs m3u8/mp4 y sont extraites. Les iframes imbriquées sont suivies une fois.
 */
internal class MovixHostEmbedExtractor(
    extractorName: String,
    override val mainUrl: String,
) : ExtractorApi() {
    override val name = extractorName
    override val requiresReferer = true

    /** Liste tous les hôtes d'embed retournés par les APIs Movix/FStream/Wiflix. */
    companion object {
        val hosts = listOf(
            "kakaflix.lol" to "Kakaflix",
            "bysebuho.com" to "Bysebuho",
            "embedseek.com" to "Embedseek",
            "neocine.embedseek.com" to "Neocine",
            "vidara.to" to "Vidara",
            "serix.upns.live" to "Serix",
            "luluvdo.com" to "Luluvdo",
            "morencius.com" to "Morencius",
            "firestream.to" to "Firestream",
            "jessicayeahcatch.com" to "Jessicayeahcatch",
            "playmogo.com" to "Playmogo",
            "vidsonic.net" to "Vidsonic",
            "fsvid.lol" to "FSVid",
            "netu.tv" to "Netu",
            "upns.live" to "Upns",
            "1.multiup.us" to "Multiup",
        ).map { (host, label) -> MovixHostEmbedExtractor(label, "https://$host") }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val refererHost = url.substringBefore("/")
        val response = app.get(
            url,
            referer = referer ?: refererHost,
            headers = mapOf("User-Agent" to USER_AGENT),
            timeout = 20L,
        )

        val embedLinks = collectLinks(response.text)

        for (nested in embedLinks) {
            if (nested == url || nested == "$url/") continue
            val nestedText = runCatching {
                app.get(nested, referer = url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L).text
            }.getOrNull() ?: continue
            collectLinks(nestedText).forEach { streamUrl ->
                emitLink(streamUrl, url, refererHost, callback)
            }
        }

        embedLinks.forEach { streamUrl ->
            emitLink(streamUrl, url, refererHost, callback)
        }
    }

    private fun collectLinks(html: String): List<String> {
        val iframeRegex = Regex(
            """https?://[^"'}\\\s]+\.(?:m3u8|mp4|webm)(?:\?[^"'}\\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        val found = iframeRegex.findAll(html).map { it.value }.toMutableSet()

        Jsoup.parse(html).select("iframe[src], source[src], script[src]").forEach { element ->
            val src = element.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            if (src.startsWith("http") && src.contains(".m3u8", ignoreCase = true)) {
                found.add(src)
            }
        }

        Jsoup.parse(html).select("script").forEach { script ->
            val packed = script.data().ifBlank { script.html() }
            JsUnpacker(packed).takeIf { it.detect() }?.unpack()?.let { unpacked ->
                iframeRegex.findAll(unpacked).forEach { found.add(it.value) }
            }
        }

        return found.filter { it.startsWith("http") }.distinct()
    }

    private suspend fun emitLink(
        streamUrl: String,
        pageUrl: String,
        refererHost: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(name, name, streamUrl, type) {
                this.referer = pageUrl
                this.headers = mapOf(
                    "Referer" to pageUrl,
                    "Origin" to refererHost,
                    "User-Agent" to USER_AGENT,
                )
                this.quality = Qualities.Unknown.value
            },
        )
    }
}
