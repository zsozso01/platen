package io.github.zsozso01.platen.protocol.pjl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sample responses are synthetic, written from the format described in the PJL Technical Reference. */
class PjlResponseTest {
    private val ff = "\u000C"

    @Test
    fun `parses INFO ID`() {
        val r = PjlResponseParser.parse("@PJL INFO ID\r\n\"Acme LaserWriter 9000\"\r\n$ff").single()
        assertEquals("INFO ID", r.command)
        assertEquals("Acme LaserWriter 9000", r.text)
    }

    @Test
    fun `parses status and classifies it`() {
        val r = PjlResponseParser.parse("@PJL INFO STATUS\r\nCODE=10001\r\nDISPLAY=\"00 READY\"\r\nONLINE=TRUE\r\n$ff").single()
        val s = PjlStatus.from(r)
        assertEquals(10001, s.code)
        assertEquals("00 READY", s.display)
        assertEquals(true, s.online)
        assertEquals(PjlStatusClass.INFORMATIONAL, s.statusClass)
    }

    @Test
    fun `classifies operator intervention`() {
        val r = PjlResponseParser.parse("@PJL USTATUS DEVICE\r\nCODE=40022\r\nDISPLAY=\"PAPER JAM\"\r\nONLINE=TRUE\r\n$ff").single()
        assertEquals(PjlStatusClass.OPERATOR_INTERVENTION, PjlStatus.from(r).statusClass)
    }

    @Test
    fun `status class boundaries`() {
        fun c(code: Int?) = PjlStatus(code, null, null).statusClass
        assertEquals(PjlStatusClass.UNKNOWN, c(null))
        assertEquals(PjlStatusClass.UNKNOWN, c(9_999))
        assertEquals(PjlStatusClass.INFORMATIONAL, c(10_000))
        assertEquals(PjlStatusClass.PJL_ERROR, c(25_001))
        assertEquals(PjlStatusClass.ATTENTION, c(35_078))
        assertEquals(PjlStatusClass.OPERATOR_INTERVENTION, c(40_000))
        assertEquals(PjlStatusClass.UNKNOWN, c(50_000))
    }

    @Test
    fun `parses job events`() {
        val start = PjlResponseParser.parse("@PJL USTATUS JOB\r\nSTART\r\nNAME=\"Report\"\r\n$ff").single()
        assertEquals(PjlJobEvent(PjlJobEvent.Type.START, "Report", null), PjlJobEvent.from(start))
        val end = PjlResponseParser.parse("@PJL USTATUS JOB\r\nEND\r\nNAME=\"Report\"\r\nPAGES=4\r\n$ff").single()
        assertEquals(PjlJobEvent(PjlJobEvent.Type.END, "Report", 4), PjlJobEvent.from(end))
        assertNull(PjlJobEvent.from(PjlResponseParser.parse("@PJL INFO ID\r\n\"x\"\r\n$ff").single()))
    }

    @Test
    fun `splits several responses and ignores UEL and noise`() {
        val text = "${Pjl.UEL}@PJL INFO ID\r\n\"X\"\r\n$ff@PJL ECHO ${PjlQueries.DONE_MARKER}\r\n$ff${Pjl.UEL}"
        val rs = PjlResponseParser.parse(text)
        assertEquals(listOf("INFO ID", "ECHO ${PjlQueries.DONE_MARKER}"), rs.map { it.command })
        assertTrue(PjlResponseParser.isComplete(text.toByteArray()))
        assertTrue(!PjlResponseParser.isComplete("@PJL INFO ID\r\n\"X\"\r\n$ff".toByteArray()))
    }

    @Test
    fun `garbage never throws`() {
        listOf("", ff, "hello", "@PJL", "@PJL\r\n", "\u0000\u0001", "$ff$ff$ff").forEach { PjlResponseParser.parse(it) }
        assertTrue(PjlResponseParser.parse("not pjl at all").isEmpty())
    }

    @Test
    fun `query builder wraps commands and appends the echo marker`() {
        val q = String(PjlQueries.query(PjlQueries.INFO_ID, PjlQueries.INFO_STATUS)).replace("\r\n", "\n").replace(Pjl.UEL, "<UEL>")
        assertEquals("<UEL>@PJL INFO ID\n@PJL INFO STATUS\n@PJL ECHO PLATEN-DONE\n<UEL>", q)
    }

    @Test
    fun `parses INFO VARIABLES`() {
        val body = """
            @PJL INFO VARIABLES
            COPIES=1 [2 RANGE]
            	1
            	999
            DUPLEX=OFF [2 ENUMERATED]
            	OFF
            	ON
            PAPER=A4 [4 ENUMERATED]
            	LETTER
            	LEGAL
            	A4
            	A5
            ECONOMODE=OFF [2 ENUMERATED]
            	OFF
            	ON
        """.trimIndent().replace("\n", "\r\n") + ff
        val vars = PjlVariableParser.parse(PjlResponseParser.parse(body).single()).associateBy { it.name }
        assertEquals(4, vars.size)
        assertEquals(1..999, vars.getValue("COPIES").range)
        assertEquals(listOf("OFF", "ON"), vars.getValue("DUPLEX").allowed)
        assertEquals("OFF", vars.getValue("DUPLEX").current)
        assertEquals(listOf("LETTER", "LEGAL", "A4", "A5"), vars.getValue("PAPER").allowed)
        assertEquals(PjlVariable.Kind.ENUMERATED, vars.getValue("PAPER").kind)
    }

    @Test
    fun `parses INFO CONFIG style entries without a current value`() {
        val body = listOf("IN TRAYS [2 ENUMERATED]", "TRAY1", "TRAY2", "LANGUAGES [3 ENUMERATED]", "PCL", "POSTSCRIPT", "PDF", "MEMORY=128000000")
        val vars = PjlVariableParser.parse(body).associateBy { it.name }
        assertEquals(listOf("TRAY1", "TRAY2"), vars.getValue("IN TRAYS").allowed)
        assertNull(vars.getValue("LANGUAGES").current)
        assertEquals(listOf("PCL", "POSTSCRIPT", "PDF"), vars.getValue("LANGUAGES").allowed)
        assertNotNull(vars["LANGUAGES"])
    }

    @Test
    fun `variable parser survives truncated and hostile input`() {
        PjlVariableParser.parse(listOf("X=1 [999999999 ENUMERATED]", "a"))
        PjlVariableParser.parse(listOf("[2 RANGE]", "1"))
        assertTrue(PjlVariableParser.parse(listOf("no brackets here")).isEmpty())
    }
}
