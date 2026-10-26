package com.clonewolf.esphomeble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal class AndroidWifiScanner(
    private val context: Context,
    callback: Listener
) {
    interface Listener {
        fun onScanStarted()
        fun onScanResults(networks: List<Network>, fresh: Boolean)
        fun onScanUnavailable()
    }

    data class Network(val ssid: String, val rssi: Int, val security: String, val frequencyMHz: Int)

    private val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val listener = object : Listener {
        override fun onScanStarted() {
            handler.post { callback.onScanStarted() }
        }
        override fun onScanResults(networks: List<Network>, fresh: Boolean) {
            handler.post { callback.onScanResults(networks, fresh) }
        }
        override fun onScanUnavailable() {
            handler.post { callback.onScanUnavailable() }
        }
    }
    private var receiverRegistered = false
    private var finished = false

    private val timeout = Runnable { deliverResults(false) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fresh = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true
            deliverResults(fresh)
        }
    }

    fun scan() {
        closeReceiver()
        finished = false
        if (!locationEnabled() || !manager.isWifiEnabled) {
            listener.onScanUnavailable()
            return
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            listener.onScanStarted()
            val requested = manager.startScan()
            if (!requested) {
                deliverResults(false)
            } else {
                handler.postDelayed(timeout, SCAN_TIMEOUT_MS)
            }
        } catch (_: SecurityException) {
            closeReceiver()
            listener.onScanUnavailable()
        } catch (_: RuntimeException) {
            closeReceiver()
            listener.onScanUnavailable()
        }
    }

    fun close() {
        handler.removeCallbacks(timeout)
        closeReceiver()
    }

    private fun deliverResults(fresh: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        val results = try {
            manager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            closeReceiver()
            listener.onScanUnavailable()
            return
        }
        closeReceiver()
        val networks = results.asSequence()
            .filter { it.SSID.isNotBlank() }
            .map(::toNetwork)
            .sortedByDescending { it.rssi }
            .distinctBy { it.ssid to WifiBandFilter.bandForFrequency(it.frequencyMHz) }
            .toList()
        listener.onScanResults(networks, fresh)
    }

    private fun toNetwork(result: ScanResult): Network =
        Network(result.SSID, result.level, result.capabilities.orEmpty(), result.frequency)

    private fun locationEnabled(): Boolean {
        val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                location.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun closeReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // A system broadcast may finish the scan while the activity is closing.
        }
        receiverRegistered = false
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
    }
}
