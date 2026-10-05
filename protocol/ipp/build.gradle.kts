plugins {
    id("platen.kotlin.jvm")
}


dependencies {
    testImplementation(projects.testing.fakePrinter)
}
