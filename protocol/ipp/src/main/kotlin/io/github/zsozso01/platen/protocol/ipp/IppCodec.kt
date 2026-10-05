package io.github.zsozso01.platen.protocol.ipp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Thrown when bytes are not a well-formed IPP message. The printer, not the user, is at fault. */
public class IppParseException(message: String, public val offset: Int) : RuntimeException("$message (at byte $offset)")

/** Result of [IppDecoder.decode]: the message and where any document data that follows it starts. */
public class IppDecoded(public val message: IppMessage, public val dataOffset: Int)

/** Writes [IppMessage]s in the binary encoding of RFC 8010. */
public object IppEncoder {
    public fun encode(message: IppMessage): ByteArray {
        val bytes = ByteArrayOutputStream(512)
        val out = DataOutputStream(bytes)
        out.writeByte(message.version.major)
        out.writeByte(message.version.minor)
        out.writeShort(message.code)
        out.writeInt(message.requestId)
        for (group in message.groups) {
            out.writeByte(group.tag.code)
            for (attribute in group.attributes) writeAttribute(out, attribute)
        }
        out.writeByte(Tag.END_OF_ATTRIBUTES)
        return bytes.toByteArray()
    }

    private fun writeAttribute(out: DataOutputStream, attribute: IppAttribute) {
        require(attribute.values.isNotEmpty()) { "Attribute '${attribute.name}' has no values" }
        attribute.values.forEachIndexed { index, value ->
            // Only the first value carries the name; the rest use a zero-length name (RFC 8010 3.1.3).
            writeValue(out, if (index == 0) attribute.name else "", value)
        }
    }

    private fun writeValue(out: DataOutputStream, name: String, value: IppValue) {
        when (value) {
            is IppInteger -> writeSimple(out, Tag.INTEGER, name, int4(value.value))
            is IppEnum -> writeSimple(out, Tag.ENUM, name, int4(value.value))
            is IppBoolean -> writeSimple(out, Tag.BOOLEAN, name, byteArrayOf(if (value.value) 1 else 0))
            is IppOctets -> writeSimple(out, Tag.OCTET_STRING, name, value.bytes)
            is IppDateTime -> writeSimple(out, Tag.DATE_TIME, name, dateTimeBytes(value))
            is IppResolution -> writeSimple(out, Tag.RESOLUTION, name, int4(value.crossFeed) + int4(value.feed) + byteArrayOf(value.units.code.toByte()))
            is IppRange -> writeSimple(out, Tag.RANGE_OF_INTEGER, name, int4(value.lower) + int4(value.upper))
            is IppString -> writeString(out, name, value)
            is IppOutOfBand -> writeSimple(out, value.tag, name, ByteArray(0))
            is IppUnknown -> writeSimple(out, value.tag, name, value.bytes)
            is IppCollection -> writeCollection(out, name, value)
        }
    }

    private fun writeString(out: DataOutputStream, name: String, value: IppString) {
        val text = value.value.toByteArray(Charsets.UTF_8)
        val lang = value.language
        val withLanguageTag = value.kind.tagWithLanguage
        if (lang != null && withLanguageTag != null) {
            val langBytes = lang.toByteArray(Charsets.US_ASCII)
            writeSimple(out, withLanguageTag, name, short2(langBytes.size) + langBytes + short2(text.size) + text)
        } else {
            writeSimple(out, value.kind.tag, name, text)
        }
    }

    private fun writeCollection(out: DataOutputStream, name: String, value: IppCollection) {
        writeSimple(out, Tag.BEG_COLLECTION, name, ByteArray(0))
        for (member in value.members) {
            require(member.values.isNotEmpty()) { "Collection member '${member.name}' has no values" }
            writeSimple(out, Tag.MEMBER_ATTR_NAME, "", member.name.toByteArray(Charsets.UTF_8))
            for (memberValue in member.values) writeValue(out, "", memberValue)
        }
        writeSimple(out, Tag.END_COLLECTION, "", ByteArray(0))
    }

