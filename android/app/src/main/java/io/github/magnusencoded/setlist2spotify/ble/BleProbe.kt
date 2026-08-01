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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import java.nio.charset.StandardCharsets
import java.util.UUID

// #30 probe: exchange a friend card over raw BLE GATT, and time every leg of it.
//
// Wire shape, chosen so an iPhone peripheral can produce the same thing:
//   advertisement  — the 128-bit service UUID and nothing else (31 bytes, and a
//                    128-bit UUID record eats 18 of them).
//   scan response  — the display name, so a row can read "Connecting with
//                    dizzi90" before any connection exists. With GATT, seeing
//                    someone is no longer the same as holding their card.
//   characteristic — the whole card (~170-200 bytes: name, public key, optional
//                    setlist.fm username). Read after negotiating the MTU up;
//                    at the default 23 that is ten round trips.
//
// Where Android and iOS differ, and it is not fixable from this side: iOS puts
// the name in the scan response as a Complete Local Name, which on Android is
// the *adapter* name — settable only by renaming the whole phone via
// BluetoothAdapter.setName(). So this advertises the app-chosen name as
// manufacturer data instead (27 usable bytes vs 13 under a 128-bit service-data
// record), and the scanner reads whichever of the two turns up. The split that
// matters — name in the scan response, card behind a connect — is identical.
private val SERVICE_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7711")
private val CARD_CHARACTERISTIC_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7712")

/** 0xFFFF is the SIG's "reserved for internal/testing use" company id. */
private const val TEST_COMPANY_ID = 0xFFFF

/** Bytes the ATT layer can carry in one read: MTU minus the 1-byte opcode and 2-byte handle. */
private const val ATT_OVERHEAD = 3

/**
 * What the GATT server hands back for one read at [offset]. A payload longer than
 * MTU-1 arrives as a rising-offset series of ATT_READ_BLOB requests; the platform
 * assembles them back into one value on the reading side, so the server only has
 * to answer each slice correctly. Pulled out of [BleCardPeripheral] so the #30
 * "does a chunked read survive without the MTU bump" question is checkable in a
 * plain JVM test rather than only on hardware.
 */
fun sliceForOffset(payload: ByteArray, offset: Int): ByteArray =
    if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)

/**
 * Sized to the operation, per the project rule: a card exchange that is meant to
 * take 2s has failed by 7, and the flow is supposed to fall through to QR rather
 * than spin. Long enough not to trip a slow-but-working connect, short enough
 * that a human notices the fallback rather than the wait.
 */
const val EXCHANGE_TIMEOUT_MS = 7_000L

data class PeerHit(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Scan start to first advertisement from this device — the "discovery" leg. */
    val discoveryMs: Long,
)

/** Every leg of one exchange, in milliseconds. Nulls are legs that never completed. */
data class ExchangeTiming(
    val discoveryMs: Long,
    val connectMs: Long? = null,
    val mtuMs: Long? = null,
    /** True when the #30 probe's "skip MTU" toggle was on — [mtuMs] is null because there was no leg to time, not because it failed. */
    val mtuSkipped: Boolean = false,
    val servicesMs: Long? = null,
    val readMs: Long? = null,
    val mtu: Int? = null,
    val cardBytes: Int? = null,
    val card: ProbeCard? = null,
    val failedAt: String? = null,
) {
    /** Card on screen to card in hand — the number #30's budget is written against. */
    val screenToCardMs: Long?
        get() = if (failedAt != null) null else {
            val required = listOfNotNull(connectMs, servicesMs, readMs)
            // mtuMs is required too, unless the leg was deliberately skipped — then it
            // contributes 0ms rather than blocking the total.
            if (required.size != 3 || (mtuMs == null && !mtuSkipped)) null
            else required.sum() + (mtuMs ?: 0)
        }

    val verdict: String
        get() = when (val t = screenToCardMs) {
            null -> "FAILED at ${failedAt ?: "unknown"}"
            in 0..2000 -> "SHIP (${t}ms)"
            in 2001..6000 -> "USABLE (${t}ms)"
            else -> "OVER BUDGET (${t}ms) — degrade to QR"
        }
}

/** The advertising half: hand out this phone's card to anyone who connects and reads. */
class BleCardPeripheral(private val context: Context, private var card: ProbeCard) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    var onLog: ((String) -> Unit)? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            onLog?.invoke("advertising: ${card.bytes().size}-byte card behind the characteristic")
        }
        override fun onStartFailure(errorCode: Int) {
            // 1 = DATA_TOO_LARGE, the one that bites when the name is too long.
            onLog?.invoke("advertising FAILED, error=$errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // A card longer than MTU-1 arrives as a series of Read Blob requests
            // with a rising offset. Ignoring it (as the old #18 probe did) silently
            // truncates anything over ~22 bytes.
            val slice = sliceForOffset(card.bytes(), offset)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            onLog?.invoke("server: read from ${device.address} at offset $offset (${slice.size} bytes)")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            onLog?.invoke("server: MTU with ${device.address} is $mtu")
        }
    }

    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"])
    fun start() {
        val characteristic = BluetoothGattCharacteristic(
            CARD_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(characteristic) }
        gattServer = manager.openGattServer(context, serverCallback)?.also { it.addService(service) }

        advertiser = manager.adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val advertisement = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        val shortName = truncateToBytes(card.name)
        if (shortName != card.name) onLog?.invoke("name truncated for scan response: \"$shortName\"")
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(TEST_COMPANY_ID, shortName.toByteArray(StandardCharsets.UTF_8))
            .build()
        advertiser?.startAdvertising(settings, advertisement, scanResponse, advertiseCallback)
    }

    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"])
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        gattServer?.close()
        gattServer = null
    }
}

