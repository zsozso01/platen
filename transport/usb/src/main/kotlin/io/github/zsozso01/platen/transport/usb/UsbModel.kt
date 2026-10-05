package io.github.zsozso01.platen.transport.usb

/** One endpoint of a USB interface, reduced to what printing needs. */
public data class UsbEndpointInfo(
    /** The endpoint address as in the descriptor (direction bit included). */
    val address: Int,
    val isIn: Boolean,
    val isBulk: Boolean,
    val maxPacketSize: Int,
)

/**
 * One interface descriptor, *including* its alternate setting: on Android and in USB, every alternate
 * setting is a separate descriptor with its own class, protocol and endpoints.
 */
public data class UsbInterfaceInfo(
    /** Index of the configuration the descriptor belongs to. */
    val configurationIndex: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointInfo>,
) {
    public val bulkOut: UsbEndpointInfo? get() = endpoints.firstOrNull { it.isBulk && !it.isIn }
    public val bulkIn: UsbEndpointInfo? get() = endpoints.firstOrNull { it.isBulk && it.isIn }

    /** USB printer class (7) with the printer subclass (1). */
    public val isPrinterClass: Boolean get() = interfaceClass == CLASS_PRINTER && interfaceSubclass == 1

    override fun toString(): String =
        "if$interfaceNumber/alt$alternateSetting cfg$configurationIndex class=$interfaceClass/$interfaceSubclass/$interfaceProtocol eps=${endpoints.map { "0x${it.address.toString(16)}" }}"

    public companion object {
        public const val CLASS_PRINTER: Int = 7
        public const val PROTOCOL_UNIDIRECTIONAL: Int = 1
        public const val PROTOCOL_BIDIRECTIONAL: Int = 2
        public const val PROTOCOL_1284_4: Int = 3
        public const val PROTOCOL_IPP_USB: Int = 4
    }
}

/** What Platen needs to know about an attached device. */
public data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    /** May be null: reading it needs permission. */
    val serialNumber: String?,
    val interfaces: List<UsbInterfaceInfo>,
) {
    /** True if any interface looks like a printer, so the device is worth offering. */
    public val looksLikePrinter: Boolean get() = interfaces.any { it.isPrinterClass || UsbInterfacePlanner.isHpIppUsb(vendorId, it) }

    public val displayName: String
        get() = listOfNotNull(manufacturer, product).joinToString(" ").ifBlank { "USB printer %04x:%04x".format(vendorId, productId) }
}

/** How Platen will talk to a device. */
public sealed interface UsbPrinterPlan {
    public val configurationIndex: Int

    /** IPP over USB: two or three interfaces, each a persistent HTTP/1.1 connection carrying IPP. */
    public data class IppUsb(override val configurationIndex: Int, val interfaces: List<UsbInterfaceInfo>) : UsbPrinterPlan

    /** A classic printer interface carrying a raw page language, usually with PJL on top. */
    public data class Legacy(override val configurationIndex: Int, val iface: UsbInterfaceInfo) : UsbPrinterPlan {
        public val bidirectional: Boolean get() = iface.bulkIn != null
    }
}

/**
 * Chooses how to use a printer's interfaces. The rules come from the USB printer class and IPP-USB
 * specifications and from how CUPS, the Linux kernel and `ipp-usb` behave on real devices:
 *
 * * IPP over USB needs **at least two** interfaces offering protocol 4; the alternate setting that
 *   carries it is often not 0. HP also uses a non-standard `255/9/1` for the same purpose.
 * * Otherwise a classic interface: bidirectional (protocol 2) before unidirectional (protocol 1).
 *   Protocol 3 (IEEE 1284.4 multiplexing) needs framing Platen does not implement, so it is skipped.
 * * Both can be offered: the caller tries IPP-USB first and falls back to the legacy interface.
 */
public object UsbInterfacePlanner {
    private const val HP_VENDOR_ID = 0x03F0
    private const val MAX_IPP_INTERFACES = 3

    internal fun isHpIppUsb(vendorId: Int, i: UsbInterfaceInfo): Boolean =
        vendorId == HP_VENDOR_ID && i.interfaceClass == 0xFF && i.interfaceSubclass == 9 && i.interfaceProtocol == 1

    private fun isIppUsb(vendorId: Int, i: UsbInterfaceInfo): Boolean =
        ((i.isPrinterClass && i.interfaceProtocol == UsbInterfaceInfo.PROTOCOL_IPP_USB) || isHpIppUsb(vendorId, i)) &&
            i.bulkIn != null && i.bulkOut != null

    /** All usable plans, best first. Empty if the device cannot be driven. */
    public fun plans(device: UsbDeviceInfo): List<UsbPrinterPlan> {
        val plans = mutableListOf<UsbPrinterPlan>()
        val configs = device.interfaces.map { it.configurationIndex }.distinct().sorted()
        for (config in configs) {
            val inConfig = device.interfaces.filter { it.configurationIndex == config }
            ippUsb(device.vendorId, config, inConfig)?.let(plans::add)
        }
        for (config in configs) {
            val inConfig = device.interfaces.filter { it.configurationIndex == config }
            legacy(config, inConfig)?.let(plans::add)
        }
        return plans
    }

    public fun best(device: UsbDeviceInfo): UsbPrinterPlan? = plans(device).firstOrNull()

    private fun ippUsb(vendorId: Int, config: Int, interfaces: List<UsbInterfaceInfo>): UsbPrinterPlan.IppUsb? {
        // One alternate per interface number: the lowest that carries IPP.
        val perInterface = interfaces.filter { isIppUsb(vendorId, it) }
            .groupBy { it.interfaceNumber }
            .map { (_, alts) -> alts.minBy { it.alternateSetting } }
            .sortedBy { it.interfaceNumber }
        // The specification requires two, so that one can be busy while the other answers.
        if (perInterface.size < 2) return null
        return UsbPrinterPlan.IppUsb(config, perInterface.take(MAX_IPP_INTERFACES))
    }

    private fun legacy(config: Int, interfaces: List<UsbInterfaceInfo>): UsbPrinterPlan.Legacy? {
        val candidates = interfaces.filter {
            it.isPrinterClass && it.bulkOut != null &&
                (it.interfaceProtocol == UsbInterfaceInfo.PROTOCOL_BIDIRECTIONAL && it.bulkIn != null || it.interfaceProtocol == UsbInterfaceInfo.PROTOCOL_UNIDIRECTIONAL)
        }
        val chosen = candidates.filter { it.interfaceProtocol == UsbInterfaceInfo.PROTOCOL_BIDIRECTIONAL }.minByOrNull { it.interfaceNumber * 256 + it.alternateSetting }
            ?: candidates.minByOrNull { it.interfaceNumber * 256 + it.alternateSetting }
        return chosen?.let { UsbPrinterPlan.Legacy(config, it) }
    }
}
