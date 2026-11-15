package com.uniplay.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.uniplay.core.NetworkUtils

/**
 * Starts a private Wi-Fi hotspot the app controls (Android 8+ "local-only hotspot"),
 * or reuses/starts the legacy system hotspot on older Android (5.0-7.1) when permitted.
 * Callers fall back to client-gateway mode or typed-in details when this reports a failure.
 */
class LocalHotspot(context: Context) {

    class Credentials(val ssid: String, val psk: String, val ip: String, val bssid: String = "")

    private companion object {
        const val TAG = "LocalHotspot"
        const val FALLBACK_AP_IP = "192.168.43.1"
        const val IP_POLL_ATTEMPTS = 8
        const val IP_POLL_DELAY_MS = 100L
    }

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var startedLegacyAp = false
    private var current: Credentials? = null
    private var starting = false
    var onStoppedUnexpectedly: (() -> Unit)? = null

    val isSupported: Boolean get() = wifi != null
    val isActive: Boolean get() = current != null

    /** Calls back on the main thread, with the existing hotspot if one is already up. */
    @SuppressLint("MissingPermission")
    fun start(onReady: (Credentials) -> Unit, onFailed: (String) -> Unit) {
        current?.let { onReady(it); return }
        if (wifi == null) return onFailed("Wi-Fi is unavailable on this device")
        if (starting) return
        starting = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startLegacyHotspot(onReady, onFailed)
            return
        }

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                reservation = started
                resolveLocalOnlyCredentials(started, onReady, onFailed)
            }

            override fun onStopped() {
                val unexpected = reservation != null
                current = null
                reservation = null
                if (unexpected) {
                    Log.w(TAG, "Local hotspot stopped by Android")
                    onStoppedUnexpectedly?.invoke()
                }
            }

            override fun onFailed(reason: Int) {
                starting = false
                Log.w(TAG, "Local hotspot failed, reason=$reason")
                readActiveLegacyCredentials()?.let { creds ->
                    current = creds
                    Log.i(TAG, "Reusing active system hotspot: ${creds.ssid} at ${creds.ip}")
                    onReady(creds)
                    return
                }
                onFailed("This device could not start its own hotspot (code $reason)")
            }
        }

        try {
            wifi.startLocalOnlyHotspot(callback, mainHandler)
        } catch (e: SecurityException) {
            starting = false
            onFailed("Location permission is needed to start the hotspot")
        } catch (e: Exception) {
            starting = false
            onFailed("Could not start the hotspot: ${e.message}")
        }
    }

    private fun resolveLocalOnlyCredentials(
        started: WifiManager.LocalOnlyHotspotReservation,
        onReady: (Credentials) -> Unit,
        onFailed: (String) -> Unit
    ) {
        Thread({
            val ip = pollApAddress()
            val creds = read(started, ip)
            mainHandler.post {
                starting = false
                if (creds == null) {
                    try {
                        started.close()
                    } catch (_: Exception) {
                    }
                    reservation = null
                    onFailed("The hotspot started but gave no name or password")
                } else {
                    current = creds
                    Log.i(TAG, "Local hotspot up: ${creds.ssid} at ${creds.ip}")
                    onReady(creds)
                }
            }
        }, "LocalHotspotIpPoll").apply {
            isDaemon = true
            start()
        }
    }

    private fun startLegacyHotspot(onReady: (Credentials) -> Unit, onFailed: (String) -> Unit) {
        Thread({
            try {
                readActiveLegacyCredentials()?.let { creds ->
                    mainHandler.post {
                        starting = false
                        current = creds
                        Log.i(TAG, "Reusing active legacy hotspot: ${creds.ssid} at ${creds.ip}")
                        onReady(creds)
                    }
                    return@Thread
                }

                // Do not tear down an active Wi-Fi client connection (e.g. connected to phone's hotspot).
                if (NetworkUtils.isWifiConnected(appContext)) {
                    postFailure(onFailed, "Already connected to Wi-Fi; skipping legacy hotspot")
                    return@Thread
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(appContext)) {
                    postFailure(onFailed, "Turn on this phone's hotspot or enter its details")
                    return@Thread
                }

                val mgr = wifi ?: run {
                    postFailure(onFailed, "Wi-Fi is unavailable on this device")
                    return@Thread
                }
                @Suppress("DEPRECATION")
                if (mgr.isWifiEnabled) {
                    @Suppress("DEPRECATION")
                    mgr.isWifiEnabled = false
                }

                val setEnabled = mgr.javaClass.getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                val ok = setEnabled.invoke(mgr, null, true) as? Boolean ?: false
                if (!ok) {
                    postFailure(onFailed, "Could not start hotspot automatically on this Android version")
                    return@Thread
                }

                startedLegacyAp = true
                val ip = pollApAddress()
                val creds = readLegacyApConfig(ip)
                mainHandler.post {
                    starting = false
                    if (creds != null) {
                        current = creds
                        Log.i(TAG, "Legacy hotspot up: ${creds.ssid} at ${creds.ip}")
                        onReady(creds)
                    } else {
                        onFailed("Hotspot started, but its name or password could not be read")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy hotspot start failed: ${e.message}")
                postFailure(onFailed, "Could not start hotspot automatically on this Android version")
            }
        }, "LegacyHotspotStarter").apply {
            isDaemon = true
            start()
        }
    }

    private fun postFailure(onFailed: (String) -> Unit, reason: String) {
        mainHandler.post {
            starting = false
            onFailed(reason)
        }
    }

    fun stop() {
        val res = reservation
        reservation = null
        try {
            res?.close()
        } catch (_: Exception) {
        }
        if (startedLegacyAp) {
            try {
                val setEnabled = wifi?.javaClass?.getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                setEnabled?.invoke(wifi, null, false)
            } catch (_: Exception) {
            }
            startedLegacyAp = false
        }
        reservation = null
        current = null
        starting = false
    }

    @Suppress("DEPRECATION")
    private fun read(r: WifiManager.LocalOnlyHotspotReservation, ip: String): Credentials? {
        val ssid: String?
        val psk: String?
        var bssid = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg: SoftApConfiguration = r.softApConfiguration
            ssid = cfg.ssid
            psk = cfg.passphrase
            bssid = cfg.bssid?.toString().orEmpty()
        } else {
            val cfg: WifiConfiguration = r.wifiConfiguration ?: return null
            ssid = cfg.SSID?.trim('"')
            psk = cfg.preSharedKey?.trim('"')
        }
        if (ssid.isNullOrBlank() || psk.isNullOrBlank()) return null
        return Credentials(ssid, psk, ip, bssid)
    }

    private fun readActiveLegacyCredentials(): Credentials? {
        val mgr = wifi ?: return null
        return try {
            val isEnabled = mgr.javaClass.getMethod("isWifiApEnabled").invoke(mgr) as? Boolean ?: false
            if (!isEnabled && !NetworkUtils.isHotspotActive()) return null
            readLegacyApConfig(findApAddress())
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readLegacyApConfig(ip: String): Credentials? {
        val mgr = wifi ?: return null
        return try {
            val cfg = mgr.javaClass.getMethod("getWifiApConfiguration").invoke(mgr) as? WifiConfiguration
                ?: return null
            val ssid = cfg.SSID?.trim('"')
            val psk = cfg.preSharedKey?.trim('"')
            if (ssid.isNullOrBlank() || psk.isNullOrBlank()) return null
            Credentials(ssid, psk, ip)
        } catch (_: Exception) {
            null
        }
    }

    private fun pollApAddress(): String {
        repeat(IP_POLL_ATTEMPTS) {
            NetworkUtils.getApIpAddress()?.let { return it }
            try {
                Thread.sleep(IP_POLL_DELAY_MS)
            } catch (_: InterruptedException) {
                return findApAddress()
            }
        }
        return findApAddress()
    }

    /**
     * The head unit's address on its own hotspot, which differs from its normal Wi-Fi
     * address. Looks for the access point interface, then falls back to local/default AP IP.
     */
    private fun findApAddress(): String =
        NetworkUtils.getApIpAddress()
            ?: NetworkUtils.getLocalIpAddress(appContext).takeIf { it != "127.0.0.1" }
            ?: FALLBACK_AP_IP
}
