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
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_MAX_WIRE_BYTES
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_NONCE_BYTES
import io.github.magnusencoded.stationtostation.data.gossip.GOSSIP_PEER_COOLDOWN
import io.github.magnusencoded.stationtostation.data.gossip.encodePublicGossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.decodePublicGossipChallenge
import io.github.magnusencoded.stationtostation.data.gossip.publicGossipAuthPayload
import io.github.magnusencoded.stationtostation.data.gossip.PublicGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.decodePublicGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.encodePublicGossipPass
import io.github.magnusencoded.stationtostation.data.gossip.GossipRadioStatus
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

/**
 * The Core Spec's ceiling on one attribute *value*, which is a different limit from the MTU
 * and is the one a CoreBluetooth peripheral enforces.
 *
 * `attMtu - 3` is how much fits in a single write PDU; this is how large the value itself is
 * permitted to be. The two agree until the negotiated MTU passes 515 — and [requestMtu] asks
 * for 517, so a chunk sized by the MTU alone is 514 bytes. An iPhone rejects that with
 * `Invalid Attribute Value Length` before `didReceiveWrite` is ever called, which presents
 * as a push that dies in "pass" against an iOS peer and never against an Android one. Both
 * bounds are real, so both are applied.
 */
private const val GOSSIP_MAX_ATTRIBUTE_BYTES = 512

private const val TAG = "GossipRadio"

/** What a listener accepted: a peer that proved itself, and what it pushed. */
data class GossipDelivery(val from: String, val batch: List<GossipCheckIn>)

/** Public v2 delivery. The sender is a transport identity; envelope authors are temporary Gig keys. */
data class PublicGossipDelivery(val from: String, val pass: PublicGossipPass)

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
 * How many bytes may go in one write to a peer, given the negotiated MTU.
 *
 * Both bounds at once: three bytes of ATT header come off the MTU, *and* an attribute value
 * may never exceed [GOSSIP_MAX_ATTRIBUTE_BYTES] however large the MTU got. Taking only the
 * first is what made a push to an iPhone die in "pass" — see [GOSSIP_MAX_ATTRIBUTE_BYTES].
 */
internal fun gossipWriteLimit(attMtu: Int): Int = minOf(attMtu - 3, GOSSIP_MAX_ATTRIBUTE_BYTES)

/** A public listener: the challenge and Pass prove possession of temporary relay keys. */
class GossipPeripheral(
    private val context: Context,
    private val myKey: () -> String,
    private val sign: (ByteArray) -> ByteArray?,
) {
    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false

    /** Fires on a binder thread. The service hops to a coroutine before doing anything. */
    var onPublicDelivery: ((PublicGossipDelivery) -> Unit)? = null

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
            advertising = false
            Log.w(TAG, "gossip advertising failed, error=$errorCode")
            GossipRadioStatus.advertising(false)
            GossipRadioStatus.note("could not advertise (error $errorCode)")
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
                // Only the nightly relay key is public; durable Contact keys stay off this link.
                challenges[device.address] = encodePublicGossipChallenge(nonce, myKey(), sign) ?: ByteArray(0)
            }
            val payload = challenges[device.address] ?: ByteArray(0)
            if (offset == 0) {
                Log.i(TAG, "a peer read our challenge (${payload.size} bytes offered)")
            }
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
                nonces.remove(device.address)
                if (responseNeeded) sendResponse(device, requestId, offset, chunk)
                return
            }
            inbox[device.address] = accumulated + chunk
            Log.i(
                TAG,
                "pass chunk in: ${chunk.size} bytes at offset $offset, " +
                    "${accumulated.size + chunk.size} accumulated, prepared=$preparedWrite",
            )
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
            // The only evidence this device gets about the other direction: a peer that never
            // appears here never tried to push to us, which is a different fault from one that
            // connects and then fails.
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "a peer connected to our server")
                    GossipRadioStatus.peerArrived(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "a peer disconnected from our server (status=$status)")
                    GossipRadioStatus.peerLeft(device.address)
                    forget(device.address)
                }
            }
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

    /** Consume one connection nonce and admit only v2 with a valid possession proof. */
    private fun deliver(address: String) {
        val payload = inbox.remove(address) ?: return
        val nonce = nonces.remove(address) ?: return
        val pass = decodePublicGossipPass(payload)
        val proof = pass?.let { runCatching { Base64.getDecoder().decode(it.proof) }.getOrNull() }
        if (pass == null || proof == null || !verifyChallenge(publicGossipAuthPayload(nonce), proof, pass.from)) {
            Log.w(TAG, "dropped unreadable or unverified public Pass (${payload.size} bytes)")
            GossipRadioStatus.note("dropped an invalid public pass")
            return
        }
        Log.i(TAG, "verified public Pass: ${pass.batch.size} envelope(s), ${payload.size} bytes")
        onPublicDelivery?.invoke(PublicGossipDelivery(pass.from, pass))
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
        advertise()
    }

    /** Keep one connectable service advertisement up; public peers need no Contact token. */
    @SuppressLint("MissingPermission")
    private fun advertise() {
        if (advertising) return
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
        runCatching { requireNotNull(advertiser).startAdvertising(settings, advertisement, advertiseCallback) }
            .onSuccess {
                advertising = true
                Log.i(TAG, "public gossip advertisement started")
                GossipRadioStatus.advertising(true)
            }
            .onFailure {
                Log.w(TAG, "gossip advertising could not start: $it")
                GossipRadioStatus.advertising(false)
            }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopAdvertising()
        advertiser = null
        runCatching { gattServer?.close() }
        gattServer = null
        inbox.clear()
        nonces.clear()
        challenges.clear()
        GossipRadioStatus.advertising(false)
    }
}

