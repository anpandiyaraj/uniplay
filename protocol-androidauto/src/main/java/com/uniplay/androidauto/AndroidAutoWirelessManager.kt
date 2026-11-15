package com.uniplay.androidauto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Auto Official Wireless Bluetooth Handshake Server.
 * Listens on the standardized Android Auto RFCOMM UUID: 4de17a00-52cb-11e6-bdf4-0800200c9a66.
 * Automatically exchanges Wi-Fi credentials with Android phones so they switch on Wi-Fi
 * and connect to the Uniplay TCP server.
 */
class AndroidAutoWirelessManager(
    private val context: Context,
    private val onPhoneHandoffInitiated: (phoneName: String) -> Unit = {},
    private val onBluetoothError: (reason: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AAWirelessManager"
        // Official Google Android Auto Wireless Bluetooth RFCOMM UUID
        val AA_BT_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private const val MAX_BIND_RETRIES = 5
        private const val POKE_RETRY_DELAY_MS = 5000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val isRunning = AtomicBoolean(false)
    private val handoffCompleted = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var serverThread: Thread? = null
    private var pokeThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(
        ssid: String,
        psk: String,
        localIp: String,
        port: Int = 5000,
        aggressiveMode: Boolean = false,
        bssid: String = ""
    ) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled. Android Auto RFCOMM handshake unavailable.")
            return
        }

        if (isRunning.getAndSet(true)) return
        handoffCompleted.set(false)

        serverThread = Thread({
            runRfcommServer(ssid, psk, localIp, port, aggressiveMode, bssid)
        }, "AA-Wireless-BT-Server").apply {
            isDaemon = true
            start()
        }

        // Also proactively poke any already-paired phones to wake their Android Auto service
        pokeBondedDevices(ssid, psk, localIp, port, aggressiveMode, bssid)
    }

    @SuppressLint("MissingPermission")
    fun pokeBondedDevices(
        ssid: String,
        psk: String,
        localIp: String,
        port: Int = 5000,
        aggressiveMode: Boolean = false,
        bssid: String = ""
    ) {
        val adapter = bluetoothAdapter ?: return
        pokeThread?.interrupt()
        pokeThread = Thread({
            try {
                do {
                    val bonded = adapter.bondedDevices ?: return@Thread
                    for (device in bonded) {
                        if (!isRunning.get() || handoffCompleted.get()) return@Thread
                        if (tryWakeBondedDevice(device, ssid, psk, localIp, port, bssid)) {
                            handoffCompleted.set(true)
                            return@Thread
                        }
                    }
                    if (!aggressiveMode || !isRunning.get() || handoffCompleted.get()) break
                    Thread.sleep(POKE_RETRY_DELAY_MS)
                } while (isRunning.get() && !handoffCompleted.get())
            } catch (_: InterruptedException) {
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth permission missing while waking paired devices: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Error enumerating bonded devices: ${e.message}")
            }
        }, "AA-Wireless-BT-Poke").apply {
            isDaemon = true
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryWakeBondedDevice(
        device: BluetoothDevice,
        ssid: String,
        psk: String,
        localIp: String,
        port: Int,
        bssid: String
    ): Boolean {
        return try {
            val deviceName = try { device.name } catch (_: Exception) { "Paired Device" }
            Log.i(TAG, "Proactively waking paired device: $deviceName (${device.address})...")
            val socket = device.createRfcommSocketToServiceRecord(AA_BT_UUID)
            socket.connect()
            Log.i(TAG, "Connected to $deviceName! Sending Wi-Fi handoff...")
            onPhoneHandoffInitiated(deviceName)
            handleBluetoothHandshake(socket, ssid, psk, localIp, port, bssid)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Device ${device.address} did not answer active poke: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRfcommServer(
        ssid: String,
        psk: String,
        localIp: String,
        port: Int,
        aggressiveMode: Boolean,
        bssid: String
    ) {
        var consecutiveFailures = 0
        try {
            while (isRunning.get()) {
                try {
                    val server = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                        "Android Auto Wireless",
                        AA_BT_UUID
                    ) ?: break
                    serverSocket = server
                    Log.i(TAG, "Android Auto Wireless RFCOMM server active on UUID: $AA_BT_UUID (aggressive=$aggressiveMode)")

                    while (isRunning.get()) {
                        val socket = server.accept() ?: break
                        consecutiveFailures = 0
                        handoffCompleted.set(true)
                        val remoteName = try { socket.remoteDevice.name ?: "Android Device" } catch (_: Exception) { "Android Device" }
                        Log.i(TAG, "Accepted Bluetooth Android Auto connection from: $remoteName (${socket.remoteDevice.address})")

                        onPhoneHandoffInitiated(remoteName)
                        Thread({
                            handleBluetoothHandshake(socket, ssid, psk, localIp, port, bssid)
                        }, "AA-BT-Handshake").apply {
                            isDaemon = true
                            start()
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Bluetooth permission denied for RFCOMM server: ${e.message}")
                    onBluetoothError("Bluetooth permission required for wireless handoff")
                    break
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    consecutiveFailures++
                    Log.w(TAG, "RFCOMM server error (attempt $consecutiveFailures): ${e.message}")
                    try { serverSocket?.close() } catch (_: Exception) {}
                    serverSocket = null
                    if (!aggressiveMode || consecutiveFailures >= MAX_BIND_RETRIES) {
                        onBluetoothError("Bluetooth RFCOMM server stopped: ${e.message}")
                        break
                    }
                    try {
                        Thread.sleep(1000L * consecutiveFailures)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            stop()
        }
    }

    private fun handleBluetoothHandshake(
        socket: BluetoothSocket,
        ssid: String,
        psk: String,
        localIp: String,
        port: Int,
        explicitBssid: String = ""
    ) {
        try {
            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream
            val bssid = explicitBssid.ifBlank { resolveApBssid() }
            val sentWifiInfo = AtomicBoolean(false)

            // 1. Send WifiStartRequest (Type 1)
            Log.i(TAG, "Sending WifiStartRequest to phone (Target: $localIp:$port)...")
            val startRequest = Wireless.WifiStartRequest.newBuilder()
                .setIpAddress(localIp)
                .setPort(port)
                .build()
            sendWirelessProtobuf(output, 1, startRequest.toByteArray())

            // Fallback: if the phone doesn't send WifiInfoRequest within 2.5s, proactively deliver credentials.
            val fallbackSender = Thread({
                try {
                    Thread.sleep(2500)
                    if (sentWifiInfo.compareAndSet(false, true)) {
                        sendWifiInfoResponse(output, ssid, psk, bssid)
                    }
                } catch (_: InterruptedException) {
                } catch (_: Exception) {
                }
            }, "AA-BT-FallbackInfo").apply {
                isDaemon = true
                start()
            }

            // 2. Keep the RFCOMM socket open and respond to the phone's handshake messages.
            try {
                while (isRunning.get() && socket.isConnected) {
                    val payloadLength = try {
                        input.readUnsignedShort()
                    } catch (_: EOFException) {
                        break
                    }
                    if (payloadLength > 16384) {
                        Log.w(TAG, "Unexpected RFCOMM wireless payload length: $payloadLength")
                        break
                    }
                    val type = input.readUnsignedShort()
                    val payload = ByteArray(payloadLength)
                    input.readFully(payload)

                    when (type) {
                        2 -> { // WifiInfoRequest
                            Log.i(TAG, "Received WifiInfoRequest from phone")
                            if (sentWifiInfo.compareAndSet(false, true)) {
                                sendWifiInfoResponse(output, ssid, psk, bssid)
                            } else {
                                sendWifiInfoResponse(output, ssid, psk, bssid)
                            }
                        }
                        4 -> { // WifiVersionRequest
                            val req = runCatching { Wireless.WifiVersionRequest.parseFrom(payload) }.getOrNull()
                            Log.i(TAG, "Received WifiVersionRequest (${req?.major}.${req?.minor})")
                            val resp = Wireless.WifiVersionResponse.newBuilder()
                                .setMajor(1)
                                .setMinor(0)
                                .setStatus(0)
                                .build()
                            sendWirelessProtobuf(output, 5, resp.toByteArray())
                        }
                        6 -> { // WifiConnectStatus
                            val status = runCatching { Wireless.WifiConnectStatus.parseFrom(payload) }.getOrNull()
                            Log.i(TAG, "Phone reported WifiConnectStatus: status=${status?.status}")
                        }
                        7 -> { // WifiStartResponse
                            val resp = runCatching { Wireless.WifiStartResponse.parseFrom(payload) }.getOrNull()
                            Log.i(TAG, "Phone reported WifiStartResponse: status=${resp?.status}, ip=${resp?.ipAddress}")
                        }
                        8 -> { // WifiPingRequest
                            val ping = Wireless.WifiPingResponse.newBuilder().build()
                            sendWirelessProtobuf(output, 9, ping.toByteArray())
                        }
                        else -> {
                            Log.d(TAG, "Received RFCOMM wireless message type=$type (${payload.size}B)")
                        }
                    }
                }
            } finally {
                fallbackSender.interrupt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during Bluetooth handshake with phone: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendWifiInfoResponse(output: OutputStream, ssid: String, psk: String, bssid: String) {
        val effectiveBssid = bssid.ifBlank { "00:00:00:00:00:00" }
        Log.i(TAG, "Delivering Wi-Fi credentials to phone: SSID=$ssid (bssid=$effectiveBssid)...")
        val infoResponse = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(psk)
            .setBssid(effectiveBssid)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(Wireless.AccessPointType.DYNAMIC)
            .build()
        sendWirelessProtobuf(output, 3, infoResponse.toByteArray())
        Log.i(TAG, "Wi-Fi credentials delivered! Waiting for phone to associate and connect.")
    }

    private fun resolveApBssid(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                val isAp = name.startsWith("ap") || name.startsWith("swlan") ||
                    name.startsWith("softap") || name == "wlan1"
                if (!isAp || !intf.isUp) continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.size == 6) {
                    return mac.joinToString(":") { "%02X".format(it) }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun sendWirelessProtobuf(output: OutputStream, type: Short, payload: ByteArray) {
        // Wireless Protobuf framing: [PayloadLength(2B)][Type(2B)][Payload]
        val payloadLength = payload.size
        val buffer = ByteArray(4 + payloadLength)
        buffer[0] = ((payloadLength shr 8) and 0xFF).toByte()
        buffer[1] = (payloadLength and 0xFF).toByte()
        buffer[2] = ((type.toInt() shr 8) and 0xFF).toByte()
        buffer[3] = (type.toInt() and 0xFF).toByte()
        System.arraycopy(payload, 0, buffer, 4, payloadLength)

        synchronized(output) {
            output.write(buffer)
            output.flush()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping Android Auto Wireless Bluetooth server...")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        pokeThread?.interrupt()
        pokeThread = null
    }
}
