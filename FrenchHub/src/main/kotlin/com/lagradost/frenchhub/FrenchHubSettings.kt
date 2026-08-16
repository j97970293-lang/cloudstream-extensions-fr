package com.lagradost.frenchhub

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

internal object FrenchHubSettings {
    private const val PREFIX = "frenchhub.provider."

    data class ProviderSpec(val key: String, val label: String)

    val providers = listOf(
        ProviderSpec("frenchstream", "French-Stream"),
        ProviderSpec("movix", "Movix"),
        ProviderSpec("fstv", "FSTV"),
        ProviderSpec("frenchmanga", "French-Manga"),
        ProviderSpec("wiflix", "Wiflix"),
        ProviderSpec("frembed", "Frembed"),
        ProviderSpec("frenchanime", "French Anime"),
        ProviderSpec("fsmirror", "FS Mirror"),
        ProviderSpec("jourfilm", "JourFilm"),
        ProviderSpec("dotriv", "DoTriv")
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
