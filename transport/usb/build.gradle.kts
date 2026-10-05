plugins {
    id("platen.kotlin.jvm")
}

dependencies {
    api(projects.core.engine)
    api(projects.protocol.ipp)
    api(projects.protocol.ieee1284)

    testImplementation(projects.testing.fakePrinter)
    testImplementation(projects.route.ipp)
}
