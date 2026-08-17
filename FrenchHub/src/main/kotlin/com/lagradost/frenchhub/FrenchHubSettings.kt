package com.lagradost.frenchhub

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

internal object FrenchHubSettings {
    private const val PREFIX = "frenchhub.provider."

    data class ProviderSpec(val key: String, val label: String)

    // Wiflix, Frembed, FS Mirror, JourFilm, DoTriv, French Anime et Anime Sama
    // ont été retirés : leurs domaines sont expirés, injoignables ou renvoyés
    // des pages de parking (diagnostic du 17/08/2026). Leurs sources ne pouvaient
    // plus produire aucun lecteur et masquaient les sources saines. Ils restent
    // dans le code (dossiers com.lagradost.frenchhub.*) pour une réactivation
    // facile si leurs domaines reviennent en ligne.
    val providers = listOf(
        ProviderSpec("frenchstream", "French-Stream"),
        ProviderSpec("movix", "Movix"),
        ProviderSpec("frenchmanga", "French-Manga"),
        ProviderSpec("moviebox", "MovieBox (VF)"),
    )

    fun isEnabled(key: String): Boolean = getKey<Boolean>(PREFIX + key) ?: true

    fun show(context: Context, onSaved: () -> Unit) {
        val checked = providers.map { isEnabled(it.key) }.toBooleanArray()
        AlertDialog.Builder(context)
            .setTitle("FrenchHub — Providers")
            .setMultiChoiceItems(providers.map { it.label }.toTypedArray(), checked) { _, index, value ->
                checked[index] = value
            }
            .setPositiveButton("Enregistrer") { _, _ ->
                providers.forEachIndexed { index, provider ->
                    setKey(PREFIX + provider.key, checked[index])
                }
                onSaved()
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Tout activer") { _, _ ->
                providers.forEach { setKey(PREFIX + it.key, true) }
                onSaved()
            }
            .show()
    }
}
