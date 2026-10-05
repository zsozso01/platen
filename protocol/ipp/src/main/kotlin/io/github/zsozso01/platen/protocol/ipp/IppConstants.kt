package io.github.zsozso01.platen.protocol.ipp

/** IPP protocol version (`major.minor`) carried in the first two bytes of every message. */
public data class IppVersion(val major: Int, val minor: Int) : Comparable<IppVersion> {
    override fun compareTo(other: IppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = "$major.$minor"

    public companion object {
        public val V1_1: IppVersion = IppVersion(1, 1)
        public val V2_0: IppVersion = IppVersion(2, 0)
        public val V2_1: IppVersion = IppVersion(2, 1)
        public val V2_2: IppVersion = IppVersion(2, 2)

        /** Parses the `ipp-versions-supported` keyword form, e.g. `"2.0"`. */
        public fun parse(text: String): IppVersion? {
            val parts = text.trim().split('.')
            if (parts.size != 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            return IppVersion(major, minor)
        }
    }
}

/** Group delimiter tags (RFC 8010 section 3.5.1). */
@JvmInline
public value class GroupTag(public val code: Int) {
    override fun toString(): String = when (this) {
        OPERATION_ATTRIBUTES -> "operation-attributes"
        JOB_ATTRIBUTES -> "job-attributes"
        PRINTER_ATTRIBUTES -> "printer-attributes"
        UNSUPPORTED_ATTRIBUTES -> "unsupported-attributes"
        DOCUMENT_ATTRIBUTES -> "document-attributes"
        else -> "group(0x${code.toString(16)})"
    }

    public companion object {
        public val OPERATION_ATTRIBUTES: GroupTag = GroupTag(0x01)
        public val JOB_ATTRIBUTES: GroupTag = GroupTag(0x02)
        public val PRINTER_ATTRIBUTES: GroupTag = GroupTag(0x04)
        public val UNSUPPORTED_ATTRIBUTES: GroupTag = GroupTag(0x05)
        public val SUBSCRIPTION_ATTRIBUTES: GroupTag = GroupTag(0x06)
        public val EVENT_NOTIFICATION_ATTRIBUTES: GroupTag = GroupTag(0x07)
        public val RESOURCE_ATTRIBUTES: GroupTag = GroupTag(0x08)
        public val DOCUMENT_ATTRIBUTES: GroupTag = GroupTag(0x09)
        public val SYSTEM_ATTRIBUTES: GroupTag = GroupTag(0x0A)
    }
}

/** Raw tag bytes (RFC 8010 section 3.5.2). Internal: callers work with [IppValue] types instead. */
internal object Tag {
    const val END_OF_ATTRIBUTES = 0x03

    // out-of-band
    const val UNSUPPORTED = 0x10
    const val UNKNOWN = 0x12
    const val NO_VALUE = 0x13
    const val NOT_SETTABLE = 0x15
    const val DELETE_ATTRIBUTE = 0x16
    const val ADMIN_DEFINE = 0x17

    // integer family
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23

    // octetString family
    const val OCTET_STRING = 0x30
    const val DATE_TIME = 0x31
    const val RESOLUTION = 0x32
    const val RANGE_OF_INTEGER = 0x33
    const val BEG_COLLECTION = 0x34
    const val TEXT_WITH_LANGUAGE = 0x35
    const val NAME_WITH_LANGUAGE = 0x36
    const val END_COLLECTION = 0x37

    // character-string family
    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val URI_SCHEME = 0x46
    const val CHARSET = 0x47
    const val NATURAL_LANGUAGE = 0x48
    const val MIME_MEDIA_TYPE = 0x49
    const val MEMBER_ATTR_NAME = 0x4A

    /** Tags below 0x10 are delimiters, not values. */
    fun isDelimiter(tag: Int): Boolean = tag in 0x00..0x0F
}

/** Operation ids (RFC 8011 section 5.2, plus PWG 5100.x additions). */
public object IppOperation {
    public const val PRINT_JOB: Int = 0x0002
    public const val PRINT_URI: Int = 0x0003
    public const val VALIDATE_JOB: Int = 0x0004
    public const val CREATE_JOB: Int = 0x0005
    public const val SEND_DOCUMENT: Int = 0x0006
    public const val SEND_URI: Int = 0x0007
    public const val CANCEL_JOB: Int = 0x0008
    public const val GET_JOB_ATTRIBUTES: Int = 0x0009
    public const val GET_JOBS: Int = 0x000A
    public const val GET_PRINTER_ATTRIBUTES: Int = 0x000B
    public const val HOLD_JOB: Int = 0x000C
    public const val RELEASE_JOB: Int = 0x000D
    public const val RESTART_JOB: Int = 0x000E
    public const val PAUSE_PRINTER: Int = 0x0010
    public const val RESUME_PRINTER: Int = 0x0011
    public const val PURGE_JOBS: Int = 0x0012
    public const val CANCEL_MY_JOBS: Int = 0x0039
    public const val CLOSE_JOB: Int = 0x003B
    public const val IDENTIFY_PRINTER: Int = 0x003C

    public fun name(code: Int): String = when (code) {
        PRINT_JOB -> "Print-Job"
        PRINT_URI -> "Print-URI"
        VALIDATE_JOB -> "Validate-Job"
        CREATE_JOB -> "Create-Job"
        SEND_DOCUMENT -> "Send-Document"
        SEND_URI -> "Send-URI"
        CANCEL_JOB -> "Cancel-Job"
        GET_JOB_ATTRIBUTES -> "Get-Job-Attributes"
        GET_JOBS -> "Get-Jobs"
        GET_PRINTER_ATTRIBUTES -> "Get-Printer-Attributes"
        HOLD_JOB -> "Hold-Job"
        RELEASE_JOB -> "Release-Job"
        RESTART_JOB -> "Restart-Job"
        PAUSE_PRINTER -> "Pause-Printer"
        RESUME_PRINTER -> "Resume-Printer"
        PURGE_JOBS -> "Purge-Jobs"
        CANCEL_MY_JOBS -> "Cancel-My-Jobs"
        CLOSE_JOB -> "Close-Job"
        IDENTIFY_PRINTER -> "Identify-Printer"
        else -> "operation(0x${code.toString(16)})"
    }
}

/** Status codes (RFC 8011 section 5.4). */
public object IppStatus {
    public const val SUCCESSFUL_OK: Int = 0x0000
    public const val SUCCESSFUL_OK_IGNORED_OR_SUBSTITUTED: Int = 0x0001
    public const val SUCCESSFUL_OK_CONFLICTING_ATTRIBUTES: Int = 0x0002

    public const val CLIENT_ERROR_BAD_REQUEST: Int = 0x0400
    public const val CLIENT_ERROR_FORBIDDEN: Int = 0x0401
    public const val CLIENT_ERROR_NOT_AUTHENTICATED: Int = 0x0402
    public const val CLIENT_ERROR_NOT_AUTHORIZED: Int = 0x0403
    public const val CLIENT_ERROR_NOT_POSSIBLE: Int = 0x0404
    public const val CLIENT_ERROR_TIMEOUT: Int = 0x0405
    public const val CLIENT_ERROR_NOT_FOUND: Int = 0x0406
    public const val CLIENT_ERROR_GONE: Int = 0x0407
    public const val CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE: Int = 0x0408
    public const val CLIENT_ERROR_REQUEST_VALUE_TOO_LONG: Int = 0x0409
    public const val CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED: Int = 0x040A
    public const val CLIENT_ERROR_ATTRIBUTES_OR_VALUES_NOT_SUPPORTED: Int = 0x040B
    public const val CLIENT_ERROR_URI_SCHEME_NOT_SUPPORTED: Int = 0x040C
    public const val CLIENT_ERROR_CHARSET_NOT_SUPPORTED: Int = 0x040D
    public const val CLIENT_ERROR_CONFLICTING_ATTRIBUTES: Int = 0x040E
    public const val CLIENT_ERROR_COMPRESSION_NOT_SUPPORTED: Int = 0x040F
    public const val CLIENT_ERROR_COMPRESSION_ERROR: Int = 0x0410
    public const val CLIENT_ERROR_DOCUMENT_FORMAT_ERROR: Int = 0x0411
    public const val CLIENT_ERROR_DOCUMENT_ACCESS_ERROR: Int = 0x0412

    public const val SERVER_ERROR_INTERNAL_ERROR: Int = 0x0500
    public const val SERVER_ERROR_OPERATION_NOT_SUPPORTED: Int = 0x0501
    public const val SERVER_ERROR_SERVICE_UNAVAILABLE: Int = 0x0502
    public const val SERVER_ERROR_VERSION_NOT_SUPPORTED: Int = 0x0503
    public const val SERVER_ERROR_DEVICE_ERROR: Int = 0x0504
    public const val SERVER_ERROR_TEMPORARY_ERROR: Int = 0x0505
    public const val SERVER_ERROR_NOT_ACCEPTING_JOBS: Int = 0x0506
    public const val SERVER_ERROR_BUSY: Int = 0x0507
    public const val SERVER_ERROR_JOB_CANCELED: Int = 0x0508
    public const val SERVER_ERROR_MULTIPLE_DOCUMENT_JOBS_NOT_SUPPORTED: Int = 0x0509

    /** 0x0000-0x00FF are successes (including "ok but some attributes were changed"). */
    public fun isSuccess(code: Int): Boolean = code in 0x0000..0x00FF

    public fun name(code: Int): String = when (code) {
        SUCCESSFUL_OK -> "successful-ok"
        SUCCESSFUL_OK_IGNORED_OR_SUBSTITUTED -> "successful-ok-ignored-or-substituted-attributes"
        SUCCESSFUL_OK_CONFLICTING_ATTRIBUTES -> "successful-ok-conflicting-attributes"
        CLIENT_ERROR_BAD_REQUEST -> "client-error-bad-request"
        CLIENT_ERROR_FORBIDDEN -> "client-error-forbidden"
        CLIENT_ERROR_NOT_AUTHENTICATED -> "client-error-not-authenticated"
        CLIENT_ERROR_NOT_AUTHORIZED -> "client-error-not-authorized"
        CLIENT_ERROR_NOT_POSSIBLE -> "client-error-not-possible"
        CLIENT_ERROR_TIMEOUT -> "client-error-timeout"
        CLIENT_ERROR_NOT_FOUND -> "client-error-not-found"
        CLIENT_ERROR_GONE -> "client-error-gone"
        CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE -> "client-error-request-entity-too-large"
        CLIENT_ERROR_REQUEST_VALUE_TOO_LONG -> "client-error-request-value-too-long"
        CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED -> "client-error-document-format-not-supported"
        CLIENT_ERROR_ATTRIBUTES_OR_VALUES_NOT_SUPPORTED -> "client-error-attributes-or-values-not-supported"
        CLIENT_ERROR_URI_SCHEME_NOT_SUPPORTED -> "client-error-uri-scheme-not-supported"
        CLIENT_ERROR_CHARSET_NOT_SUPPORTED -> "client-error-charset-not-supported"
        CLIENT_ERROR_CONFLICTING_ATTRIBUTES -> "client-error-conflicting-attributes"
        CLIENT_ERROR_COMPRESSION_NOT_SUPPORTED -> "client-error-compression-not-supported"
        CLIENT_ERROR_COMPRESSION_ERROR -> "client-error-compression-error"
        CLIENT_ERROR_DOCUMENT_FORMAT_ERROR -> "client-error-document-format-error"
        CLIENT_ERROR_DOCUMENT_ACCESS_ERROR -> "client-error-document-access-error"
        SERVER_ERROR_INTERNAL_ERROR -> "server-error-internal-error"
        SERVER_ERROR_OPERATION_NOT_SUPPORTED -> "server-error-operation-not-supported"
        SERVER_ERROR_SERVICE_UNAVAILABLE -> "server-error-service-unavailable"
        SERVER_ERROR_VERSION_NOT_SUPPORTED -> "server-error-version-not-supported"
        SERVER_ERROR_DEVICE_ERROR -> "server-error-device-error"
        SERVER_ERROR_TEMPORARY_ERROR -> "server-error-temporary-error"
        SERVER_ERROR_NOT_ACCEPTING_JOBS -> "server-error-not-accepting-jobs"
        SERVER_ERROR_BUSY -> "server-error-busy"
        SERVER_ERROR_JOB_CANCELED -> "server-error-job-canceled"
        SERVER_ERROR_MULTIPLE_DOCUMENT_JOBS_NOT_SUPPORTED -> "server-error-multiple-document-jobs-not-supported"
        else -> "status(0x${code.toString(16)})"
    }
}

/** `job-state` enum values (RFC 8011 section 5.3.7). */
public object IppJobState {
    public const val PENDING: Int = 3
    public const val PENDING_HELD: Int = 4
    public const val PROCESSING: Int = 5
    public const val PROCESSING_STOPPED: Int = 6
    public const val CANCELED: Int = 7
    public const val ABORTED: Int = 8
    public const val COMPLETED: Int = 9

    public fun isTerminal(state: Int): Boolean = state == CANCELED || state == ABORTED || state == COMPLETED

    public fun name(state: Int): String = when (state) {
        PENDING -> "pending"
        PENDING_HELD -> "pending-held"
        PROCESSING -> "processing"
        PROCESSING_STOPPED -> "processing-stopped"
        CANCELED -> "canceled"
        ABORTED -> "aborted"
        COMPLETED -> "completed"
        else -> "job-state($state)"
    }
}

/** `printer-state` enum values (RFC 8011 section 5.4.11). */
public object IppPrinterState {
    public const val IDLE: Int = 3
    public const val PROCESSING: Int = 4
    public const val STOPPED: Int = 5

    public fun name(state: Int): String = when (state) {
        IDLE -> "idle"
        PROCESSING -> "processing"
        STOPPED -> "stopped"
        else -> "printer-state($state)"
    }
}
