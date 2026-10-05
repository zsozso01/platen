plugins {
    id("platen.android.library")
}

android {
    namespace = "io.github.zsozso01.platen.platform.discovery"
}

dependencies {
    api(projects.transport.network)
    implementation(libs.kotlinx.coroutines.android)
}
