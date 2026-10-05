plugins {
    id("platen.android.application")
    id("platen.android.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.zsozso01.platen"

    defaultConfig {
        applicationId = "io.github.zsozso01.platen"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Reproducible builds / F-Droid: no dependency-info blob that only Google can read.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.engine)
    implementation(projects.backend.raster)
    implementation(projects.route.ipp)
    implementation(projects.transport.network)
    implementation(projects.platform.render)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
