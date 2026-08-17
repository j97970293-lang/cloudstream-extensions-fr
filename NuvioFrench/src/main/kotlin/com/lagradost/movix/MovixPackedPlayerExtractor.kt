package com.lagradost.nuviofrench.movix

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

/** Extrait les pistes sidecar déclarées par les lecteurs Movix et embeds associés. */
internal object MovixSubtitleParser {
    private val subtitleUrl = Regex(
        """https?://[^\s"'\\]+\.(?:vtt|srt|ass|ttml)(?:\?[^\s"'\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    fun emit(html: String, baseUrl: String, callback: (SubtitleFile) -> Unit) {
        val emitted = mutableSetOf<String>()
        val document = Jsoup.parse(html, baseUrl)
        document.select("track[src]").forEach { track ->
            val url = track.attr("abs:src").takeIf(String::isNotBlank) ?: return@forEach
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Sous-titres" }
            if (emitted.add(url)) callback(SubtitleFile(label, url))
        }
        subtitleUrl.findAll(html).map { it.value }.forEach { url ->
            if (emitted.add(url)) callback(SubtitleFile("Sous-titres", url))
        }
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

        MovixSubtitleParser.emit(response.text, url, subtitleCallback)

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

        // Récupération de la page embed avec en-têtes de navigateur complets : les
        // hôtes Cloudflare rejettent les requêtes minimales, ce qui faisait
        // disparaître une partie des lecteurs.
        val response = runCatching {
            app.get(
                url,
                referer = referer ?: refererHost,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "fr,fr-FR;q=0.8,en-US;q=0.5",
                ),
                timeout = 20L,
            )
        }.getOrNull() ?: run {
            // Même en cas d'échec, un lien de secours reste visible dans la liste
            // des lecteurs pour que l'utilisateur sache que ce lecteur existe.
            emitEmbedFallback(url, callback)
            return
        }

        val pageText = response.text
        MovixSubtitleParser.emit(pageText, url, subtitleCallback)
        val embedLinks = collectLinks(pageText)

        if (embedLinks.isEmpty()) {
            // Le flux est peut-être intégré directement dans la page embed
            // (player HTML/JS embarqué) : on émet la page comme lien de secours.
            emitEmbedFallback(url, callback)
            return
        }

        var resolved = false
        for (nested in embedLinks) {
            if (nested == url || nested == "$url/") continue
            val nestedText = runCatching {
                app.get(nested, referer = url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 20L).text
            }.getOrNull() ?: continue
            MovixSubtitleParser.emit(nestedText, nested, subtitleCallback)
            collectLinks(nestedText).forEach { streamUrl ->
                resolved = true
                emitLink(streamUrl, url, refererHost, callback)
            }
        }

        embedLinks.forEach { streamUrl ->
            resolved = true
            emitLink(streamUrl, url, refererHost, callback)
        }

        if (!resolved) {
            emitEmbedFallback(url, callback)
        }
    }

    /**
     * Lien de secours quand la page embed ne peut être résolue (blocage
     * Cloudflare ou player intégré) : l'utilisateur voit au moins le lecteur
     * dans la liste et l'application tente de le lire directement.
     */
    private fun emitEmbedFallback(url: String, callback: (ExtractorLink) -> Unit) {
        callback(
            ExtractorLink(
                source = "${name} (embed)",
                name = "${name} (embed)",
                url = url,
                referer = "https://movix.show/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO,
                headers = mapOf(
                    "Referer" to "https://movix.show/",
                    "Origin" to "https://movix.show",
                    "User-Agent" to USER_AGENT,
                ),
            ),
        )
    }

    private fun collectLinks(html: String): List<String> {
        val iframeRegex = Regex(
            """https?://[^"'}\\\s]+\.(?:m3u8|mp4|webm)(?:\?[^"'}\\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        val found = iframeRegex.findAll(html).map { it.value }.toMutableSet()

        val document = Jsoup.parse(html)
        document.select("iframe[src], source[src], script[src], video source[src]").forEach { element ->
            val src = element.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            if (src.startsWith("http") && src.contains(".m3u8", ignoreCase = true)) {
                found.add(src)
            }
            element.attr("data-src").takeIf {
                it.startsWith("http") && it.contains(".m3u8", ignoreCase = true)
            }?.let { found.add(it) }
        }

        document.select("script").forEach { script ->
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
        val quality = qualityFromUrl(streamUrl)
        callback(
            newExtractorLink(name, name, streamUrl, type) {
                this.referer = pageUrl
                this.headers = mapOf(
                    "Referer" to pageUrl,
                    "Origin" to refererHost,
                    "User-Agent" to USER_AGENT,
                )
                this.quality = quality
            },
        )
    }

    /** Détecte la qualité depuis le nom du fichier ou les paramètres de l'URL du flux. */
    private fun qualityFromUrl(url: String): Int {
        val upper = url.uppercase()
        return when {
            "2160" in upper || "/4K" in upper -> Qualities.P2160.value
            "1080" in upper || "/FHD" in upper -> Qualities.P1080.value
            "720" in upper || "/HD" in upper -> Qualities.P720.value
            "480" in upper -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}
