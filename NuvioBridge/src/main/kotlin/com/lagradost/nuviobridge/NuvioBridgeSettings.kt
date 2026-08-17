package com.lagradost.nuviobridge

import android.content.Context
import android.text.InputType
import android.widget.EditText
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

/** Réglages du client : les secrets restent exclusivement dans l’application compagnon. */
internal object NuvioBridgeSettings {
    private const val BRIDGE_URL_KEY = "nuviobridge.url"
    const val DEFAULT_BRIDGE_URL = "http://127.0.0.1:8123"

    fun bridgeUrl(): String {
        val candidate = getKey<String>(BRIDGE_URL_KEY)?.trim()?.trimEnd('/')
        return candidate?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: DEFAULT_BRIDGE_URL
    }

    fun show(context: Context, onSaved: () -> Unit) {
        val input = EditText(context).apply {
            hint = DEFAULT_BRIDGE_URL
            setText(bridgeUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("NuvioBridge — serveur compagnon")
            .setMessage("Installez et ouvrez l’application Nuvio CloudStream Bridge sur le même appareil. Les dépôts, providers et clés API se configurent dans cette application ; le client CloudStream ne conserve aucun secret.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val value = input.text.toString().trim().trimEnd('/')
                setKey(BRIDGE_URL_KEY, value.takeIf { it.startsWith("http://") || it.startsWith("https://") })
                onSaved()
            }
            .setNeutralButton("Réinitialiser") { _, _ ->
                setKey(BRIDGE_URL_KEY, null)
                onSaved()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
