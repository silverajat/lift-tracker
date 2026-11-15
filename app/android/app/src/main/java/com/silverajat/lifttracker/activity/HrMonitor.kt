package com.silverajat.lifttracker.activity

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Live heart rate over Bluetooth, from anything that shares it with the standard
 * Heart Rate profile: the Fitbit Air ("Share heart rate" in Google Health), a
 * Pixel Watch, a chest strap. Runs while a run or game is being tracked.
 */
object HrMonitor {
    val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    enum class Status { OFF, NO_PERMISSION, SEARCHING, CONNECTED }
    data class State(val status: Status = Status.OFF, val bpm: Int? = null, val device: String? = null, val atMs: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Each reading while a session is tracked (time ms, bpm). */
    var onReading: ((Long, Int) -> Unit)? = null

    private var ctx: Context? = null
    private var gatt: BluetoothGatt? = null
    private var wanted = false
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Heart Rate Measurement: flags byte, then bpm as uint8 or uint16 (bit 0). */
    fun parse(v: ByteArray): Int? {
        if (v.size < 2) return null
        val wide = v[0].toInt() and 1 == 1
        return if (wide) { if (v.size < 3) null else (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8) } else v[1].toInt() and 0xFF
    }

    private fun allowed(c: Context) = Build.VERSION.SDK_INT < 31 ||
        (c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    fun start(c: Context) {
        ctx = c.applicationContext
        wanted = true
        if (gatt != null) return
        if (!allowed(c)) { _state.value = State(Status.NO_PERMISSION); return }
        scan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wanted = false
        runCatching { scanner()?.stopScan(scanCb) }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        _state.value = State()
    }

    /** Try again (after turning on heart-rate sharing, or granting Bluetooth). */
    fun retry(c: Context) { stop(); start(c) }

    private fun scanner() = (ctx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    private fun scan() {
        val s = scanner() ?: run { _state.value = State(Status.SEARCHING); return }
        _state.value = State(Status.SEARCHING)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        runCatching { s.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb) }
        // keep looking: re-arm the scan every 20 s until something shows up
        main.postDelayed({ if (wanted && gatt == null) { runCatching { s.stopScan(scanCb) }; scan() } }, 20_000)
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, r: ScanResult) {
            if (gatt != null || !wanted) return
            runCatching { scanner()?.stopScan(this) }
            connect(r.device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        val c = ctx ?: return
        gatt = d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE)
        _state.value = State(Status.SEARCHING, device = runCatching { d.name }.getOrNull())
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                gatt = null
                _state.value = State(if (wanted) Status.SEARCHING else Status.OFF)
                if (wanted) main.postDelayed({ if (wanted && gatt == null) scan() }, 2_000)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // battery first if it has one (one Bluetooth request at a time), then heart rate
            val batt = g.getService(DeviceBattery.SERVICE)?.getCharacteristic(DeviceBattery.LEVEL)
            if (batt != null && g.readCharacteristic(batt)) return
            enableHr(g)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (ch.uuid == DeviceBattery.LEVEL && status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty())
                ctx?.let { DeviceBattery.save(it, value[0].toInt() and 0xFF) }
            enableHr(g)
        }

        @SuppressLint("MissingPermission")
        private fun enableHr(g: BluetoothGatt) {
            val ch = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT) ?: return
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = reading(g, value)

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) reading(g, ch.value ?: ByteArray(0))
        }
    }

    @SuppressLint("MissingPermission")
    private fun reading(g: BluetoothGatt, v: ByteArray) {
        val bpm = parse(v)?.takeIf { it in 30..240 } ?: return
        val now = System.currentTimeMillis()
        // on the main thread, like the GPS fixes, so the two never trip over each other
        main.post {
            _state.value = State(Status.CONNECTED, bpm, runCatching { g.device.name }.getOrNull(), now)
            onReading?.invoke(now, bpm)
        }
    }
}
