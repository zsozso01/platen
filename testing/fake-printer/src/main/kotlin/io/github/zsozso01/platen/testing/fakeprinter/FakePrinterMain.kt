package io.github.zsozso01.platen.testing.fakeprinter

import java.net.InetAddress

/**
 * Runs a fake printer you can point the app at:
 *
 *     ./gradlew :testing:fake-printer:run --args="inkjet 6310"
 *
 * From the Android emulator the host is `10.0.2.2`, so add the printer manually as
 * `ipp://10.0.2.2:6310/ipp/print`. Received jobs are written to the current directory.
 */
fun main(args: Array<String>) {
    val profile = when (args.getOrNull(0) ?: "inkjet") {
        "inkjet" -> FakePrinterProfile.inkjetRasterOnly
        "laser" -> FakePrinterProfile.laserPdf
        else -> error("Unknown profile '${args[0]}'. Use: inkjet | laser")
    }
    val port = args.getOrNull(1)?.toInt() ?: 6310
    val printer = FakeIppPrinter(profile, port, InetAddress.getByName("0.0.0.0"))
    println("Fake printer '${profile.name}' listening on port ${printer.port}  (emulator: ipp://10.0.2.2:${printer.port}/ipp/print)")
    var seen = 0
    while (true) {
        Thread.sleep(500)
        while (seen < printer.jobs.size) {
            val job = printer.jobs[seen++]
            val extension = when (job.documentFormat) {
                "image/pwg-raster" -> "pwg"
                "image/urf" -> "urf"
                "application/pdf" -> "pdf"
                "application/postscript" -> "ps"
                else -> "bin"
            }
            val file = java.io.File("fake-printer-job-${job.id}.$extension")
            file.writeBytes(job.document)
            println("Job ${job.id} '${job.jobName}' (${job.documentFormat}, ${job.document.size} bytes) -> ${file.absolutePath}")
        }
    }
}
