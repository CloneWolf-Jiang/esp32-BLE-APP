package com.clonewolf.esphomeble

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import android.widget.FrameLayout
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
        val band: WifiBandFilter.Band,
        val select: () -> Unit
    )

    private lateinit var bleClient: ImprovBleClient
    private lateinit var wifiScanner: AndroidWifiScanner
    private val foundDevices = LinkedHashMap<String, FoundDevice>()
    private var screen = Screen.GUIDE
    private var guideReturnScreen: Screen? = null
    private var selectedDeviceName = ""
    private var connectionMessage = ""
    private var errorMessage = ""
    private var ssid = ""
    private var password = ""
    private var hiddenNetwork = false
    private var passwordVisible = false
    private var networkQuery = ""
    private var showFiveGhz = false
    private var phoneWifiFresh = false
    private var phoneScanAttempted = false
    private var scanActive = false
    private var radarSweepAngle = -90f
    private var radarLastFrameAt = 0L
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
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        bleClient = ImprovBleClient(this, this)
        wifiScanner = AndroidWifiScanner(this, this)
        render()
    }

    private fun accentColor(): Int {
        return 0xffdbcabd.toInt()
    }

    private fun accentSurface(): Int {
        return 0xff233c59.toInt()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onBackPressed() {
        if (screen == Screen.GUIDE) {
            super.onBackPressed()
            return
        }
        if (screen == Screen.WIFI || screen == Screen.WAITING_AUTH) {
            guideReturnScreen = screen
            screen = Screen.GUIDE
            render()
        } else {
            super.onBackPressed()
        }
    }

    private fun render() {
        val background = 0xff0b1b33.toInt()
        val surface = 0xff142c4b.toInt()
        val text = 0xffe7ddd3.toInt()
        val muted = 0xffb9c8d9.toInt()
        window.statusBarColor = background
        window.navigationBarColor = background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val scroll = ScrollView(this).apply {
            setBackgroundColor(background)
            isFillViewport = true
            clipToPadding = false
            setOnApplyWindowInsetsListener { view, insets ->
                val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                val left = maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
                val top = maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
                val right = maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
                val bottom = maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
                view.setPadding(left, top, right, bottom)
                insets
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(28))
        }
        root = column
        scroll.addView(column)
        column.addView(appHeader())
        column.addView(space(22))

        when (screen) {
            Screen.GUIDE -> {
                column.addView(label(getString(R.string.intro_title), 30, text, true))
                column.addView(space(8))
                column.addView(label(getString(R.string.guide_subtitle), 15, muted, false))
                column.addView(space(20))
                column.addView(card(surface) {
                    addView(label(getString(R.string.guide_step), 13, accentColor(), true))
                    addView(space(10))
                    addView(label(getString(R.string.intro_body), 16, text, false))
                    addView(space(20))
                    addView(buttonDiagram(text, muted))
                    addView(space(20))
                    addView(label(getString(R.string.button_window), 15, text, true))
                })
                column.addView(space(18))
                column.addView(primaryButton(
                    getString(if (guideReturnScreen == null) R.string.start_scan else R.string.return_flow)
                ) {
                    if (guideReturnScreen == null) {
                        beginBleScan()
                    } else {
                        screen = if (currentDeviceState == ImprovProtocol.AUTHORIZED) {
                            Screen.WIFI
                        } else {
                            Screen.WAITING_AUTH
                        }
                        guideReturnScreen = null
                        render()
                    }
                })
                column.addView(space(8))
                column.addView(label(getString(R.string.guide_footer), 13, muted, false))
            }
            Screen.SCANNING -> {
                column.addView(scanIllustration())
                column.addView(space(8))
                column.addView(label(
                    getString(if (scanActive) R.string.scanning else R.string.scan_done),
                    14,
                    muted,
                    false
                ).apply { gravity = Gravity.CENTER })
                column.addView(space(20))
                column.addView(card(surface) {
                    addView(label(getString(R.string.scan_card_title), 17, text, true))
                    addView(space(12))
                    if (foundDevices.isNotEmpty()) {
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
                column.addView(connectionIllustration())
                column.addView(space(14))
                column.addView(card(surface) {
                    addView(label(getString(R.string.connecting_step), 13, accentColor(), true))
                    addView(space(10))
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
                    addView(label(
                        selectedDeviceName.ifBlank { getString(R.string.device) },
                        20,
                        text,
                        true
                    ))
                    addView(space(6))
                    addView(label(getString(R.string.pairing_ready), 13, 0xffa6d6cc.toInt(), false))
                    addView(space(18))
                    addView(buttonDiagram(text, muted))
                    addView(space(18))
                    addView(label(getString(R.string.button_window), 15, muted, false))
                    addView(space(12))
                    addView(label(getString(R.string.authorized), 14, muted, false))
                })
                column.addView(space(14))
                column.addView(secondaryButton(getString(R.string.show_guide)) {
                    guideReturnScreen = Screen.WAITING_AUTH
                    screen = Screen.GUIDE
                    render()
                })
                column.addView(space(8))
                column.addView(secondaryButton(getString(R.string.check_authorization)) {
                    bleClient.readCurrentState()
                })
            }
            Screen.WIFI -> renderWifiScreen(column, surface, text, muted)
            Screen.SUBMITTING -> {
                column.addView(connectionIllustration())
                column.addView(space(14))
                column.addView(card(surface) {
                    addView(label(getString(R.string.connecting_step), 13, accentColor(), true))
                    addView(space(10))
                    addView(label(getString(R.string.provisioning), 16, muted, false))
                    addView(space(10))
                    addView(label("SSID: ${ssid.ifBlank { "—" }}", 15, text, true))
                })
            }
            Screen.SUCCESS -> {
                column.addView(successIllustration())
                column.addView(space(12))
                column.addView(label(getString(R.string.success_eyebrow), 13, accentColor(), true))
                column.addView(space(8))
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
                column.addView(statusIcon("!", 0xffe98f83.toInt()))
                column.addView(space(12))
                column.addView(label(getString(R.string.error_eyebrow), 13, 0xffb14d39.toInt(), true))
                column.addView(space(8))
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
        column.addView(label(getString(R.string.wifi_eyebrow), 13, accentColor(), true))
        column.addView(space(8))
        column.addView(label(getString(R.string.wifi_title), 28, text, true))
        column.addView(space(6))
        column.addView(label(getString(R.string.wifi_subtitle), 14, muted, false))
        column.addView(space(16))
        column.addView(card(surface) {
            val source = when {
                deviceNetworks.isNotEmpty() && phoneNetworks.isNotEmpty() ->
                    getString(R.string.wifi_source_both)
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
            addView(space(14))
            addView(label(getString(R.string.nearby_networks), 17, text, true))
            addView(space(4))
            addView(label(getString(R.string.wifi_band_hint), 13, muted, false))
            addView(space(6))
            val bandToggle = CheckBox(this@MainActivity).apply {
                this.text = this@MainActivity.getString(R.string.wifi_show_5ghz)
                setTextColor(text)
                textSize = 14f
                isChecked = showFiveGhz
                setOnCheckedChangeListener { _, checked ->
                    if (showFiveGhz != checked) {
                        showFiveGhz = checked
                        render()
                    }
                }
            }
            addView(bandToggle)
            if (deviceNetworks.isNotEmpty()) {
                addView(label(getString(R.string.device_wifi_band_note), 12, muted, false))
            }
            addView(space(8))
            val phoneSsids = phoneNetworks.mapTo(mutableSetOf()) { it.ssid }
            val networks: List<NetworkChoice> = buildList {
                phoneNetworks.forEach { network ->
                    val band = WifiBandFilter.bandForFrequency(network.frequencyMHz)
                    add(
                        NetworkChoice(
                            network.ssid,
                            network.ssid,
                            "${network.rssi} dBm · ${network.security.ifBlank { getString(R.string.open_network) }}",
                            band,
                            { chooseNetwork(network.ssid) }
                        )
                    )
                }
                deviceNetworks.filterNot { it.ssid in phoneSsids }.forEach { network ->
                    add(
                        NetworkChoice(
                            network.ssid,
                            network.ssid,
                            "${network.rssi} dBm · ${network.security.ifBlank { getString(R.string.open_network) }}",
                            WifiBandFilter.Band.UNKNOWN,
                            { chooseNetwork(network.ssid) }
                        )
                    )
                }
            }.distinctBy { it.ssid to it.band }
                .filter { WifiBandFilter.isVisible(it.band, showFiveGhz) }
            val networkRows = mutableListOf<View>()
            if (networks.isEmpty() && !wifiScanStarted) {
                addView(label(
                    getString(
                        when {
                            phoneNetworks.isNotEmpty() -> R.string.wifi_no_24ghz
                            deviceNetworks.isNotEmpty() -> R.string.wifi_unknown_band_empty
                            else -> R.string.wifi_scan_unavailable
                        }
                    ),
                    14,
                    muted,
                    false
                ))
            }
            if (networks.isNotEmpty()) {
                val search = EditText(this@MainActivity).apply {
                    hint = this@MainActivity.getString(R.string.wifi_search_hint)
                    setSingleLine(true)
                    setText(networkQuery)
                    textSize = 14f
                    setTextColor(text)
                    setHintTextColor(muted)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(fieldBackground(), fieldBorder())
                }
                addView(label(getString(R.string.network_count, networks.size), 12, muted, false))
                addView(space(6))
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
                val row = networkRow(network, text, muted)
                    .apply { tag = network.ssid }
                networkRows += row
                row.visibility =
                    if (network.ssid.contains(networkQuery, ignoreCase = true)) View.VISIBLE else View.GONE
                addView(row)
                addView(space(5))
            }
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
            addView(label(getString(R.string.wifi_credentials), 17, text, true))
            addView(space(12))
            addView(label(getString(R.string.ssid_label), 12, muted, true))
            addView(space(4))
            val ssidField = EditText(this@MainActivity).apply {
                hint = this@MainActivity.getString(R.string.ssid_hint)
                setSingleLine(true)
                setText(ssid)
                textSize = 16f
                setTextColor(text)
                setHintTextColor(muted)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(fieldBackground(), fieldBorder())
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
            addView(space(8))
            addView(label(getString(R.string.password_label), 12, muted, true))
            addView(space(4))
            val pwField = EditText(this@MainActivity).apply {
                hint = this@MainActivity.getString(R.string.password_hint)
                setSingleLine(true)
                setText(password)
                textSize = 16f
                setTextColor(text)
                setHintTextColor(muted)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(fieldBackground(), fieldBorder())
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (passwordVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD
                addTextChangedListener(SimpleTextWatcher { password = it })
            }
            passwordInput = pwField
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
            guideReturnScreen = Screen.WIFI
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
                setTextColor(if (isProvisionButton) accentColor() else muted)
                background = rounded(
                    if (isProvisionButton) accentSurface() else fieldBackground(),
                    if (isProvisionButton) accentColor() else fieldBorder()
                )
                setPadding(dp(7), dp(5), dp(7), dp(5))
            }
            val params = LinearLayout.LayoutParams(dp(76), dp(82))
            if (number != 1) params.leftMargin = dp(12)
            diagram.addView(button, params)
        }
        return diagram
    }

    private fun scanIllustration(): View = object : View(this) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sweepPath = android.graphics.Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) * 0.43f
            ringPaint.color = 0xff365274.toInt()
            canvas.drawCircle(centerX, centerY, radius, ringPaint)
            canvas.drawCircle(centerX, centerY, radius * 0.62f, ringPaint)
            canvas.drawCircle(centerX, centerY, radius * 0.31f, ringPaint)

            if (scanActive) {
                val now = SystemClock.uptimeMillis()
                if (radarLastFrameAt != 0L) {
                    val delta = (now - radarLastFrameAt).coerceIn(0L, 48L)
                    radarSweepAngle = (radarSweepAngle + delta * RADAR_DEGREES_PER_MS) % 360f
                }
                radarLastFrameAt = now
            } else {
                radarLastFrameAt = 0L
            }

            val sweepBounds = RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
            sweepPath.reset()
            sweepPath.moveTo(centerX, centerY)
            sweepPath.arcTo(sweepBounds, radarSweepAngle - RADAR_SWEEP_DEGREES, RADAR_SWEEP_DEGREES)
            sweepPath.close()
            sweepPaint.color = 0x5577a9d8
            canvas.drawPath(sweepPath, sweepPaint)

            ringPaint.color = accentColor()
            ringPaint.strokeWidth = dp(2).toFloat()
            val angleRadians = Math.toRadians(radarSweepAngle.toDouble())
            canvas.drawLine(
                centerX,
                centerY,
                centerX + (radius * 0.94f * kotlin.math.cos(angleRadians)).toFloat(),
                centerY + (radius * 0.94f * kotlin.math.sin(angleRadians)).toFloat(),
                ringPaint
            )
            ringPaint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, dp(3).toFloat(), ringPaint)
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeWidth = dp(1).toFloat()

            if (scanActive) postInvalidateOnAnimation()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val desiredWidth = if (availableWidth > 0) availableWidth else resources.displayMetrics.widthPixels
            val maxHeight = (resources.displayMetrics.heightPixels * 0.34f).toInt()
                .coerceAtLeast(dp(160))
            val desiredHeight = minOf(
                desiredWidth - dp(24),
                dp(300),
                maxHeight
            ).coerceAtLeast(dp(160))
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
            )
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        contentDescription = getString(R.string.scanning)
    }

    private fun connectionIllustration(): View = object : View(this) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            drawProgressRing(canvas, centerX, centerY, dp(72).toFloat(), 0.65f, ringPaint)
            val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xffe7ddd3.toInt()
                textSize = dp(18).toFloat()
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("BLE", centerX, centerY + dp(6), centerText)
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(184)
        )
        contentDescription = getString(R.string.connecting)
    }

    private fun drawProgressRing(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        progress: Float,
        paint: Paint
    ) {
        val bounds = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        paint.color = 0xff365274.toInt()
        canvas.drawArc(bounds, 0f, 360f, false, paint)
        paint.color = accentColor()
        canvas.drawArc(bounds, -90f, progress * 360f, false, paint)
    }

    private fun successIllustration(): View = statusIcon("✓", 0xffa6d6cc.toInt())

    private fun statusIcon(symbol: String, color: Int): View =
        TextView(this).apply {
            text = symbol
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(color)
            background = rounded(0xff142c4b.toInt(), 0xff365274.toInt(), 48)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
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

    private fun networkRow(network: NetworkChoice, text: Int, muted: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val selected = ssid == network.ssid
            background = rounded(
                if (selected) accentSurface() else 0xff152d49.toInt(),
                if (selected) accentColor() else fieldBorder(),
                14
            )
            isClickable = true
            isFocusable = true
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(network.title, 15, text, true))
                addView(space(2))
                addView(label(network.detail, 12, muted, false))
            }
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val badgeText = when (network.band) {
                WifiBandFilter.Band.GHZ_2_4 -> getString(R.string.band_24ghz)
                WifiBandFilter.Band.GHZ_5 -> getString(R.string.band_5ghz)
                WifiBandFilter.Band.UNKNOWN -> getString(R.string.band_unknown)
                WifiBandFilter.Band.OTHER -> getString(R.string.band_other)
            }
            val badge = label(badgeText, 11, accentColor(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = rounded(accentSurface(), accentSurface())
            }
            addView(badge)
            setOnClickListener { network.select() }
        }

    private fun clickableRow(surface: Int, content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(surface, fieldBorder())
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
                guideReturnScreen = null
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
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
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
                if (screen == Screen.GUIDE && guideReturnScreen != null) {
                    guideReturnScreen = null
                }
                screen = Screen.WAITING_AUTH
                render()
            }
            ImprovProtocol.AUTHORIZED -> {
                if (capabilities >= 0 &&
                    (screen == Screen.CONNECTING || screen == Screen.WAITING_AUTH ||
                        (screen == Screen.GUIDE && guideReturnScreen != null))
                ) {
                    guideReturnScreen = null
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
        if (currentDeviceState == ImprovProtocol.AUTHORIZED &&
            (screen == Screen.CONNECTING || screen == Screen.WAITING_AUTH ||
                (screen == Screen.GUIDE && guideReturnScreen != null))
        ) {
            guideReturnScreen = null
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
        } else {
            if (screen == Screen.WIFI) render()
            if (!phoneScanAttempted && phoneNetworks.isEmpty()) startPhoneWifiScan()
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

    private fun appHeader(): View =
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            val title = TextView(this@MainActivity).apply {
                this.text = when (screen) {
                    Screen.GUIDE -> getString(R.string.brand)
                    Screen.SCANNING -> getString(R.string.scan_title)
                    Screen.CONNECTING -> getString(R.string.connecting)
                    Screen.WAITING_AUTH -> getString(R.string.step_authorize)
                    Screen.WIFI -> getString(R.string.wifi_title)
                    Screen.SUBMITTING -> getString(R.string.submitting)
                    Screen.SUCCESS -> getString(R.string.success_title)
                    Screen.ERROR -> getString(R.string.error_title)
                }
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xffe7ddd3.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            addView(title)
            if (screen != Screen.GUIDE) {
                val back = TextView(this@MainActivity).apply {
                    this.text = "‹"
                    textSize = 36f
                    setTextColor(0xffd6c7bc.toInt())
                    gravity = Gravity.CENTER
                    contentDescription = getString(R.string.return_flow)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onBackPressed() }
                }
                addView(
                    back,
                    FrameLayout.LayoutParams(
                        dp(48),
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.START
                    )
                )
            }
        }

    private fun card(color: Int, content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(color, fieldBorder(), 18)
            content()
        }

    private fun primaryButton(title: String, action: () -> Unit): Button = button(title, true, action)
    private fun secondaryButton(title: String, action: () -> Unit): Button = button(title, false, action)

    private fun button(title: String, primary: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) 0xff0b1b33.toInt() else 0xffe7ddd3.toInt())
            background = rounded(
                if (primary) accentColor() else 0xff142c4b.toInt(),
                if (primary) accentColor() else fieldBorder(),
                if (primary) 24 else 14
            )
            minHeight = dp(if (primary) 56 else 48)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            elevation = 0f
            setOnClickListener { action() }
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun rounded(color: Int, strokeColor: Int, radiusDp: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != color) setStroke(dp(1), strokeColor)
        }

    private fun fieldBackground(surface: Int? = null): Int {
        return surface ?: 0xff142c4b.toInt()
    }

    private fun fieldBorder(): Int {
        return 0xff365274.toInt()
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
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isValidPassword(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size <= 63 ||
            (bytes.size == 64 && value.all { it.digitToIntOrNull(16) != null })
    }

    companion object {
        private const val RADAR_DEGREES_PER_MS = 0.09f
        private const val RADAR_SWEEP_DEGREES = 48f
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