/** Discover public service advertisements, verify their temporary key, and push one Pass. */
class GossipCentral(
    private val context: Context,
    private val publicPassFor: (String, ByteArray) -> ByteArray?,
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

    /** Addresses already named in the log, so a scan does not repeat itself every second. */
    private val logged = mutableSetOf<String>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (logged.add(result.device.address)) Log.i(TAG, "saw a public gossip radio")
            if (busy) return
            val address = result.device.address
            val now = Instant.now()
            val last = attempted[address]
            if (last != null && now.isBefore(last.plus(GOSSIP_PEER_COOLDOWN))) return
            attempted[address] = now
            busy = true
            push(address)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "gossip scan failed, error=$errorCode")
            scanning = false
            GossipRadioStatus.scanning(false)
            GossipRadioStatus.note("the scan failed (error $errorCode)")
        }
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
            .onSuccess {
                scanning = true
                Log.i(TAG, "gossip scan started")
                GossipRadioStatus.scanning(true)
            }
            .onFailure {
                Log.w(TAG, "gossip scan could not start: $it")
                GossipRadioStatus.scanning(false)
            }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (scanning) runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        main.removeCallbacksAndMessages(null)
        busy = false
        attempted.clear()
        logged.clear()
        GossipRadioStatus.scanning(false)
        GossipRadioStatus.pushClosed()
    }

    /** Read a fresh signed challenge, send bounded chunks and an empty terminator, then disconnect. */
    @SuppressLint("MissingPermission")
    private fun push(address: String) {
        val device = runCatching { manager.adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { busy = false; return }
        GossipRadioStatus.pushOpened(address)
        var gattRef: BluetoothGatt? = null
        var done = false
        var phase = "connect"

        /**
         * The outcome in words, for the screen, when there is one worth telling apart.
         *
         * The phase name alone is not enough to read a panel by: five different things end a
         * push in `"challenge"`, and one of them — the cooldown — is the radio working. A line
         * that cannot separate "we spoke a minute ago" from "I could not sign the nonce" is a
         * line that has to be checked against logcat, which is what the panel exists to avoid.
         */
        var why: String? = null

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
            GossipRadioStatus.pushClosed()
            val who = peer
            if (pushed && who != null) {
                Log.i(TAG, "push delivered to a relay")
                GossipRadioStatus.note("handed over ${chunks.size - 1} chunk(s)")
                onPushed(who)
            } else {
                val known = if (peer != null) "relay resolved" else "peer never resolved"
                Log.w(TAG, "gossip push to a peer gave up in \"$phase\" ($known)")
                GossipRadioStatus.note(why ?: "gave up in \"$phase\"")
            }
        }

        main.postDelayed({
            // The phase is the whole story here: nothing answered, and this says what we
            // were waiting on. Assigning unconditionally is safe — every other `why` is
            // set immediately before its own `finish`, so if one of those ran first the
            // `done` guard drops this before the value is ever read.
            why = "no answer while waiting on \"$phase\""
            finish(false)
        }, GOSSIP_PUSH_TIMEOUT_MS)

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
                    why = "they dropped the connection during \"$phase\""
                    finish(false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // Three bytes of ATT header come off whatever was negotiated. A refusal
                // leaves the default 23, which still works — it is just more round trips.
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) attMtu = mtu
                Log.i(TAG, "mtu negotiated to $mtu (status=$status), using $attMtu")
                phase = "services"
                if (!gatt.discoverServices()) finish(false)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                phase = "challenge"
                val characteristic = gatt.getService(GOSSIP_SERVICE_UUID)
                    ?.getCharacteristic(GOSSIP_CHALLENGE_UUID)
                // Told apart because they mean opposite things. No characteristic is *their*
                // side: the service was not there to read, so this is not a gossip peer at
                // all — or their server went away between the advertisement and the connect.
                // A refused read is *our* side, and points at this phone's stack.
                if (characteristic == null) {
                    why = "they have no gossip challenge to read (status=$status)"
                    return finish(false)
                }
                if (!gatt.readCharacteristic(characteristic)) {
                    why = "could not start the challenge read"
                    return finish(false)
                }
            }

            @Suppress("DEPRECATION") // the API 33 ByteArray overload does not exist on minSdk 26
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "challenge read failed, status=$status")
                    why = "could not read their challenge (status=$status)"
                    return finish(false)
                }
                val challenge = decodePublicGossipChallenge(characteristic.value) ?: run {
                    Log.w(TAG, "challenge unreadable (${characteristic.value?.size ?: 0} bytes)")
                    why = "their challenge was unreadable"
                    return finish(false)
                }
                val who = challenge.from
                peer = who
                GossipRadioStatus.pushNamed(who)
                if (!due(who)) {
                    Log.i(TAG, "relay resolved but still inside the push cooldown")
                    why = "spoke to them recently, waiting out the cooldown"
                    return finish(false)
                }
                val payload = publicPassFor(who, challenge.nonce) ?: return finish(false)
                phase = "pass"
                val limit = gossipWriteLimit(attMtu)
                chunks = payload.intoChunks(limit) + listOf(ByteArray(0))
                sent = 0
                Log.i(TAG, "public push: ${payload.size} bytes in ${chunks.size} chunks, limit=$limit")
                if (!writeNext(gatt)) finish(false)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // 7 = INVALID_ATTRIBUTE_LENGTH, what an over-512 chunk earns from iOS.
                    Log.w(TAG, "pass chunk ${sent - 1} of ${chunks.size} refused, status=$status")
                    return finish(false)
                }
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
