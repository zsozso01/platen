plugins {
    id("platen.kotlin.jvm")
}

dependencies {
    api(projects.core.engine)
    api(projects.protocol.ipp)
    api(projects.transport.network)

    testImplementation(projects.testing.fakePrinter)
    testImplementation(projects.testing.support)
    testImplementation(projects.backend.raster)
}
