package com.clonewolf.esphomeble

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.LinkedHashMap

internal class MainActivity : Activity(), ImprovBleClient.Listener, AndroidWifiScanner.Listener {
    private enum class Screen { GUIDE, SCANNING, CONNECTING, WAITING_AUTH, WIFI, SUBMITTING, SUCCESS, ERROR }
    private data class FoundDevice(val device: BluetoothDevice, val name: String, val lastSeen: Long)
    private data class NetworkChoice(
        val ssid: String,
        val title: String,
        val detail: String,
        val select: () -> Unit
    )

    private lateinit var bleClient: ImprovBleClient
    private lateinit var wifiScanner: AndroidWifiScanner
    private val foundDevices = LinkedHashMap<String, FoundDevice>()
    private var screen = Screen.GUIDE
    private var selectedDeviceName = ""
    private var connectionMessage = ""
    private var errorMessage = ""
    private var ssid = ""
    private var password = ""
    private var hiddenNetwork = false
    private var passwordVisible = false
    private var networkQuery = ""
    private var phoneWifiFresh = false
    private var phoneScanAttempted = false
    private var scanActive = false
    private var wifiScanStarted = false
    private var didReadState = false
    private var capabilities = -1
    private var deviceNetworks: List<ImprovProtocol.WifiNetwork> = emptyList()
    private var phoneNetworks: List<AndroidWifiScanner.Network> = emptyList()
    private var scanFallbackUsed = false
    private var root: LinearLayout? = null
    private var ssidInput: EditText? = null
    private var passwordInput: EditText? = null
    private var passwordToggle: Button? = null
    private var scanTimeout: Runnable? = null
    private var provisioningTimeout: Runnable? = null
    private var deviceExpiry: Runnable? = null
    private var pendingAction: (() -> Unit)? = null
    private var pendingWifiScan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        bleClient = ImprovBleClient(this, this)
        wifiScanner = AndroidWifiScanner(this, this)
        render()
    }

    override fun onDestroy() {
        wifiScanner.close()
        bleClient.close()
        password = ""
        scanTimeout?.let { root?.removeCallbacks(it) }
        provisioningTimeout?.let { root?.removeCallbacks(it) }
        deviceExpiry?.let { root?.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (screen == Screen.GUIDE) {
            super.onBackPressed()
            return
        }
        if (screen == Screen.WIFI || screen == Screen.WAITING_AUTH) {
            screen = Screen.GUIDE
            render()
        } else {
            super.onBackPressed()
        }
    }

    private fun render() {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val background = if (dark) 0xff111816.toInt() else 0xfff5f7f6.toInt()
        val surface = if (dark) 0xff1a2421.toInt() else 0xffffffff.toInt()
        val text = if (dark) 0xfff0f5f3.toInt() else 0xff18221f.toInt()
        val muted = if (dark) 0xffb0beb9.toInt() else 0xff61716b.toInt()
        window.statusBarColor = background
        window.navigationBarColor = background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val scroll = ScrollView(this).apply {
            setBackgroundColor(background)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(30))
        }
        root = column
        scroll.addView(column)
        column.addView(label(getString(R.string.brand), 13, muted, true))
        column.addView(space(18))

        when (screen) {
            Screen.GUIDE -> {
                column.addView(label(getString(R.string.intro_title), 27, text, true))
                column.addView(space(12))
                column.addView(card(surface) {
                    addView(label(getString(R.string.intro_body), 16, text, false))
                    addView(space(20))
                    addView(buttonDiagram(text, muted))
                    addView(space(20))
                    addView(label(getString(R.string.button_window), 15, text, true))
                })
                column.addView(space(18))
                column.addView(primaryButton(getString(R.string.start_scan)) { beginBleScan() })
                column.addView(space(8))
                column.addView(label(getString(R.string.intro_body), 13, muted, false))
            }
            Screen.SCANNING -> {
                column.addView(label(getString(R.string.scan_title), 26, text, true))
                column.addView(space(12))
                column.addView(card(surface) {
                    addView(label(
                        when {
                            scanActive -> getString(R.string.scanning)
                            foundDevices.isEmpty() -> getString(R.string.scan_empty)
                            else -> getString(R.string.scan_done)
                        },
                        15,
                        muted,
                        false
                    ))
                    addView(space(12))
                    if (foundDevices.isEmpty()) {
                        addView(label(getString(R.string.scan_empty), 15, text, false))
                    } else {
                        foundDevices.values.forEach { item ->
                            addView(deviceRow(item, surface, text, muted))
                            addView(space(8))
                        }
                    }
                })
                column.addView(space(14))
                column.addView(secondaryButton(
                    getString(if (scanActive) R.string.stop_scan else R.string.retry)
                ) {
                    if (scanActive) {
                        bleClient.stopScan()
                        screen = Screen.GUIDE
                        render()
                    } else {
                        beginBleScan()
                    }
                })
            }
            Screen.CONNECTING -> {
                column.addView(label(getString(R.string.connecting), 22, text, true))
                column.addView(space(12))
                column.addView(card(surface) {
                    addView(label("${getString(R.string.device)}：$selectedDeviceName", 15, text, true))
                    addView(space(6))
                    addView(label(connectionMessage, 15, muted, false))
                    addView(space(12))
                    addView(label(getString(R.string.waiting_auth), 15, text, false))
                })
            }
            Screen.WAITING_AUTH -> {
                column.addView(label(getString(R.string.waiting_auth), 23, text, true))
                column.addView(space(14))
                column.addView(card(surface) {
                    addView(buttonDiagram(text, muted))
                    addView(space(18))
                    addView(label(getString(R.string.button_window), 15, muted, false))
                    addView(space(12))
                    addView(label(getString(R.string.authorized), 14, muted, false))
                })
                column.addView(space(14))
                column.addView(secondaryButton(getString(R.string.show_guide)) {
                    screen = Screen.GUIDE
                    render()
                })
            }
            Screen.WIFI -> renderWifiScreen(column, surface, text, muted)
            Screen.SUBMITTING -> {
                column.addView(label(getString(R.string.submitting), 22, text, true))
                column.addView(space(12))
                column.addView(card(surface) {
                    addView(label(getString(R.string.provisioning), 16, muted, false))
                    addView(space(10))
                    addView(label("SSID: ${ssid.ifBlank { "—" }}", 15, text, true))
                })
            }
            Screen.SUCCESS -> {
                column.addView(label(getString(R.string.success_title), 27, text, true))
                column.addView(space(12))
                column.addView(card(surface) {
                    addView(label(getString(R.string.success_body), 16, text, false))
                    addView(space(12))
                    addView(label("SSID: ${ssid.ifBlank { "—" }}", 15, muted, false))
                })
                column.addView(space(18))
                column.addView(secondaryButton(getString(R.string.show_guide)) {
                    clearFlow()
                    screen = Screen.GUIDE
                    render()
                })
            }
            Screen.ERROR -> {
                column.addView(label(getString(R.string.error_title), 25, text, true))
                column.addView(space(12))
                column.addView(card(surface) {
                    addView(label(errorMessage, 16, text, false))
                })
                column.addView(space(16))
                column.addView(primaryButton(getString(R.string.retry)) {
                    clearFlow()
                    screen = Screen.GUIDE
                    render()
                })
            }
        }
        setContentView(scroll)
    }

    private fun renderWifiScreen(column: LinearLayout, surface: Int, text: Int, muted: Int) {
        column.addView(label(getString(R.string.wifi_title), 26, text, true))
        column.addView(space(12))
        column.addView(card(surface) {
            val source = when {
                deviceNetworks.isNotEmpty() -> getString(R.string.wifi_source_device)
                wifiScanStarted -> getString(R.string.wifi_scanning)
                phoneScanAttempted -> getString(R.string.wifi_source_phone)
                else -> getString(R.string.device_scan_not_supported)
            }
            addView(label(source, 14, muted, true))
            if (phoneScanAttempted && !phoneWifiFresh) {
                addView(space(5))
                addView(label(getString(R.string.cached_scan_results), 12, muted, false))
            }
            addView(space(10))
            val networks: List<NetworkChoice> =
                if (deviceNetworks.isNotEmpty()) {
                    deviceNetworks.map { network ->
                        NetworkChoice(
                            network.ssid,
                            "${network.ssid}  ${network.rssi} dBm",
                            network.security,
                            { chooseNetwork(network.ssid) }
                        )
                    }
                } else {
                    phoneNetworks.map { network ->
                        NetworkChoice(
                            network.ssid,
                            "${network.ssid}  ${network.rssi} dBm",
                            network.security,
                            { chooseNetwork(network.ssid) }
                        )
                    }
                }
            if (networks.isEmpty() && !wifiScanStarted) {
                addView(label(getString(R.string.wifi_scan_unavailable), 14, muted, false))
            }
            val networkRows = mutableListOf<View>()
            if (networks.isNotEmpty()) {
                val search = EditText(this@MainActivity).apply {
                    hint = this@MainActivity.getString(R.string.wifi_search_hint)
                    setSingleLine(true)
                    setText(networkQuery)
                    textSize = 14f
                    setTextColor(text)
                    setHintTextColor(muted)
                    setBackgroundColor(0x00000000)
                }
                addView(search, matchWrap())
                search.addTextChangedListener(SimpleTextWatcher { query ->
                    networkQuery = query
                    networkRows.forEach { row ->
                        val name = row.tag as? String ?: ""
                        row.visibility = if (name.contains(query, ignoreCase = true)) View.VISIBLE else View.GONE
                    }
                })
            }
            networks.forEach { network ->
                val row = networkRow(network.title, network.detail, surface, text, muted, network.select)
                    .apply { tag = network.ssid }
                networkRows += row
                row.visibility =
                    if (network.ssid.contains(networkQuery, ignoreCase = true)) View.VISIBLE else View.GONE
                addView(row)
                addView(space(5))
            }
            addView(label(getString(R.string.wifi_security), 12, muted, false))
        })
        column.addView(space(10))
        column.addView(secondaryButton(getString(R.string.wifi_rescan)) {
            scanFallbackUsed = false
            phoneNetworks = emptyList()
            deviceNetworks = emptyList()
            phoneWifiFresh = false
            phoneScanAttempted = false
            discoverWifi()
        })
        column.addView(space(14))
        column.addView(card(surface) {
            val ssidField = EditText(this@MainActivity).apply {
                hint = this@MainActivity.getString(R.string.ssid_hint)
                setSingleLine(true)
                setText(ssid)
                textSize = 16f
                setTextColor(text)
                setHintTextColor(muted)
                setBackgroundColor(0x00000000)
                addTextChangedListener(SimpleTextWatcher { ssid = it })
            }
            ssidInput = ssidField
            addView(ssidField, matchWrap())
            val hidden = CheckBox(this@MainActivity).apply {
                this.text = this@MainActivity.getString(R.string.hidden_network)
                setTextColor(text)
                isChecked = hiddenNetwork
                setOnCheckedChangeListener { _, checked -> hiddenNetwork = checked }
            }
            addView(hidden)
            val pwField = EditText(this@MainActivity).apply {
                hint = this@MainActivity.getString(R.string.password_hint)
                setSingleLine(true)
                setText(password)
                textSize = 16f
                setTextColor(text)
                setHintTextColor(muted)
                setBackgroundColor(0x00000000)
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (passwordVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD
                addTextChangedListener(SimpleTextWatcher { password = it })
            }
            passwordInput = pwField
            addView(space(8))
            addView(pwField, matchWrap())
            passwordToggle = secondaryButton(
                this@MainActivity.getString(
                    if (passwordVisible) R.string.hide_password else R.string.show_password
                )
            ) {
                passwordVisible = !passwordVisible
                render()
                passwordInput?.requestFocus()
            }
            addView(passwordToggle)
        })
        column.addView(space(14))
        column.addView(primaryButton(getString(R.string.submit)) { submitCredentials() })
        column.addView(space(8))
        column.addView(secondaryButton(getString(R.string.show_guide)) {
            screen = Screen.GUIDE
            render()
        })
    }

    private fun buttonDiagram(text: Int, muted: Int): LinearLayout {
        val diagram = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (number in 1..3) {
            val isProvisionButton = number == 1
            val button = TextView(this).apply {
                this.text = if (isProvisionButton) "1\n配网" else number.toString()
                gravity = Gravity.CENTER
                textSize = if (isProvisionButton) 16f else 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isProvisionButton) 0xff146b5b.toInt() else muted)
                background = rounded(
                    if (isProvisionButton) 0xffdcefe9.toInt() else 0xffedf1ef.toInt(),
                    if (isProvisionButton) 0xff146b5b.toInt() else 0xffd5ded9.toInt()
                )
                setPadding(dp(7), dp(5), dp(7), dp(5))
            }
            val params = LinearLayout.LayoutParams(dp(76), dp(82))
            if (number != 1) params.leftMargin = dp(12)
            diagram.addView(button, params)
        }
        return diagram
    }

    private fun deviceRow(item: FoundDevice, surface: Int, text: Int, muted: Int): View =
        clickableRow(surface) {
            addView(label(item.name.ifBlank { "ESPHome 配网设备" }, 16, text, true))
            addView(label(item.device.address, 12, muted, false))
        }.apply {
            setOnClickListener {
                selectedDeviceName = item.name.ifBlank { "ESPHome 配网设备" }
                connectionMessage = getString(R.string.connecting)
                didReadState = false
                capabilities = -1
                deviceNetworks = emptyList()
                phoneNetworks = emptyList()
                phoneWifiFresh = false
                phoneScanAttempted = false
                networkQuery = ""
                screen = Screen.CONNECTING
                render()
                bleClient.connect(item.device)
            }
        }

    private fun networkRow(
        title: String,
        detail: String,
        surface: Int,
        text: Int,
        muted: Int,
        action: () -> Unit
    ): View = clickableRow(surface) {
        addView(label(title, 15, text, true))
        addView(label(detail, 12, muted, false))
    }.apply { setOnClickListener { action() } }

    private fun clickableRow(surface: Int, content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(surface, 0xffdce4df.toInt())
            isClickable = true
            isFocusable = true
            content()
        }

    private fun beginBleScan() {
        pendingAction = { beginBleScan() }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else if (Build.VERSION.SDK_INT >= 23) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        requestNeededPermissions(needed) {
            if (Build.VERSION.SDK_INT in 23..30 && !locationServicesEnabled()) {
                showError(getString(R.string.location_disabled))
                return@requestNeededPermissions
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                showError(getString(R.string.error_ble_not_supported))
            } else if (!adapter.isEnabled) {
                try {
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
                } catch (_: SecurityException) {
                    showError(getString(R.string.permission_denied))
                }
            } else {
                foundDevices.clear()
                screen = Screen.SCANNING
                render()
                bleClient.startScan()
                scheduleDeviceExpiry()
            }
        }
    }

    private fun requestNeededPermissions(permissions: List<String>, action: () -> Unit) {
        val missing = permissions.distinct().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        private fun scheduleDeviceExpiry() {
            deviceExpiry?.let { root?.removeCallbacks(it) }
            val expiry = object : Runnable {
                override fun run() {
                    if (!scanActive || screen != Screen.SCANNING) return
                    val cutoff = SystemClock.elapsedRealtime() - DEVICE_LOST_AFTER_MS
                    val iterator = foundDevices.entries.iterator()
                    var changed = false
                    while (iterator.hasNext()) {
                        if (iterator.next().value.lastSeen < cutoff) {
                            iterator.remove()
                            changed = true
                        }
                    }
                    if (changed) render()
                    root?.postDelayed(this, DEVICE_EXPIRY_CHECK_MS)
                }
            }
            deviceExpiry = expiry
            root?.postDelayed(expiry, DEVICE_EXPIRY_CHECK_MS)
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    @Deprecated("Uses the system Bluetooth enable prompt for older Android versions.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) beginBleScan()
            else showError(getString(R.string.bluetooth_off))
        }
    }

    @Deprecated("Permission callback is retained for API 21 compatibility.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            pendingAction = null
            if (pendingWifiScan) {
                pendingWifiScan = false
                onScanUnavailable()
            } else {
                showError(getString(R.string.permission_denied))
            }
            return
        }
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    private fun discoverWifi() {
        if (deviceNetworks.isNotEmpty()) return
        if (ImprovProtocol.supportsWifiScan(capabilities)) {
            wifiScanStarted = true
            render()
            bleClient.scanWifiNetworks()
            scanTimeout?.let { root?.removeCallbacks(it) }
            val timeout = Runnable {
                if (screen == Screen.WIFI && deviceNetworks.isEmpty() && !scanFallbackUsed) {
                    scanFallbackUsed = true
                    startPhoneWifiScan()
                }
            }
            scanTimeout = timeout
            root?.postDelayed(timeout, DEVICE_WIFI_SCAN_TIMEOUT_MS)
        } else {
            startPhoneWifiScan()
        }
    }

    private fun startPhoneWifiScan() {
        pendingWifiScan = true
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 23) permissions += Manifest.permission.ACCESS_FINE_LOCATION
        requestNeededPermissions(permissions) {
            pendingWifiScan = false
            phoneScanAttempted = true
            wifiScanStarted = true
            render()
            wifiScanner.scan()
        }
    }

    private fun chooseNetwork(name: String) {
        ssid = name
        networkQuery = ""
        hiddenNetwork = false
        render()
        ssidInput?.requestFocus()
    }

    private fun submitCredentials() {
        val networkName = ssid
        if (networkName.isEmpty()) {
            Toast.makeText(this, R.string.ssid_required, Toast.LENGTH_SHORT).show()
            ssidInput?.requestFocus()
            return
        }
        if (networkName.toByteArray(Charsets.UTF_8).size > 32) {
            Toast.makeText(this, R.string.ssid_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isValidPassword(password)) {
            Toast.makeText(this, R.string.password_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        if (!didReadState || currentDeviceState != ImprovProtocol.AUTHORIZED) {
            showError(getString(R.string.auth_timeout))
            return
        }
        ssid = networkName
        screen = Screen.SUBMITTING
        render()
        bleClient.sendWifi(networkName, password)
        provisioningTimeout?.let { root?.removeCallbacks(it) }
        val timeout = Runnable {
            if (screen == Screen.SUBMITTING) showError(getString(R.string.response_timeout))
        }
        provisioningTimeout = timeout
        root?.postDelayed(timeout, PROVISION_TIMEOUT_MS)
    }

    private var currentDeviceState = -1

    private fun showError(message: String) {
        errorMessage = message
        screen = Screen.ERROR
        provisioningTimeout?.let { root?.removeCallbacks(it) }
        scanTimeout?.let { root?.removeCallbacks(it) }
        render()
    }

    private fun clearFlow() {
        bleClient.stopScan()
        bleClient.close()
        wifiScanner.close()
        password = ""
        ssid = ""
        currentDeviceState = -1
        didReadState = false
        deviceNetworks = emptyList()
        phoneNetworks = emptyList()
        phoneWifiFresh = false
        phoneScanAttempted = false
        foundDevices.clear()
        deviceExpiry?.let { root?.removeCallbacks(it) }
        scanTimeout?.let { root?.removeCallbacks(it) }
        provisioningTimeout?.let { root?.removeCallbacks(it) }
    }

    override fun onScanState(scanning: Boolean) {
        scanActive = scanning
        if (!scanning) deviceExpiry?.let { root?.removeCallbacks(it) }
        if (screen == Screen.SCANNING) render()
    }

    override fun onDeviceFound(device: BluetoothDevice, name: String) {
        val key = try {
            device.address
        } catch (_: SecurityException) {
            return
        }
        val previous = foundDevices[key]
        val newName = name.ifBlank { previous?.name.orEmpty() }
        foundDevices[key] = FoundDevice(device, newName, SystemClock.elapsedRealtime())
        if (screen == Screen.SCANNING) render()
    }

    override fun onConnected() {
        connectionMessage = getString(R.string.connecting)
        if (screen == Screen.CONNECTING) render()
    }

    override fun onProtocolState(state: Int) {
        currentDeviceState = state
        didReadState = true
        when (state) {
            ImprovProtocol.AUTHORIZATION_REQUIRED -> {
                screen = Screen.WAITING_AUTH
                render()
            }
            ImprovProtocol.AUTHORIZED -> {
                if (capabilities >= 0 &&
                    (screen == Screen.CONNECTING || screen == Screen.WAITING_AUTH)
                ) {
                    enterWifiScreen()
                }
            }
            ImprovProtocol.PROVISIONING -> {
                screen = Screen.SUBMITTING
                render()
            }
            ImprovProtocol.PROVISIONED -> {
                provisioningTimeout?.let { root?.removeCallbacks(it) }
                screen = Screen.SUCCESS
                password = ""
                render()
            }
            else -> showError(getString(R.string.state_unknown))
        }
    }

    override fun onProtocolError(error: Int) {
        if (error == ImprovProtocol.NO_ERROR) return
        when (error) {
            ImprovProtocol.NOT_AUTHORIZED -> showError(getString(R.string.auth_timeout))
            ImprovProtocol.UNABLE_TO_CONNECT -> showError(getString(R.string.wifi_failed))
            else -> showError(getString(R.string.device_rejected))
        }
    }

    override fun onCapabilities(capabilities: Int) {
        this.capabilities = capabilities
        if (currentDeviceState == ImprovProtocol.AUTHORIZED && screen == Screen.CONNECTING) {
            enterWifiScreen()
        }
    }

    private fun enterWifiScreen() {
        if (screen == Screen.SUBMITTING || screen == Screen.SUCCESS || screen == Screen.ERROR) return
        screen = Screen.WIFI
        if (deviceNetworks.isEmpty() && phoneNetworks.isEmpty()) discoverWifi() else render()
    }

    override fun onWifiNetworks(networks: List<ImprovProtocol.WifiNetwork>) {
        scanTimeout?.let { root?.removeCallbacks(it) }
        deviceNetworks = networks
        wifiScanStarted = false
        if (networks.isEmpty()) {
            if (!scanFallbackUsed) {
                scanFallbackUsed = true
                startPhoneWifiScan()
            } else {
                render()
            }
        } else if (screen == Screen.WIFI) {
            render()
        }
    }

    override fun onFailure(message: ImprovBleClient.Failure) {
        when (message) {
            ImprovBleClient.Failure.BLUETOOTH_OFF -> showError(getString(R.string.bluetooth_off))
            ImprovBleClient.Failure.PERMISSION -> showError(getString(R.string.permission_denied))
            ImprovBleClient.Failure.SCAN -> showError(getString(R.string.scan_failed))
            ImprovBleClient.Failure.CONNECT -> showError(getString(R.string.connection_lost))
            ImprovBleClient.Failure.SERVICE -> showError(getString(R.string.service_missing))
            ImprovBleClient.Failure.AUTHORIZATION -> showError(getString(R.string.auth_timeout))
            ImprovBleClient.Failure.WRITE -> showError(getString(R.string.device_rejected))
            ImprovBleClient.Failure.PROTOCOL -> {
                if (ImprovProtocol.supportsWifiScan(capabilities) && screen == Screen.WIFI && !scanFallbackUsed) {
                    scanFallbackUsed = true
                    startPhoneWifiScan()
                } else {
                    showError(getString(R.string.service_missing))
                }
            }
        }
    }

    override fun onDisconnected() {
        if (screen == Screen.SUBMITTING) return
        if (screen != Screen.SUCCESS && screen != Screen.ERROR) {
            showError(getString(R.string.connection_lost))
        }
    }

    override fun onScanStarted() {
        wifiScanStarted = true
        render()
    }

    override fun onScanResults(networks: List<AndroidWifiScanner.Network>, fresh: Boolean) {
        phoneNetworks = networks
        phoneWifiFresh = fresh
        phoneScanAttempted = true
        wifiScanStarted = false
        if (screen == Screen.WIFI) render()
    }

    override fun onScanUnavailable() {
        wifiScanStarted = false
        if (screen == Screen.WIFI) {
            render()
            Toast.makeText(this, R.string.wifi_scan_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun card(color: Int, content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(color, color)
            content()
        }

    private fun primaryButton(title: String, action: () -> Unit): Button = button(title, true, action)
    private fun secondaryButton(title: String, action: () -> Unit): Button = button(title, false, action)

    private fun button(title: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) 0xffffffff.toInt() else 0xff146b5b.toInt())
            background = rounded(if (primary) 0xff146b5b.toInt() else 0xffe4eee9.toInt(), 0xff146b5b.toInt())
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { action() }
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(color: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), strokeColor)
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun locationServicesEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                manager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }

            private fun isValidPassword(value: String): Boolean {
                val bytes = value.toByteArray(Charsets.UTF_8)
                return bytes.size <= 63 ||
                    (bytes.size == 64 && value.all { it.digitToIntOrNull(16) != null })
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BLUETOOTH = 71
        private const val REQUEST_PERMISSIONS = 72
        private const val DEVICE_WIFI_SCAN_TIMEOUT_MS = 12_000L
        private const val DEVICE_LOST_AFTER_MS = 8_000L
        private const val DEVICE_EXPIRY_CHECK_MS = 3_000L
        private const val PROVISION_TIMEOUT_MS = 45_000L
    }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) :
    android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        changed(s?.toString().orEmpty())
    }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
