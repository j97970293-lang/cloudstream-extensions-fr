package com.lagradost.frenchhub

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Détection automatique des domaines des providers : au premier chargement,
 * plusieurs miroirs connus de chaque site sont testés en parallèle (simple GET
 * sur la page racine). Le premier qui répond en HTTP 2xx est mémorisé et
 * utilisé tant qu'il reste joignable ; si tous les miroirs sont muets, la
 * valeur manuelle du menu de l'extension est conservée (aucune dégradation).
 *
 * Le mode est contrôlable depuis les réglages (menu de l'extension) avec la
 * bascule « Détection automatique » : désactivée, l'extension utilise
 * uniquement les domaines configurés manuellement.
 */
internal object FrenchHubDomainDetector {

    private const val DETECT_PREFIX = "frenchhub.auto."

    data class MirrorSpec(val key: String, val mirrors: List<String>)

    /** Liste des miroirs connus de chaque provider, testés dans l'ordre. */
    val specs = listOf(
        MirrorSpec("frenchstream", listOf(
            "https://french-stream.one",
            "https://french-stream.pink",
            "https://fstream.info",
            "https://french-stream.me",
        )),
        MirrorSpec("movix", listOf(
            "https://api.movix.fun",
            "https://api.movix.cc",
        )),
        MirrorSpec("animesama", listOf(
            "https://anime-sama.to",
            "https://anime-sama.tv",
            "https://anime-sama.fr",
        )),
        MirrorSpec("moviebox", listOf(
            "https://h5-api.aoneroom.com",
        )),
    )

    private fun enabled(): Boolean = getKey<Boolean>(DETECT_PREFIX + "enabled") ?: true

    /** Cache du dernier miroir détecté qui répondait. */
    private fun cached(key: String): String? = getKey<String>(DETECT_PREFIX + key, null)
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun setCached(key: String, url: String) = setKey(DETECT_PREFIX + key, url)

    fun setEnabled(enabled: Boolean) = setKey(DETECT_PREFIX + "enabled", enabled)

    fun isEnabled(): Boolean = enabled()

    /**
     * Résout le domaine à utiliser pour un provider :
     * 1. Si la détection automatique est activée, le cache est vérifié ; s'il
     *    est absent ou périmé, les miroirs sont testés (parallele, 5 s chacun).
     * 2. Sinon, le domaine manuel du menu de l'extension est renvoyé tel quel.
     */
    suspend fun resolve(key: String, manualDomain: String): String {
        if (!enabled()) return manualDomain
        cached(key)?.let { return it }
        val detected = detect(key)
        if (detected != null) {
            setCached(key, detected)
            return detected
        }
        // Aucun miroir ne répond : le manuel reste la valeur de repli et le
        // cache est vidé pour retenter la détection au prochain chargement.
        setKey(DETECT_PREFIX + key, null as String?)
        return manualDomain
    }

    private suspend fun detect(key: String): String? {
        val spec = specs.firstOrNull { it.key == key } ?: return null
        return coroutineScope {
            spec.mirrors.map { mirror ->
                async {
                    val ok = withTimeoutOrNull(5_000L) {
                        runCatching {
                            val response = app.get(mirror, timeout = 5L)
                            response.isSuccessful && response.code in 200..399
                        }.getOrDefault(false)
                    } ?: false
                    mirror.takeIf { ok }
                }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    /** Vide le cache des détections pour forcer une nouvelle vérification. */
    fun resetCache() {
        specs.forEach { setKey(DETECT_PREFIX + it.key, null as String?) }
    }
}
