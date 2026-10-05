package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.CapabilityKey
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Provenance
import io.github.zsozso01.platen.core.model.Severity
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.ieee1284.Ieee1284DeviceId
import io.github.zsozso01.platen.protocol.pjl.PjlJobSettings
import io.github.zsozso01.platen.protocol.pjl.PjlStatus
import io.github.zsozso01.platen.protocol.pjl.PjlVariable
import io.github.zsozso01.platen.protocol.pjl.PjlVariableParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PjlMappingTest {
    private fun vars(vararg lines: String) = PjlVariableParser.parse(lines.toList())

    private val config = vars("LANGUAGES [4 ENUMERATED]", "PCL", "PCLXL", "POSTSCRIPT", "PDF")

    private val variables = vars(
        "COPIES=1 [2 RANGE]", "1", "999",
        "DUPLEX=OFF [2 ENUMERATED]", "OFF", "ON",
        "BINDING=LONGEDGE [2 ENUMERATED]", "LONGEDGE", "SHORTEDGE",
        "PAPER=A4 [5 ENUMERATED]", "LETTER", "A4", "A5", "COM10", "WEIRD",
        "MEDIASOURCE=TRAY1 [2 ENUMERATED]", "TRAY1", "TRAY2",
        "OUTBIN=UPPER [1 ENUMERATED]", "UPPER",
        "RESOLUTION=600 [3 ENUMERATED]", "1200", "300", "600",
        "ECONOMODE=OFF [2 ENUMERATED]", "OFF", "ON",
    )

    // --- capabilities -----------------------------------------------------------------------------

    @Test
    fun `capabilities come from the printer's own lists`() {
        val caps = PjlCapabilityMapper.map(null, config, variables)
        assertEquals(listOf(DocumentFormat.PDF, DocumentFormat.POSTSCRIPT, DocumentFormat.PCL), caps.formats.sortedBy { it.mime })
        assertEquals(setOf(Sides.ONE_SIDED, Sides.TWO_SIDED_LONG_EDGE, Sides.TWO_SIDED_SHORT_EDGE), caps.sides)
        assertEquals(listOf(MediaSize.LETTER.widthHundredthsMm, MediaSize.A4.widthHundredthsMm, MediaSize.A5.widthHundredthsMm), caps.mediaSizes.map { it.widthHundredthsMm })
        assertEquals(MediaSize.A4.widthHundredthsMm, caps.defaultMedia?.widthHundredthsMm)
        assertEquals(listOf(MediaSource("TRAY1"), MediaSource("TRAY2")), caps.trays)
        assertEquals(999, caps.maxCopies)
        assertEquals(listOf(300, 600, 1200), caps.resolutionsDpi)
        assertEquals(Provenance.REPORTED_PJL, caps.provenanceOf(CapabilityKey.FORMATS))
        assertEquals(Provenance.REPORTED_PJL, caps.provenanceOf(CapabilityKey.SIDES))
        assertEquals(Provenance.ASSUMED, caps.provenanceOf(CapabilityKey.MARGINS))
    }

    @Test
    fun `a printer without a duplex unit is simplex`() {
        val caps = PjlCapabilityMapper.map(null, config, vars("DUPLEX=OFF [1 ENUMERATED]", "OFF"))
        assertEquals(setOf(Sides.ONE_SIDED), caps.sides)
    }

    @Test
    fun `without PJL the device id is the only evidence and is marked as a hint`() {
        val id = Ieee1284DeviceId.parse("MFG:HP;MDL:HP LaserJet MFP E42540;CMD:PJL,PCL,PCLXL,POSTSCRIPT,PDF;CLS:PRINTER;")
        val caps = PjlCapabilityMapper.map(id, emptyList(), emptyList())
        assertTrue(caps.supports(DocumentFormat.PDF))
        assertEquals(Provenance.DEVICE_ID_HINT, caps.provenanceOf(CapabilityKey.FORMATS))
        assertEquals(setOf(Sides.ONE_SIDED), caps.sides)
        assertNull(caps.maxCopies)
    }

    @Test
    fun `no evidence at all gives empty capabilities rather than guesses`() {
        val caps = PjlCapabilityMapper.map(null, emptyList(), emptyList())
        assertTrue(caps.formats.isEmpty())
        assertTrue(caps.mediaSizes.isEmpty())
        assertTrue(caps.trays.isEmpty())
    }

    @Test
    fun `installed papers from INFO CONFIG fill in when VARIABLES lists none`() {
        val caps = PjlCapabilityMapper.map(null, vars("PAPERS [2 ENUMERATED]", "A4", "LEGAL"), emptyList())
        assertEquals(2, caps.mediaSizes.size)
    }

    // --- job ticket -------------------------------------------------------------------------------

    private val byName = variables.associateBy(PjlVariable::name)

    private fun settings(copies: Int = 1, sides: Sides = Sides.ONE_SIDED, paper: MediaSize? = null, tray: String? = null, dpi: Int? = null, economy: Boolean = false) =
        PrinterJobSettings(jobName = "Doc", copies = copies, sides = sides, paper = paper, tray = tray?.let(::MediaSource), resolutionDpi = dpi, economy = economy)

    @Test
    fun `settings the printer lists are sent`() {
        val t = PjlJobTicket.build(settings(copies = 3, sides = Sides.TWO_SIDED_SHORT_EDGE, paper = MediaSize.A5, tray = "TRAY2", dpi = 600, economy = true), byName)
        assertEquals(3, t.copies)
        assertEquals(true, t.duplex)
        assertEquals("A5", t.paper)
        assertEquals("TRAY2", t.mediaSource)
        assertEquals(600, t.resolutionDpi)
        assertEquals(true, t.economode)
        assertTrue(t.statusReporting)
    }

    @Test
    fun `one-sided is stated explicitly when the printer has a duplex unit`() {
        assertEquals(false, PjlJobTicket.build(settings(), byName).duplex)
    }

    @Test
    fun `values the printer did not list are left out`() {
        val t = PjlJobTicket.build(settings(paper = MediaSize.LEGAL, tray = "TRAY9", dpi = 2400, copies = 5000), byName)
        assertNull(t.paper)
        assertNull(t.mediaSource)
        assertNull(t.resolutionDpi)
        assertNull(t.copies)
    }

    @Test
    fun `knowing nothing sends only copies and an explicit duplex request`() {
        val t = PjlJobTicket.build(settings(copies = 2, sides = Sides.TWO_SIDED_LONG_EDGE, paper = MediaSize.A4, tray = "TRAY1", dpi = 600), emptyMap())
        assertEquals(2, t.copies)
        assertEquals(true, t.duplex)
        assertNull(t.paper)
        assertNull(t.mediaSource)
        assertNull(t.resolutionDpi)
        assertNull(PjlJobTicket.build(settings(), emptyMap()).duplex)
    }

    @Test
    fun `colour is only requested where the printer offers it`() {
        val colourVars = byName + ("RENDERMODE" to PjlVariable("RENDERMODE", "COLOR", PjlVariable.Kind.ENUMERATED, listOf("COLOR", "GRAYSCALE")))
        val mono = PrinterJobSettings("x", colorMode = ColorMode.MONOCHROME)
        assertEquals(PjlJobSettings.RenderMode.GRAYSCALE, PjlJobTicket.build(mono, colourVars).renderMode)
        assertNull(PjlJobTicket.build(mono, byName).renderMode)
    }

    // --- issues -----------------------------------------------------------------------------------

    private fun status(code: Int, display: String, online: Boolean? = true) = PjlStatus(code, display, online)

    @Test
    fun `paper out and jams are errors a person can fix`() {
        assertEquals(PrinterIssue.Kind.PAPER_OUT, PjlIssues.from(status(41002, "LOAD PAPER TRAY 2", online = false)).single().kind)
        assertEquals(PrinterIssue.Kind.PAPER_JAM, PjlIssues.from(status(42001, "13.1 PAPER JAM")).single().kind)
        assertEquals(PrinterIssue.Kind.DOOR_OPEN, PjlIssues.from(status(40079, "DOOR OPEN")).single().kind)
        assertEquals(Severity.ERROR, PjlIssues.from(status(41002, "LOAD PAPER")).single().severity)
    }

    @Test
    fun `low toner is a warning and a cartridge to order is not an empty one`() {
        val low = PjlIssues.from(status(30010, "ORDER TONER")).single()
        assertEquals(PrinterIssue.Kind.TONER_LOW, low.kind)
        assertEquals(Severity.WARNING, low.severity)
        assertEquals(PrinterIssue.Kind.TONER_LOW, PjlIssues.from(status(30011, "ORDER BLACK CARTRIDGE")).single().kind)
        assertEquals(PrinterIssue.Kind.TONER_EMPTY, PjlIssues.from(status(41003, "REPLACE BLACK CARTRIDGE")).single().kind)
    }

    @Test
    fun `ready and PJL syntax complaints raise no issue`() {
        assertTrue(PjlIssues.from(status(10001, "00 READY")).isEmpty())
        assertTrue(PjlIssues.from(status(20002, "SYNTAX ERROR")).isEmpty())
        assertTrue(PjlIssues.from(PjlStatus(null, null, null)).isEmpty())
    }

    @Test
    fun `printer state follows the code and the display`() {
        assertEquals(PrinterState.IDLE, PjlIssues.state(status(10001, "00 READY")))
        assertEquals(PrinterState.PRINTING, PjlIssues.state(status(10023, "PRINTING")))
        assertEquals(PrinterState.STOPPED, PjlIssues.state(status(41002, "LOAD PAPER")))
        assertEquals(PrinterState.STOPPED, PjlIssues.state(status(10001, "OFFLINE", online = false)))
        assertEquals(PrinterState.UNKNOWN, PjlIssues.state(null))
        assertFalse(PjlIssues.from(status(10001, "READY")).isNotEmpty())
    }
}
