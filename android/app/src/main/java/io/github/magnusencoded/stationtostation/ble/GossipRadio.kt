package io.github.magnusencoded.stationtostation.ble

import android.annotation.SuppressLint
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
import android.util.Log
import io.github.magnusencoded.stationtostation.data.GossipCheckIn
import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_ADVERTISE_SLOT
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_MAX_WIRE_BYTES
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_NONCE_BYTES
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_PEER_COOLDOWN
import io.github.magnusencoded.stationtostation.data.gossip.GossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.GossipPass
import io.github.magnusencoded.stationtostation.data.gossip.decodeGossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.decodeGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.encodeGossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.encodeGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.gossipAdvertisedToken
import io.github.magnusencoded.stationtostation.data.gossip.gossipAuthPayload
import io.github.magnusencoded.stationtostation.data.gossip.gossipResolveOffer
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenOffer
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenOwner
import io.github.magnusencoded.stationtostation.data.gossip.gossipTokenTable
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The gossip channel's radio (#416): advertise a rotating token, scan for **Contacts**,
 * and push this device's live check-ins to whoever answers.
 *
 * Modelled on [BleCardPeripheral]/[BleCardCentral] rather than reusing them, and on purpose
 * — this is a **different service, on a different UUID, with a different trust rule**. The
 * Exchange's server accepts a card from any radio in range because being on that screen is
 * the consent (ADR-0016); this one accepts nothing from anyone who has not first proved
 * possession of a key already on a **Friend** record. Sharing a GATT service between the
 * two would put the foreground-only rule and the background carve-out behind one door.
 *
 * **Push-only.** Every device runs both halves at once, so there is no authenticated read
 * to design: a device with something to say connects and writes, and a device with nothing
 * to say never has to be believed about anything. The one thing that *is* read is the
 * listener's challenge, and reading that grants nothing.
 *
 * Nothing in this file decides whether a message is any good. It hands `(from, batch)` to
 * its owner, which runs
 * [gossipStormGate][io.github.magnusencoded.stationtostation.data.gossipStormGate] — the
 * one place that rule lives.
 */

/** The gossip service. Deliberately not the Exchange's UUID — see the file comment. */
internal val GOSSIP_SERVICE_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721")

/** Read: a fresh nonce and this listener's token offer — never its own key. */
internal val GOSSIP_CHALLENGE_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722")

/** Write: the pusher's key, its signature over that nonce, and its batch. */
internal val GOSSIP_PASS_UUID: UUID = UUID.fromString("7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723")

/** 0xFFFF is the SIG's "reserved for internal/testing use" company id, as in [BleCardPeripheral]. */
private const val TEST_COMPANY_ID = 0xFFFF

/**
 * How long one push may take before it is abandoned.
 *
 * Longer than [EXCHANGE_TIMEOUT_MS] because there is no human waiting and the payload is
 * larger — a full outbox is kilobytes against a card's two hundred bytes, and at a
 * negotiated MTU that is still several round trips. Bounded all the same: a stalled peer
 * must not hold this device's single connection slot for the rest of the night.
 */
private const val GOSSIP_PUSH_TIMEOUT_MS = 20_000L

private const val TAG = "GossipRadio"

/** What a listener accepted: a peer that proved itself, and what it pushed. */
data class GossipDelivery(val from: String, val batch: List<GossipCheckIn>)

/**
 * The payload in pieces that fit one ATT write, in order.
 *
 * Not `Iterable.chunked`, which would box every byte on the way through — a **Pass** is tens
 * of kilobytes and this runs on a phone that is trying to stay asleep.
 */
internal fun ByteArray.intoChunks(size: Int): List<ByteArray> {
    if (size <= 0 || isEmpty()) return listOf(copyOf())
    return (indices step size).map { copyOfRange(it, minOf(it + size, this.size)) }
}

/**
 * The listening half: advertise, and take pushes from **Contacts** that prove themselves.
 *
 * [contacts] and [myKey] are read on every operation rather than captured once, so a
 * **Contact** made tonight can be gossiped with tonight.
 */
