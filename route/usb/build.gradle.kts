plugins {
    id("platen.kotlin.jvm")
}

dependencies {
    api(projects.core.engine)
    api(projects.transport.usb)
    api(projects.route.ipp)
    api(projects.route.pjl)

    testImplementation(projects.testing.usb)
    testImplementation(projects.testing.support)
}
