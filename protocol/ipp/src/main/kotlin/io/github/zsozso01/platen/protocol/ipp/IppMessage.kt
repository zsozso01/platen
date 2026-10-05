package io.github.zsozso01.platen.protocol.ipp

/** A named attribute with one or more values. */
public data class IppAttribute(val name: String, val values: List<IppValue>) {
    public constructor(name: String, value: IppValue) : this(name, listOf(value))

    public val first: IppValue? get() = values.firstOrNull()

    /** First value as text, for any string-like syntax; null if absent or not a string. */
    public fun string(): String? = (first as? IppString)?.value

    public fun strings(): List<String> = values.mapNotNull { (it as? IppString)?.value }

    /** First value as an int, for `integer` and `enum`. */
    public fun int(): Int? = when (val v = first) {
        is IppInteger -> v.value
        is IppEnum -> v.value
        else -> null
    }

    public fun ints(): List<Int> = values.mapNotNull {
        when (it) {
            is IppInteger -> it.value
            is IppEnum -> it.value
            else -> null
        }
    }

    public fun boolean(): Boolean? = (first as? IppBoolean)?.value

    public fun resolutions(): List<IppResolution> = values.filterIsInstance<IppResolution>()

    public fun ranges(): List<IppRange> = values.filterIsInstance<IppRange>()

    public fun collections(): List<IppCollection> = values.filterIsInstance<IppCollection>()

    /** True for `unsupported`, `no-value`, ... placeholders. */
    public val isOutOfBand: Boolean get() = values.isNotEmpty() && values.all { it is IppOutOfBand }

    override fun toString(): String = "$name=${values.joinToString(",")}"
}

/** An attribute group: a delimiter tag plus its attributes in wire order. */
public data class IppGroup(val tag: GroupTag, val attributes: List<IppAttribute>) {
    public operator fun get(name: String): IppAttribute? = attributes.firstOrNull { it.name == name }
}

/**
 * A complete IPP request or response, without any trailing document data.
 *
 * For a request [code] is an operation id ([IppOperation]); for a response it is a status code
 * ([IppStatus]).
 */
public class IppMessage(
    public val version: IppVersion,
    public val code: Int,
    public val requestId: Int,
    public val groups: List<IppGroup>,
) {
    public fun group(tag: GroupTag): IppGroup? = groups.firstOrNull { it.tag == tag }

    public fun groups(tag: GroupTag): List<IppGroup> = groups.filter { it.tag == tag }

    /** Looks an attribute up in the first group with [tag]. */
    public fun attribute(tag: GroupTag, name: String): IppAttribute? = group(tag)?.get(name)

    /** The response's `status-message` operation attribute, if the printer sent one. */
    public val statusMessage: String?
        get() = attribute(GroupTag.OPERATION_ATTRIBUTES, "status-message")?.string()

    public val isSuccess: Boolean get() = IppStatus.isSuccess(code)

    override fun toString(): String = "IppMessage(v$version, code=0x${code.toString(16)}, id=$requestId, groups=${groups.map { it.tag }})"
}
