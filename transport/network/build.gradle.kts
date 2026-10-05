plugins {
    id("platen.kotlin.jvm")
}

dependencies {
    api(projects.protocol.ipp)

    testImplementation(projects.testing.fakePrinter)
}
