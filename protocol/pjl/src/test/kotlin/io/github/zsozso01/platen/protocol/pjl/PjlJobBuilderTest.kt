package io.github.zsozso01.platen.protocol.pjl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PjlJobBuilderTest {
    private fun String.visible() = replace("\u001B%-12345X", "<UEL>").replace("\r\n", "\n")

    @Test
    fun `minimal header and footer`() {
        val b = PjlJobBuilder()
        assertEquals("<UEL>@PJL\n@PJL ENTER LANGUAGE=PDF\n", String(b.header(Pjl.Language.PDF)).visible())
        assertEquals("<UEL>@PJL EOJ\n<UEL>", String(b.footer()).visible())
    }

    @Test
    fun `full header keeps a stable order`() {
        val b = PjlJobBuilder(
            PjlJobSettings(
                jobName = "Report.pdf",
                copies = 3,
                duplex = true,
                binding = PjlJobSettings.Binding.LONG_EDGE,
                paper = "A4",
                mediaSource = "TRAY2",
                resolutionDpi = 600,
                renderMode = PjlJobSettings.RenderMode.GRAYSCALE,
                economode = true,
                orientation = PjlJobSettings.Orientation.PORTRAIT,
            ),
        )
        assertEquals(
            """
            <UEL>@PJL JOB NAME="Report.pdf"
            @PJL SET COPIES=3
            @PJL SET DUPLEX=ON
            @PJL SET BINDING=LONGEDGE
            @PJL SET PAPER=A4
            @PJL SET MEDIASOURCE=TRAY2
            @PJL SET RESOLUTION=600
            @PJL SET RENDERMODE=GRAYSCALE
            @PJL SET ECONOMODE=ON
            @PJL SET ORIENTATION=PORTRAIT
            @PJL ENTER LANGUAGE=PDF

            """.trimIndent(),
            String(b.header(Pjl.Language.PDF)).visible(),
        )
        assertEquals("<UEL>@PJL EOJ NAME=\"Report.pdf\"\n<UEL>", String(b.footer()).visible())
    }

    @Test
    fun `status reporting is switched on before the job starts`() {
        val named = String(PjlJobBuilder(PjlJobSettings(jobName = "x", statusReporting = true)).header(Pjl.Language.PDF)).visible()
        assertEquals("<UEL>@PJL USTATUS JOB=ON\n@PJL USTATUS DEVICE=ON\n@PJL JOB NAME=\"x\"\n@PJL ENTER LANGUAGE=PDF\n", named)
        val unnamed = String(PjlJobBuilder(PjlJobSettings(statusReporting = true)).header(Pjl.Language.PDF)).visible()
        assertEquals("<UEL>@PJL USTATUS JOB=ON\n@PJL USTATUS DEVICE=ON\n@PJL ENTER LANGUAGE=PDF\n", unnamed)
    }

    @Test
    fun `binding is dropped when duplex is off`() {
        val h = String(PjlJobBuilder(PjlJobSettings(duplex = false, binding = PjlJobSettings.Binding.SHORT_EDGE)).header(Pjl.Language.PCL))
        assertTrue("DUPLEX=OFF" in h)
        assertFalse("BINDING" in h)
    }

    @Test
    fun `job name is sanitised`() {
        val h = String(PjlJobBuilder(PjlJobSettings(jobName = "A \"quoted\"\r\nname é")).header(Pjl.Language.PDF)).visible()
        assertTrue("""@PJL JOB NAME="A 'quoted' name ?"""" in h, h)
    }

    @Test
    fun `long job names are truncated`() {
        val h = String(PjlJobBuilder(PjlJobSettings(jobName = "x".repeat(500))).header(Pjl.Language.PDF))
        val name = Regex("NAME=\"(x+)\"").find(h)!!.groupValues[1]
        assertEquals(Pjl.MAX_JOB_NAME_LENGTH, name.length)
    }

    @Test
    fun `copies are clamped to a sane range`() {
        assertTrue("COPIES=1" in String(PjlJobBuilder(PjlJobSettings(copies = 0)).header(Pjl.Language.PDF)))
        assertTrue("COPIES=999" in String(PjlJobBuilder(PjlJobSettings(copies = 100_000)).header(Pjl.Language.PDF)))
    }

    @Test
    fun `values that could inject commands are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PjlJobBuilder(PjlJobSettings(paper = "A4\r\n@PJL SET DUPLEX=ON")).header(Pjl.Language.PDF)
        }
        assertFailsWith<IllegalArgumentException> {
            PjlJobBuilder(PjlJobSettings(extra = mapOf("x\n" to "1"))).header(Pjl.Language.PDF)
        }
        assertFailsWith<IllegalArgumentException> {
            PjlJobBuilder(PjlJobSettings(extra = mapOf("FOO" to "a b"))).header(Pjl.Language.PDF)
        }
    }

    @Test
    fun `extra variables are sent`() {
        val h = String(PjlJobBuilder(PjlJobSettings(extra = mapOf("PRINTQUALITY" to "DRAFT"))).header(Pjl.Language.PDF))
        assertTrue("@PJL SET PRINTQUALITY=DRAFT" in h)
    }
}
