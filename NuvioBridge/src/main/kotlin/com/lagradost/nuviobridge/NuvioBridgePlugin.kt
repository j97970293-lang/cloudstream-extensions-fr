package com.lagradost.nuviobridge

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NuvioBridgePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NuvioBridgeCatalog())
        openSettings = { settingsContext: Context ->
            NuvioBridgeSettings.show(settingsContext) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
