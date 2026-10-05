package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.pjl.PjlJobSettings
import io.github.zsozso01.platen.protocol.pjl.PjlVariable

/**
 * Maps planned settings onto `@PJL SET` variables, sending only what the printer said it accepts. A
 * variable the printer never listed, or a value outside its list, is left out: an unknown variable is
 * ignored by some printers and makes others reject the whole job.
 */
internal object PjlJobTicket {
    fun build(settings: PrinterJobSettings, variables: Map<String, PjlVariable>): PjlJobSettings {
        fun allowed(name: String): List<String>? = variables[name]?.takeIf { it.allowed.isNotEmpty() }?.allowed?.map { it.uppercase() }
        fun accepts(name: String, value: String): Boolean = allowed(name)?.contains(value.uppercase()) == true
        fun inRange(name: String, value: Int): Boolean = variables[name]?.range?.let { value in it } == true

        // Copies: QTY (collated) if the printer has it, else COPIES. With no knowledge, COPIES is the common one.
        val copies = settings.copies.takeIf { it > 1 }
        val useQty = copies != null && variables.containsKey("QTY") && inRange("QTY", copies)
        val useCopies = copies != null && !useQty && (variables.isEmpty() || inRange("COPIES", copies))

        val duplex = if (settings.sides.isDuplex) true else if (variables.containsKey("DUPLEX")) false else null
        val binding = when (settings.sides) {
            Sides.TWO_SIDED_LONG_EDGE -> PjlJobSettings.Binding.LONG_EDGE
            Sides.TWO_SIDED_SHORT_EDGE -> PjlJobSettings.Binding.SHORT_EDGE
            Sides.ONE_SIDED -> null
        }

        val paperName = settings.paper?.let(PjlPaper::nameFor)?.takeIf { accepts("PAPER", it) }
        val resolution = settings.resolutionDpi?.takeIf { dpi -> allowed("RESOLUTION")?.contains(dpi.toString()) == true }
        val render = when (settings.colorMode) {
            ColorMode.COLOR -> PjlJobSettings.RenderMode.COLOR.takeIf { accepts("RENDERMODE", "COLOR") }
            ColorMode.MONOCHROME -> PjlJobSettings.RenderMode.GRAYSCALE.takeIf { accepts("RENDERMODE", "GRAYSCALE") }
            ColorMode.AUTO, null -> null
        }
        val economy = (settings.economy || settings.quality == Quality.DRAFT) && accepts("ECONOMODE", "ON")

        return PjlJobSettings(
            jobName = settings.jobName,
            copies = if (useCopies) copies else null,
            quantity = if (useQty) copies else null,
            duplex = duplex,
            binding = binding,
            paper = paperName,
            mediaSource = settings.tray?.id?.takeIf { accepts("MEDIASOURCE", it) },
            outBin = settings.outputBin?.takeIf { accepts("OUTBIN", it) },
            resolutionDpi = resolution,
            renderMode = render,
            economode = if (economy) true else null,
            statusReporting = true,
        )
    }
}
