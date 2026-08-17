package com.lagradost.frenchhub

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

internal object FrenchHubSettings {
    private const val PREFIX = "frenchhub.provider."
    private const val DOMAIN_PREFIX = PREFIX + "domain."

    data class ProviderSpec(val key: String, val label: String)
    data class DomainSpec(val key: String, val label: String, val defaultUrl: String)

    val providers = listOf(
        ProviderSpec("frenchstream", "French-Stream"),
        ProviderSpec("movix", "Movix"),
        ProviderSpec("frenchmanga", "French-Manga"),
        ProviderSpec("moviebox", "MovieBox"),
        ProviderSpec("animesama", "Anime-Sama (VF/VOSTFR)"),
    )

    /** Domaines éditables depuis les réglages de l'extension. */
    val domains = listOf(
        DomainSpec("frenchstream", "French-Stream", "https://french-stream.one"),
        DomainSpec("movix", "Movix API", "https://api.movix.fun"),
        DomainSpec("animesama", "Anime-Sama", "https://anime-sama.to"),
        DomainSpec("moviebox", "MovieBox API", "https://h5-api.aoneroom.com"),
    )

    fun isEnabled(key: String): Boolean = getKey<Boolean>(PREFIX + key) ?: true

    fun setEnabled(key: String, enabled: Boolean) = setKey(PREFIX + key, enabled)

    fun domain(key: String): String =
        getKey<String>(DOMAIN_PREFIX + key)?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: domains.firstOrNull { it.key == key }?.defaultUrl.orEmpty()

    private fun setDomain(key: String, value: String) {
        val normalized = value.trim().trimEnd('/')
        if (normalized.isBlank()) {
            setKey(DOMAIN_PREFIX + key, null)
        } else {
            setKey(DOMAIN_PREFIX + key, normalized)
        }
    }

    fun show(context: Context, onSaved: () -> Unit) {
        val checked = providers.map { isEnabled(it.key) }.toBooleanArray()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val domainInputs = linkedMapOf<String, EditText>()
        domains.forEach { spec ->
            val input = EditText(context).apply {
                hint = spec.defaultUrl
                setText(domain(spec.key))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            root.addView(input)
            domainInputs[spec.key] = input
        }
        val scroll = ScrollView(context).apply { addView(root) }
        AlertDialogBuilder(context)
            .setTitle("FrenchHub — Providers et domaines")
            .setMultiChoiceItems(providers.map { it.label }.toTypedArray(), checked) { _, index, value ->
                checked[index] = value
            }
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                providers.forEachIndexed { index, provider ->
                    setEnabled(provider.key, checked[index])
                }
                domainInputs.forEach { (key, input) -> setDomain(key, input.text.toString()) }
                onSaved()
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Réinitialiser domaines") { _, _ ->
                domains.forEach { setDomain(it.key, it.defaultUrl) }
                onSaved()
            }
            .show()
    }

    private fun AlertDialogBuilder(context: Context): android.app.AlertDialog.Builder =
        android.app.AlertDialog.Builder(context)
}
