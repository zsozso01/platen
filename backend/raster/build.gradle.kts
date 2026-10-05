plugins {
    id("platen.kotlin.jvm")
}


dependencies {
    api(projects.core.engine)
    api(projects.protocol.raster)

    testImplementation(projects.testing.support)
}
