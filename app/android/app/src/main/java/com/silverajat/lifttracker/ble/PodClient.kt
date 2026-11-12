package com.silverajat.lifttracker.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class PodConn { NO_PERMISSION, BLUETOOTH_OFF, IDLE, SCANNING, CONNECTING, READY }

/**
 * Finds the pod (by its service UUID), connects, and keeps reconnecting
 * while [connect] is wanted. Android only allows one GATT operation in
 * flight, so every operation goes through [gattOp] one at a time.
 */
@SuppressLint("MissingPermission")   // checked in hasPermissions() before any call
class PodClient(private val context: Context, private val scope: CoroutineScope) {

    private val _conn = MutableStateFlow(PodConn.IDLE)
    val conn: StateFlow<PodConn> = _conn

    private val _status = MutableStateFlow<PodStatus?>(null)
    val status: StateFlow<PodStatus?> = _status

    private val _samples = MutableSharedFlow<PodSample>(extraBufferCapacity = 512)
    val samples: SharedFlow<PodSample> = _samples

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    private var wanted = false
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var scanJob: Job? = null

    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null

    fun hasPermissions(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Start looking for the pod and stay connected until [disconnect]. */
    fun connect() {
        wanted = true
        if (gatt == null && scanJob?.isActive != true) startScan()
    }

    fun disconnect() {
        wanted = false
        scanJob?.cancel()
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        control = null
        _status.value = null
        _conn.value = PodConn.IDLE
    }

    /** Write one control command (see PodProtocol). True if the pod accepted it. */
    suspend fun send(cmd: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = control ?: return false
        if (_conn.value != PodConn.READY) return false
        val r = gattOp {
            g.writeCharacteristic(ch, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
        return r == BluetoothGatt.GATT_SUCCESS
    }

    // ---- scanning ----------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_conn.value != PodConn.SCANNING) return
            stopScan()
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
        }
    }

    private fun startScan() {
        if (!hasPermissions()) { _conn.value = PodConn.NO_PERMISSION; return }
        val a = adapter
        if (a == null || !a.isEnabled) { _conn.value = PodConn.BLUETOOTH_OFF; retryLater(); return }
        val scanner = a.bluetoothLeScanner ?: run { retryLater(); return }
        _conn.value = PodConn.SCANNING
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(PodProtocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
        // Android throttles long scans: restart every 20 s until found.
        scanJob = scope.launch {
            delay(20_000)
            if (_conn.value == PodConn.SCANNING && wanted) {
                stopScan()
                startScan()
            }
        }
    }

    private fun stopScan() {
        if (!hasPermissions()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun retryLater() {
        scanJob = scope.launch {
            delay(3_000)
            if (wanted && gatt == null) startScan()
        }
    }

    // ---- connection --------------------------------------------------------

    private fun connectTo(device: BluetoothDevice) {
        _conn.value = PodConn.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch { setUp(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected ($status)")
                g.close()
                if (gatt == g) gatt = null
                control = null
                _status.value = null
                pending?.complete(-1)
                _conn.value = PodConn.IDLE
                if (wanted) retryLater()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            if (c.uuid == PodProtocol.STATUS) PodStatus.parse(value)?.let { _status.value = it }
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            when (c.uuid) {
                PodProtocol.STATUS -> PodStatus.parse(value)?.let { _status.value = it }
                PodProtocol.STREAM -> PodSample.parse(value)?.let { _samples.tryEmit(it) }
            }
        }
    }

    private suspend fun setUp(g: BluetoothGatt) {
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (gattOp { g.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            g.disconnect(); return
        }
        val svc = g.getService(PodProtocol.SERVICE) ?: run { g.disconnect(); return }
        control = svc.getCharacteristic(PodProtocol.CONTROL)
        for (uuid in listOf(PodProtocol.STATUS, PodProtocol.STREAM)) {
            val ch = svc.getCharacteristic(uuid) ?: continue
            g.setCharacteristicNotification(ch, true)
            val ccc = ch.getDescriptor(PodProtocol.CCC) ?: continue
            gattOp {
                g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            }
        }
        svc.getCharacteristic(PodProtocol.STATUS)?.let { ch -> gattOp { g.readCharacteristic(ch) } }
        _conn.value = PodConn.READY
        Log.i(TAG, "pod ready")
    }

    /** Run one GATT operation and wait for its callback (status), or -2 on timeout. */
    private suspend fun gattOp(start: () -> Boolean): Int = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pending = d
        val started = runCatching(start).getOrDefault(false)
        val r = if (started) withTimeoutOrNull(5_000) { d.await() } ?: -2 else -1
        pending = null
        r
    }

    companion object { private const val TAG = "PodClient" }
}
