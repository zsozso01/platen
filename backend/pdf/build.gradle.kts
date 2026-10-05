plugins {
    id("platen.kotlin.jvm")
}

dependencies {
    api(projects.core.engine)

    testImplementation(projects.testing.support)
}
