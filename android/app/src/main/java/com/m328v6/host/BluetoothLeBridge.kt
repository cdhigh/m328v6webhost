package com.m328v6.host

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Modern Bluetooth Low Energy (BLE 4.0 / 4.2 / 5.0+) bridge for transparent UART modules:
 * - HC-08 / HM-10 / AT-09 / BT05 / CC2541 / JDY-08 / JDY-10 / JDY-19 / JDY-23 (Service 0xFFE0 / Char 0xFFE1)
 * - Nordic Semiconductor UART Service (NUS 6E400001-... / 6E400002 RX / 6E400003 TX)
 * - FFF0 Service (0xFFF0 / 0xFFF1 / 0xFFF2)
 * - Automatic fallback detection for any generic notify/write characteristic pair
 * Supports MTU negotiation (up to 247 bytes), CCCD descriptor notification enabling,
 * packet slicing, and live BLE scanning.
 */
class BluetoothLeBridge(
    private val activity: Activity,
    private val callback: Callback
) {

    interface Callback {
        fun onBleConnected(name: String, address: String, isAuto: Boolean)
        fun onBleData(chunk: String)
        fun onBleDisconnected(unexpected: Boolean, reason: String?)
        fun onBleError(message: String)
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "M328V6-BLE-Tx").apply { isDaemon = true }
    }

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var currentMtu: Int = DEFAULT_MTU

    @Volatile
    private var connecting = false

    @Volatile
    private var manualClosing = false

    @Volatile
    private var activeDeviceName: String = ""

    @Volatile
    private var activeDeviceAddress: String = ""

    @Volatile
    private var isScanning = false

    private var activeScanCallback: ScanCallback? = null

    val isConnected: Boolean
        get() = gatt != null && txCharacteristic != null && rxCharacteristic != null

    fun isSupported(): Boolean =
        bluetoothAdapter != null && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun isEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.trim().takeIf { !it.isNullOrEmpty() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedBleDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled || !hasConnectPermission()) return emptyList()
        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        return bonded.filter { dev ->
            try {
                dev.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    dev.type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                    dev.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
            } catch (_: SecurityException) {
                true
            }
        }.sortedWith(
            compareByDescending<BluetoothDevice> { isLikelyBleUart(safeDeviceName(it)) }
                .thenBy { safeDeviceName(it).lowercase() }
        )
    }

    @SuppressLint("MissingPermission")
    fun findDeviceByAddress(address: String): BluetoothDevice? {
        if (address.isBlank()) return null
        val adapter = bluetoothAdapter ?: return null
        return try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Start scanning for nearby advertising BLE devices.
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        durationMs: Long = 8000L,
        onDeviceFound: (BluetoothDevice, String, Int) -> Unit,
        onScanFinished: (() -> Unit)? = null
    ): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled || !hasScanPermission()) return false
        stopScan()

        val scanner = adapter.bluetoothLeScanner ?: return false
        val foundAddresses = mutableSetOf<String>()

        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val addr = dev.address ?: return
                if (foundAddresses.add(addr)) {
                    val name = safeDeviceName(dev)
                    val rssi = result.rssi
                    mainHandler.post {
                        onDeviceFound(dev, name, rssi)
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                mainHandler.post { onScanFinished?.invoke() }
            }
        }

        activeScanCallback = scanCb
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCb)
        } catch (_: Exception) {
            isScanning = false
            activeScanCallback = null
            return false
        }

        mainHandler.postDelayed({
            if (isScanning && activeScanCallback === scanCb) {
                stopScan()
                onScanFinished?.invoke()
            }
        }, durationMs)

        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        val cb = activeScanCallback
        activeScanCallback = null
        if (cb != null) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, isAuto: Boolean = false) {
        if (!hasConnectPermission()) {
            if (!isAuto) callback.onBleError("Bluetooth permission not granted")
            return
        }
        if (!isEnabled()) {
            if (!isAuto) callback.onBleError("Bluetooth is turned off")
            return
        }

        stopScan()
        disconnect(unexpected = false, notify = false)

        manualClosing = false
        connecting = true
        currentMtu = DEFAULT_MTU
        activeDeviceName = safeDeviceName(device)
        activeDeviceAddress = device.address

        try {
            val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    activity,
                    false,
                    gattCallback(isAuto),
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(
                    activity,
                    false,
                    gattCallback(isAuto)
                )
            }
            gatt = g
        } catch (e: Exception) {
            connecting = false
            if (!isAuto) callback.onBleError(e.message ?: "Failed to initiate BLE connection")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(unexpected: Boolean = false, notify: Boolean = true) {
        manualClosing = !unexpected
        connecting = false
        stopScan()

        val g = gatt
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null

        if (g != null) {
            try {
                g.disconnect()
            } catch (_: Exception) {}
            try {
                g.close()
            } catch (_: Exception) {}
        }

        SerialKeepAliveService.stop(activity)

        if (notify) {
            callback.onBleDisconnected(unexpected, if (unexpected) "BLE disconnected unexpectedly" else null)
        }
    }

    fun release() {
        stopScan()
        disconnect(unexpected = false, notify = false)
        sendExecutor.shutdownNow()
    }

    /**
     * Send string data to the BLE UART module.
     * Slices into chunks matching the negotiated MTU (or 20 bytes default) with pacing.
     */
    fun write(data: String) {
        val tx = txCharacteristic ?: return
        val g = gatt ?: return
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) return

        sendExecutor.submit {
            val maxPayload = (currentMtu - 3).coerceIn(20, 244)
            var offset = 0
            while (offset < bytes.size && isConnected) {
                val len = (bytes.size - offset).coerceAtMost(maxPayload)
                val chunk = bytes.copyOfRange(offset, offset + len)
                offset += len

                sendChunkDirect(g, tx, chunk)

                if (offset < bytes.size) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendChunkDirect(
        g: BluetoothGatt,
        tx: BluetoothGattCharacteristic,
        chunk: ByteArray
    ) {
        val writeType = if ((tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(tx, chunk, writeType)
        } else {
            @Suppress("DEPRECATION")
            tx.writeType = writeType
            @Suppress("DEPRECATION")
            tx.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(tx)
        }
    }

    private fun gattCallback(isAuto: Boolean) = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (connecting) {
                    connecting = false
                    disconnect(unexpected = false, notify = false)
                    if (!isAuto) {
                        callback.onBleError("BLE connect error (status: $status)")
                    }
                } else if (!manualClosing) {
                    disconnect(unexpected = true, notify = true)
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Request MTU 247 on Android 5.0+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val requested = g.requestMtu(TARGET_MTU)
                    if (!requested) {
                        g.discoverServices()
                    } else {
                        // Fallback discovery if MTU callback is not fired by buggy peripheral
                        mainHandler.postDelayed({
                            if (connecting && rxCharacteristic == null) {
                                g.discoverServices()
                            }
                        }, 1200L)
                    }
                } else {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting = false
                if (!manualClosing) {
                    disconnect(unexpected = true, notify = true)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                currentMtu = mtu
            }
            if (connecting && rxCharacteristic == null) {
                g.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false
                disconnect(unexpected = false, notify = false)
                if (!isAuto) callback.onBleError("BLE service discovery failed (status $status)")
                return
            }

            var rx: BluetoothGattCharacteristic? = null
            var tx: BluetoothGattCharacteristic? = null

            // 1. Check Standard 0xFFE0 Service (HC-08, HM-10, JDY-08/23, AT-09, BT05, CC2541)
            val ffe0 = g.getService(UUID_SERVICE_FFE0)
            if (ffe0 != null) {
                val ffe1 = ffe0.getCharacteristic(UUID_CHAR_FFE1)
                if (ffe1 != null) {
                    rx = ffe1
                    tx = ffe1
                }
            }

            // 2. Check Nordic NUS Service (6E400001-...)
            if (rx == null || tx == null) {
                val nus = g.getService(UUID_SERVICE_NUS)
                if (nus != null) {
                    tx = nus.getCharacteristic(UUID_CHAR_NUS_RX) // Host write to MCU
                    rx = nus.getCharacteristic(UUID_CHAR_NUS_TX) // MCU notify to Host
                }
            }

            // 3. Check 0xFFF0 Service
            if (rx == null || tx == null) {
                val fff0 = g.getService(UUID_SERVICE_FFF0)
                if (fff0 != null) {
                    for (c in fff0.characteristics) {
                        val p = c.properties
                        if (rx == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                            rx = c
                        }
                        if (tx == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                            tx = c
                        }
                    }
                }
            }

            // 4. Fallback: Search all discovered services for valid notify & write pair
            if (rx == null || tx == null) {
                for (s in g.services) {
                    for (c in s.characteristics) {
                        val p = c.properties
                        if (rx == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                            rx = c
                        }
                        if (tx == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                            tx = c
                        }
                    }
                    if (rx != null && tx != null) break
                }
            }

            if (rx == null || tx == null) {
                connecting = false
                disconnect(unexpected = false, notify = false)
                if (!isAuto) callback.onBleError("No compatible transparent UART service found on device")
                return
            }

            rxCharacteristic = rx
            txCharacteristic = tx

            // Enable Notifications on RX characteristic
            g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(UUID_CCCD)
            if (cccd != null) {
                val enableVal = if ((rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, enableVal)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = enableVal
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                markReady(isAuto)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == UUID_CCCD) {
                markReady(isAuto)
            }
        }

        // Android 13+ (API 33+) overload
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (value.isNotEmpty()) {
                val str = String(value, StandardCharsets.UTF_8)
                callback.onBleData(str)
            }
        }

        // Legacy pre-API 33 overload
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                if (value.isNotEmpty()) {
                    val str = String(value, StandardCharsets.UTF_8)
                    callback.onBleData(str)
                }
            }
        }
    }

    private fun markReady(isAuto: Boolean) {
        if (!connecting) return
        connecting = false
        SerialKeepAliveService.start(activity)
        callback.onBleConnected(activeDeviceName, activeDeviceAddress, isAuto)
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val TARGET_MTU = 247

        val UUID_SERVICE_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val UUID_CHAR_FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        val UUID_SERVICE_NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UUID_CHAR_NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val UUID_CHAR_NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        val UUID_SERVICE_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val BLE_KEYWORDS = listOf(
            "hc-08", "hm-10", "at-09", "bt05", "mlt-bt05", "jdy-08", "jdy-10", "jdy-19",
            "jdy-23", "jdy-33", "dx-bt", "kt6368", "ch9141", "m328", "load", "ble", "uart"
        )

        fun isLikelyBleUart(name: String): Boolean {
            val lower = name.lowercase()
            return BLE_KEYWORDS.any { lower.contains(it) }
        }
    }
}
