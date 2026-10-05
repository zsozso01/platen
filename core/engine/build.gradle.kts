plugins {
    id("platen.kotlin.jvm")
}


dependencies {
    api(projects.core.model)
    api(projects.core.layout)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(projects.testing.support)
}
