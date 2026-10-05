package io.github.zsozso01.platen.protocol.pjl

/** Coarse meaning of a PJL status code, from its numeric range (see the PJL Technical Reference). */
public enum class PjlStatusClass {
    /** 10xxx-19xxx: ready, printing, warming up, paper/tray background status. Not a problem. */
    INFORMATIONAL,

    /** 20xxx-29xxx: the printer rejected or could not parse part of the job's PJL. */
    PJL_ERROR,

    /** 30xxx-39xxx: the printer can continue but something needs attention (toner low, ...). */
    ATTENTION,

    /** 40xxx-49xxx: the printer is stopped until someone acts (paper out, jam, door open). */
    OPERATOR_INTERVENTION,

    UNKNOWN,
}

/** A printer status report from `INFO STATUS` or an unsolicited `USTATUS DEVICE`. */
public data class PjlStatus(
    val code: Int?,
    /** The text the printer shows on its own display, e.g. `00 READY`. */
    val display: String?,
    val online: Boolean?,
) {
    public val statusClass: PjlStatusClass
        get() = when (code) {
            null -> PjlStatusClass.UNKNOWN
            in 10_000..19_999 -> PjlStatusClass.INFORMATIONAL
            in 20_000..29_999 -> PjlStatusClass.PJL_ERROR
            in 30_000..39_999 -> PjlStatusClass.ATTENTION
            in 40_000..49_999 -> PjlStatusClass.OPERATOR_INTERVENTION
            else -> PjlStatusClass.UNKNOWN
        }

    public companion object {
        /** Reads CODE / DISPLAY / ONLINE from an `INFO STATUS` or `USTATUS DEVICE` response. */
        public fun from(response: PjlResponse): PjlStatus {
            val kv = response.keyValues
            return PjlStatus(
                code = kv["CODE"]?.trim()?.toIntOrNull(),
                display = kv["DISPLAY"],
                online = kv["ONLINE"]?.trim()?.uppercase()?.let { it == "TRUE" || it == "ON" },
            )
        }
    }
}

/** An unsolicited `USTATUS JOB` event. */
public data class PjlJobEvent(val type: Type, val name: String?, val pages: Int?) {
    public enum class Type { START, END, CANCELED }

    public companion object {
        /** Returns null when [response] is not a `USTATUS JOB` event. */
        public fun from(response: PjlResponse): PjlJobEvent? {
            if (!response.command.uppercase().startsWith("USTATUS JOB")) return null
            val type = when (response.body.firstOrNull()?.uppercase()) {
                "START" -> Type.START
                "END" -> Type.END
                "CANCELED", "CANCELLED" -> Type.CANCELED
                else -> return null
            }
            val kv = response.keyValues
            return PjlJobEvent(type, kv["NAME"], kv["PAGES"]?.trim()?.toIntOrNull())
        }
    }
}