class GossipPeripheral(
    private val context: Context,
    private val myKey: () -> String,
    private val contacts: () -> Set<String>,
) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false

    /**
     * One object, kept, because `::rotate` builds a *new* `Runnable` every time it is
     * mentioned — so posting one reference and cancelling another leaves the timer running
     * after [stop], and the service keeps advertising after it has been told to shut up.
     */
    private val rotation = Runnable { rotate() }

    /** Fires on a binder thread. The service hops to a coroutine before doing anything. */
    var onDelivery: ((GossipDelivery) -> Unit)? = null

    /**
     * The nonce this listener last issued to each device address, and the bytes it answered
     * the read with.
     *
     * Per address rather than global: two **Contacts** can be pushing at once, and a shared
     * nonce would mean whichever read last decided what the other one had to sign. Dropped
     * on disconnect, so a nonce is good for one connection and no longer.
     */
    private val nonces = mutableMapOf<String, ByteArray>()
    private val challenges = mutableMapOf<String, ByteArray>()

    /** Accumulated write payload per device — a **Pass** arrives as a long write. */
    private val inbox = mutableMapOf<String, ByteArray>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "gossip advertising failed, error=$errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != GOSSIP_CHALLENGE_UUID) {
                sendResponse(device, requestId, offset, ByteArray(0))
                return
            }
            // A fresh nonce at offset 0, the same answer for the blob reads that follow it.
            // Re-reading from the start is what asks for a new one, which is also what makes
            // a recorded exchange useless the moment the connection it belonged to ends.
            if (offset == 0) {
                val nonce = ByteArray(GOSSIP_NONCE_BYTES).also(random::nextBytes)
                nonces[device.address] = nonce
                // The offer, never this device's own key: a stranger who connects and reads
                // must not walk away holding a stable identifier for this phone. See
                // [gossipTokenOffer].
                challenges[device.address] = encodeGossipChallenge(
                    GossipChallenge(nonce, gossipTokenOffer(myKey(), contacts(), Instant.now())),
                )
            }
            val payload = challenges[device.address] ?: ByteArray(0)
            sendResponse(device, requestId, offset, sliceForOffset(payload, offset))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val chunk = value ?: ByteArray(0)
            if (characteristic.uuid != GOSSIP_PASS_UUID) {
                if (responseNeeded) sendResponse(device, requestId, offset, chunk)
                return
            }
            // Append-and-terminate, not write-at-offset: a CoreBluetooth central cannot
            // perform a long write and sends every chunk at offset 0, so the offset is
            // ignored here on purpose and a zero-length write is what ends the **Pass**.
            // Writing at the offset a peer states would mean an iPhone's whole batch
            // overwrote itself down to its last chunk. See `GossipWire.kt`'s header.
            val accumulated = inbox[device.address] ?: ByteArray(0)
            if (accumulated.size + chunk.size > GOSSIP_MAX_WIRE_BYTES) {
                // Refused whole rather than truncated, and the peer is not told which of the
                // two it was — the same silence every other rejection here keeps.
                inbox.remove(device.address)
                if (responseNeeded) sendResponse(device, requestId, offset, chunk)
                return
            }
            inbox[device.address] = accumulated + chunk
            // Withholding the response on a write-with-response hangs the pusher until its
            // own timeout — the same trap [BleCardPeripheral] documents.
            if (responseNeeded) sendResponse(device, requestId, offset, chunk)
            if (chunk.isEmpty()) deliver(device.address)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            sendResponse(device, requestId, 0, null)
            if (execute) deliver(device.address) else inbox.remove(device.address)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) forget(device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendResponse(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
        runCatching {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    private fun forget(address: String) {
        inbox.remove(address)
        nonces.remove(address)
        challenges.remove(address)
    }

    /**
     * One completed write is one **Pass**, or it is dropped.
     *
     * **This is where "the transport proves possession before calling" is actually done** —
     * the sentence the storm-gate's `from` parameter is written against. Three things have
     * to hold, and a failure of any of them is silent: an unreadable envelope, a claimed key
     * that is nobody this device has met, or a signature that is not over the nonce *this*
     * connection issued. Nothing is reported back to the peer, for the reason the gate gives
     * for its own rejections — a diagnosis is a probe's oracle.
     */
    private fun deliver(address: String) {
        val payload = inbox.remove(address) ?: return
        val nonce = nonces[address] ?: return
        val pass = decodeGossipPass(payload) ?: run {
            Log.w(TAG, "unreadable pass (${payload.size} bytes), dropped")
            return
        }
        if (pass.from !in contacts()) return
        val signature = runCatching { Base64.getDecoder().decode(pass.proof) }.getOrNull() ?: return
        if (!verifyChallenge(gossipAuthPayload(nonce), signature, pass.from)) {
            Log.w(TAG, "a pass failed the possession proof, dropped")
            return
        }
        // Spent: a nonce answers exactly one **Pass**, so a peer that pushes twice on one
        // connection has to read a new challenge for the second.
        nonces.remove(address)
        onDelivery?.invoke(GossipDelivery(pass.from, pass.batch))
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (gattServer != null) return
        val challenge = BluetoothGattCharacteristic(
            GOSSIP_CHALLENGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val passing = BluetoothGattCharacteristic(
            GOSSIP_PASS_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(GOSSIP_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(challenge); addCharacteristic(passing) }
        gattServer = runCatching {
            manager.openGattServer(context, serverCallback)?.also { it.addService(service) }
        }.getOrNull()
        advertiser = manager.adapter?.bluetoothLeAdvertiser
        rotate()
    }

    /**
     * Show the next **Contact**'s token, and come back in [GOSSIP_ADVERTISE_SLOT].
     *
     * A token is per pair, so a phone with several **Contacts** has several to show and one
     * advertisement to show them in — [gossipAdvertisedToken] owns which. Each turn is a
     * stop and a start of the advertiser, which is why the slot is seconds rather than
     * milliseconds.
     */
    @SuppressLint("MissingPermission")
    private fun rotate() {
        main.removeCallbacks(rotation)
        val token = gossipAdvertisedToken(myKey(), contacts(), Instant.now())
        stopAdvertising()
        if (token == null) {
            // Nobody to advertise to yet. Come back anyway rather than stopping the timer:
            // a **Contact** made while the service is running is one this loop picks up on
            // its next turn, and dying here would mean the radio silently never woke.
            main.postDelayed(rotation, GOSSIP_ADVERTISE_SLOT.toMillis())
            return
        }
        val settings = AdvertiseSettings.Builder()
            // Low power, not low latency: this runs for a night, not for the two seconds
            // somebody is looking at the Exchange screen. A one-second advertising interval
            // is well inside a scan window that reopens every four.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val advertisement = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(GOSSIP_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        // The token rides the scan response for the same reason the Exchange's name does: a
        // 128-bit service UUID takes 18 of the advertisement's 31 bytes, and the phone's own
        // name would take the rest. Manufacturer data, not a service-data record, because a
        // 128-bit service-data record costs 18 bytes of the 31 to say the same thing again.
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(TEST_COMPANY_ID, token)
            .build()
        runCatching { advertiser?.startAdvertising(settings, advertisement, scanResponse, advertiseCallback) }
            .onSuccess { advertising = true }
        main.postDelayed(rotation, GOSSIP_ADVERTISE_SLOT.toMillis())
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacks(rotation)
        stopAdvertising()
        advertiser = null
        runCatching { gattServer?.close() }
        gattServer = null
        inbox.clear()
        nonces.clear()
        challenges.clear()
    }
}

/**
 * The pushing half: find a gossip radio, connect, learn whose it is, prove who we are, hand
 * over.
 *
 * Two ways in, and the second is the one that matters for reaching an iPhone. A peer that put
 * a token in its scan response is recognised before a connection is opened; a peer that could
 * not — every iPhone, which cannot advertise bytes at all — is connected to and identified
 * from its challenge instead. The first is a shortcut, the second is the contract.
 *
 * [outboxFor] is asked what to send only once the peer's key is known, because what is sent
 * depends on who is listening — a message is never handed back to the **Contact** it came
 * from or to its own author.
 */
class GossipCentral(
    private val context: Context,
    private val myKey: () -> String,
    private val contacts: () -> Set<String>,
    private val sign: (ByteArray) -> ByteArray?,
    private val outboxFor: (String) -> List<GossipCheckIn>,
    /** Whether this peer's cooldown has elapsed — [gossipPassDue][io.github.magnusencoded.stationtostation.data.gossip.gossipPassDue]. */
    private val due: (String) -> Boolean,
    private val onPushed: (String) -> Unit,
) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    private var scanning = false

    /**
     * One connection at a time.
     *
     * Not a throughput choice — a phone's GATT client can hold several — but a battery and
     * reliability one: a crowd at a festival is the case this has to survive, and a device
     * that opens a connection per **Contact** it sees is a device that spends the night in
     * connect storms. The peers it skips are still advertising a minute later.
     */
    private var busy = false

    /** Recomputed per bucket rather than per hit — see [gossipTokenTable]. */
    private var table: Map<String, String> = emptyMap()
    private var tableAt = Instant.EPOCH

    /**
     * When each device address was last connected to, for peers whose identity is not known
     * until after the connection.
     *
     * The token-in-the-advertisement path can ask [due] before spending anything, because it
     * already knows *who* it is looking at. The cross-platform path cannot — an iPhone
     * advertises a service UUID and nothing else — so without this a phone would reconnect to
     * every iPhone in the room on every scan result. Keyed by BLE address rather than by
     * **Contact**, because an address is the only name this side has yet; the real per-peer
     * bound is still [due], applied the moment the challenge resolves.
     */
    private val attempted = mutableMapOf<String, Instant>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (busy) return
            val advertised = result.scanRecord?.getManufacturerSpecificData(TEST_COMPANY_ID)
            if (advertised != null) {
                // The Android-to-Android shortcut: the peer named itself in its scan
                // response, so everything can be decided before paying for a connection.
                val peer = gossipTokenOwner(currentTable(), advertised) ?: return
                if (!due(peer) || outboxFor(peer).isEmpty()) return
                busy = true
                push(result.device.address, peer)
                return
            }
            // No manufacturer data. Either an iPhone — which cannot put bytes in an
            // advertisement at all — or an Android with nobody to advertise to. Connect and
            // read the challenge, which is the path both platforms share; who this is, and
            // whether there is anything to say to them, is settled there.
            val address = result.device.address
            val now = Instant.now()
            val last = attempted[address]
            if (last != null && now.isBefore(last.plus(GOSSIP_PEER_COOLDOWN))) return
            attempted[address] = now
            busy = true
            push(address, null)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "gossip scan failed, error=$errorCode")
            scanning = false
        }
    }

    private fun currentTable(): Map<String, String> {
        val now = Instant.now()
        // Rebuilt a good deal more often than the quarter-hour it covers, because it is also
        // how a **Contact** made a minute ago enters the set of people worth connecting to.
        if (table.isEmpty() || now.isAfter(tableAt.plusSeconds(60))) {
            table = gossipTokenTable(myKey(), contacts(), now)
            tableAt = now
        }
        return table
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GOSSIP_SERVICE_UUID)).build()
        // Balanced, not low-latency: the Exchange's two-second budget has a human waiting on
        // it, and this does not. Balanced still reopens the window every few seconds, which
        // against a one-second advertising interval finds a peer well inside the time two
        // people stand near each other. Low power would roughly halve the radio cost and
        // multiply the discovery time by five; that trade can be revisited from a real
        // night's battery figures rather than guessed at twice.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        runCatching { manager.adapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, callback) }
            .onSuccess { scanning = true }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (scanning) runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        main.removeCallbacksAndMessages(null)
        busy = false
        table = emptyMap()
        attempted.clear()
    }

    /**
     * Connect, read the challenge, resolve who this is, sign the nonce, write the **Pass**,
     * let go.
     *
     * [expected] is the **Contact** the advertisement already named, on the Android-only fast
     * path, and null on the cross-platform path where nothing is known until the challenge is
     * read. Either way the challenge has to resolve, and when both are present they have to
     * agree — a device that advertises one **Contact**'s token and then offers another's is
     * not having its word taken for it.
     *
     * A token is derived from public material (see [gossipToken][io.github.magnusencoded.stationtostation.data.gossip.gossipToken]),
     * so a device holding both keys could present one it has no business presenting. What
     * stops that mattering is that a **Pass** is signed by a key it cannot impersonate and
     * every message inside it is verified again by the storm-gate — but there is no reason to
     * hand anything over to it, and the check costs a map lookup.
     */
    @SuppressLint("MissingPermission")
    private fun push(address: String, expected: String?) {
        val device = runCatching { manager.adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { busy = false; return }
        var gattRef: BluetoothGatt? = null
        var done = false
        var phase = "connect"

        /** Who the challenge said this is. Null until it has been read and resolved. */
        var peer: String? = null

        /** The **Pass**, in MTU-sized pieces, and how far through them this connection is. */
        var chunks: List<ByteArray> = emptyList()
        var sent = 0
        var attMtu = 23

        fun finish(pushed: Boolean) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(null)
            runCatching { gattRef?.disconnect(); gattRef?.close() }
            busy = false
            val who = peer
            if (pushed && who != null) onPushed(who)
            else Log.w(TAG, "gossip push to a peer gave up in \"$phase\"")
        }

        main.postDelayed({ finish(false) }, GOSSIP_PUSH_TIMEOUT_MS)

        gattRef = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    phase = "mtu"
                    // Worth the leg here, unlike the Exchange's card read: a batch is
                    // kilobytes, and at the default 23 that is a long write in twenty-byte
                    // pieces. A refusal is not fatal — the write still goes, slowly.
                    if (!gatt.requestMtu(517)) {
                        phase = "services"
                        if (!gatt.discoverServices()) finish(false)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // Three bytes of ATT header come off whatever was negotiated. A refusal
                // leaves the default 23, which still works — it is just more round trips.
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) attMtu = mtu
                phase = "services"
                if (!gatt.discoverServices()) finish(false)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                phase = "challenge"
                val characteristic = gatt.getService(GOSSIP_SERVICE_UUID)
                    ?.getCharacteristic(GOSSIP_CHALLENGE_UUID)
                if (characteristic == null || !gatt.readCharacteristic(characteristic)) finish(false)
            }

            @Suppress("DEPRECATION") // the API 33 ByteArray overload does not exist on minSdk 26
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return finish(false)
                val challenge = decodeGossipChallenge(characteristic.value) ?: return finish(false)
                // Who this is, decided here and nowhere else on this path. A stranger's offer
                // resolves to nobody, which is the ordinary case and not an error.
                val who = gossipResolveOffer(challenge.tokens, currentTable()) ?: return finish(false)
                if (expected != null && expected != who) return finish(false)
                peer = who
                if (!due(who)) return finish(false)
                val batch = outboxFor(who)
                if (batch.isEmpty()) return finish(false)
                val signature = sign(gossipAuthPayload(challenge.nonce)) ?: return finish(false)
                val payload = encodeGossipPass(
                    GossipPass(
                        from = myKey(),
                        proof = Base64.getEncoder().encodeToString(signature),
                        batch = batch,
                    ),
                ) ?: return finish(false)
                phase = "pass"
                // Chunked by hand and terminated by an empty write, because that is the one
                // framing a CoreBluetooth peripheral reads the same way — see
                // `GossipWire.kt`'s header. The empty chunk at the end is part of the
                // protocol, not padding.
                chunks = payload.intoChunks(attMtu - 3) + listOf(ByteArray(0))
                sent = 0
                if (!writeNext(gatt)) finish(false)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return finish(false)
                if (sent >= chunks.size) return finish(true)
                if (!writeNext(gatt)) finish(false)
            }

            /** The next piece, or false when there was nothing left to send. */
            @Suppress("DEPRECATION") // as onCharacteristicRead
            fun writeNext(gatt: BluetoothGatt): Boolean {
                if (sent >= chunks.size) return false
                val outbound = gatt.getService(GOSSIP_SERVICE_UUID)
                    ?.getCharacteristic(GOSSIP_PASS_UUID) ?: return false
                outbound.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                outbound.value = chunks[sent]
                sent += 1
                return gatt.writeCharacteristic(outbound)
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}
