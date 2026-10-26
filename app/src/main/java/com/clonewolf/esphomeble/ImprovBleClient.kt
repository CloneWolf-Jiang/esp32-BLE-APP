package com.clonewolf.esphomeble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID

internal class ImprovBleClient(
    private val context: Context,
    callback: Listener
) {
    interface Listener {
        fun onScanState(scanning: Boolean)
        fun onDeviceFound(device: BluetoothDevice, name: String)
        fun onConnected()
        fun onProtocolState(state: Int)
        fun onProtocolError(error: Int)
        fun onCapabilities(capabilities: Int)
        fun onWifiNetworks(networks: List<ImprovProtocol.WifiNetwork>)
        fun onFailure(message: Failure)
        fun onDisconnected()
    }

    enum class Failure {
        BLUETOOTH_OFF,
        PERMISSION,
        SCAN,
        CONNECT,
        SERVICE,
        PROTOCOL,
        AUTHORIZATION,
        WRITE
    }

    private enum class OperationType { DESCRIPTOR, READ, WRITE }
    private data class Operation(
        val type: OperationType,
        val characteristic: BluetoothGattCharacteristic? = null,
        val descriptor: BluetoothGattDescriptor? = null,
        val start: () -> Boolean
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener = object : Listener {
        override fun onScanState(scanning: Boolean) {
            mainHandler.post { callback.onScanState(scanning) }
        }
        override fun onDeviceFound(device: BluetoothDevice, name: String) {
            mainHandler.post { callback.onDeviceFound(device, name) }
        }
        override fun onConnected() {
            mainHandler.post { callback.onConnected() }
        }
        override fun onProtocolState(state: Int) {
            mainHandler.post { callback.onProtocolState(state) }
        }
        override fun onProtocolError(error: Int) {
            mainHandler.post { callback.onProtocolError(error) }
        }
        override fun onCapabilities(capabilities: Int) {
            mainHandler.post { callback.onCapabilities(capabilities) }
        }
        override fun onWifiNetworks(networks: List<ImprovProtocol.WifiNetwork>) {
            mainHandler.post { callback.onWifiNetworks(networks) }
        }
        override fun onFailure(message: Failure) {
            mainHandler.post { callback.onFailure(message) }
        }
        override fun onDisconnected() {
            mainHandler.post { callback.onDisconnected() }
        }
    }
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var operations = ArrayDeque<Operation>()
    private var activeOperation: Operation? = null
    private var serviceCharacteristics: Map<UUID, BluetoothGattCharacteristic> = emptyMap()
    private var currentState = -1
    private var capabilities = 0
    private var wifiScanPending = false
    private var explicitlyClosed = false
    private val scanTimeout = Runnable { stopScan() }
    private val operationTimeout = Runnable {
        operations.clear()
        activeOperation = null
        listener.onFailure(Failure.PROTOCOL)
    }
    private val connectionTimeout = Runnable {
        if (gatt != null) {
            listener.onFailure(Failure.CONNECT)
            close()
        }
    }
    private val serviceTimeout = Runnable {
        if (serviceCharacteristics.isEmpty() && gatt != null) {
            listener.onFailure(Failure.SERVICE)
            close()
        }
    }

    fun startScan() {
        if (!hasScanPermission()) {
            listener.onFailure(Failure.PERMISSION)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            listener.onFailure(Failure.BLUETOOTH_OFF)
            return
        }
        stopScan()
        val currentScanner = adapter.bluetoothLeScanner
        if (currentScanner == null) {
            listener.onFailure(Failure.SCAN)
            return
        }
        scanner = currentScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                listener.onFailure(Failure.SCAN)
            }
        }
        scanCallback = callback
        try {
            currentScanner.startScan(callback)
            listener.onScanState(true)
            mainHandler.postDelayed(scanTimeout, SCAN_WINDOW_MS)
        } catch (_: SecurityException) {
            listener.onFailure(Failure.PERMISSION)
        } catch (_: RuntimeException) {
            listener.onFailure(Failure.SCAN)
        }
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        val callback = scanCallback
        val currentScanner = scanner
        if (callback != null && currentScanner != null && hasScanPermission()) {
            try {
                currentScanner.stopScan(callback)
            } catch (_: SecurityException) {
                listener.onFailure(Failure.PERMISSION)
            } catch (_: RuntimeException) {
                // The scanner may already have stopped after an adapter state change.
            }
        }
        scanCallback = null
        scanner = null
        listener.onScanState(false)
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        close()
        if (!hasConnectPermission()) {
            listener.onFailure(Failure.PERMISSION)
            return
        }
        explicitlyClosed = false
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
            if (gatt == null) {
                listener.onFailure(Failure.CONNECT)
            } else {
                mainHandler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
            }
        } catch (_: SecurityException) {
            listener.onFailure(Failure.PERMISSION)
        } catch (_: RuntimeException) {
            listener.onFailure(Failure.CONNECT)
        }
    }

    fun sendWifi(ssid: String, password: String) {
        if (currentState != ImprovProtocol.AUTHORIZED) {
            listener.onFailure(Failure.AUTHORIZATION)
            return
        }
        val command = serviceCharacteristics[ImprovProtocol.RPC_COMMAND]
        if (command == null) {
            listener.onFailure(Failure.PROTOCOL)
            return
        }
        val packet = try {
            ImprovProtocol.wifiSettings(ssid, password)
        } catch (_: IllegalArgumentException) {
            listener.onFailure(Failure.WRITE)
            return
        }
        enqueue(Operation(OperationType.WRITE, characteristic = command) {
            writeCharacteristic(command, packet)
        })
    }

    fun scanWifiNetworks() {
        if (!ImprovProtocol.supportsWifiScan(capabilities)) {
            listener.onFailure(Failure.PROTOCOL)
            return
        }
        val command = serviceCharacteristics[ImprovProtocol.RPC_COMMAND]
        if (command == null) {
            listener.onFailure(Failure.PROTOCOL)
            return
        }
        wifiScanPending = true
        enqueue(Operation(OperationType.WRITE, characteristic = command) {
            writeCharacteristic(command, ImprovProtocol.command(ImprovProtocol.GET_WIFI_NETWORKS))
        })
    }

    fun close() {
        explicitlyClosed = true
        mainHandler.removeCallbacks(connectionTimeout)
        mainHandler.removeCallbacks(serviceTimeout)
        mainHandler.removeCallbacks(operationTimeout)
        operations.clear()
        activeOperation = null
        val currentGatt = gatt
        gatt = null
        serviceCharacteristics = emptyMap()
        currentState = -1
        wifiScanPending = false
        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (_: SecurityException) {
            listener.onFailure(Failure.PERMISSION)
        } catch (_: RuntimeException) {
            // The remote connection may already have closed.
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceUuids = record.serviceUuids?.map(ParcelUuid::getUuid)
        val serviceData = record.serviceData?.entries?.associate { it.key.uuid to it.value }
        if (!ImprovProtocol.isImprovAdvertisement(serviceUuids, serviceData)) return
        val displayName = result.device.name ?: record.deviceName ?: ""
        listener.onDeviceFound(result.device, displayName)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onFailure(Failure.CONNECT)
                close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.removeCallbacks(connectionTimeout)
                    mainHandler.postDelayed(serviceTimeout, GATT_OPERATION_TIMEOUT_MS)
                    listener.onConnected()
                    try {
                        if (!gatt.discoverServices()) listener.onFailure(Failure.SERVICE)
                    } catch (_: SecurityException) {
                        listener.onFailure(Failure.PERMISSION)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!explicitlyClosed) listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.removeCallbacks(serviceTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onFailure(Failure.SERVICE)
                return
            }
            val service = gatt.getService(ImprovProtocol.SERVICE)
            if (service == null) {
                listener.onFailure(Failure.SERVICE)
                return
            }
            val required = listOf(
                ImprovProtocol.STATUS,
                ImprovProtocol.ERROR,
                ImprovProtocol.RPC_COMMAND,
                ImprovProtocol.RPC_RESULT,
                ImprovProtocol.CAPABILITIES
            )
            val found = required.associateWith { service.getCharacteristic(it) }
            if (found.values.any { it == null }) {
                listener.onFailure(Failure.PROTOCOL)
                return
            }
            serviceCharacteristics = found.mapValues { it.value!! }
            queueNotification(gatt, serviceCharacteristics.getValue(ImprovProtocol.STATUS))
            queueNotification(gatt, serviceCharacteristics.getValue(ImprovProtocol.ERROR))
            queueNotification(gatt, serviceCharacteristics.getValue(ImprovProtocol.RPC_RESULT))
            enqueue(Operation(OperationType.READ, characteristic = serviceCharacteristics.getValue(ImprovProtocol.CAPABILITIES)) {
                readCharacteristic(serviceCharacteristics.getValue(ImprovProtocol.CAPABILITIES))
            })
            enqueue(Operation(OperationType.READ, characteristic = serviceCharacteristics.getValue(ImprovProtocol.STATUS)) {
                readCharacteristic(serviceCharacteristics.getValue(ImprovProtocol.STATUS))
            })
            enqueue(Operation(OperationType.READ, characteristic = serviceCharacteristics.getValue(ImprovProtocol.ERROR)) {
                readCharacteristic(serviceCharacteristics.getValue(ImprovProtocol.ERROR))
            })
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!completeOperation(OperationType.DESCRIPTOR, descriptor.characteristic)) return
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onFailure(Failure.PROTOCOL)
            runNextOperation()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleRead(gatt, characteristic, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!completeOperation(OperationType.WRITE, characteristic)) return
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onFailure(Failure.WRITE)
            runNextOperation()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(characteristic, value)
        }
    }

    private fun handleRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (!completeOperation(OperationType.READ, characteristic)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onFailure(Failure.PROTOCOL)
        } else {
            handleCharacteristic(characteristic, value)
        }
        runNextOperation()
    }

    private fun handleCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (value.isEmpty()) {
            listener.onFailure(Failure.PROTOCOL)
            return
        }
        val byteValue = value[0].toInt() and 0xff
        when (characteristic.uuid) {
            ImprovProtocol.STATUS -> {
                currentState = byteValue
                listener.onProtocolState(byteValue)
            }
            ImprovProtocol.ERROR -> listener.onProtocolError(byteValue)
            ImprovProtocol.CAPABILITIES -> {
                capabilities = byteValue
                listener.onCapabilities(capabilities)
            }
            ImprovProtocol.RPC_RESULT -> {
                try {
                    val result = ImprovProtocol.parseResponse(value)
                    if (result.command == ImprovProtocol.GET_WIFI_NETWORKS && wifiScanPending) {
                        wifiScanPending = false
                        listener.onWifiNetworks(ImprovProtocol.parseWifiNetworks(result))
                    } else if (result.command != ImprovProtocol.SEND_WIFI) {
                        listener.onFailure(Failure.PROTOCOL)
                    }
                } catch (_: IllegalArgumentException) {
                    wifiScanPending = false
                    listener.onFailure(Failure.PROTOCOL)
                }
            }
        }
    }

    private fun queueNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            listener.onFailure(Failure.PROTOCOL)
            return
        }
        enqueue(Operation(OperationType.DESCRIPTOR, characteristic, descriptor) {
            try {
                if (gatt.setCharacteristicNotification(characteristic, true)) {
                    writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    false
                }
            } catch (_: SecurityException) {
                listener.onFailure(Failure.PERMISSION)
                false
            }
        })
    }

    private fun enqueue(operation: Operation) {
        operations.addLast(operation)
        if (activeOperation == null) runNextOperation()
    }

    private fun runNextOperation() {
        if (activeOperation != null || operations.isEmpty()) return
        val operation = operations.removeFirst()
        activeOperation = operation
        try {
            if (!operation.start()) {
                activeOperation = null
                listener.onFailure(if (operation.type == OperationType.WRITE) Failure.WRITE else Failure.PROTOCOL)
                runNextOperation()
                return
            }
            mainHandler.removeCallbacks(operationTimeout)
            mainHandler.postDelayed(operationTimeout, GATT_OPERATION_TIMEOUT_MS)
        } catch (_: SecurityException) {
            activeOperation = null
            listener.onFailure(Failure.PERMISSION)
            runNextOperation()
        } catch (_: RuntimeException) {
            activeOperation = null
            listener.onFailure(Failure.PROTOCOL)
            runNextOperation()
        }
    }

    private fun completeOperation(type: OperationType, characteristic: BluetoothGattCharacteristic?): Boolean {
        val active = activeOperation ?: return false
        if (active.type != type || active.characteristic !== characteristic) return false
        mainHandler.removeCallbacks(operationTimeout)
        activeOperation = null
        return true
    }

    private fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        val currentGatt = gatt ?: return false
        return currentGatt.readCharacteristic(characteristic)
    }

    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val currentGatt = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        val currentGatt = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeDescriptor(descriptor)
        }
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            Build.VERSION.SDK_INT < 23 ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SCAN_WINDOW_MS = 15_000L
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val GATT_OPERATION_TIMEOUT_MS = 10_000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
