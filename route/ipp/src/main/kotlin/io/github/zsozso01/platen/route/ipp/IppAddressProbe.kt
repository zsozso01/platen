package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.core.engine.PrinterProbe
import io.github.zsozso01.platen.protocol.ipp.IppConnector
import io.github.zsozso01.platen.protocol.ipp.IppHttpException
import io.github.zsozso01.platen.protocol.ipp.IppOperationException
import io.github.zsozso01.platen.protocol.ipp.IppParseException
import io.github.zsozso01.platen.protocol.ipp.IppStatus
import io.github.zsozso01.platen.transport.network.PrinterAddress
import java.io.IOException

/** A printer found at an address: where its IPP service lives and what it reported. */
public class ProbedPrinter(
    public val address: PrinterAddress,
    public val path: String,
    public val printerUri: String,
    public val probe: PrinterProbe,
)

/** Why an address did not lead to a printer. The UI maps [reason] to advice. */
public class AddressProbeException(public val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    public enum class Reason {
        /** Nothing answered: wrong address, printer off or asleep, or a different network. */
        UNREACHABLE,

        /** Something answered but it is not an IPP printer (or not at any usual path). */
        NOT_A_PRINTER,

        /** The printer insists on an encrypted connection. */
        ENCRYPTION_REQUIRED,

        /** The user asked for `ipps://`, which is not supported yet. */
        SECURE_NOT_SUPPORTED,

        AUTHENTICATION_REQUIRED,
    }
}

/** Finds the IPP service of a printer given only what a person typed. */
public object IppAddressProbe {
    /**
     * Tries the path in [address], or the usual ones if none was given, and returns the first that answers
     * `Get-Printer-Attributes`. Blocking.
     */
    @Throws(AddressProbeException::class)
    public fun probe(address: PrinterAddress, connectorFor: (PrinterAddress) -> IppConnector): ProbedPrinter {
        if (address.secure) {
            throw AddressProbeException(
                AddressProbeException.Reason.SECURE_NOT_SUPPORTED,
                "Encrypted (ipps) connections are not supported yet. Use the plain ipp:// address of the printer.",
            )
        }
        val connector = connectorFor(address)
        var lastNotFound: Exception? = null
        for (path in address.path?.let { listOf(it) } ?: PrinterAddress.COMMON_PATHS) {
            val uri = address.printerUri(path)
            try {
                val probe = IppJobProtocol(IppEndpoint(connector, address.hostHeader, path, uri)).probe()
                return ProbedPrinter(address, path, uri, probe)
            } catch (e: IppHttpException) {
                when {
                    e.needsTls -> throw AddressProbeException(AddressProbeException.Reason.ENCRYPTION_REQUIRED, "The printer only accepts encrypted connections.", e)
                    e.needsAuthentication -> throw AddressProbeException(AddressProbeException.Reason.AUTHENTICATION_REQUIRED, "The printer asks for a user name and password.", e)
                    else -> lastNotFound = e // 404 and friends: not this path
                }
            } catch (e: IppOperationException) {
                if (e.status == IppStatus.CLIENT_ERROR_NOT_AUTHENTICATED || e.status == IppStatus.CLIENT_ERROR_NOT_AUTHORIZED) {
                    throw AddressProbeException(AddressProbeException.Reason.AUTHENTICATION_REQUIRED, "The printer asks for a user name and password.", e)
                }
                lastNotFound = e
            } catch (e: IppParseException) {
                lastNotFound = e // something answered, but not with IPP
            } catch (e: IOException) {
                // Could not connect or the connection died: no point trying other paths.
                throw AddressProbeException(
                    AddressProbeException.Reason.UNREACHABLE,
                    "Could not reach ${address.hostHeader}. Check the address, that the printer is on, and that this phone is on the same network.",
                    e,
                )
            }
        }
        throw AddressProbeException(
            AddressProbeException.Reason.NOT_A_PRINTER,
            "${address.hostHeader} answered, but not as an IPP printer.",
            lastNotFound,
        )
    }
}