    private fun writeSimple(out: DataOutputStream, tag: Int, name: String, value: ByteArray) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 0xFFFF && value.size <= 0xFFFF) { "IPP value too long for attribute '$name'" }
        out.writeByte(tag)
        out.writeShort(nameBytes.size)
        out.write(nameBytes)
        out.writeShort(value.size)
        out.write(value)
    }

    private fun dateTimeBytes(v: IppDateTime): ByteArray = byteArrayOf(
        (v.year shr 8).toByte(), v.year.toByte(), v.month.toByte(), v.day.toByte(),
        v.hour.toByte(), v.minute.toByte(), v.second.toByte(), v.decisecond.toByte(),
        v.utcSign.code.toByte(), v.utcHours.toByte(), v.utcMinutes.toByte(),
    )

    private fun int4(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun short2(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
}

/**
 * Reads [IppMessage]s. Hardened for hostile input: every read is bounds-checked, nesting depth and
 * element counts are capped, so a broken or malicious printer cannot cause a crash or exhaust memory.
 */
public object IppDecoder {
    private const val MAX_COLLECTION_DEPTH = 8
    private const val MAX_ATTRIBUTES = 20_000
    private const val MAX_GROUPS = 256

    public fun decode(bytes: ByteArray): IppDecoded = Reader(bytes).readMessage()

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        private var attributeCount = 0

        fun readMessage(): IppDecoded {
            if (b.size < 9) fail("message shorter than the 8-byte header")
            val version = IppVersion(u8(), u8())
            val code = u16()
            val requestId = i32()
            val groups = mutableListOf<IppGroup>()
            while (true) {
                if (groups.size >= MAX_GROUPS) fail("too many attribute groups")
                val tag = u8()
                if (tag == Tag.END_OF_ATTRIBUTES) break
                if (!Tag.isDelimiter(tag)) fail("expected a group or end tag but found 0x${tag.toString(16)}")
                groups += IppGroup(GroupTag(tag), readGroupAttributes())
            }
            return IppDecoded(IppMessage(version, code, requestId, groups), pos)
        }

        private fun readGroupAttributes(): List<IppAttribute> {
            val result = mutableListOf<IppAttribute>()
            var pendingName: String? = null
            var pendingValues = mutableListOf<IppValue>()

            fun flush() {
                pendingName?.let { result += IppAttribute(it, pendingValues) }
                pendingName = null
                pendingValues = mutableListOf()
            }

            while (true) {
                val tag = peekU8()
                if (Tag.isDelimiter(tag)) {
                    flush()
                    return result
                }
                pos++
                val name = readString(u16())
                val valueLength = u16()
                val value = readValue(tag, valueLength, depth = 0)
                if (name.isNotEmpty()) {
                    flush()
                    if (++attributeCount > MAX_ATTRIBUTES) fail("too many attributes")
                    pendingName = name
                } else if (pendingName == null) {
                    fail("additional value with no preceding attribute")
                }
                pendingValues += value
            }
        }

        private fun readValue(tag: Int, length: Int, depth: Int): IppValue {
            if (tag == Tag.BEG_COLLECTION) {
                skip(length)
                return readCollection(depth + 1)
            }
            val start = pos
            val end = pos + length
            if (end > b.size) fail("value runs past the end of the message")
            val value: IppValue = when (tag) {
                Tag.INTEGER -> IppInteger(fixed(length, 4).let { i32At(start) })
                Tag.ENUM -> IppEnum(fixed(length, 4).let { i32At(start) })
                Tag.BOOLEAN -> IppBoolean(fixed(length, 1).let { b[start].toInt() != 0 })
                Tag.OCTET_STRING -> IppOctets(b.copyOfRange(start, end))
                Tag.DATE_TIME -> {
                    fixed(length, 11)
                    IppDateTime(
                        year = ((b[start].toInt() and 0xFF) shl 8) or (b[start + 1].toInt() and 0xFF),
                        month = b[start + 2].toInt() and 0xFF,
                        day = b[start + 3].toInt() and 0xFF,
                        hour = b[start + 4].toInt() and 0xFF,
                        minute = b[start + 5].toInt() and 0xFF,
                        second = b[start + 6].toInt() and 0xFF,
                        decisecond = b[start + 7].toInt() and 0xFF,
                        utcSign = (b[start + 8].toInt() and 0xFF).toChar(),
                        utcHours = b[start + 9].toInt() and 0xFF,
                        utcMinutes = b[start + 10].toInt() and 0xFF,
                    )
                }
                Tag.RESOLUTION -> {
                    fixed(length, 9)
                    val units = IppResolution.Units.fromCode(b[start + 8].toInt() and 0xFF)
                        ?: return IppUnknown(tag, b.copyOfRange(start, end)).also { pos = end }
                    IppResolution(i32At(start), i32At(start + 4), units)
                }
                Tag.RANGE_OF_INTEGER -> {
                    fixed(length, 8)
                    IppRange(i32At(start), i32At(start + 4))
                }
                Tag.TEXT_WITH_LANGUAGE, Tag.NAME_WITH_LANGUAGE -> readWithLanguage(tag, start, end)
                Tag.TEXT -> str(IppString.Kind.TEXT, start, end)
                Tag.NAME -> str(IppString.Kind.NAME, start, end)
                Tag.KEYWORD -> str(IppString.Kind.KEYWORD, start, end)
                Tag.URI -> str(IppString.Kind.URI, start, end)
                Tag.URI_SCHEME -> str(IppString.Kind.URI_SCHEME, start, end)
                Tag.CHARSET -> str(IppString.Kind.CHARSET, start, end)
                Tag.NATURAL_LANGUAGE -> str(IppString.Kind.NATURAL_LANGUAGE, start, end)
                Tag.MIME_MEDIA_TYPE -> str(IppString.Kind.MIME_MEDIA_TYPE, start, end)
                Tag.UNSUPPORTED, Tag.UNKNOWN, Tag.NO_VALUE, Tag.NOT_SETTABLE, Tag.DELETE_ATTRIBUTE, Tag.ADMIN_DEFINE -> IppOutOfBand(tag)
                else -> IppUnknown(tag, b.copyOfRange(start, end))
            }
            pos = end
            return value
        }

        private fun readWithLanguage(tag: Int, start: Int, end: Int): IppValue {
            var p = start
            fun u16At(at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
            if (p + 2 > end) fail("truncated text-with-language")
            val langLen = u16At(p).also { p += 2 }
            if (p + langLen + 2 > end) fail("truncated text-with-language")
            val lang = String(b, p, langLen, Charsets.US_ASCII).also { p += langLen }
            val textLen = u16At(p).also { p += 2 }
            if (p + textLen > end) fail("truncated text-with-language")
            val text = String(b, p, textLen, Charsets.UTF_8)
            val kind = if (tag == Tag.TEXT_WITH_LANGUAGE) IppString.Kind.TEXT else IppString.Kind.NAME
            return IppString(kind, text, lang)
        }

        private fun readCollection(depth: Int): IppCollection {
            if (depth > MAX_COLLECTION_DEPTH) fail("collections nested too deeply")
            val members = mutableListOf<IppAttribute>()
            while (true) {
                val tag = u8()
                val nameLen = u16()
                if (nameLen != 0) fail("collection entry with a non-empty name")
                val len = u16()
                when (tag) {
                    Tag.END_COLLECTION -> {
                        if (len != 0) fail("endCollection with a value")
                        return IppCollection(members)
                    }
                    Tag.MEMBER_ATTR_NAME -> {
                        if (pos + len > b.size) fail("member name runs past the end of the message")
                        val memberName = readString(len)
                        if (++attributeCount > MAX_ATTRIBUTES) fail("too many attributes")
                        val values = mutableListOf<IppValue>()
                        // One or more values follow, each with an empty name, until the next member or the end.
                        while (true) {
                            val next = peekU8()
                            if (next == Tag.MEMBER_ATTR_NAME || next == Tag.END_COLLECTION) break
                            pos++
                            if (u16() != 0) fail("collection member value with a non-empty name")
                            val vlen = u16()
                            values += readValue(next, vlen, depth)
                        }
                        if (values.isEmpty()) fail("collection member '$memberName' has no value")
                        members += IppAttribute(memberName, values)
                    }
                    else -> fail("unexpected tag 0x${tag.toString(16)} inside a collection")
                }
            }
        }

        private fun str(kind: IppString.Kind, start: Int, end: Int) = IppString(kind, String(b, start, end - start, Charsets.UTF_8))

        private fun fixed(actual: Int, expected: Int) {
            if (actual != expected) fail("value length $actual where $expected was expected")
        }

        private fun readString(length: Int): String {
            if (pos + length > b.size) fail("string runs past the end of the message")
            return String(b, pos, length, Charsets.UTF_8).also { pos += length }
        }

        private fun skip(n: Int) {
            if (pos + n > b.size) fail("value runs past the end of the message")
            pos += n
        }

        private fun u8(): Int {
            if (pos >= b.size) fail("unexpected end of message")
            return b[pos++].toInt() and 0xFF
        }

        private fun peekU8(): Int {
            if (pos >= b.size) fail("unexpected end of message (no end-of-attributes tag)")
            return b[pos].toInt() and 0xFF
        }

        private fun u16(): Int = (u8() shl 8) or u8()

        private fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

        private fun i32At(at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

        private fun fail(message: String): Nothing = throw IppParseException(message, pos)
    }
}
