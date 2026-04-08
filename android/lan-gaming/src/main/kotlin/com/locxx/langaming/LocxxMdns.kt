package com.locxx.langaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** DNS-SD / Bonjour type (Android expects the trailing dot). */
const val LOCXX_MDNS_SERVICE_TYPE: String = "_locxx._tcp."

/** TXT key for host display name ([LocxxMdnsAdvertiser] / [LocxxMdnsBrowser]). */
internal const val LOCXX_MDNS_TXT_DISPLAY_NAME: String = "dn"

/**
 * Advertises LocXX HTTP on LAN so clients can discover it without sharing a URL.
 *
 * On API 34+, [hostDisplayNameForTxt] is published in the DNS-SD TXT record so browsers can show
 * the host’s name as soon as the service resolves (before any HTTP call).
 */
class LocxxMdnsAdvertiser(
    private val appContext: Context,
    private val port: Int,
    private val hostDisplayNameForTxt: String = "",
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val dn = hostDisplayNameForTxt.trim()
                if (dn.isNotEmpty()) {
                    runCatching {
                        // mDNS TXT value length is limited; keep a safe bound.
                        setAttribute(LOCXX_MDNS_TXT_DISPLAY_NAME, dn.take(200))
                    }.onFailure { ex ->
                        onError("NSD TXT: ${ex.message ?: "dn"}")
                    }
                }
            }
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
    /**
     * [txtDisplayName] comes from DNS-SD TXT `dn` on API 34+ when the host advertised it; otherwise null.
     */
    private val onResolved: (hostForUrl: String, port: Int, txtDisplayName: String?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val nsd = appContext.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveInFlight = AtomicBoolean(false)
    private val resolvedOnce = AtomicBoolean(false)
    @Volatile
    private var stopped = false

    @Volatile
    private var activeServiceInfoCallback: NsdManager.ServiceInfoCallback? = null

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            resolveInFlight.set(false)
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            resolveInFlight.set(false)
            completeResolveIfReady(serviceInfo)
        }
    }

    /**
     * Returns true once [onResolved] has been scheduled (or was already).
     * For API 34+ [ServiceInfoCallback] may deliver partial [NsdServiceInfo] before host/port exist.
     */
    private fun completeResolveIfReady(serviceInfo: NsdServiceInfo): Boolean {
        if (stopped) return false
        if (resolvedOnce.get()) return true
        val host = serviceInfo.resolvedHostOrNull() ?: return false
        val p = serviceInfo.port
        if (p <= 0) return false
        val hostStr = host.canonicalHostNameForHttpUrl()
        if (!shouldAcceptResolved(hostStr, p)) return false
        if (!resolvedOnce.compareAndSet(false, true)) return true
        resolveInFlight.set(false)
        unregisterServiceInfoCallbackIfAny()
        stopDiscoveryOnly()
        val txtName = serviceInfo.locxxDnsSdDisplayNameOrNull()
        mainHandler.post { onResolved(hostStr, p, txtName) }
        return true
    }

    private fun unregisterServiceInfoCallbackIfAny() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val cb = activeServiceInfoCallback ?: return
        activeServiceInfoCallback = null
        runCatching { nsd.unregisterServiceInfoCallback(cb) }
    }

    fun start() {
        stopped = false
        resolvedOnce.set(false)
        resolveInFlight.set(false)
        activeServiceInfoCallback = null
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val exec = ContextCompat.getMainExecutor(appContext)
                        val cb = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                resolveInFlight.set(false)
                                activeServiceInfoCallback = null
                            }

                            override fun onServiceInfoCallbackUnregistered() {
                                activeServiceInfoCallback = null
                            }

                            override fun onServiceLost() {
                                resolveInFlight.set(false)
                            }

                            override fun onServiceUpdated(info: NsdServiceInfo) {
                                completeResolveIfReady(info)
                            }
                        }
                        activeServiceInfoCallback = cb
                        nsd.registerServiceInfoCallback(serviceInfo, exec, cb)
                    } else {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(serviceInfo, resolveListener)
                    }
                } catch (_: Exception) {
                    resolveInFlight.set(false)
                    activeServiceInfoCallback = null
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
        unregisterServiceInfoCallbackIfAny()
        resolveInFlight.set(false)
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

@Suppress("NewApi")
private fun NsdServiceInfo.locxxDnsSdDisplayNameOrNull(): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    val raw = attributes[LOCXX_MDNS_TXT_DISPLAY_NAME]
        ?: attributes.entries.firstOrNull { it.key.equals(LOCXX_MDNS_TXT_DISPLAY_NAME, ignoreCase = true) }
            ?.value
        ?: return null
    return try {
        String(raw, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
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

private fun java.net.InetAddress.canonicalHostNameForHttpUrl(): String {
    return when (this) {
        is Inet6Address -> {
            val raw = hostAddress ?: return "127.0.0.1"
            "[$raw]"
        }
        else -> hostAddress ?: "127.0.0.1"
    }
}
