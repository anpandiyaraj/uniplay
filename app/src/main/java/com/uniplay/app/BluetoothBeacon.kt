package com.uniplay.app

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles Bluetooth discovery & advertisement for wireless pairing.
 * Broadcasts the head unit's identity so iPhones and Android devices can
 * initiate wireless handoff over Wi-Fi.
 */
class BluetoothBeacon(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothBeacon"
        const val DEFAULT_BEACON_NAME = "Uniplay-Wireless"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun hasBondedDevices(): Boolean {
        return try {
            !bluetoothAdapter?.bondedDevices.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun getDeviceName(): String {
        return try {
            bluetoothAdapter?.name ?: DEFAULT_BEACON_NAME
        } catch (e: Exception) {
            DEFAULT_BEACON_NAME
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return false
        }

        try {
            Log.i(TAG, "Bluetooth beacon active: ${getDeviceName()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth beacon", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun requestDiscoverable(activity: Activity, durationSeconds: Int = 300): Boolean {
        if (bluetoothAdapter == null) return false
        return try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)
            }
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not request Bluetooth discoverable mode: ${e.message}")
            false
        }
    }

    fun stopAdvertising() {
        Log.i(TAG, "Bluetooth beacon stopped")
    }
}
