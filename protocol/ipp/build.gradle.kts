plugins {
    id("platen.kotlin.jvm")
}

group = "io.github.zsozso01.platen"

dependencies {
    testImplementation(projects.testing.fakePrinter)
}
