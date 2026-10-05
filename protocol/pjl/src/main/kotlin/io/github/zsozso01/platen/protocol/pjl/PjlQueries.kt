package io.github.zsozso01.platen.protocol.pjl

/** Ready-made PJL queries. Responses are parsed with [PjlResponseParser]. */
public object PjlQueries {
    /** Marker echoed by the printer when it has processed everything before it. */
    public const val DONE_MARKER: String = "PLATEN-DONE"

    /**
     * Builds a query job: `UEL`, one command per line, an `@PJL ECHO` marker, `UEL`. Wait for the
     * echoed [DONE_MARKER] instead of a fixed timeout: printers that do not know one of the commands
     * stay silent for it but still echo the marker.
     */
    public fun query(vararg commands: String): ByteArray = buildString {
        append(Pjl.UEL)
        for (c in commands) append("@PJL ").append(c).append(Pjl.EOL)
        append("@PJL ECHO ").append(DONE_MARKER).append(Pjl.EOL)
        append(Pjl.UEL)
    }.toByteArray(Charsets.US_ASCII)

    public const val INFO_ID: String = "INFO ID"
    public const val INFO_STATUS: String = "INFO STATUS"
    public const val INFO_CONFIG: String = "INFO CONFIG"
    public const val INFO_VARIABLES: String = "INFO VARIABLES"
    public const val INFO_PAGECOUNT: String = "INFO PAGECOUNT"
    public const val INFO_USTATUS: String = "INFO USTATUS"

    /** Ask for unsolicited status: device status changes and job start/end events. */
    public fun enableUnsolicitedStatus(): ByteArray =
        query("USTATUS DEVICE=ON", "USTATUS JOB=ON")

    public fun disableUnsolicitedStatus(): ByteArray =
        query("USTATUS DEVICE=OFF", "USTATUS JOB=OFF")
}
