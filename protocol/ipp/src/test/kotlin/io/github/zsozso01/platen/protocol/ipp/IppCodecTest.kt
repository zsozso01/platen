package io.github.zsozso01.platen.protocol.ipp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IppCodecTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    private fun operationAttributes(extra: IppAttributesBuilder.() -> Unit = {}): IppAttributesBuilder.() -> Unit = {
        charset("attributes-charset", "utf-8")
        naturalLanguage("attributes-natural-language", "en")
        extra()
    }

    @Test
    fun `get-printer-attributes request matches the RFC 8010 layout byte for byte`() {
        val msg = ippMessage(IppOperation.GET_PRINTER_ATTRIBUTES, requestId = 1) {
            group(GroupTag.OPERATION_ATTRIBUTES, operationAttributes {
                uri("printer-uri", "ipp://p/ipp")
                keyword("requested-attributes", "all")
            })
        }
        // version 2.0, operation 0x000B, request-id 1, then group tag 01
        val bytes = IppEncoder.encode(msg)
        assertEquals("02 00 00 0b 00 00 00 01 01", hex(bytes.copyOfRange(0, 9)))
        // charset: tag 47, name-length 0012, "attributes-charset", value-length 0005, "utf-8"
        val charset = "47 00 12 " + "attributes-charset".map { "%02x".format(it.code) }.joinToString(" ") + " 00 05 75 74 66 2d 38"
        assertTrue(hex(bytes).contains(charset), hex(bytes))
        assertEquals("03", hex(bytes.copyOfRange(bytes.size - 1, bytes.size)))
    }

    @Test
    fun `second value of an attribute uses a zero-length name`() {
        val bytes = IppEncoder.encode(
            ippMessage(IppOperation.GET_PRINTER_ATTRIBUTES) {
                group(GroupTag.OPERATION_ATTRIBUTES) { keywords("k", listOf("a", "b")) }
            },
        )
        // ... 44 0001 'k' 0001 'a'   44 0000 0001 'b' 03
        assertTrue(hex(bytes).endsWith("44 00 01 6b 00 01 61 44 00 00 00 01 62 03"), hex(bytes))
    }

    @Test
    fun `collection encoding follows RFC 8010 section 3 1 6`() {
        val bytes = IppEncoder.encode(
            ippMessage(IppOperation.PRINT_JOB) {
                group(GroupTag.JOB_ATTRIBUTES) {
                    collection("media-col") {
                        integer("x", 1)
                    }
                }
            },
        )
        // begCollection 34, name "media-col", value-len 0
        // memberAttrName 4a 0000 0001 'x'  | integer 21 0000 0004 00000001 | endCollection 37 0000 0000
        val tail = "34 00 09 " + "media-col".map { "%02x".format(it.code) }.joinToString(" ") +
            " 00 00 4a 00 00 00 01 78 21 00 00 00 04 00 00 00 01 37 00 00 00 00 03"
        assertTrue(hex(bytes).endsWith(tail), hex(bytes))
    }

    private fun roundTrip(message: IppMessage): IppMessage {
        val decoded = IppDecoder.decode(IppEncoder.encode(message))
        return decoded.message
    }

    @Test
    fun `every syntax survives a round trip`() {
        val original = ippMessage(IppOperation.PRINT_JOB, requestId = 0x01020304, version = IppVersion.V1_1) {
            group(GroupTag.OPERATION_ATTRIBUTES, operationAttributes {
                name("requesting-user-name", "zsombor")
                text("job-info", "héllo wörld ✓")
                mimeMediaType("document-format", "application/pdf")
            })
            group(GroupTag.JOB_ATTRIBUTES) {
                integer("copies", 2)
                enum("print-quality", 5)
                boolean("flag", true)
                boolean("flag2", false)
                range("page-ranges", 3, 7)
                resolution("printer-resolution", 600, 300)
                resolution("metric", 118, 118, IppResolution.Units.DOTS_PER_CM)
                add("date", IppDateTime(2026, 10, 5, 13, 45, 59, 7, '+', 2, 0))
                add("blob", IppOctets(byteArrayOf(0, 1, 2, -1)))
                add("oob", IppOutOfBand(0x13))
                add("weird", IppUnknown(0x7F, byteArrayOf(9, 9)))
                add("localized", IppString(IppString.Kind.TEXT, "szia", "hu"))
                add("localized-name", IppString(IppString.Kind.NAME, "név", "hu"))
                collection("media-col") {
                    collection("media-size") {
                        integer("x-dimension", 21000)
                        integer("y-dimension", 29700)
                    }
                    keyword("media-type", "stationery")
                    addAll("multi", listOf(IppInteger(1), IppInteger(2)))
                }
            }
        }
        assertEquals(0x01020304, roundTrip(original).requestId)
        val back = roundTrip(original)
        assertEquals(IppVersion.V1_1, back.version)
        assertEquals(original.groups, back.groups)
    }

    @Test
    fun `multi-valued collections round trip`() {
        val original = ippMessage(IppStatus.SUCCESSFUL_OK, requestId = 7) {
            group(GroupTag.PRINTER_ATTRIBUTES) {
                addAll(
                    "media-col-database",
                    listOf(
                        IppCollection(listOf(IppAttribute("media-source", IppString(IppString.Kind.KEYWORD, "tray-1")))),
                        IppCollection(listOf(IppAttribute("media-source", IppString(IppString.Kind.KEYWORD, "tray-2")))),
                    ),
                )
            }
        }
        val back = roundTrip(original)
        assertEquals(original.groups, back.groups)
        assertEquals(2, back.attribute(GroupTag.PRINTER_ATTRIBUTES, "media-col-database")!!.collections().size)
    }

    @Test
    fun `decoder reports where document data starts`() {
        val msg = ippMessage(IppOperation.PRINT_JOB) { group(GroupTag.OPERATION_ATTRIBUTES, operationAttributes()) }
        val ipp = IppEncoder.encode(msg)
        val withData = ipp + "%PDF-1.7".toByteArray()
        assertEquals(ipp.size, IppDecoder.decode(withData).dataOffset)
    }

    @Test
    fun `decodes a response with status message and two groups`() {
        val msg = ippMessage(IppStatus.CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED, requestId = 9) {
            group(GroupTag.OPERATION_ATTRIBUTES, operationAttributes { text("status-message", "no PDF here") })
            group(GroupTag.UNSUPPORTED_ATTRIBUTES) { mimeMediaType("document-format", "application/pdf") }
        }
        val back = roundTrip(msg)
        assertEquals("no PDF here", back.statusMessage)
        assertEquals(false, back.isSuccess)
        assertNotNull(back.group(GroupTag.UNSUPPORTED_ATTRIBUTES))
    }

    @Test
    fun `an additional value with no preceding attribute is rejected`() {
        val bytes = byteArrayOf(2, 0, 0, 0x0b, 0, 0, 0, 1, 1, 0x44, 0, 0, 0, 1, 0x61, 3)
        assertFailsWith<IppParseException> { IppDecoder.decode(bytes) }
    }

    @Test
    fun `nesting beyond the limit is rejected`() {
        fun nested(depth: Int): IppValue =
            if (depth == 0) IppInteger(1) else IppCollection(listOf(IppAttribute("m", nested(depth - 1))))
        val deep = IppMessage(IppVersion.V2_0, 0, 1, listOf(IppGroup(GroupTag.JOB_ATTRIBUTES, listOf(IppAttribute("c", nested(40))))))
        assertFailsWith<IppParseException> { IppDecoder.decode(IppEncoder.encode(deep)) }
    }

    @Test
    fun `every truncation of a valid message fails cleanly or succeeds`() {
        val rich = IppEncoder.encode(sampleMessage())
        for (cut in 0 until rich.size) {
            val prefix = rich.copyOfRange(0, cut)
            // A cut message must never succeed (the end tag is missing) and must never throw anything else.
            assertFailsWith<IppParseException>("cut at $cut") { IppDecoder.decode(prefix) }
        }
    }

    @Test
    fun `random corruption never throws anything but IppParseException`() {
        val rich = IppEncoder.encode(sampleMessage())
        val random = Random(1234)
        repeat(20_000) {
            val copy = rich.copyOf()
            repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            try {
                IppDecoder.decode(copy)
            } catch (_: IppParseException) {
                // expected for most mutations
            }
        }
    }

    @Test
    fun `pure random bytes never throw anything but IppParseException`() {
        val random = Random(99)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(0, 80))
            try {
                IppDecoder.decode(bytes)
            } catch (_: IppParseException) {
            }
        }
    }

    @Test
    fun `huge declared lengths do not allocate`() {
        // string attribute claiming 65535 bytes in a 20 byte message
        val bytes = byteArrayOf(2, 0, 0, 0x0b, 0, 0, 0, 1, 1, 0x44, 0, 1, 0x6b, 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)
        assertFailsWith<IppParseException> { IppDecoder.decode(bytes) }
    }

    @Test
    fun `typed accessors`() {
        val a = IppAttribute("n", listOf(IppInteger(1), IppEnum(2)))
        assertEquals(listOf(1, 2), a.ints())
        assertEquals(1, a.int())
        assertIs<IppInteger>(a.first)
        assertEquals(null, a.string())
        assertEquals(IppResolution(600, 600, IppResolution.Units.DOTS_PER_INCH).dpiOrNull, 600)
        assertEquals(null, IppResolution(600, 300, IppResolution.Units.DOTS_PER_INCH).dpiOrNull)
        assertTrue(5 in IppRange(1, 9))
    }

    private fun sampleMessage() = ippMessage(IppOperation.PRINT_JOB, requestId = 5) {
        group(GroupTag.OPERATION_ATTRIBUTES, operationAttributes {
            name("requesting-user-name", "u")
            mimeMediaType("document-format", "application/pdf")
        })
        group(GroupTag.JOB_ATTRIBUTES) {
            integer("copies", 2)
            keywords("sides", listOf("one-sided", "two-sided-long-edge"))
            range("page-ranges", 1, 4)
            resolution("printer-resolution", 600, 600)
            add("date", IppDateTime(2026, 1, 2, 3, 4, 5, 6, '-', 5, 30))
            add("t", IppString(IppString.Kind.TEXT, "x", "en"))
            collection("media-col") {
                collection("media-size") { integer("x-dimension", 21000); integer("y-dimension", 29700) }
                keyword("media-source", "tray-1")
            }
        }
    }
}
