package com.uniplay.app

import android.content.Context

/**
 * How the receiver establishes the Wi-Fi link for wireless projection.
 */
enum class HotspotMode(val key: String) {
    /** Head unit default: tries LocalOnlyHotspot, falls back to saved SSID/PSK. */
    AUTO("auto"),
    /** Phone-to-phone: receiver phone hosts its own system Wi-Fi hotspot with saved SSID/PSK. */
    PHONE_HOTSPOT("phone_hotspot"),
    /** Reverse client: receiver joins the primary phone's hotspot and connects to its gateway IP. */
    CLIENT_GATEWAY("client_gateway");

    companion object {
        fun fromKey(key: String?): HotspotMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * Wireless connection preferences: hotspot mode, credentials, and Bluetooth handoff behavior.
 * Android does not let an app read the system hotspot's SSID/password, so the phone is told
 * what the user typed here; a made-up password would never match the real access point.
 */
class HotspotSettings(context: Context) {

    private val prefs = context.getSharedPreferences("hotspot", Context.MODE_PRIVATE)

    val mode: HotspotMode
        get() = HotspotMode.fromKey(prefs.getString(KEY_MODE, HotspotMode.AUTO.key))

    val ssid: String get() = prefs.getString(KEY_SSID, "") ?: ""
    val psk: String get() = prefs.getString(KEY_PSK, "") ?: ""
    val bssid: String get() = prefs.getString(KEY_BSSID, "") ?: ""

    /**
     * When enabled, keeps the Android Auto RFCOMM server resilient across transient socket
     * errors and retries waking bonded phones until a handoff succeeds.
     */
    val aggressiveBluetooth: Boolean
        get() = prefs.getBoolean(KEY_AGGRESSIVE_BT, false)

    /** WPA2 needs an 8 to 63 character passphrase. */
    val isConfigured: Boolean get() = ssid.isNotBlank() && psk.length in 8..63

    fun save(ssid: String, psk: String) {
        prefs.edit().putString(KEY_SSID, ssid.trim()).putString(KEY_PSK, psk).apply()
    }

    fun save(
        mode: HotspotMode,
        ssid: String,
        psk: String,
        aggressiveBluetooth: Boolean,
        bssid: String = ""
    ) {
        prefs.edit()
            .putString(KEY_MODE, mode.key)
            .putString(KEY_SSID, ssid.trim())
            .putString(KEY_PSK, psk)
            .putString(KEY_BSSID, bssid.trim().uppercase())
            .putBoolean(KEY_AGGRESSIVE_BT, aggressiveBluetooth)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_SSID = "ssid"
        const val KEY_PSK = "psk"
        const val KEY_BSSID = "bssid"
        const val KEY_AGGRESSIVE_BT = "aggressive_bt"
    }
}
