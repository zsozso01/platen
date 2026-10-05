plugins {
    id("platen.kotlin.jvm")
    application
}


kotlin {
    // Test tooling, not a published API.
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

application {
    mainClass.set("io.github.zsozso01.platen.testing.fakeprinter.FakePrinterMainKt")
}

dependencies {
    api(projects.protocol.ipp)
    api(projects.protocol.pjl)
    api(projects.core.engine)
}
