plugins {
    id("platen.android.library")
}

android {
    namespace = "io.github.zsozso01.platen.platform.usb"
}

dependencies {
    api(projects.transport.usb)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
