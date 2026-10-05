package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.core.engine.ByteChannel
import java.io.Closeable
import java.io.IOException

/**
 * What the platform provides for one attached USB printer the user has granted access to. On Android this
 * wraps a `UsbDeviceConnection`; in tests it is a simulated printer. Everything above this interface is
 * plain Kotlin and is exercised without hardware.
 *
 * One access object stands for one open device. Operations that claim interfaces release them again when
 * the returned pipes or channel are closed, so a probe and a job can follow each other.
 */
public interface UsbPrinterAccess : Closeable {
    public val device: UsbDeviceInfo

    /**
     * Claims the interfaces of [plan], selects their alternate settings and returns one pipe per interface,
     * in the plan's order. Closing a pipe releases its interface.
     */
    @Throws(IOException::class)
    public fun openIppPipes(plan: UsbPrinterPlan.IppUsb): List<UsbIppPipe>

    /** Claims the classic printer interface of [plan]. Closing the channel releases it. */
    @Throws(IOException::class)
    public fun openLegacy(plan: UsbPrinterPlan.Legacy): ByteChannel

    /** The printer's IEEE 1284 Device ID from the class request `GET_DEVICE_ID`, or null if it cannot be read. Never throws. */
    public fun readDeviceId(iface: UsbInterfaceInfo): String?
}
