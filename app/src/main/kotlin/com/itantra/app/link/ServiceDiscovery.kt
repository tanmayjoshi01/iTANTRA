package com.itantra.app.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** DNS-SD service type the Wi-Fi receiver advertises on the local network. */
const val ITANTRA_SERVICE_TYPE = "_itantra._tcp."

/**
 * Phone B: advertises its running Wi-Fi receiver with Android NSD so Phone A can
 * find it without typing an IP. Manual IP entry remains the fallback.
 */
class ReceiverAdvertiser(context: Context, private val log: (String) -> Unit = {}) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int, name: String) {
        if (listener != null || nsd == null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = ITANTRA_SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = log("nsd registered as ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = log("nsd registration failed: $error")
            override fun onServiceUnregistered(info: NsdServiceInfo) = log("nsd unregistered")
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = log("nsd unregistration failed: $error")
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            log("nsd register error: $e")
            listener = null
        }
    }

    fun unregister() {
        val l = listener ?: return
        listener = null
        try {
            nsd?.unregisterService(l)
        } catch (e: Exception) {
            log("nsd unregister error: $e")
        }
    }
}

/** Phone A: finds advertised iTANTRA receivers on the local network. */
class ReceiverBrowser(context: Context, private val log: (String) -> Unit = {}) {
    data class Found(val name: String, val host: String, val port: Int)

    data class State(val searching: Boolean = false, val found: List<Found> = emptyList(), val error: String? = null)

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    fun start() {
        if (discovery != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                log("nsd discovery started")
                _state.update { it.copy(searching = true, error = null) }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                log("nsd found ${info.serviceName}")
                synchronized(lock) { toResolve.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                log("nsd lost ${info.serviceName}")
                _state.update { s -> s.copy(found = s.found.filterNot { it.name == info.serviceName }) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _state.update { it.copy(searching = false) }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("nsd discovery failed: $errorCode")
                discovery = null
                _state.update { it.copy(searching = false, error = "Discovery failed ($errorCode)") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = l
        try {
            nsd.discoverServices(ITANTRA_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            discovery = null
            _state.update { it.copy(searching = false, error = "Discovery failed: ${e.message}") }
        }
    }

    fun stop() {
        val l = discovery ?: return
        discovery = null
        try {
            nsd?.stopServiceDiscovery(l)
        } catch (_: Exception) {
        }
        _state.update { it.copy(searching = false) }
    }

    // NsdManager resolves one service at a time, so resolve found services sequentially.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(lock) {
            if (resolving || toResolve.isEmpty()) return
            resolving = true
            toResolve.removeFirst()
        }
        nsd?.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                log("nsd resolved ${info.serviceName} -> $host:${info.port}")
                if (host != null) {
                    val found = Found(info.serviceName, host, info.port)
                    _state.update { s -> s.copy(found = s.found.filterNot { it.name == found.name } + found) }
                }
                done()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                log("nsd resolve failed for ${info.serviceName}: $errorCode")
                done()
            }

            private fun done() {
                synchronized(lock) { resolving = false }
                resolveNext()
            }
        })
    }
}
