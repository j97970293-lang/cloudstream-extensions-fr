version = 1

plugins {
    id("com.android.library")
    kotlin("android")
}

cloudstream {
    language = "fr"
    description = "Providers Nuvio français portés vers CloudStream avec activation individuelle."
    authors = listOf("j97970293-lang")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://raw.githubusercontent.com/j97970293-lang/cloudstream-extensions-fr/master/docs/nuviofrench.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
