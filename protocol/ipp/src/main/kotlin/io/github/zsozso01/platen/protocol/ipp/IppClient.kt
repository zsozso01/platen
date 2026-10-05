package io.github.zsozso01.platen.protocol.ipp

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The printer answered the IPP request with a failure status. */
public class IppOperationException(
    public val operation: Int,
    public val status: Int,
    public val statusMessage: String?,
    public val response: IppMessage,
) : IOException(
    "${IppOperation.name(operation)} failed: ${IppStatus.name(status)}" + (statusMessage?.let { " ($it)" } ?: ""),
) {
    /** Attributes the printer rejected, from the `unsupported-attributes` group. */
    public val unsupportedAttributes: List<IppAttribute>
        get() = response.group(GroupTag.UNSUPPORTED_ATTRIBUTES)?.attributes.orEmpty()
}

/** A successful response plus convenience accessors. */
public class IppResponse(public val message: IppMessage) {
    /** True when the printer accepted the request but changed or ignored some attributes. */
    public val hadSubstitutions: Boolean
        get() = message.code == IppStatus.SUCCESSFUL_OK_IGNORED_OR_SUBSTITUTED || message.code == IppStatus.SUCCESSFUL_OK_CONFLICTING_ATTRIBUTES

    public val unsupportedAttributes: List<IppAttribute>
        get() = message.group(GroupTag.UNSUPPORTED_ATTRIBUTES)?.attributes.orEmpty()
}

/** Result of a `Print-Job` / `Create-Job`. */
public class IppJob(
    public val id: Int,
    public val uri: String?,
    public val state: Int?,
    public val stateReasons: List<String>,
    public val response: IppResponse,
)

/** Snapshot from `Get-Job-Attributes`. */
public class IppJobStatus(
    public val id: Int,
    public val state: Int?,
    public val stateReasons: List<String>,
    public val stateMessage: String?,
    public val impressionsCompleted: Int?,
    public val mediaSheetsCompleted: Int?,
    public val impressions: Int?,
    public val attributes: IppGroup?,
) {
    public val isTerminal: Boolean get() = state != null && IppJobState.isTerminal(state)
}

/**
 * Blocking IPP client for one printer endpoint. Call from a background thread/dispatcher; cancel by
 * closing the underlying connection. One request, one connection.
 */
