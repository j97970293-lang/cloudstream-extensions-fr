package com.lagradost.frenchhub

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.frenchhub.movix.MovixHostEmbedExtractor
import com.lagradost.frenchhub.movix.MovixUqloadExtractor
import com.lagradost.frenchhub.movix.MovixVidzyExtractor

@CloudstreamPlugin
class FrenchHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FrenchHubCatalog())
        registerExtractorAPI(MovixVidzyExtractor())
        registerExtractorAPI(MovixUqloadExtractor())
        MovixHostEmbedExtractor.hosts.forEach { registerExtractorAPI(it) }

        openSettings = { settingsContext: Context ->
            FrenchHubSettings.show(settingsContext) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
