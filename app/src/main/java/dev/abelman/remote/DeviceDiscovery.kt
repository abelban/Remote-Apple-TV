package dev.abelman.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.Locale

/** Uses Android's mDNS service, which works without location access or Python interface probing. */
@Suppress("DEPRECATION")
class DeviceDiscovery(context: Context, private val found: (TvDevice) -> Unit, private val finished: (String?) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0
    private val timeout = Runnable { stop(); finished(null) }

    fun start() {
        stop()
        val currentGeneration = generation
        try {
            multicastLock = wifi?.createMulticastLock("apple-tv-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val callback = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onServiceFound(service: NsdServiceInfo) { handler.post {
                    if (generation == currentGeneration) {
                        queue.add(service)
                        resolveNext(currentGeneration)
                    }
                } }
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post {
                    if (generation == currentGeneration) {
                        stop()
                        finished("Не удалось запустить поиск. Проверьте подключение к Wi-Fi.")
                    }
                } }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            }
            listener = callback
            manager.discoverServices("_companion-link._tcp.", NsdManager.PROTOCOL_DNS_SD, callback)
            handler.postDelayed(timeout, 12_000)
        } catch (_: Exception) {
            stop()
            finished("Поиск недоступен. Проверьте Wi-Fi или укажите IP-адрес Apple TV.")
        }
    }

    private fun resolveNext(currentGeneration: Int) {
        if (resolving || queue.isEmpty() || generation != currentGeneration) return
        resolving = true
        val next = queue.removeFirst()
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post {
                if (generation == currentGeneration) {
                    resolving = false
                    resolveNext(currentGeneration)
                }
            } }
            override fun onServiceResolved(info: NsdServiceInfo) { handler.post {
                if (generation != currentGeneration) return@post
                resolving = false
                val properties = info.attributes.entries.associate {
                    it.key.lowercase(Locale.ROOT) to String(it.value, Charsets.UTF_8)
                }
                val model = properties["rpmd"].orEmpty()
                val flags = properties["rpfl"]?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
                // Macs, iPads and HomePods advertise Companion too. When the model
                // is omitted, use pyatv's observed PIN-pairing capability bit.
                val isAppleTv = model.startsWith("AppleTV", true) || (model.isEmpty() && (flags and 0x4000L) != 0L)
                val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()
                    else info.host as? Inet4Address
                if (host is Inet4Address && info.port > 0 && isAppleTv) {
                    found(TvDevice(info.serviceName, info.serviceName, host.hostAddress!!, info.port, properties))
                }
                resolveNext(currentGeneration)
            } }
        })
    }

    fun stop() {
        generation++
        handler.removeCallbacks(timeout)
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
        queue.clear()
        resolving = false
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
