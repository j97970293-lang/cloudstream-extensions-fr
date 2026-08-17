package com.lagradost.nuviofrench

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.nuviofrench.movix.MovixHostEmbedExtractor
import com.lagradost.nuviofrench.movix.MovixUqloadExtractor
import com.lagradost.nuviofrench.movix.MovixVidzyExtractor

@CloudstreamPlugin
class NuvioFrenchPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NuvioFrenchCatalog())
        registerExtractorAPI(MovixVidzyExtractor())
        registerExtractorAPI(MovixUqloadExtractor())
        MovixHostEmbedExtractor.hosts.forEach { registerExtractorAPI(it) }

        openSettings = { settingsContext: Context ->
            NuvioFrenchSettings.show(settingsContext) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
