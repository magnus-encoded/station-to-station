package io.github.magnusencoded.setlist2spotify.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import java.nio.charset.StandardCharsets
import java.util.UUID

// #18 field-test probe: a minimal raw-BLE-GATT co-presence check between two
// phones. Not the shippable protocol (no signing, no RSSI sanity check) — this
// only exists to measure discovery rate/latency and foreground-only behaviour
// empirically, per #13's finding that raw GATT is the only iOS<->Android option.
private val SERVICE_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7711")
private val CHARACTERISTIC_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7712")

data class PeerHit(val address: String, val rssi: Int, val firstSeenLatencyMs: Long)

class BleAdvertiser(private val context: Context, private val label: String) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            onLog?.invoke("advertising started as \"$label\"")
        }
        override fun onStartFailure(errorCode: Int) {
            onLog?.invoke("advertising FAILED, error=$errorCode")
        }
    }
    var onLog: ((String) -> Unit)? = null

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val payload = label.toByteArray(StandardCharsets.UTF_8)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
            onLog?.invoke("server: read request from ${device.address}, sent \"$label\"")
        }
    }

    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"])
    fun start() {
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(characteristic)
        }
        gattServer = manager.openGattServer(context, serverCallback)?.also { it.addService(service) }

        advertiser = manager.adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"])
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        gattServer?.close()
        gattServer = null
    }
}

class BleScanner(private val context: Context) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    var onHit: ((PeerHit) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    private val firstSeenAt = mutableMapOf<String, Long>()
    private val scanStartedAt = System.currentTimeMillis()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val firstSeen = firstSeenAt.getOrPut(address) { System.currentTimeMillis() }
            onHit?.invoke(PeerHit(address, result.rssi, firstSeen - scanStartedAt))
        }
        override fun onScanFailed(errorCode: Int) {
            onLog?.invoke("scan FAILED, error=$errorCode")
        }
    }

    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"])
    fun start() {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        manager.adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
        onLog?.invoke("scanning started")
    }

    @RequiresPermission("android.permission.BLUETOOTH_SCAN")
    fun stop() {
        manager.adapter.bluetoothLeScanner?.stopScan(callback)
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    fun readPeer(address: String, onResult: (payload: String?, roundTripMs: Long) -> Unit) {
        val device = manager.adapter.getRemoteDevice(address)
        val requestedAt = System.currentTimeMillis()
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                if (char != null) gatt.readCharacteristic(char) else gatt.disconnect()
            }
            @Suppress("DEPRECATION") // old single-arg overload: works on minSdk 26, unlike the API 33 ByteArray one
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val roundTripMs = System.currentTimeMillis() - requestedAt
                val payload = if (status == BluetoothGatt.GATT_SUCCESS) {
                    String(characteristic.value, StandardCharsets.UTF_8)
                } else null
                onResult(payload, roundTripMs)
                gatt.disconnect()
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}
