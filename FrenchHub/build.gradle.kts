version = 1

plugins {
    id("com.android.library")
    kotlin("android")
}

cloudstream {
    language = "fr"
    description = "Catalogue français fédéré avec lecteurs multi-providers configurables."
    authors = listOf("j97970293-lang")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon", "Documentary", "Live")
    iconUrl = "https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/docs/frenchhub.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
