package io.github.zsozso01.platen.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.github.zsozso01.platen.transport.network.DiscoveredPrinter
import io.github.zsozso01.platen.transport.network.DnsSdPrinters
import io.github.zsozso01.platen.transport.network.DnsSdService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Finds IPP printers announced with mDNS/DNS-SD (`_ipp._tcp` and `_ipps._tcp`) using the platform's own
 * `NsdManager`, so no extra library and no raw multicast sockets are needed.
 *
 * `NsdManager` only answers "a service exists" first; the address and the TXT record need a second step
 * ("resolve"). Before Android 14 resolves must be done one at a time, so they are queued; from Android 14
 * the service-info callback is used, which also keeps addresses fresh.
 */
public class NsdPrinterDiscovery(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Emits the current set of printers each time it changes. Cancel the collector to stop searching. */
    public fun discover(): Flow<List<DiscoveredPrinter>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredPrinter>() // keyed by service instance + type
        val lock = multicastLock()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "platen-nsd").apply { isDaemon = true } }
        val resolver = ResolveQueue(nsd, worker)
        val serviceCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

        fun publish() = trySend(DnsSdPrinters.merge(found.values.toList()))

        fun accept(info: NsdServiceInfo, secure: Boolean) {
            val address = pickAddress(info) ?: return
            val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
            val service = DnsSdService(info.serviceName.orEmpty(), address, info.port, txt, secure)
            val printer = DnsSdPrinters.fromService(service) ?: return
            synchronized(found) { found[key(info, secure)] = printer }
            publish()
        }

        fun listener(type: String, secure: Boolean) = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                        override fun onServiceUpdated(info: NsdServiceInfo) = accept(info, secure)

                        override fun onServiceLost() {
                            synchronized(found) { found.remove(key(serviceInfo, secure)) }
                            publish()
                        }

                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    serviceCallbacks[key(serviceInfo, secure)] = callback
                    runCatching { nsd.registerServiceInfoCallback(serviceInfo, worker, callback) }
                } else {
                    resolver.enqueue(serviceInfo) { resolved -> if (resolved != null) accept(resolved, secure) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(found) { found.remove(key(serviceInfo, secure)) }
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        val plain = listener(IPP_TYPE, secure = false)
        val tls = listener(IPPS_TYPE, secure = true)
        runCatching { nsd.discoverServices(IPP_TYPE, NsdManager.PROTOCOL_DNS_SD, plain) }
        runCatching { nsd.discoverServices(IPPS_TYPE, NsdManager.PROTOCOL_DNS_SD, tls) }
        trySend(emptyList())

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(plain) }
            runCatching { nsd.stopServiceDiscovery(tls) }
            if (Build.VERSION.SDK_INT >= 34) serviceCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            resolver.clear()
            worker.shutdown()
            lock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("platen-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun key(info: NsdServiceInfo, secure: Boolean) = "${info.serviceName}|${if (secure) IPPS_TYPE else IPP_TYPE}"

    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return null
        return chosen.hostAddress?.substringBefore('%') // drop an IPv6 zone id
    }

    private companion object {
        const val IPP_TYPE = "_ipp._tcp"
        const val IPPS_TYPE = "_ipps._tcp"
    }
}

/** Before Android 14 only one resolve may run at a time; this queues them. */
private class ResolveQueue(private val nsd: NsdManager, private val executor: java.util.concurrent.Executor) {
    private class Item(val info: NsdServiceInfo, val done: (NsdServiceInfo?) -> Unit)

    private val queue = ArrayDeque<Item>()
    private var busy = false

    @Synchronized
    fun enqueue(info: NsdServiceInfo, done: (NsdServiceInfo?) -> Unit) {
        queue.addLast(Item(info, done))
        next()
    }

    @Synchronized
    fun clear() {
        queue.clear()
    }

    @Synchronized
    @Suppress("DEPRECATION")
    private fun next() {
        if (busy) return
        val item = queue.removeFirstOrNull() ?: return
        busy = true
        fun finish(result: NsdServiceInfo?) {
            runCatching { item.done(result) }
            synchronized(this) {
                busy = false
                next()
            }
        }
        try {
            nsd.resolveService(
                item.info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finish(null)

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = finish(serviceInfo)
                },
            )
        } catch (e: RuntimeException) {
            finish(null)
        }
    }
}
