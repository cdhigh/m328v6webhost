package com.m328v6.host

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Manages USB OTG serial connections using usb-serial-for-android
 * (CH340/CH341, CP210x, FTDI, PL2303, CDC-ACM, etc.).
 */
class UsbSerialBridge(
    private val activity: Activity,
    private val callback: Callback
) : SerialInputOutputManager.Listener {

    interface Callback {
        fun onConnected(name: String, vid: Int, pid: Int, baudRate: Int, isAuto: Boolean)
        fun onData(chunk: String)
        fun onDisconnected(unexpected: Boolean, reason: String?)
        fun onPermissionDenied()
        fun onError(message: String)
    }

    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "M328V6-USB-IO").apply { isDaemon = true }
    }
    private val writeLock = Any()

    @Volatile
    private var serialPort: UsbSerialPort? = null

    @Volatile
    private var activeDevice: UsbDevice? = null

    @Volatile
    private var ioManager: SerialInputOutputManager? = null

    private var pendingDriver: UsbSerialDriver? = null
    private var pendingBaudRate: Int = 19200
    private var pendingIsAuto: Boolean = false
    private var receiverRegistered = false

    val isConnected: Boolean
        get() = serialPort != null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java
                    )
                    val driver = pendingDriver
                    val baud = pendingBaudRate
                    val isAuto = pendingIsAuto
                    pendingDriver = null

                    if (granted && driver != null && (device == null || device.deviceId == driver.device.deviceId)) {
                        openDriver(driver, baud, isAuto)
                    } else {
                        callback.onPermissionDenied()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = IntentCompat.getParcelableExtra(
                        intent,
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice::class.java
                    )
                    if (detached != null && activeDevice?.deviceId == detached.deviceId) {
                        disconnect(unexpected = true, reason = "USB detached")
                    }
                }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            activity,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            activity.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    fun findAllDrivers(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    fun connectDriver(driver: UsbSerialDriver, baudRate: Int, isAuto: Boolean = false) {
        connectToDriver(driver, baudRate, isAuto)
    }

    fun tryAutoConnect(baudRate: Int) {
        if (isConnected) return
        val drivers = findAllDrivers()
        val permitted = drivers.firstOrNull { usbManager.hasPermission(it.device) } ?: return
        openDriver(permitted, baudRate, isAuto = true)
    }

    private fun connectToDriver(driver: UsbSerialDriver, baudRate: Int, isAuto: Boolean) {
        if (usbManager.hasPermission(driver.device)) {
            openDriver(driver, baudRate, isAuto)
        } else {
            if (isAuto) return
            pendingDriver = driver
            pendingBaudRate = baudRate
            pendingIsAuto = false
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(activity, 0, intent, flags)
            usbManager.requestPermission(driver.device, pendingIntent)
        }
    }

    private fun openDriver(driver: UsbSerialDriver, baudRate: Int, isAuto: Boolean) {
        disconnect(unexpected = false, notify = false)

        val device = driver.device
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            callback.onError("Failed to open USB device")
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            callback.onError("No serial port on USB device")
            return
        }

        try {
            port.open(connection)
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            try {
                port.dtr = true
                port.rts = true
            } catch (_: UnsupportedOperationException) {
                // Some CDC adapters do not support DTR/RTS control lines
            }

            serialPort = port
            activeDevice = device

            val mgr = SerialInputOutputManager(port, this).apply {
                readBufferSize = 1024
                readTimeout = 200
            }
            ioManager = mgr
            ioExecutor.submit(mgr)

            SerialKeepAliveService.start(activity)

            val name = formatShortName(driver)
            callback.onConnected(name, device.vendorId, device.productId, baudRate, isAuto)
        } catch (e: Exception) {
            try {
                port.close()
            } catch (_: Exception) {
            }
            serialPort = null
            activeDevice = null
            callback.onError(e.message ?: "USB serial open failed")
        }
    }

    fun write(text: String) {
        val port = serialPort ?: return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        synchronized(writeLock) {
            try {
                port.write(bytes, WRITE_TIMEOUT_MS)
            } catch (e: IOException) {
                callback.onError("Write failed: ${e.message}")
                disconnect(unexpected = true, reason = e.message)
            }
        }
    }

    fun disconnect(unexpected: Boolean = false, reason: String? = null, notify: Boolean = true) {
        val wasOpen = serialPort != null
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
        } catch (_: Exception) {
        }
        serialPort = null
        activeDevice = null

        SerialKeepAliveService.stop(activity)

        if (notify && wasOpen) {
            callback.onDisconnected(unexpected, reason)
        }
    }

    fun release() {
        disconnect(unexpected = false, notify = false)
        unregister()
        ioExecutor.shutdownNow()
    }

    override fun onNewData(data: ByteArray) {
        if (data.isEmpty()) return
        val chunk = String(data, StandardCharsets.UTF_8)
        callback.onData(chunk)
    }

    override fun onRunError(e: Exception) {
        if (serialPort != null) {
            disconnect(unexpected = true, reason = e.message)
        }
    }

    private fun formatShortName(driver: UsbSerialDriver): String {
        val dev = driver.device
        val prod = dev.productName?.trim().orEmpty()
        if (prod.isNotEmpty()) return prod
        return driver.javaClass.simpleName.removeSuffix("SerialDriver")
    }

    fun formatDeviceLabel(driver: UsbSerialDriver): String {
        val dev = driver.device
        val name = formatShortName(driver)
        val vid = dev.vendorId.toString(16).uppercase().padStart(4, '0')
        val pid = dev.productId.toString(16).uppercase().padStart(4, '0')
        return "$name (VID:0x$vid PID:0x$pid)"
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.m328v6.host.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 1000
    }
}
