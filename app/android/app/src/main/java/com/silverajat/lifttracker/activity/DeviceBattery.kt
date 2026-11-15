package com.silverajat.lifttracker.activity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * The Fitbit's battery, read over Bluetooth with the standard Battery service (0x180F).
 * Health Connect doesn't carry battery, so this needs "Share heart rate" on (that's when the
 * Fitbit Air shows up over Bluetooth). It's read whenever the live heart rate connects, and
 * with a quick connect-read-disconnect when you open the app (at most every 20 minutes).
 */
object DeviceBattery {
    val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    data class Reading(val pct: Int, val atMs: Long)

    private val _state = MutableStateFlow<Reading?>(null)
    val state: StateFlow<Reading?> = _state

    private const val PREFS = "hub"
    private const val PEEK_EVERY_MS = 20 * 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var lastPeek = 0L
    private var gatt: BluetoothGatt? = null
    private var ctx: Context? = null

    fun load(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pct = p.getInt("fitbit_batt", -1)
        if (pct in 0..100) _state.value = Reading(pct, p.getLong("fitbit_batt_at", 0))
    }

    fun save(c: Context, pct: Int) {
        if (pct !in 0..100) return
        val now = System.currentTimeMillis()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("fitbit_batt", pct).putLong("fitbit_batt_at", now).apply()
        _state.value = Reading(pct, now)
    }

    private fun allowed(c: Context) =
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun scanner() = (ctx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner

    /** A quick look: find the heart-rate sharer, read its battery, hang up. Skipped while a session has it connected. */
    @SuppressLint("MissingPermission")
    fun peek(c: Context) {
        ctx = c.applicationContext
        load(c)
        val now = System.currentTimeMillis()
        if (now - lastPeek < PEEK_EVERY_MS || HrMonitor.state.value.status != HrMonitor.Status.OFF || gatt != null) return
        if (!allowed(c)) return
        val s = scanner() ?: return
        lastPeek = now
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HrMonitor.SERVICE)).build()
        runCatching { s.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCb) }
        // give up after 12 s of looking, and hang up after 20 s whatever happens
        main.postDelayed({ runCatching { scanner()?.stopScan(scanCb) } }, 12_000)
        main.postDelayed({ hangUp() }, 20_000)
    }

    @SuppressLint("MissingPermission")
    private fun hangUp() {
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, r: ScanResult) {
            runCatching { scanner()?.stopScan(this) }
            val c = ctx ?: return
            if (gatt != null || HrMonitor.state.value.status != HrMonitor.Status.OFF) return
            gatt = r.device.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { runCatching { g.close() }; if (gatt == g) gatt = null }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(LEVEL)
            if (ch == null) { main.post { hangUp() }; return }
            g.readCharacteristic(ch)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (ch.uuid == LEVEL && status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) ctx?.let { save(it, value[0].toInt() and 0xFF) }
            main.post { hangUp() }
        }
    }
}
