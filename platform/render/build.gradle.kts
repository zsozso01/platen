plugins {
    id("platen.android.library")
}

android {
    namespace = "io.github.zsozso01.platen.platform.render"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(projects.core.engine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(projects.backend.raster)
    androidTestImplementation(projects.route.ipp)
    androidTestImplementation(projects.testing.fakePrinter)
}
