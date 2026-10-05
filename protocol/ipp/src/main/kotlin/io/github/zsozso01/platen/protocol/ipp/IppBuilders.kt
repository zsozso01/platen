package io.github.zsozso01.platen.protocol.ipp

/** DSL for building the attributes of one group or one collection. */
public class IppAttributesBuilder {
    private val attributes = mutableListOf<IppAttribute>()

    public fun add(attribute: IppAttribute) {
        attributes += attribute
    }

    public fun keyword(name: String, value: String): Unit = add(name, IppString(IppString.Kind.KEYWORD, value))

    public fun keywords(name: String, values: List<String>): Unit = addAll(name, values.map { IppString(IppString.Kind.KEYWORD, it) })

    public fun name(name: String, value: String): Unit = add(name, IppString(IppString.Kind.NAME, value))

    public fun text(name: String, value: String): Unit = add(name, IppString(IppString.Kind.TEXT, value))

    public fun uri(name: String, value: String): Unit = add(name, IppString(IppString.Kind.URI, value))

    public fun charset(name: String, value: String): Unit = add(name, IppString(IppString.Kind.CHARSET, value))

    public fun naturalLanguage(name: String, value: String): Unit = add(name, IppString(IppString.Kind.NATURAL_LANGUAGE, value))

    public fun mimeMediaType(name: String, value: String): Unit = add(name, IppString(IppString.Kind.MIME_MEDIA_TYPE, value))

    public fun integer(name: String, value: Int): Unit = add(name, IppInteger(value))

    public fun enum(name: String, value: Int): Unit = add(name, IppEnum(value))

    public fun boolean(name: String, value: Boolean): Unit = add(name, IppBoolean(value))

    public fun range(name: String, lower: Int, upper: Int): Unit = add(name, IppRange(lower, upper))

    public fun ranges(name: String, values: List<IppRange>): Unit = addAll(name, values)

    public fun resolution(name: String, crossFeed: Int, feed: Int, units: IppResolution.Units = IppResolution.Units.DOTS_PER_INCH): Unit =
        add(name, IppResolution(crossFeed, feed, units))

    /** Adds a collection-valued attribute, e.g. `media-col`. */
    public fun collection(name: String, block: IppAttributesBuilder.() -> Unit): Unit =
        add(name, IppCollection(IppAttributesBuilder().apply(block).build()))

    public fun add(name: String, value: IppValue) {
        attributes += IppAttribute(name, value)
    }

    public fun addAll(name: String, values: List<IppValue>) {
        require(values.isNotEmpty()) { "Attribute '$name' needs at least one value" }
        attributes += IppAttribute(name, values)
    }

    public fun build(): List<IppAttribute> = attributes.toList()
}

/** DSL for building an [IppMessage]. */
public class IppMessageBuilder internal constructor(
    private val version: IppVersion,
    private val code: Int,
    private val requestId: Int,
) {
    private val groups = mutableListOf<IppGroup>()

    public fun group(tag: GroupTag, block: IppAttributesBuilder.() -> Unit) {
        groups += IppGroup(tag, IppAttributesBuilder().apply(block).build())
    }

    internal fun build(): IppMessage = IppMessage(version, code, requestId, groups.toList())
}

/** Builds an IPP request. The caller supplies the operation attributes group, which must start with charset and language. */
public fun ippMessage(
    code: Int,
    requestId: Int = 1,
    version: IppVersion = IppVersion.V2_0,
    block: IppMessageBuilder.() -> Unit,
): IppMessage = IppMessageBuilder(version, code, requestId).apply(block).build()