public class IppClient(
    private val transport: IppHttpTransport,
    /** The `printer-uri` operation attribute, e.g. `ipp://printer.local:631/ipp/print`. */
    public val printerUri: String,
    private val userName: String = "platen",
    private var version: IppVersion = IppVersion.V2_0,
) {
    private val requestIds = AtomicInteger(1)

    /** Protocol version in use. Drops to 1.1 automatically if the printer rejects 2.0. */
    public val protocolVersion: IppVersion get() = version

    /**
     * `Get-Printer-Attributes`. [requested] null asks for the printer's defaults;
     * `listOf("all")` and `media-col-database` get the full capability picture.
     */
    @Throws(IOException::class)
    public fun getPrinterAttributes(requested: List<String>? = DEFAULT_CAPABILITY_ATTRIBUTES): IppPrinterAttributes {
        val response = try {
            execute(IppOperation.GET_PRINTER_ATTRIBUTES) {
                operation { requested?.let { keywords("requested-attributes", it) } }
            }
        } catch (e: IppOperationException) {
            // Some printers choke on an attribute name in the list. Retry once asking for everything.
            if (requested != null && e.status == IppStatus.CLIENT_ERROR_BAD_REQUEST) {
                execute(IppOperation.GET_PRINTER_ATTRIBUTES) { operation { keywords("requested-attributes", listOf("all")) } }
            } else {
                throw e
            }
        }
        val group = response.message.group(GroupTag.PRINTER_ATTRIBUTES)
            ?: throw IppParseException("Get-Printer-Attributes response has no printer-attributes group", 0)
        return IppPrinterAttributes(group)
    }

    /** `Validate-Job`: asks the printer whether it would accept this job, without sending a document. */
    @Throws(IOException::class)
    public fun validateJob(documentFormat: String?, jobName: String?, jobTemplate: List<IppAttribute>): IppResponse =
        execute(IppOperation.VALIDATE_JOB) {
            operation {
                jobName?.let { name("job-name", it) }
                documentFormat?.let { mimeMediaType("document-format", it) }
            }
            if (jobTemplate.isNotEmpty()) group(GroupTag.JOB_ATTRIBUTES) { jobTemplate.forEach(::add) }
        }

    /** `Print-Job`: creates the job and streams [document] in one request. */
    @Throws(IOException::class)
    public fun printJob(
        documentFormat: String,
        jobName: String,
        jobTemplate: List<IppAttribute>,
        document: IppDocument,
        onBytesSent: (Long) -> Unit = {},
    ): IppJob {
        val response = execute(IppOperation.PRINT_JOB, document, onBytesSent) {
            operation {
                name("job-name", jobName)
                mimeMediaType("document-format", documentFormat)
            }
            if (jobTemplate.isNotEmpty()) group(GroupTag.JOB_ATTRIBUTES) { jobTemplate.forEach(::add) }
        }
        return parseJob(response)
    }

    @Throws(IOException::class)
    public fun getJobAttributes(jobId: Int): IppJobStatus {
        val response = execute(IppOperation.GET_JOB_ATTRIBUTES) {
            operation { integer("job-id", jobId) }
        }
        val group = response.message.group(GroupTag.JOB_ATTRIBUTES)
        fun int(name: String) = group?.get(name)?.int()
        return IppJobStatus(
            id = jobId,
            state = int("job-state"),
            stateReasons = group?.get("job-state-reasons")?.strings().orEmpty(),
            stateMessage = group?.get("job-state-message")?.string(),
            impressionsCompleted = int("job-impressions-completed"),
            mediaSheetsCompleted = int("job-media-sheets-completed"),
            impressions = int("job-impressions"),
            attributes = group,
        )
    }

    @Throws(IOException::class)
    public fun cancelJob(jobId: Int) {
        execute(IppOperation.CANCEL_JOB) { operation { integer("job-id", jobId) } }
    }

    /** Sends a raw request built with the IPP DSL: for diagnostics and experiments. */
    @Throws(IOException::class)
    public fun execute(
        operation: Int,
        document: IppDocument? = null,
        onBytesSent: (Long) -> Unit = {},
        build: RequestScope.() -> Unit,
    ): IppResponse {
        val response = try {
            send(operation, document, onBytesSent, build)
        } catch (e: IppOperationException) {
            if (e.status == IppStatus.SERVER_ERROR_VERSION_NOT_SUPPORTED && version > IppVersion.V1_1) {
                version = IppVersion.V1_1
                send(operation, document, onBytesSent, build)
            } else {
                throw e
            }
        }
        return response
    }

    private fun send(operation: Int, document: IppDocument?, onBytesSent: (Long) -> Unit, build: RequestScope.() -> Unit): IppResponse {
        val scope = RequestScope().apply(build)
        val message = ippMessage(operation, requestIds.getAndIncrement(), version) {
            group(GroupTag.OPERATION_ATTRIBUTES) {
                // RFC 8011 3.1.4.1: charset and natural language must be the first two attributes.
                charset("attributes-charset", "utf-8")
                naturalLanguage("attributes-natural-language", "en")
                uri("printer-uri", printerUri)
                name("requesting-user-name", userName)
                scope.operationAttributes.forEach(::add)
            }
            scope.extraGroups.forEach { (tag, attrs) -> group(tag) { attrs.forEach(::add) } }
        }
        val http = transport.post(IppEncoder.encode(message), document, onBytesSent)
        val decoded = IppDecoder.decode(http.body).message
        if (!decoded.isSuccess) {
            throw IppOperationException(operation, decoded.code, decoded.statusMessage, decoded)
        }
        return IppResponse(decoded)
    }

    private fun parseJob(response: IppResponse): IppJob {
        val group = response.message.group(GroupTag.JOB_ATTRIBUTES)
            ?: throw IppParseException("Print-Job response has no job-attributes group", 0)
        val id = group["job-id"]?.int() ?: throw IppParseException("Print-Job response has no job-id", 0)
        return IppJob(
            id = id,
            uri = group["job-uri"]?.string(),
            state = group["job-state"]?.int(),
            stateReasons = group["job-state-reasons"]?.strings().orEmpty(),
            response = response,
        )
    }

    /** Collects the operation-specific parts of a request. */
    public class RequestScope internal constructor() {
        internal val operationAttributes = mutableListOf<IppAttribute>()
        internal val extraGroups = mutableListOf<Pair<GroupTag, List<IppAttribute>>>()

        /** Adds operation attributes after the standard ones. */
        public fun operation(block: IppAttributesBuilder.() -> Unit) {
            operationAttributes += IppAttributesBuilder().apply(block).build()
        }

        public fun group(tag: GroupTag, block: IppAttributesBuilder.() -> Unit) {
            extraGroups += tag to IppAttributesBuilder().apply(block).build()
        }
    }

    public companion object {
        /**
         * What Platen asks for to learn a printer's capabilities. `media-col-database` is requested
         * explicitly because "all" does not include it on many printers.
         */
        public val DEFAULT_CAPABILITY_ATTRIBUTES: List<String> = listOf("all", "media-col-database")
    }
}
