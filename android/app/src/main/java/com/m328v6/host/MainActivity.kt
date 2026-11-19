package com.m328v6.host

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.hoho.android.usbserial.driver.UsbSerialDriver
import org.json.JSONObject

class MainActivity : AppCompatActivity(),
    UsbSerialBridge.Callback,
    BluetoothSppBridge.Callback,
    BluetoothLeBridge.Callback {

    private lateinit var webView: WebView
    private lateinit var usbBridge: UsbSerialBridge
    private lateinit var btBridge: BluetoothSppBridge
    private lateinit var bleBridge: BluetoothLeBridge

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressedAt: Long = 0L
    private var pendingPickerBaudRate: Int = 19200
    private var reopenPickerAfterBtAction: Boolean = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val isAnyConnected: Boolean
        get() = usbBridge.isConnected || btBridge.isConnected || bleBridge.isConnected

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@registerForActivityResult

        val uris = if (result.resultCode == RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        cb.onReceiveValue(uris)
    }

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val shouldReopen = reopenPickerAfterBtAction
        reopenPickerAfterBtAction = false
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (shouldReopen) showUnifiedDevicePicker(pendingPickerBaudRate)
        } else {
            emitJsEvent("permissionDenied", "{}")
        }
    }

    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val shouldReopen = reopenPickerAfterBtAction
        reopenPickerAfterBtAction = false
        if (shouldReopen && btBridge.isEnabled()) {
            showUnifiedDevicePicker(pendingPickerBaudRate)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbBridge = UsbSerialBridge(this, this)
        usbBridge.register()
        btBridge = BluetoothSppBridge(this, this)
        bleBridge = BluetoothLeBridge(this, this)

        requestNotificationPermissionIfNeeded()

        webView = WebView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_bar_dark))
        }
        setContentView(webView)

        // Respect system status/navigation bars & IME keyboard cleanly on Android 15+ edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message.orEmpty())
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.addJavascriptInterface(AndroidSerialJsInterface(), "AndroidSerial")
        webView.loadUrl(HOST_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isAnyConnected) {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressedAt < 2000L) {
                        finish()
                    } else {
                        lastBackPressedAt = now
                        Toast.makeText(this@MainActivity, R.string.msg_back_confirm, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && !isAnyConnected) {
            usbBridge.tryAutoConnect(pendingPickerBaudRate)
        }
    }

    override fun onDestroy() {
        usbBridge.release()
        btBridge.release()
        bleBridge.release()
        webView.removeJavascriptInterface("AndroidSerial")
        webView.destroy()
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF_PERM
                )
            }
        }
    }

    private fun requestBtPermissions() {
        reopenPickerAfterBtAction = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            btPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    // ==================== Unified USB OTG + SPP + BLE Picker ====================
    private sealed class PickerEntry {
        class Usb(val driver: UsbSerialDriver) : PickerEntry()
        class Spp(val device: BluetoothDevice) : PickerEntry()
        class Ble(val device: BluetoothDevice) : PickerEntry()
        object BleScanAction : PickerEntry()
        object BtGrant : PickerEntry()
        object BtEnable : PickerEntry()
        object BtSettings : PickerEntry()
    }

    private fun showUnifiedDevicePicker(baudRate: Int) {
        pendingPickerBaudRate = baudRate
        val usbDrivers = usbBridge.findAllDrivers()
        val btSupported = btBridge.isSupported() || bleBridge.isSupported()
        val btPermitted = btBridge.hasConnectPermission() && bleBridge.hasConnectPermission()
        val btEnabled = btBridge.isEnabled()

        val sppDevices = if (btSupported && btPermitted && btEnabled) btBridge.getPairedDevices() else emptyList()
        val blePaired = if (btSupported && btPermitted && btEnabled) bleBridge.getPairedBleDevices() else emptyList()

        // Fast path: 1 USB OTG device attached and Bluetooth is off/unsupported -> connect directly
        if (usbDrivers.size == 1 && (!btSupported || !btEnabled)) {
            btBridge.disconnect(unexpected = false, notify = false)
            bleBridge.disconnect(unexpected = false, notify = false)
            usbBridge.connectDriver(usbDrivers[0], baudRate, isAuto = false)
            return
        }

        // If no USB attached and Android 12+ Bluetooth permission not yet granted, prompt immediately
        if (usbDrivers.isEmpty() && btSupported && !btPermitted) {
            requestBtPermissions()
            return
        }

        val entries = mutableListOf<PickerEntry>()
        usbDrivers.forEach { d ->
            entries.add(PickerEntry.Usb(d))
        }
        sppDevices.forEach { dev ->
            entries.add(PickerEntry.Spp(dev))
        }
        blePaired.forEach { dev ->
            entries.add(PickerEntry.Ble(dev))
        }

        if (btSupported && btEnabled && btPermitted) {
            entries.add(PickerEntry.BleScanAction)
        }

        if (btSupported) {
            when {
                !btPermitted -> entries.add(PickerEntry.BtGrant)
                !btEnabled -> entries.add(PickerEntry.BtEnable)
                else -> entries.add(PickerEntry.BtSettings)
            }
        }

        if (entries.isEmpty()) {
            onError(getString(R.string.msg_no_usb_device))
            return
        }

        val adapter = object : ArrayAdapter<PickerEntry>(this, 0, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.dialog_device_item, parent, false)
                val item = getItem(position) ?: return view

                val sectionHeader = view.findViewById<TextView>(R.id.section_header)
                val badge = view.findViewById<TextView>(R.id.item_badge)
                val title = view.findViewById<TextView>(R.id.item_title)
                val subtitle = view.findViewById<TextView>(R.id.item_subtitle)
                val divider = view.findViewById<View>(R.id.item_divider)

                val prevItem = if (position > 0) getItem(position - 1) else null
                val showHeader = prevItem == null || prevItem::class != item::class
                if (showHeader) {
                    sectionHeader.visibility = View.VISIBLE
                    sectionHeader.text = when (item) {
                        is PickerEntry.Usb -> getString(R.string.section_usb)
                        is PickerEntry.Spp -> getString(R.string.section_bluetooth)
                        is PickerEntry.Ble -> getString(R.string.section_ble)
                        else -> getString(R.string.section_actions)
                    }
                } else {
                    sectionHeader.visibility = View.GONE
                }

                val nextItem = if (position < count - 1) getItem(position + 1) else null
                val hideDivider = nextItem == null || nextItem::class != item::class
                divider.visibility = if (hideDivider) View.GONE else View.VISIBLE

                fun setBadge(text: String, bgColor: Int) {
                    badge.text = text
                    val gd = GradientDrawable().apply {
                        cornerRadius = 4f * resources.displayMetrics.density
                        setColor(bgColor)
                    }
                    badge.background = gd
                }

                when (item) {
                    is PickerEntry.Usb -> {
                        setBadge("USB", 0xFF2563EB.toInt())
                        val prod = item.driver.device.productName
                        val devName = item.driver.device.deviceName
                        title.text = if (!prod.isNullOrBlank()) prod else "USB Serial Device"
                        val vid = String.format("0x%04X", item.driver.device.vendorId)
                        val pid = String.format("0x%04X", item.driver.device.productId)
                        subtitle.text = "$devName (VID:$vid PID:$pid)"
                    }
                    is PickerEntry.Spp -> {
                        setBadge("SPP", 0xFF0D9488.toInt())
                        val name = btBridge.safeDeviceName(item.device)
                        title.text = name
                        subtitle.text = item.device.address
                    }
                    is PickerEntry.Ble -> {
                        setBadge("BLE", 0xFF6366F1.toInt())
                        val name = bleBridge.safeDeviceName(item.device)
                        title.text = name
                        subtitle.text = item.device.address
                    }
                    is PickerEntry.BleScanAction -> {
                        setBadge("搜索", 0xFF059669.toInt())
                        title.text = getString(R.string.msg_ble_scan_title)
                        subtitle.text = getString(R.string.msg_ble_scan_subtitle)
                    }
                    is PickerEntry.BtGrant -> {
                        setBadge("权限", 0xFFD97706.toInt())
                        title.text = getString(R.string.msg_bt_grant_title)
                        subtitle.text = getString(R.string.msg_bt_grant_subtitle)
                    }
                    is PickerEntry.BtEnable -> {
                        setBadge("开启", 0xFFD97706.toInt())
                        title.text = getString(R.string.msg_bt_enable_title)
                        subtitle.text = getString(R.string.msg_bt_enable_subtitle)
                    }
                    is PickerEntry.BtSettings -> {
                        setBadge("设置", 0xFF64748B.toInt())
                        title.text = getString(R.string.msg_bt_settings_title)
                        subtitle.text = getString(R.string.msg_bt_settings_subtitle)
                    }
                }
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_device)
            .setAdapter(adapter) { _, which ->
                when (val item = entries[which]) {
                    is PickerEntry.Usb -> {
                        btBridge.disconnect(unexpected = false, notify = false)
                        bleBridge.disconnect(unexpected = false, notify = false)
                        usbBridge.connectDriver(item.driver, baudRate, isAuto = false)
                    }

                    is PickerEntry.Spp -> {
                        usbBridge.disconnect(unexpected = false, notify = false)
                        bleBridge.disconnect(unexpected = false, notify = false)
                        val name = btBridge.safeDeviceName(item.device)
                        Toast.makeText(this, getString(R.string.msg_bt_connecting, name), Toast.LENGTH_SHORT).show()
                        btBridge.connect(item.device, isAuto = false)
                    }

                    is PickerEntry.Ble -> {
                        usbBridge.disconnect(unexpected = false, notify = false)
                        btBridge.disconnect(unexpected = false, notify = false)
                        val name = bleBridge.safeDeviceName(item.device)
                        Toast.makeText(this, getString(R.string.msg_ble_connecting, name), Toast.LENGTH_SHORT).show()
                        bleBridge.connect(item.device, isAuto = false)
                    }

                    is PickerEntry.BleScanAction -> {
                        showBleScanDialog()
                    }

                    is PickerEntry.BtGrant -> {
                        requestBtPermissions()
                    }

                    is PickerEntry.BtEnable -> {
                        reopenPickerAfterBtAction = true
                        btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }

                    is PickerEntry.BtSettings -> {
                        try {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (_: Exception) {}
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBleScanDialog() {
        if (!bleBridge.hasScanPermission()) {
            requestBtPermissions()
            return
        }

        val scannedDevices = mutableListOf<BluetoothDevice>()
        val deviceAdapter = object : ArrayAdapter<BluetoothDevice>(this, 0, scannedDevices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.dialog_device_item, parent, false)
                val dev = getItem(position) ?: return view

                view.findViewById<TextView>(R.id.section_header).visibility = View.GONE
                val badge = view.findViewById<TextView>(R.id.item_badge)
                val title = view.findViewById<TextView>(R.id.item_title)
                val subtitle = view.findViewById<TextView>(R.id.item_subtitle)
                view.findViewById<View>(R.id.item_divider).visibility = View.VISIBLE

                badge.text = "BLE"
                val gd = GradientDrawable().apply {
                    cornerRadius = 4f * resources.displayMetrics.density
                    setColor(0xFF6366F1.toInt())
                }
                badge.background = gd

                title.text = bleBridge.safeDeviceName(dev)
                subtitle.text = dev.address
                return view
            }
        }

        var scanDialog: AlertDialog? = null

        scanDialog = AlertDialog.Builder(this)
            .setTitle(R.string.msg_ble_scanning)
            .setAdapter(deviceAdapter) { _, which ->
                val dev = scannedDevices[which]
                bleBridge.stopScan()
                usbBridge.disconnect(unexpected = false, notify = false)
                btBridge.disconnect(unexpected = false, notify = false)
                val name = bleBridge.safeDeviceName(dev)
                Toast.makeText(this, getString(R.string.msg_ble_connecting, name), Toast.LENGTH_SHORT).show()
                bleBridge.connect(dev, isAuto = false)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                bleBridge.stopScan()
            }
            .setOnDismissListener {
                bleBridge.stopScan()
            }
            .create()

        scanDialog.show()

        bleBridge.startScan(
            durationMs = 9000L,
            onDeviceFound = { dev, _, _ ->
                if (scannedDevices.none { it.address.equals(dev.address, ignoreCase = true) }) {
                    scannedDevices.add(dev)
                    deviceAdapter.notifyDataSetChanged()
                }
            },
            onScanFinished = {
                if (scannedDevices.isEmpty()) {
                    scanDialog.setTitle("未发现附近 BLE 设备")
                } else {
                    scanDialog.setTitle("扫描完成，请选择 BLE 设备")
                }
            }
        )
    }

    private fun tryAutoConnectLastTransport(baudRate: Int) {
        if (isAnyConnected) return
        val lastTransport = prefs.getString(KEY_LAST_TRANSPORT, "usb")
        if (lastTransport == "ble") {
            val addr = prefs.getString(KEY_LAST_BLE_ADDR, "").orEmpty()
            val dev = bleBridge.findDeviceByAddress(addr)
            if (dev != null) {
                bleBridge.connect(dev, isAuto = true)
                return
            }
        } else if (lastTransport == "bt") {
            val addr = prefs.getString(KEY_LAST_BT_ADDR, "").orEmpty()
            val dev = btBridge.findPairedByAddress(addr)
            if (dev != null) {
                btBridge.connect(dev, isAuto = true)
                return
            }
        }
        usbBridge.tryAutoConnect(baudRate)
    }

    // ==================== JS <-> Native Bridge ====================
    inner class AndroidSerialJsInterface {

        @JavascriptInterface
        fun connect(baudRate: Int) {
            val safeBaud = if (baudRate > 0) baudRate else 19200
            runOnUiThread { showUnifiedDevicePicker(safeBaud) }
        }

        @JavascriptInterface
        fun tryAutoConnect(baudRate: Int) {
            val safeBaud = if (baudRate > 0) baudRate else 19200
            runOnUiThread { tryAutoConnectLastTransport(safeBaud) }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                usbBridge.disconnect(unexpected = false, notify = false)
                btBridge.disconnect(unexpected = false, notify = false)
                bleBridge.disconnect(unexpected = false, notify = false)
            }
        }

        @JavascriptInterface
        fun write(data: String) {
            when {
                usbBridge.isConnected -> usbBridge.write(data)
                btBridge.isConnected -> btBridge.write(data)
                bleBridge.isConnected -> bleBridge.write(data)
            }
        }

        @JavascriptInterface
        fun setKeepAwake(enabled: Boolean) {
            runOnUiThread {
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            runOnUiThread {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("M328V6", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun shareText(title: String, text: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(intent, title))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveFile(filename: String, mimeType: String, base64Data: String) {
            Thread {
                try {
                    val saved = FileExportHelper.saveBase64ToDownloads(
                        applicationContext,
                        filename,
                        mimeType,
                        base64Data
                    )
                    val json = JSONObject().apply {
                        put("filename", saved.filename)
                        put("path", saved.displayPath)
                    }
                    emitJsEvent("fileSaved", json.toString())
                } catch (e: Exception) {
                    val json = JSONObject().apply {
                        put("message", e.message ?: "Save failed")
                    }
                    emitJsEvent("error", json.toString())
                }
            }.start()
        }
    }

    // ==================== UsbSerialBridge.Callback ====================
    override fun onConnected(name: String, vid: Int, pid: Int, baudRate: Int, isAuto: Boolean) {
        prefs.edit().putString(KEY_LAST_TRANSPORT, "usb").apply()
        val json = JSONObject().apply {
            put("transport", "usb")
            put("name", name)
            put("vid", vid)
            put("pid", pid)
            put("baud", baudRate)
            put("isAuto", isAuto)
        }
        emitJsEvent("connected", json.toString())
    }

    override fun onData(chunk: String) {
        emitJsEvent("data", chunk)
    }

    override fun onDisconnected(unexpected: Boolean, reason: String?) {
        val json = JSONObject().apply {
            put("unexpected", unexpected)
            if (!reason.isNullOrBlank()) put("reason", reason)
        }
        emitJsEvent("disconnected", json.toString())
    }

    override fun onPermissionDenied() {
        emitJsEvent("permissionDenied", "{}")
    }

    override fun onError(message: String) {
        val json = JSONObject().apply { put("message", message) }
        emitJsEvent("error", json.toString())
    }

    // ==================== BluetoothSppBridge.Callback ====================
    override fun onBtConnected(name: String, address: String, isAuto: Boolean) {
        prefs.edit()
            .putString(KEY_LAST_TRANSPORT, "bt")
            .putString(KEY_LAST_BT_ADDR, address)
            .apply()
        val json = JSONObject().apply {
            put("transport", "bt")
            put("name", name)
            put("address", address)
            put("baud", 0)
            put("isAuto", isAuto)
        }
        emitJsEvent("connected", json.toString())
    }

    override fun onBtData(chunk: String) {
        emitJsEvent("data", chunk)
    }

    override fun onBtDisconnected(unexpected: Boolean, reason: String?) {
        onDisconnected(unexpected, reason)
    }

    override fun onBtError(message: String) {
        onError(message)
    }

    // ==================== BluetoothLeBridge.Callback ====================
    override fun onBleConnected(name: String, address: String, isAuto: Boolean) {
        prefs.edit()
            .putString(KEY_LAST_TRANSPORT, "ble")
            .putString(KEY_LAST_BLE_ADDR, address)
            .apply()
        val json = JSONObject().apply {
            put("transport", "ble")
            put("name", name)
            put("address", address)
            put("baud", 0)
            put("isAuto", isAuto)
        }
        emitJsEvent("connected", json.toString())
    }

    override fun onBleData(chunk: String) {
        emitJsEvent("data", chunk)
    }

    override fun onBleDisconnected(unexpected: Boolean, reason: String?) {
        onDisconnected(unexpected, reason)
    }

    override fun onBleError(message: String) {
        onError(message)
    }

    private fun emitJsEvent(event: String, payload: String) {
        val quotedEvent = JSONObject.quote(event)
        val quotedPayload = JSONObject.quote(payload)
        val js = "window.__androidSerialCallback && window.__androidSerialCallback($quotedEvent, $quotedPayload);"
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    companion object {
        private const val HOST_URL = "https://appassets.androidplatform.net/assets/m328v6_web_host.html"
        private const val REQ_NOTIF_PERM = 1001
        private const val PREFS_NAME = "m328v6_host_prefs"
        private const val KEY_LAST_TRANSPORT = "last_transport"
        private const val KEY_LAST_BT_ADDR = "last_bt_addr"
        private const val KEY_LAST_BLE_ADDR = "last_ble_addr"
    }
}
