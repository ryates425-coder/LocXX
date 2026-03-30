package com.locxx.langaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** DNS-SD / Bonjour type (Android expects the trailing dot). */
const val LOCXX_MDNS_SERVICE_TYPE: String = "_locxx._tcp."

/**
 * Advertises LocXX HTTP on LAN so clients can discover it without sharing a URL.
 */
class LocxxMdnsAdvertiser(
    private val appContext: Context,
    private val port: Int,
    private val onError: (String) -> Unit,
) {
    private val nsd = appContext.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            onError("NSD register failed ($errorCode)")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
    }

    fun start() {
        val name = "LocXX-${UUID.randomUUID().toString().take(8)}"
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = LOCXX_MDNS_SERVICE_TYPE
            port = this@LocxxMdnsAdvertiser.port
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            onError(e.message ?: "NSD register")
        }
    }

    fun stop() {
        try {
            nsd.unregisterService(registrationListener)
        } catch (_: Exception) {
        }
    }
}

/**
 * Browses for a LocXX host and resolves the first service to an address + port for `http://…`.
 */
class LocxxMdnsBrowser(
    private val appContext: Context,
    private val mainHandler: Handler,
    /** When false, discovery continues (e.g. ignore self or wrong tie-break peer). */
    private val shouldAcceptResolved: (hostForUrl: String, port: Int) -> Boolean = { _, _ -> true },
    private val onResolved: (hostForUrl: String, port: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val nsd = appContext.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveInFlight = AtomicBoolean(false)
    private val resolvedOnce = AtomicBoolean(false)
    @Volatile
    private var stopped = false

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            resolveInFlight.set(false)
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            resolveInFlight.set(false)
            if (stopped || resolvedOnce.get()) return
            val host = serviceInfo.resolvedHostOrNull() ?: return
            val p = serviceInfo.port
            if (p <= 0) return
            val hostStr = host.canonicalHostNameForHttpUrl()
            if (!shouldAcceptResolved(hostStr, p)) {
                return
            }
            if (!resolvedOnce.compareAndSet(false, true)) return
            stopDiscoveryOnly()
            mainHandler.post { onResolved(hostStr, p) }
        }
    }

    fun start() {
        stopped = false
        resolvedOnce.set(false)
        resolveInFlight.set(false)
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("NSD browse failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (stopped || resolvedOnce.get()) return
                if (!resolveInFlight.compareAndSet(false, true)) return
                try {
                    nsd.resolveServiceCompat(appContext, serviceInfo, resolveListener)
                } catch (_: Exception) {
                    resolveInFlight.set(false)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices(LOCXX_MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
        } catch (e: Exception) {
            onError(e.message ?: "NSD discover")
        }
    }

    fun stop() {
        stopped = true
        stopDiscoveryOnly()
    }

    private fun stopDiscoveryOnly() {
        val dl = discoveryListener ?: return
        discoveryListener = null
        try {
            nsd.stopServiceDiscovery(dl)
        } catch (_: Exception) {
        }
    }
}

private fun NsdServiceInfo.resolvedHostOrNull(): java.net.InetAddress? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        hostAddresses.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        host
    }
}

private fun NsdManager.resolveServiceCompat(
    appContext: Context,
    serviceInfo: NsdServiceInfo,
    listener: NsdManager.ResolveListener,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        resolveService(
            serviceInfo,
            ContextCompat.getMainExecutor(appContext),
            listener,
        )
    } else {
        @Suppress("DEPRECATION")
        resolveService(serviceInfo, listener)
    }
}

private fun java.net.InetAddress.canonicalHostNameForHttpUrl(): String {
    return when (this) {
        is Inet6Address -> {
            val raw = hostAddress ?: return "127.0.0.1"
            "[$raw]"
        }
        else -> hostAddress ?: "127.0.0.1"
    }
}
