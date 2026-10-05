plugins {
    id("platen.kotlin.jvm")
}


kotlin {
    // Test tooling, not a published API.
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

dependencies {
    api(projects.core.engine)
    api(projects.protocol.raster)
}
