package io.github.zsozso01.platen.core.model

/** Stable identity of a printer across reconnects and across the ways it can be reached. */
@JvmInline
public value class PrinterId(public val value: String) {
    override fun toString(): String = value
}

/** One way to reach a printer. A printer may have several: e.g. the same device over Wi-Fi and USB. */
public sealed interface Endpoint {
    /** IPP or IPPS over the network. [uri] uses the `ipp://` or `ipps://` scheme. */
    public data class Ipp(val uri: String) : Endpoint

    /** A raw TCP socket (HP JetDirect / AppSocket), normally port 9100. */
    public data class RawSocket(val host: String, val port: Int = 9100) : Endpoint

    /**
     * A printer on the USB cable. [systemName] is the platform's handle (Android's device name); it
     * changes on every replug, so identity comes from the vendor/product ids and serial number.
     */
    public data class Usb(
        val vendorId: Int,
        val productId: Int,
        val serialNumber: String?,
        val systemName: String,
    ) : Endpoint
}

/** A printer the user can pick. Capabilities are fetched separately because that needs a connection. */
public data class Printer(
    val id: PrinterId,
    /** Name to show: user-chosen name, else the one the printer announces. */
    val name: String,
    val makeAndModel: String? = null,
    val location: String? = null,
    val endpoints: List<Endpoint>,
    /** The printer's IEEE 1284 Device ID string, if known. */
    val deviceId: String? = null,
) {
    init {
        require(endpoints.isNotEmpty()) { "A printer needs at least one endpoint" }
    }
}
