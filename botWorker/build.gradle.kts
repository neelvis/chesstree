plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets.jsMain.dependencies {
        implementation(projects.gameDomain)
        implementation(projects.botWire)
    }
}
