package io.github.zsozso01.platen.platform.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.github.zsozso01.platen.transport.usb.UsbDeviceInfo
import io.github.zsozso01.platen.transport.usb.UsbEndpointInfo
import io.github.zsozso01.platen.transport.usb.UsbHostConnection
import io.github.zsozso01.platen.transport.usb.UsbInterfaceInfo
import java.util.concurrent.ConcurrentHashMap

/** Describes an attached device in Platen's terms: every interface of every configuration, alternate settings included. */
internal fun UsbDevice.toInfo(): UsbDeviceInfo {
    val interfaces = buildList {
        for (c in 0 until configurationCount) {
            val configuration = getConfiguration(c)
            for (i in 0 until configuration.interfaceCount) add(configuration.getInterface(i).toInfo(c))
        }
    }
    return UsbDeviceInfo(
        vendorId = vendorId,
        productId = productId,
        manufacturer = manufacturerName,
        product = productName,
        // Reading the serial number needs permission from Android 10 on.
        serialNumber = try {
            serialNumber
        } catch (e: SecurityException) {
            null
        },
        interfaces = interfaces,
    )
}

private fun UsbInterface.toInfo(configurationIndex: Int) = UsbInterfaceInfo(
    configurationIndex = configurationIndex,
    interfaceNumber = id,
    alternateSetting = alternateSetting,
    interfaceClass = interfaceClass,
    interfaceSubclass = interfaceSubclass,
    interfaceProtocol = interfaceProtocol,
    endpoints = (0 until endpointCount).map { index ->
        val endpoint = getEndpoint(index)
        UsbEndpointInfo(
            address = endpoint.address,
            isIn = endpoint.direction == UsbConstants.USB_DIR_IN,
            isBulk = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK,
            maxPacketSize = endpoint.maxPacketSize,
        )
    },
)

/**
 * [UsbHostConnection] on Android's `UsbDeviceConnection`. Deliberately thin: all the rules about how a
 * printer is driven live in `HostUsbPrinterAccess`, which is tested against a simulated host. Android
 * addresses endpoints and interfaces by object, so descriptors are translated back through a lookup built
 * from the device.
 */
internal class AndroidUsbHost(device: UsbDevice, private val connection: UsbDeviceConnection) : UsbHostConnection {
    private data class Key(val configuration: Int, val number: Int, val alternate: Int)

    private val known: Map<Key, UsbInterface> = buildMap {
        for (c in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(c)
            for (i in 0 until configuration.interfaceCount) {
                val iface = configuration.getInterface(i)
                put(Key(c, iface.id, iface.alternateSetting), iface)
            }
        }
    }

    /** The alternate setting currently selected per interface number, whose endpoints transfers address. */
    private val selected = ConcurrentHashMap<Int, UsbInterface>()

    private fun find(info: UsbInterfaceInfo): UsbInterface? = known[Key(info.configurationIndex, info.interfaceNumber, info.alternateSetting)]

    override fun claimInterface(iface: UsbInterfaceInfo): Boolean = find(iface)?.let { connection.claimInterface(it, true) } ?: false

    override fun releaseInterface(iface: UsbInterfaceInfo): Boolean {
        selected.remove(iface.interfaceNumber)
        return find(iface)?.let { connection.releaseInterface(it) } ?: false
    }

    override fun selectAlternateSetting(iface: UsbInterfaceInfo): Boolean {
        val target = find(iface) ?: return false
        if (!connection.setInterface(target)) return false
        selected[iface.interfaceNumber] = target
        return true
    }

    override fun bulkTransfer(endpointAddress: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        val endpoint = selected.values.firstNotNullOfOrNull { iface -> endpointOf(iface, endpointAddress) } ?: return -1
        return connection.bulkTransfer(endpoint, buffer, offset, length, timeoutMillis)
    }

    private fun endpointOf(iface: UsbInterface, address: Int) =
        (0 until iface.endpointCount).map(iface::getEndpoint).firstOrNull { it.address == address }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int = connection.controlTransfer(requestType, request, value, index, buffer, offset, length, timeoutMillis)

    override fun close() = connection.close()
}