/** The scanning half: find peers, then connect and read the card off one. */
class BleCardCentral(private val context: Context) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    var onHit: ((PeerHit) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    private val firstSeenAt = mutableMapOf<String, Long>()
    private var scanStartedAt = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val firstSeen = firstSeenAt.getOrPut(address) { System.currentTimeMillis() }
            onHit?.invoke(PeerHit(address, nameFrom(result), result.rssi, firstSeen - scanStartedAt))
        }
        override fun onScanFailed(errorCode: Int) {
            onLog?.invoke("scan FAILED, error=$errorCode")
        }
    }

    /** iOS sends a Complete Local Name; this app sends manufacturer data. Accept both. */
    private fun nameFrom(result: ScanResult): String? {
        val record = result.scanRecord ?: return null
        record.getManufacturerSpecificData(TEST_COMPANY_ID)
            ?.let { return String(it, StandardCharsets.UTF_8) }
        return record.deviceName
    }

    @RequiresPermission(allOf = ["android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"])
    fun start() {
        scanStartedAt = System.currentTimeMillis()
        firstSeenAt.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        manager.adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
        onLog?.invoke("scanning started")
    }

    @RequiresPermission("android.permission.BLUETOOTH_SCAN")
    fun stop() {
        manager.adapter.bluetoothLeScanner?.stopScan(callback)
    }

    /**
     * Connect, optionally negotiate the MTU, discover services, read the card —
     * reporting how long each leg took. Gives up at [EXCHANGE_TIMEOUT_MS] naming
     * the leg it died on, which in the shipped flow is the cue to fall through to
     * QR.
     *
     * [negotiateMtu] exists so the probe screen can run both paths on the same two
     * phones and compare: at the default MTU (23) the card comes back as a series
     * of ATT_READ_BLOB round trips instead of one bigger read. See #30.
     */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    fun readCard(peer: PeerHit, negotiateMtu: Boolean = true, onResult: (ExchangeTiming) -> Unit) {
        val device = manager.adapter.getRemoteDevice(peer.address)
        var leg = System.currentTimeMillis()
        var phase = "connect"
        var timing = ExchangeTiming(discoveryMs = peer.discoveryMs)
        var done = false

        fun split(): Long = (System.currentTimeMillis() - leg).also { leg = System.currentTimeMillis() }

        var gattRef: BluetoothGatt? = null
        fun finish(result: ExchangeTiming) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(null)
            runCatching { gattRef?.disconnect(); gattRef?.close() }
            onResult(result)
        }

        main.postDelayed({
            onLog?.invoke("gave up after ${EXCHANGE_TIMEOUT_MS}ms in \"$phase\"")
            finish(timing.copy(failedAt = phase))
        }, EXCHANGE_TIMEOUT_MS)

        gattRef = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    timing = timing.copy(connectMs = split())
                    if (!negotiateMtu) {
                        timing = timing.copy(mtuSkipped = true)
                        phase = "services"
                        if (!gatt.discoverServices()) finish(timing.copy(failedAt = "service discovery rejected"))
                        return
                    }
                    phase = "mtu"
                    // 517 is the ATT maximum; the stack settles on whatever both ends allow.
                    if (!gatt.requestMtu(517)) finish(timing.copy(failedAt = "mtu request rejected"))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // No-op once the read has already landed; the disconnect that
                    // follows a successful exchange arrives here too.
                    finish(timing.copy(failedAt = "$phase (disconnected, status=$status)"))
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                timing = timing.copy(mtuMs = split(), mtu = mtu)
                onLog?.invoke("MTU $mtu — ${mtu - ATT_OVERHEAD} bytes per read")
                phase = "services"
                if (!gatt.discoverServices()) finish(timing.copy(failedAt = "service discovery rejected"))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                timing = timing.copy(servicesMs = split())
                phase = "read"
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CARD_CHARACTERISTIC_UUID)
                if (characteristic == null) finish(timing.copy(failedAt = "no card characteristic"))
                else if (!gatt.readCharacteristic(characteristic)) finish(timing.copy(failedAt = "read rejected"))
            }

            @Suppress("DEPRECATION") // the API 33 ByteArray overload does not exist on minSdk 26
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val readMs = split()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(timing.copy(failedAt = "read (status=$status)"))
                    return
                }
                val payload = String(characteristic.value ?: ByteArray(0), StandardCharsets.UTF_8)
                finish(
                    timing.copy(
                        readMs = readMs,
                        cardBytes = characteristic.value?.size ?: 0,
                        card = parseProbeCard(payload),
                    ),
                )
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}
