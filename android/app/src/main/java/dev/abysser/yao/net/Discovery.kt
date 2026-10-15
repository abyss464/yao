package dev.abysser.yao.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Host(val name: String, val address: String, val port: Int)

/** Finds computers running yaod on the local network (mDNS `_yao._tcp`). */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    val hosts: StateFlow<List<Host>> = _hosts

    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(pending) { pending.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _hosts.value = _hosts.value.filterNot { it.name == info.serviceName }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(Proto.SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        _hosts.value = emptyList()
    }

    // Older Android allows one resolve at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val info = synchronized(pending) {
            if (resolving || pending.isEmpty()) return
            resolving = true
            pending.removeFirst()
        }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()

            override fun onServiceResolved(s: NsdServiceInfo) {
                val v4 = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses.firstOrNull { it is Inet4Address } else null
                val addr = (v4 ?: s.host)?.hostAddress
                if (addr != null) {
                    val host = Host(s.serviceName, addr, s.port)
                    _hosts.value = _hosts.value.filterNot { it.name == host.name } + host
                }
                done()
            }

            fun done() {
                synchronized(pending) { resolving = false }
                resolveNext()
            }
        })
    }
}
