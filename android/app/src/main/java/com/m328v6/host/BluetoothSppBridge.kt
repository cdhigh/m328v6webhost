package com.m328v6.host

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Classic Bluetooth SPP (RFCOMM) bridge for HC-06 / HC-05 / JDY-31 serial modules.
 * Uses Android's built-in Bluetooth APIs with standard SPP UUID (zero extra libraries).
 */
class BluetoothSppBridge(
    private val activity: Activity,
    private val callback: Callback
) {

    interface Callback {
        fun onBtConnected(name: String, address: String, isAuto: Boolean)
        fun onBtData(chunk: String)
        fun onBtDisconnected(unexpected: Boolean, reason: String?)
        fun onBtError(message: String)
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val connectExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "M328V6-BT-Connect").apply { isDaemon = true }
    }
    private val writeLock = Any()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    @Volatile
    private var readThread: Thread? = null

    @Volatile
    private var manualClosing = false

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun isSupported(): Boolean = bluetoothAdapter != null

    fun isEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Returns bonded (paired) classic Bluetooth devices, prioritizing HC-06 / HC-05 / SPP names.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled || !hasConnectPermission()) return emptyList()
        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        return bonded
            .filter { dev ->
                // Exclude pure BLE-only devices if type is known
                try {
                    dev.type != BluetoothDevice.DEVICE_TYPE_LE
                } catch (_: SecurityException) {
                    true
                }
            }
            .sortedWith(
                compareByDescending<BluetoothDevice> { isLikelySerialDevice(safeDeviceName(it)) }
                    .thenBy { safeDeviceName(it).lowercase() }
            )
    }

    @SuppressLint("MissingPermission")
    fun findPairedByAddress(address: String): BluetoothDevice? {
        if (address.isBlank()) return null
        return getPairedDevices().firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.trim().takeIf { !it.isNullOrEmpty() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    fun formatDeviceLabel(device: BluetoothDevice): String {
        val name = safeDeviceName(device)
        return if (name == device.address) name else "$name (${device.address})"
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, isAuto: Boolean = false) {
        if (!hasConnectPermission()) {
            if (!isAuto) callback.onBtError("Bluetooth permission not granted")
            return
        }
        if (!isEnabled()) {
            if (!isAuto) callback.onBtError("Bluetooth is turned off")
            return
        }

        val devName = safeDeviceName(device)
        val devAddr = device.address

        connectExecutor.submit {
            disconnect(unexpected = false, notify = false)
            manualClosing = false

            try {
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (_: Exception) {
                }

                val connectedSocket = openRfcommWithFallback(device)
                val inStream = connectedSocket.inputStream
                val outStream = connectedSocket.outputStream

                socket = connectedSocket
                outputStream = outStream

                SerialKeepAliveService.start(activity)
                callback.onBtConnected(devName, devAddr, isAuto)
                startReadLoop(connectedSocket, inStream)
            } catch (e: Exception) {
                disconnect(unexpected = false, notify = false)
                if (!isAuto) {
                    callback.onBtError(e.message ?: "Bluetooth SPP connect failed")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRfcommWithFallback(device: BluetoothDevice): BluetoothSocket {
        // 1. Try standard secure RFCOMM SPP socket first
        var primaryErr: Exception? = null
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            return s
        } catch (e: Exception) {
            primaryErr = e
        }

        // 2. Fallback to insecure RFCOMM (many HC-06 / HC-05 clones prefer insecure RFCOMM)
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            return s
        } catch (e: Exception) {
            throw IOException(primaryErr?.message ?: e.message ?: "RFCOMM connect failed", e)
        }
    }

    private fun startReadLoop(activeSocket: BluetoothSocket, inStream: InputStream) {
        val t = Thread({
            val buf = ByteArray(1024)
            try {
                while (!manualClosing && socket === activeSocket) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val chunk = String(buf, 0, n, StandardCharsets.UTF_8)
                        callback.onBtData(chunk)
                    }
                }
            } catch (e: IOException) {
                if (!manualClosing && socket === activeSocket) {
                    disconnect(unexpected = true, reason = e.message)
                    return@Thread
                }
            }
            if (!manualClosing && socket === activeSocket) {
                disconnect(unexpected = true, reason = "Bluetooth link closed")
            }
        }, "M328V6-BT-RX").apply {
            isDaemon = true
        }
        readThread = t
        t.start()
    }

    fun write(text: String) {
        val out = outputStream ?: return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        synchronized(writeLock) {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                callback.onBtError("Bluetooth write failed: ${e.message}")
                disconnect(unexpected = true, reason = e.message)
            }
        }
    }

    fun disconnect(unexpected: Boolean = false, reason: String? = null, notify: Boolean = true) {
        val wasOpen = socket != null
        manualClosing = !unexpected
        outputStream = null
        val s = socket
        socket = null
        try {
            s?.close()
        } catch (_: Exception) {
        }
        readThread?.interrupt()
        readThread = null

        if (wasOpen) {
            SerialKeepAliveService.stop(activity)
            if (notify) {
                callback.onBtDisconnected(unexpected, reason)
            }
        }
    }

    fun release() {
        disconnect(unexpected = false, notify = false)
        connectExecutor.shutdownNow()
    }

    private fun isLikelySerialDevice(name: String): Boolean {
        val u = name.uppercase()
        return u.contains("HC-06") || u.contains("HC-05") || u.contains("HC06") ||
            u.contains("HC05") || u.contains("M328") || u.contains("SPP") ||
            u.contains("JDY") || u.contains("BT04") || u.contains("BT05") ||
            u.contains("LOAD") || u.contains("SERIAL")
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
