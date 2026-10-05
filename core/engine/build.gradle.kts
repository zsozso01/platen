plugins {
    id("platen.kotlin.jvm")
}

group = "io.github.zsozso01.platen"

dependencies {
    api(projects.core.model)
    api(projects.core.layout)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
