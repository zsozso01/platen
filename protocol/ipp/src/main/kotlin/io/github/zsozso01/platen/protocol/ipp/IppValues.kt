package io.github.zsozso01.platen.protocol.ipp

/** One value of an IPP attribute. Attributes hold a list of these (IPP's "1setOf"). */
public sealed interface IppValue

public data class IppInteger(val value: Int) : IppValue

/** An `enum` syntax value, e.g. `printer-state` = 3. Kept distinct from [IppInteger] because the tag differs. */
public data class IppEnum(val value: Int) : IppValue

public data class IppBoolean(val value: Boolean) : IppValue

public class IppOctets(bytes: ByteArray) : IppValue {
    public val bytes: ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is IppOctets && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "IppOctets(${bytes.size} bytes)"
}

/** RFC 2579 DateAndTime as sent on the wire. */
public data class IppDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val decisecond: Int,
    /** `'+'` or `'-'`. */
    val utcSign: Char,
    val utcHours: Int,
    val utcMinutes: Int,
) : IppValue

public data class IppResolution(val crossFeed: Int, val feed: Int, val units: Units) : IppValue {
    public enum class Units(public val code: Int) {
        DOTS_PER_INCH(3),
        DOTS_PER_CM(4),
        ;

        public companion object {
            public fun fromCode(code: Int): Units? = entries.firstOrNull { it.code == code }
        }
    }

    /** True when the resolution is square and in dpi (the common case: 300x300, 600x600). */
    public val dpiOrNull: Int?
        get() = if (units == Units.DOTS_PER_INCH && crossFeed == feed) crossFeed else null

    override fun toString(): String = "${crossFeed}x$feed${if (units == Units.DOTS_PER_INCH) "dpi" else "dpcm"}"
}

public data class IppRange(val lower: Int, val upper: Int) : IppValue {
    public operator fun contains(value: Int): Boolean = value in lower..upper

    override fun toString(): String = "$lower-$upper"
}

/**
 * Any of IPP's character-string syntaxes. [kind] says which one, because the wire tag matters
 * (a `keyword` is not a `name` even though both are text).
 */
public data class IppString(val kind: Kind, val value: String, val language: String? = null) : IppValue {
    public enum class Kind(internal val tag: Int, internal val tagWithLanguage: Int? = null) {
        TEXT(Tag.TEXT, Tag.TEXT_WITH_LANGUAGE),
        NAME(Tag.NAME, Tag.NAME_WITH_LANGUAGE),
        KEYWORD(Tag.KEYWORD),
        URI(Tag.URI),
        URI_SCHEME(Tag.URI_SCHEME),
        CHARSET(Tag.CHARSET),
        NATURAL_LANGUAGE(Tag.NATURAL_LANGUAGE),
        MIME_MEDIA_TYPE(Tag.MIME_MEDIA_TYPE),
    }

    override fun toString(): String = value
}

/** A `collection`, e.g. `media-col`: an ordered list of named member attributes. */
public data class IppCollection(val members: List<IppAttribute>) : IppValue {
    public operator fun get(name: String): IppAttribute? = members.firstOrNull { it.name == name }
}

/** `unsupported`, `unknown`, `no-value`, ... The attribute exists but has no value. */
public data class IppOutOfBand(val tag: Int) : IppValue {
    public val name: String
        get() = when (tag) {
            Tag.UNSUPPORTED -> "unsupported"
            Tag.UNKNOWN -> "unknown"
            Tag.NO_VALUE -> "no-value"
            Tag.NOT_SETTABLE -> "not-settable"
            Tag.DELETE_ATTRIBUTE -> "delete-attribute"
            Tag.ADMIN_DEFINE -> "admin-define"
            else -> "out-of-band(0x${tag.toString(16)})"
        }
}

/** A value with a tag this library does not know. Kept so unknown vendor syntaxes survive a round trip. */
public class IppUnknown(public val tag: Int, bytes: ByteArray) : IppValue {
    public val bytes: ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is IppUnknown && other.tag == tag && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * tag + bytes.contentHashCode()

    override fun toString(): String = "IppUnknown(tag=0x${tag.toString(16)}, ${bytes.size} bytes)"
}
