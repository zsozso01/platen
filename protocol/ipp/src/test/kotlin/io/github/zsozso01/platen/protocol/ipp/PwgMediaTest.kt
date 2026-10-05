package io.github.zsozso01.platen.protocol.ipp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PwgMediaTest {
    @Test
    fun `parses metric and inch sizes`() {
        val a4 = PwgMediaSize.parse("iso_a4_210x297mm")!!
        assertEquals("iso_a4", a4.name)
        assertEquals(21000, a4.widthHundredthsMm)
        assertEquals(29700, a4.heightHundredthsMm)
        val letter = PwgMediaSize.parse("na_letter_8.5x11in")!!
        assertEquals(21590, letter.widthHundredthsMm)
        assertEquals(27940, letter.heightHundredthsMm)
        val index = PwgMediaSize.parse("na_index-4x6_4x6in")!!
        assertEquals("na_index-4x6", index.name)
        assertEquals(10160, index.widthHundredthsMm)
        assertEquals(PwgMediaSize.parse("jpn_photo-2l_127x177.8mm")!!.heightHundredthsMm, 17780)
    }

    @Test
    fun `rejects non self-describing names`() {
        listOf("na_number-10", "iso_dl", "custom_min_3x5in", "custom_max_8.5x14in", "", "A4", "iso_a4_210x297").forEach {
            assertNull(PwgMediaSize.parse(it), it)
        }
    }

    @Test
    fun `custom range limits are recognised separately`() {
        val min = PwgMediaSize.parseCustomLimit("custom_min_3x5in")!!
        assertEquals(true, min.isMinimum)
        assertEquals(7620, min.size.widthHundredthsMm)
        val max = PwgMediaSize.parseCustomLimit("custom_max_8.5x14in")!!
        assertEquals(false, max.isMinimum)
        assertEquals(35560, max.size.heightHundredthsMm)
        assertNull(PwgMediaSize.parseCustomLimit("iso_a4_210x297mm"))
    }
}
